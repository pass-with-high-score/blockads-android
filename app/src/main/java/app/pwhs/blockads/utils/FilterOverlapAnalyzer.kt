package app.pwhs.blockads.utils

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class FilterTrieSource(
    val id: Long,
    val name: String,
    val trieFile: File
)

data class FilterOverlapStat(
    val filterId: Long,
    val filterName: String,
    val totalDomains: Int,
    val overlappingDomains: Int,
    val uniqueOnlyDomains: Int
) {
    val overlapPercent: Float
        get() = if (totalDomains > 0) (overlappingDomains * 100f) / totalDomains else 0f
}

data class PairwiseFilterOverlap(
    val filterAId: Long,
    val filterAName: String,
    val filterBId: Long,
    val filterBName: String,
    val sharedDomains: Int
)

data class OverlappingDomainEntry(
    val domain: String,
    val filterNames: List<String>,
    val filterIds: List<Long>
)

data class FilterOverlapReport(
    val analyzedFilters: List<FilterTrieSource>,
    val totalRawEntries: Int,
    val totalUniqueDomains: Int,
    val overlappingDomainCount: Int,
    val perFilterStats: List<FilterOverlapStat>,
    val pairwiseOverlaps: List<PairwiseFilterOverlap>,
    val duplicateEntries: List<OverlappingDomainEntry>,
    val hasMoreDuplicates: Boolean
) {
    val redundantEntries: Int
        get() = (totalRawEntries - totalUniqueDomains).coerceAtLeast(0)

    val redundancyPercent: Float
        get() = if (totalRawEntries > 0) (redundantEntries * 100f) / totalRawEntries else 0f
}

/**
 * Memory-efficient multi-trie DFS analyzer for `.trie` v2 binary filter files.
 * Walks all enabled tries simultaneously by reversed DNS label without loading
 * all domain strings into memory.
 */
object FilterOverlapAnalyzer {

    private const val TRIE_MAGIC = 0x54524945 // "TRIE"
    private const val TRIE_VERSION = 2
    private const val HEADER_SIZE = 16
    private const val MAX_DEPTH = 64
    const val DEFAULT_MAX_DISPLAY_ENTRIES = 500

