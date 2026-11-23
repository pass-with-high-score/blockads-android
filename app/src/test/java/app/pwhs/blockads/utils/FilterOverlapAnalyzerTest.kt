package app.pwhs.blockads.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.IdentityHashMap

class FilterOverlapAnalyzerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `analyzes overlap, unique counts, pairwise counts, and combined export`() {
        val trieA = writeTrieFile("a.trie", listOf("ads.example.com", "tracker.net", "unique-a.org"))
        val trieB = writeTrieFile("b.trie", listOf("ads.example.com", "tracker.net", "unique-b.io"))

        val sources = listOf(
            FilterTrieSource(1L, "List A", trieA),
            FilterTrieSource(2L, "List B", trieB)
        )

        val report = FilterOverlapAnalyzer.analyze(sources)

        assertEquals(2, report.analyzedFilters.size)
        assertEquals(6, report.totalRawEntries)
        assertEquals(4, report.totalUniqueDomains)
        assertEquals(2, report.overlappingDomainCount)
        assertEquals(2, report.redundantEntries)

        assertEquals(1, report.pairwiseOverlaps.size)
        assertEquals(2, report.pairwiseOverlaps[0].sharedDomains)

        val duplicateDomains = report.duplicateEntries.map { it.domain }.toSet()
        assertEquals(setOf("ads.example.com", "tracker.net"), duplicateDomains)

        val searchReport = FilterOverlapAnalyzer.analyze(sources, searchQuery = "tracker")
        assertEquals(listOf("tracker.net"), searchReport.duplicateEntries.map { it.domain })

        val out = ByteArrayOutputStream()
        val exportedCount = FilterOverlapAnalyzer.exportCombinedDomains(sources, out)
        assertEquals(4, exportedCount)
        val exportedText = out.toString(Charsets.UTF_8.name())
        assertTrue(exportedText.contains("ads.example.com"))
        assertTrue(exportedText.contains("unique-a.org"))
        assertTrue(exportedText.contains("unique-b.io"))
    }

    @Test
    fun `analyzes cosmetic css, scriptlets, ips, and modules overlap across categories`() {
        val cssA = tempFolder.newFile("a.css").apply {
            writeText("##.ad-banner\n! comment\nexample.com##.sponsor\n")
        }
        val cssB = tempFolder.newFile("b.css").apply {
            writeText("##.ad-banner\nother.com##.popup\n")
        }
        val scriptA = tempFolder.newFile("a.scriptlets").apply {
            writeText("##+js(set, foo, false)\n")
        }
        val scriptB = tempFolder.newFile("b.scriptlets").apply {
            writeText("##+js(set, foo, false)\n##+js(abort-current-script)\n")
        }
        val ipsA = tempFolder.newFile("a.ips").apply {
            writeText("1.2.3.4\n")
        }
        val ipsB = tempFolder.newFile("b.ips").apply {
            writeText("1.2.3.4\n8.8.8.8\n")
        }

        val sources = listOf(
            FilterRuleSource(
                id = 1L,
                name = "List A",
                cssFile = cssA,
                scriptletsFile = scriptA,
                ipsFile = ipsA
            ),
            FilterRuleSource(
                id = 2L,
                name = "List B",
                cssFile = cssB,
                scriptletsFile = scriptB,
                ipsFile = ipsB
            )
        )

        val cosmeticReport = FilterOverlapAnalyzer.analyze(sources, category = OverlapRuleCategory.COSMETIC)
        assertEquals(3, cosmeticReport.totalUniqueDomains)
        assertEquals(1, cosmeticReport.overlappingDomainCount)
        assertEquals("##.ad-banner", cosmeticReport.duplicateEntries[0].rule)
        assertEquals(app.pwhs.blockads.data.models.FilterRuleKind.COSMETIC, cosmeticReport.duplicateEntries[0].kind)

        val scriptReport = FilterOverlapAnalyzer.analyze(sources, category = OverlapRuleCategory.SCRIPTLETS)
        assertEquals(2, scriptReport.totalUniqueDomains)
        assertEquals(1, scriptReport.overlappingDomainCount)
        assertEquals("##+js(set, foo, false)", scriptReport.duplicateEntries[0].rule)
        assertEquals(app.pwhs.blockads.data.models.FilterRuleKind.SCRIPTLETS, scriptReport.duplicateEntries[0].kind)

        val allReport = FilterOverlapAnalyzer.analyze(sources, category = OverlapRuleCategory.ALL)
        assertEquals(7, allReport.totalUniqueDomains)
        assertEquals(3, allReport.overlappingDomainCount)
        val duplicateRules = allReport.duplicateEntries.map { it.rule }.toSet()
        assertTrue(duplicateRules.contains("##.ad-banner"))
        assertTrue(duplicateRules.contains("##+js(set, foo, false)"))
        assertTrue(duplicateRules.contains("1.2.3.4"))

        val out = ByteArrayOutputStream()
        val exportedCount = FilterOverlapAnalyzer.exportCombinedRules(sources, OverlapRuleCategory.ALL, out)
        assertEquals(7, exportedCount)
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.contains("##.ad-banner"))
        assertTrue(text.contains("1.2.3.4"))
    }

    private class TrieNode {
        var isTerminal = false
        val children = linkedMapOf<String, TrieNode>()
    }

    private fun writeTrieFile(name: String, domains: List<String>): File {
        val root = TrieNode()
        for (domain in domains) {
            val labels = domain.split(".")
            var curr = root
            for (i in labels.lastIndex downTo 0) {
                curr = curr.children.getOrPut(labels[i]) { TrieNode() }
            }
            curr.isTerminal = true
        }

        val headerSize = 16
        val nodes = mutableListOf<TrieNode>()
        fun collect(n: TrieNode) {
            nodes.add(n)
            n.children.values.forEach { collect(it) }
        }
        collect(root)

        val offsets = IdentityHashMap<TrieNode, Int>()
        var cursor = headerSize
        for (n in nodes) {
            offsets[n] = cursor
            cursor += 1 + 4
            for (label in n.children.keys) {
                cursor += 2 + label.toByteArray(Charsets.UTF_8).size + 4
            }
        }

        val file = tempFolder.newFile(name)
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x54524945) // "TRIE"
            out.writeInt(2)          // version 2
            out.writeInt(domains.size)
            out.writeInt(0)
            for (n in nodes) {
                out.writeByte(if (n.isTerminal) 1 else 0)
                out.writeInt(n.children.size)
                for ((label, child) in n.children) {
                    val bytes = label.toByteArray(Charsets.UTF_8)
                    out.writeShort(bytes.size)
                    out.write(bytes)
                    out.writeInt(offsets.getValue(child))
                }
            }
        }
        return file
    }
}
