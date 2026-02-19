package app.pwhs.blockads.data

import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A memory-efficient reversed-label Trie for domain matching.
 *
 * Domains are stored by reversing their labels (splitting on '.').
 * For example, "ads.google.com" is stored as ["com", "google", "ads"].
 * This allows shared prefixes (like ".com", ".net") to be stored once,
 * reducing memory usage by ~60-70% compared to a HashSet.
 *
 * The Trie naturally supports parent-domain matching:
 * if "google.com" is blocked, any lookup for "ads.google.com"
 * will find the terminal node at "google" while traversing the tree.
 *
 * Supports serialization to a compact binary format that can be
 * memory-mapped (mmap) for near-zero heap usage at runtime.
 */
class DomainTrie {

    private class TrieNode {
        var isTerminal: Boolean = false
        var children: HashMap<String, TrieNode>? = null
        @JvmField var bfsOffset: Int = 0 // transient, used only during serialization

        fun getOrCreateChild(label: String): TrieNode {
            val map = children ?: HashMap<String, TrieNode>(4).also { children = it }
            return map.getOrPut(label) { TrieNode() }
        }

        fun getChild(label: String): TrieNode? = children?.get(label)
    }

    private var root = TrieNode()
    private var _size = 0

    /** Number of terminal (blocked) domains in the trie. */
    val size: Int get() = _size

    /**
     * Add a domain to the trie.
     * Domain is split by '.' and labels are reversed for storage.
     */
    fun add(domain: String) {
        if (domain.isBlank()) return

        val labels = domain.split('.')
        var node = root
        // Traverse in reverse: "ads.google.com" → com → google → ads
        for (i in labels.lastIndex downTo 0) {
            node = node.getOrCreateChild(labels[i])
        }
        if (!node.isTerminal) {
            node.isTerminal = true
            _size++
        }
    }

    /**
     * Check if an exact domain exists in the trie.
     */
    fun contains(domain: String): Boolean {
        val labels = domain.split('.')
        var node = root
        for (i in labels.lastIndex downTo 0) {
            node = node.getChild(labels[i]) ?: return false
        }
        return node.isTerminal
    }

    /**
     * Check if a domain or any of its parent domains is in the trie.
     *
     * For "sub.ads.google.com", checks:
     * 1. While traversing com → google: if "google.com" is terminal → true
     * 2. Continue to ads: if "ads.google.com" is terminal → true
     * 3. Continue to sub: if "sub.ads.google.com" is terminal → true
     *
     * This replaces the old checkDomainAndParents() loop with a single
     * tree traversal — more efficient for deep subdomains.
     */
    fun containsOrParent(domain: String): Boolean {
        val labels = domain.split('.')
        var node = root
        for (i in labels.lastIndex downTo 0) {
            node = node.getChild(labels[i]) ?: return false
            if (node.isTerminal) return true
        }
        return false
    }

    /** Clear all entries from the trie. */
    fun clear() {
        root = TrieNode()
        _size = 0
    }

    // ═══════════════════════════════════════════════════════════
    // Binary serialization
    // ═══════════════════════════════════════════════════════════

