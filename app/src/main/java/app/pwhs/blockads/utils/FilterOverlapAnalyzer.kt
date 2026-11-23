package app.pwhs.blockads.utils

import app.pwhs.blockads.data.models.FilterRuleKind
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Multi-rule overlap analyzer supporting DNS binary tries (.trie),
 * cosmetic CSS (.css), scriptlets (.scriptlets), IPs (.ips), and URL rules (.modules.json).
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
            fun open(file: File?): MappedTrieReader? {
                if (file == null || !file.exists() || file.length() < HEADER_SIZE) return null
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
        sources: List<FilterRuleSource>,
        category: OverlapRuleCategory = OverlapRuleCategory.ALL,
        searchQuery: String = "",
        filterId: Long? = null,
        maxDisplayEntries: Int = DEFAULT_MAX_DISPLAY_ENTRIES
    ): FilterOverlapReport {
        if (sources.isEmpty()) {
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

        val k = sources.size
        val perFilterTotal = IntArray(k)
        val perFilterOverlap = IntArray(k)
        val pairwiseCounts = Array(k) { IntArray(k) }
        var totalRawEntries = 0
        var totalUniqueRules = 0
        var overlappingRuleCount = 0
        val duplicates = ArrayList<OverlappingRuleEntry>(maxDisplayEntries.coerceAtMost(1000))
        var hasMoreDuplicates = false
        val ruleKindCounts = mutableMapOf<FilterRuleKind, Int>()

        // 1. Scan non-domain rules if applicable
        if (category != OverlapRuleCategory.DOMAINS) {
            val nonDomain = NonDomainRuleScanner.scan(
                sources = sources,
                category = category,
                searchQuery = searchQuery,
                filterId = filterId,
                maxDisplayEntries = maxDisplayEntries
            )
            for (i in 0 until k) {
                perFilterTotal[i] += nonDomain.perFilterTotal[i]
                perFilterOverlap[i] += nonDomain.perFilterOverlap[i]
                for (j in 0 until k) {
                    pairwiseCounts[i][j] += nonDomain.pairwiseCounts[i][j]
                }
            }
            totalRawEntries += nonDomain.totalRawEntries
            totalUniqueRules += nonDomain.totalUniqueRules
            overlappingRuleCount += nonDomain.overlappingRuleCount
            duplicates.addAll(nonDomain.duplicateEntries)
            hasMoreDuplicates = hasMoreDuplicates || nonDomain.hasMoreDuplicates
            nonDomain.ruleKindCounts.forEach { (kind, count) ->
                ruleKindCounts[kind] = (ruleKindCounts[kind] ?: 0) + count
            }
        }

        // 2. Scan domains via Trie DFS if category includes DOMAINS
        if (category.matches(FilterRuleKind.DOMAINS)) {
            val readers = sources.map { MappedTrieReader.open(it.trieFile) }
            try {
                val activeTrieIndices = readers.mapIndexedNotNull { idx, r -> if (r != null) idx else null }
                if (activeTrieIndices.isNotEmpty()) {
                    val queryLower = searchQuery.trim().lowercase()
                    val labelStack = ArrayList<String>(16)

                    fun dfs(cursors: List<Cursor>, depth: Int) {
                        if (depth > MAX_DEPTH || cursors.isEmpty()) return

                        if (depth > 0) {
                            var terminalCount = 0
                            val terminalIndices = IntArray(cursors.size)
                            for (c in cursors) {
                                val reader = readers[c.filterIndex]
                                if (reader != null && reader.isTerminal(c.nodeOffset)) {
                                    terminalIndices[terminalCount++] = c.filterIndex
                                }
                            }

                            if (terminalCount > 0) {
                                totalRawEntries += terminalCount
                                totalUniqueRules += 1
                                ruleKindCounts[FilterRuleKind.DOMAINS] =
                                    (ruleKindCounts[FilterRuleKind.DOMAINS] ?: 0) + 1
                                for (t in 0 until terminalCount) {
                                    perFilterTotal[terminalIndices[t]]++
                                }

                                if (terminalCount >= 2) {
                                    overlappingRuleCount += 1
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
                                        (0 until terminalCount).any { sources[terminalIndices[it]].id == filterId }
                                    if (matchesSelectedFilter) {
                                        val domain = buildDomainFromStack(labelStack)
                                        if (queryLower.isEmpty() || domain.contains(queryLower)) {
                                            if (duplicates.size < maxDisplayEntries) {
                                                val names = (0 until terminalCount).map { sources[terminalIndices[it]].name }
                                                val ids = (0 until terminalCount).map { sources[terminalIndices[it]].id }
                                                duplicates.add(
                                                    OverlappingRuleEntry(
                                                        rule = domain,
                                                        kind = FilterRuleKind.DOMAINS,
                                                        filterNames = names,
                                                        filterIds = ids
                                                    )
                                                )
                                            } else {
                                                hasMoreDuplicates = true
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val childGroups = HashMap<String, MutableList<Cursor>>()
                        for (c in cursors) {
                            val reader = readers[c.filterIndex] ?: continue
                            val children = reader.readChildren(c.nodeOffset)
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

                    val rootCursors = activeTrieIndices.map { Cursor(it, HEADER_SIZE) }
                    dfs(rootCursors, 0)
                }
            } finally {
                readers.forEach { it?.close() }
            }
        }

        val perFilterStats = sources.indices.map { idx ->
            val src = sources[idx]
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
                            filterAId = sources[i].id,
                            filterAName = sources[i].name,
                            filterBId = sources[j].id,
                            filterBName = sources[j].name,
                            sharedDomains = shared
                        )
                    )
                }
            }
        }
        pairwiseOverlaps.sortByDescending { it.sharedDomains }

        return FilterOverlapReport(
            analyzedFilters = sources,
            totalRawEntries = totalRawEntries,
            totalUniqueDomains = totalUniqueRules,
            overlappingDomainCount = overlappingRuleCount,
            perFilterStats = perFilterStats,
            pairwiseOverlaps = pairwiseOverlaps,
            duplicateEntries = duplicates,
            hasMoreDuplicates = hasMoreDuplicates,
            ruleKindCounts = ruleKindCounts
        )
    }

    /**
     * Streams deduplicated unified rules across all valid filters to [outputStream].
     * Returns total unique rules written.
     */
    fun exportCombinedRules(
        sources: List<FilterRuleSource>,
        category: OverlapRuleCategory = OverlapRuleCategory.ALL,
        outputStream: OutputStream
    ): Int {
        var count = 0
        BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
            writer.write("# BlockAds Combined Deduplicated Rules\n")
            writer.write("# Category: ${category.name}\n")
            writer.write("# Sources: ${sources.joinToString(", ") { it.name }}\n\n")

            // 1. Export domains if applicable
            if (category.matches(FilterRuleKind.DOMAINS)) {
                val readers = sources.map { MappedTrieReader.open(it.trieFile) }
                try {
                    val activeTrieIndices = readers.mapIndexedNotNull { idx, r -> if (r != null) idx else null }
                    if (activeTrieIndices.isNotEmpty()) {
                        writer.write("# === DNS Domains ===\n")
                        val labelStack = ArrayList<String>(16)
                        fun dfs(cursors: List<Cursor>, depth: Int) {
                            if (depth > MAX_DEPTH || cursors.isEmpty()) return
                            if (depth > 0 && cursors.any { readers[it.filterIndex]?.isTerminal(it.nodeOffset) == true }) {
                                writer.write(buildDomainFromStack(labelStack))
                                writer.newLine()
                                count++
                            }

                            val childGroups = HashMap<String, MutableList<Cursor>>()
                            for (c in cursors) {
                                val reader = readers[c.filterIndex] ?: continue
                                val children = reader.readChildren(c.nodeOffset)
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

                        val rootCursors = activeTrieIndices.map { Cursor(it, HEADER_SIZE) }
                        dfs(rootCursors, 0)
                        writer.newLine()
                    }
                } finally {
                    readers.forEach { it?.close() }
                }
            }

            // 2. Export non-domain rules if applicable
            if (category != OverlapRuleCategory.DOMAINS) {
                val uniqueRules = NonDomainRuleScanner.collectUniqueRules(sources, category)
                if (uniqueRules.isNotEmpty()) {
                    writer.write("# === Content & Cosmetic Rules ===\n")
                    for ((rule, _) in uniqueRules) {
                        writer.write(rule)
                        writer.newLine()
                        count++
                    }
                }
            }
        }
        return count
    }

    fun exportCombinedDomains(
        sources: List<FilterRuleSource>,
        outputStream: OutputStream
    ): Int = exportCombinedRules(sources, OverlapRuleCategory.DOMAINS, outputStream)

    private fun buildDomainFromStack(stack: List<String>): String {
        val sb = StringBuilder(stack.size * 8)
        for (i in stack.lastIndex downTo 0) {
            if (i < stack.lastIndex) sb.append('.')
            sb.append(stack[i])
        }
        return sb.toString()
    }
}