    private class MappedTrieReader(
        private val raf: RandomAccessFile,
        private val channel: FileChannel,
        private val buf: ByteBuffer,
        val limit: Int
    ) : AutoCloseable {

        fun isTerminal(nodeOffset: Int): Boolean {
            if (nodeOffset < HEADER_SIZE || nodeOffset >= limit) return false
            return buf.get(nodeOffset).toInt() != 0
        }

        fun readChildren(nodeOffset: Int): List<Pair<String, Int>> {
            if (nodeOffset < HEADER_SIZE || nodeOffset + 5 > limit) return emptyList()
            val childCount = buf.getInt(nodeOffset + 1)
            if (childCount <= 0) return emptyList()

            val result = ArrayList<Pair<String, Int>>(childCount.coerceAtMost(256))
            var pos = nodeOffset + 5
            for (c in 0 until childCount) {
                if (pos + 2 > limit) break
                val labelLen = buf.getShort(pos).toInt() and 0xFFFF
                pos += 2
                if (labelLen <= 0 || pos + labelLen + 4 > limit) break

                val labelBytes = ByteArray(labelLen)
                for (b in 0 until labelLen) {
                    labelBytes[b] = buf.get(pos + b)
                }
                val childOffset = buf.getInt(pos + labelLen)
                pos += labelLen + 4

                if (childOffset in HEADER_SIZE until limit) {
                    result.add(String(labelBytes, Charsets.UTF_8) to childOffset)
                }
            }
            return result
        }

        override fun close() {
            runCatching { channel.close() }
            runCatching { raf.close() }
        }

        companion object {
            fun open(file: File): MappedTrieReader? {
                if (!file.exists() || file.length() < HEADER_SIZE) return null
                return runCatching {
                    val raf = RandomAccessFile(file, "r")
                    val channel = raf.channel
                    val size = channel.size().toInt()
                    val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, size.toLong())
                    buf.order(ByteOrder.BIG_ENDIAN)
                    val magic = buf.getInt(0)
                    val version = buf.getInt(4)
                    if (magic != TRIE_MAGIC || version != TRIE_VERSION) {
                        channel.close()
                        raf.close()
                        return null
                    }
                    MappedTrieReader(raf, channel, buf, size)
                }.getOrNull()
            }
        }
    }

    private data class Cursor(
        val filterIndex: Int,
        val nodeOffset: Int
    )

    fun analyze(
        sources: List<FilterTrieSource>,
        searchQuery: String = "",
        filterId: Long? = null,
        maxDisplayEntries: Int = DEFAULT_MAX_DISPLAY_ENTRIES
    ): FilterOverlapReport {
        val validSources = mutableListOf<FilterTrieSource>()
        val readers = mutableListOf<MappedTrieReader>()

        try {
            for (src in sources) {
                val reader = MappedTrieReader.open(src.trieFile) ?: continue
                validSources.add(src)
                readers.add(reader)
            }

            if (validSources.isEmpty()) {
                return FilterOverlapReport(
                    analyzedFilters = emptyList(),
                    totalRawEntries = 0,
                    totalUniqueDomains = 0,
                    overlappingDomainCount = 0,
                    perFilterStats = emptyList(),
                    pairwiseOverlaps = emptyList(),
                    duplicateEntries = emptyList(),
                    hasMoreDuplicates = false
                )
            }

            val k = readers.size
            val perFilterTotal = IntArray(k)
            val perFilterOverlap = IntArray(k)
            val pairwiseCounts = Array(k) { IntArray(k) }
            var totalRawEntries = 0
            var totalUniqueDomains = 0
            var overlappingDomainCount = 0

            val duplicates = ArrayList<OverlappingDomainEntry>(maxDisplayEntries.coerceAtMost(1000))
            var hasMoreDuplicates = false
            val queryLower = searchQuery.trim().lowercase()
            val labelStack = ArrayList<String>(16)

            fun dfs(cursors: List<Cursor>, depth: Int) {
                if (depth > MAX_DEPTH || cursors.isEmpty()) return

                // Check terminals at current domain node
                if (depth > 0) {
                    var terminalCount = 0
                    val terminalIndices = IntArray(cursors.size)
                    for (c in cursors) {
                        if (readers[c.filterIndex].isTerminal(c.nodeOffset)) {
                            terminalIndices[terminalCount++] = c.filterIndex
                        }
                    }

                    if (terminalCount > 0) {
                        totalRawEntries += terminalCount
                        totalUniqueDomains += 1
                        for (t in 0 until terminalCount) {
                            perFilterTotal[terminalIndices[t]]++
                        }

                        if (terminalCount >= 2) {
                            overlappingDomainCount += 1
                            for (t in 0 until terminalCount) {
                                perFilterOverlap[terminalIndices[t]]++
                                for (u in t + 1 until terminalCount) {
                                    val a = terminalIndices[t]
                                    val b = terminalIndices[u]
                                    pairwiseCounts[a][b]++
                                    pairwiseCounts[b][a]++
                                }
                            }

                            val matchesSelectedFilter = filterId == null ||
                                (0 until terminalCount).any { validSources[terminalIndices[it]].id == filterId }
                            if (matchesSelectedFilter) {
                                val domain = buildDomainFromStack(labelStack)
                                if (queryLower.isEmpty() || domain.contains(queryLower)) {
                                    if (duplicates.size < maxDisplayEntries) {
                                        val names = (0 until terminalCount).map { validSources[terminalIndices[it]].name }
                                        val ids = (0 until terminalCount).map { validSources[terminalIndices[it]].id }
                                        duplicates.add(OverlappingDomainEntry(domain, names, ids))
                                    } else {
                                        hasMoreDuplicates = true
                                    }
                                }
                            }
                        }
                    }
                }

                // Group children by label across all active cursors
                val childGroups = HashMap<String, MutableList<Cursor>>()
                for (c in cursors) {
                    val children = readers[c.filterIndex].readChildren(c.nodeOffset)
                    for ((label, childOffset) in children) {
                        childGroups.getOrPut(label) { ArrayList(2) }.add(Cursor(c.filterIndex, childOffset))
                    }
                }

                for ((label, nextCursors) in childGroups) {
                    labelStack.add(label)
                    dfs(nextCursors, depth + 1)
                    labelStack.removeAt(labelStack.lastIndex)
                }
            }

            val rootCursors = readers.indices.map { Cursor(it, HEADER_SIZE) }
            dfs(rootCursors, 0)

            val perFilterStats = validSources.indices.map { idx ->
                val src = validSources[idx]
                val total = perFilterTotal[idx]
                val overlap = perFilterOverlap[idx]
                FilterOverlapStat(
                    filterId = src.id,
                    filterName = src.name,
                    totalDomains = total,
                    overlappingDomains = overlap,
                    uniqueOnlyDomains = (total - overlap).coerceAtLeast(0)
                )
            }.sortedByDescending { it.overlappingDomains }

            val pairwiseOverlaps = mutableListOf<PairwiseFilterOverlap>()
            for (i in 0 until k) {
                for (j in i + 1 until k) {
                    val shared = pairwiseCounts[i][j]
                    if (shared > 0) {
                        pairwiseOverlaps.add(
                            PairwiseFilterOverlap(
                                filterAId = validSources[i].id,
                                filterAName = validSources[i].name,
                                filterBId = validSources[j].id,
                                filterBName = validSources[j].name,
                                sharedDomains = shared
                            )
                        )
                    }
                }
            }
            pairwiseOverlaps.sortByDescending { it.sharedDomains }

            return FilterOverlapReport(
                analyzedFilters = validSources,
                totalRawEntries = totalRawEntries,
                totalUniqueDomains = totalUniqueDomains,
                overlappingDomainCount = overlappingDomainCount,
                perFilterStats = perFilterStats,
                pairwiseOverlaps = pairwiseOverlaps,
                duplicateEntries = duplicates,
                hasMoreDuplicates = hasMoreDuplicates
            )
        } finally {
            readers.forEach { it.close() }
        }
    }

    /**
     * Streams the deduplicated unified domain list across all valid filter tries to [outputStream].
     * Returns the number of unique domains written.
     */
    fun exportCombinedDomains(
        sources: List<FilterTrieSource>,
        outputStream: OutputStream
    ): Int {
        val readers = mutableListOf<MappedTrieReader>()
        try {
            for (src in sources) {
                val reader = MappedTrieReader.open(src.trieFile) ?: continue
                readers.add(reader)
            }
            if (readers.isEmpty()) return 0

            var count = 0
            val labelStack = ArrayList<String>(16)
            BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
                writer.write("# BlockAds Combined Deduplicated Filter List\n")
                writer.write("# Sources: ${sources.joinToString(", ") { it.name }}\n\n")

                fun dfs(cursors: List<Cursor>, depth: Int) {
                    if (depth > MAX_DEPTH || cursors.isEmpty()) return
                    if (depth > 0 && cursors.any { readers[it.filterIndex].isTerminal(it.nodeOffset) }) {
                        writer.write(buildDomainFromStack(labelStack))
                        writer.newLine()
                        count++
                    }

                    val childGroups = HashMap<String, MutableList<Cursor>>()
                    for (c in cursors) {
                        val children = readers[c.filterIndex].readChildren(c.nodeOffset)
                        for ((label, childOffset) in children) {
                            childGroups.getOrPut(label) { ArrayList(2) }.add(Cursor(c.filterIndex, childOffset))
                        }
                    }

                    for ((label, nextCursors) in childGroups) {
                        labelStack.add(label)
                        dfs(nextCursors, depth + 1)
                        labelStack.removeAt(labelStack.lastIndex)
                    }
                }

                dfs(readers.indices.map { Cursor(it, HEADER_SIZE) }, 0)
            }
            return count
        } finally {
            readers.forEach { it.close() }
        }
    }

    private fun buildDomainFromStack(stack: List<String>): String {
        val sb = StringBuilder(stack.size * 8)
        for (i in stack.lastIndex downTo 0) {
            if (i < stack.lastIndex) sb.append('.')
            sb.append(stack[i])
        }
        return sb.toString()
    }
}