    companion object {
        private const val MAGIC = 0x54524945 // "TRIE"
        private const val VERSION = 1

        /**
         * Load a DomainTrie from a binary file using memory-mapped I/O.
         * The returned trie wraps a MappedByteBuffer for near-zero heap usage.
         * Lookup operations read directly from the mapped file.
         */
        fun loadFromMmap(file: File): MmapDomainTrie? {
            if (!file.exists() || file.length() < 16) return null
            return try {
                val raf = RandomAccessFile(file, "r")
                val channel = raf.channel
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                channel.close()
                raf.close()

                val magic = buffer.getInt()
                val version = buffer.getInt()
                if (magic != MAGIC || version != VERSION) return null

                val nodeCount = buffer.getInt()
                val domainCount = buffer.getInt()

                MmapDomainTrie(buffer, headerSize = 16, nodeCount, domainCount)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Serialize the trie to a compact binary file.
     * Memory-efficient: uses BFS order with offsets embedded in TrieNode
     * to avoid creating a large HashMap<TrieNode, Int>.
     *
     * Format (all big-endian):
     * ┌─────────────────────────────────────┐
     * │ Header: magic(4) version(4) nodeCount(4) domainCount(4) │
     * ├─────────────────────────────────────┤
     * │ Nodes in BFS order:                                      │
     * │   isTerminal(1) childCount(2)                            │
     * │   For each child:                                        │
     * │     labelLen(2) label(N) childOffset(4)                  │
     * └─────────────────────────────────────┘
     */
    fun saveToBinary(file: File) {
        // Two-pass BFS: no ArrayList or HashMap — only the BFS queue exists,
        // which holds at most one "level" of the tree at a time.

        // Pass 1: BFS to calculate byte offsets and count nodes.
        // Offsets are stored directly in TrieNode.bfsOffset.
        val queue = ArrayDeque<TrieNode>()
        var nodeCount = 0

        queue.add(root)
        var offset = 16 // header size
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.bfsOffset = offset
            nodeCount++

            offset += 3 // isTerminal(1) + childCount(2)
            node.children?.forEach { (label, child) ->
                offset += 2 + label.toByteArray(Charsets.UTF_8).size + 4
                queue.add(child)
            }
        }

        // Pass 2: BFS again to write bytes — read offsets from TrieNode.bfsOffset.
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(nodeCount)
            out.writeInt(_size)

            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                out.writeByte(if (node.isTerminal) 1 else 0)
                val childCount = node.children?.size ?: 0
                out.writeShort(childCount)

                node.children?.forEach { (label, child) ->
                    val labelBytes = label.toByteArray(Charsets.UTF_8)
                    out.writeShort(labelBytes.size)
                    out.write(labelBytes)
                    out.writeInt(child.bfsOffset)
                    queue.add(child)
                }

                // Clear offset after writing to free the int slot
                node.bfsOffset = 0
            }
        }
    }
}

/**
 * A read-only DomainTrie backed by a memory-mapped file.
 * All lookups read directly from the MappedByteBuffer —
 * the OS manages page-in/page-out, resulting in near-zero heap usage.
 */
class MmapDomainTrie(
    private val buffer: ByteBuffer,
    private val headerSize: Int,
    val nodeCount: Int,
    val domainCount: Int
) {
    /** Alias for domainCount for API compatibility. */
    val size: Int get() = domainCount

    /**
     * Check if a domain or any parent domain exists in the trie.
     * Reads directly from the memory-mapped buffer.
     */
    fun containsOrParent(domain: String): Boolean {
        val labels = domain.split('.')
        var nodeOffset = headerSize // root node starts after header

        for (i in labels.lastIndex downTo 0) {
            val label = labels[i]
            val childOffset = findChildOffset(nodeOffset, label) ?: return false
            nodeOffset = childOffset

            // Check if this node is terminal (parent domain match)
            if (isTerminal(nodeOffset)) return true
        }
        return false
    }

    /**
     * Check if an exact domain exists in the trie.
     */
    fun contains(domain: String): Boolean {
        val labels = domain.split('.')
        var nodeOffset = headerSize

        for (i in labels.lastIndex downTo 0) {
            val label = labels[i]
            val childOffset = findChildOffset(nodeOffset, label) ?: return false
            nodeOffset = childOffset
        }
        return isTerminal(nodeOffset)
    }

    private fun isTerminal(nodeOffset: Int): Boolean {
        return buffer.get(nodeOffset).toInt() != 0
    }

    private fun findChildOffset(nodeOffset: Int, targetLabel: String): Int? {
        val targetBytes = targetLabel.toByteArray(Charsets.UTF_8)
        var pos = nodeOffset + 1 // skip isTerminal byte
        val childCount = buffer.getShort(pos).toInt() and 0xFFFF
        pos += 2

        for (c in 0 until childCount) {
            val labelLen = buffer.getShort(pos).toInt() and 0xFFFF
            pos += 2

            // Compare label bytes directly
            if (labelLen == targetBytes.size) {
                var match = true
                for (b in 0 until labelLen) {
                    if (buffer.get(pos + b) != targetBytes[b]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    // Found — return child node offset
                    return buffer.getInt(pos + labelLen)
                }
            }

            pos += labelLen + 4 // skip label bytes + child offset
        }

        return null // label not found in children
    }
}
