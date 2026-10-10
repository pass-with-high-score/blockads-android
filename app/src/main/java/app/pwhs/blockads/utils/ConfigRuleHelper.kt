package app.pwhs.blockads.utils

data class ParsedFilterRule(
    val id: String = "",
    val type: String,
    val param: String,
    val policy: String,
    val rawLine: String,
    val isEnabled: Boolean = true
)

object ConfigRuleHelper {

    fun appendRuleToSection(content: String, sectionName: String, ruleLine: String): String {
        val lines = content.lines().toMutableList()
        val headerPattern = "[$sectionName]"
        val sectionIndex = lines.indexOfFirst { it.trim().equals(headerPattern, ignoreCase = true) }

        if (sectionIndex == -1) {
            val prefix = if (content.endsWith("\n") || content.isEmpty()) "" else "\n"
            return content + prefix + "\n[$sectionName]\n$ruleLine\n"
        }

        var insertIndex = lines.size
        for (i in (sectionIndex + 1) until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                insertIndex = i
                break
            }
        }

        if (!ruleLine.trim().startsWith("final", ignoreCase = true)) {
            val finalIndex = (sectionIndex + 1 until insertIndex).firstOrNull { i ->
                val trimmed = lines[i].trim()
                !trimmed.startsWith("#") && !trimmed.startsWith(";") && !trimmed.startsWith("//") && trimmed.startsWith("final", ignoreCase = true)
            }
            if (finalIndex != null) {
                insertIndex = finalIndex
            }
        }

        lines.add(insertIndex, ruleLine)
        return lines.joinToString("\n")
    }

    fun normalizeFilterLocalSection(content: String): String {
        val lines = content.lines().toMutableList()
        val sectionIndex = lines.indexOfFirst { it.trim().equals("[filter_local]", ignoreCase = true) }
        if (sectionIndex == -1) return content

        var endIndex = lines.size
        for (i in (sectionIndex + 1) until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                endIndex = i
                break
            }
        }

        val sectionLines = lines.subList(sectionIndex + 1, endIndex)
        val nonFinalLines = mutableListOf<String>()
        val finalLines = mutableListOf<String>()

        for (line in sectionLines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#") && !trimmed.startsWith(";") && !trimmed.startsWith("//") && trimmed.startsWith("final", ignoreCase = true)) {
                finalLines.add(line)
            } else {
                nonFinalLines.add(line)
            }
        }

        if (finalLines.isNotEmpty()) {
            val combined = nonFinalLines + finalLines
            lines.subList(sectionIndex + 1, endIndex).clear()
            lines.addAll(sectionIndex + 1, combined)
        }

        return lines.joinToString("\n")
    }

    fun removeRuleFromContent(content: String, rawLine: String): String {
        val trimmedTarget = rawLine.trim()
        val lines = content.lines().filter { it.trim() != trimmedTarget }
        return lines.joinToString("\n")
    }

    fun replaceRuleInContent(content: String, oldDomain: String, newRuleLine: String): String {
        val lines = content.lines().toMutableList()
        var inFilterSection = false
        var targetIndex = -1
        val targetParam = oldDomain.trim().lowercase()

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inFilterSection = trimmed.equals("[filter_local]", ignoreCase = true)
                continue
            }
            if (inFilterSection && trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith(";") && !trimmed.startsWith("//")) {
                val parts = trimmed.split(",").map { it.trim() }
                if (parts.size >= 2 && parts[1].equals(targetParam, ignoreCase = true)) {
                    targetIndex = i
                    break
                }
            }
        }

        if (targetIndex == -1) {
            inFilterSection = false
            for (i in lines.indices) {
                val trimmed = lines[i].trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inFilterSection = trimmed.equals("[filter_local]", ignoreCase = true)
                    continue
                }
                if (inFilterSection && trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith(";") && !trimmed.startsWith("//")) {
                    if (trimmed.contains(targetParam, ignoreCase = true)) {
                        targetIndex = i
                        break
                    }
                }
            }
        }

        return if (targetIndex != -1) {
            lines[targetIndex] = newRuleLine
            lines.joinToString("\n")
        } else {
            appendRuleToSection(content, "filter_local", newRuleLine)
        }
    }

    private val VALID_RULE_TYPES = setOf(
        "HOST", "HOST-SUFFIX", "HOST-KEYWORD",
        "IP-CIDR", "IP-CIDR6", "GEOIP", "USER-AGENT", "FINAL"
    )

    fun toggleRuleInContent(content: String, rule: ParsedFilterRule): String {
        val lines = content.lines().toMutableList()
        val targetRaw = rule.rawLine.trim()
        val targetIndex = lines.indexOfFirst { it.trim() == targetRaw }
        if (targetIndex != -1) {
            val currentLine = lines[targetIndex].trim()
            if (rule.isEnabled) {
                // Enabled -> comment out
                lines[targetIndex] = "# $currentLine"
            } else {
                // Disabled -> uncomment
                lines[targetIndex] = currentLine.trimStart('#', ';', '/').trim()
            }
            return lines.joinToString("\n")
        }
        return content
    }

    fun parseFilterRules(content: String): List<ParsedFilterRule> {
        val result = mutableListOf<ParsedFilterRule>()
        val lines = content.lines()
        var inFilterSection = false

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inFilterSection = trimmed.equals("[filter_local]", ignoreCase = true)
                continue
            }
            if (!inFilterSection || trimmed.isEmpty()) continue

            val isCommented = trimmed.startsWith("#") || trimmed.startsWith(";") || trimmed.startsWith("//")
            val cleanLine = if (isCommented) {
                trimmed.trimStart('#', ';', '/').trim()
            } else {
                trimmed
            }

            val parts = cleanLine.split(",").map { it.trim() }
            if (parts.size >= 3) {
                val rType = parts[0].uppercase()
                if (VALID_RULE_TYPES.contains(rType)) {
                    result.add(
                        ParsedFilterRule(
                            id = "$rType:${parts[1]}:${parts[2]}:$index",
                            type = rType,
                            param = parts[1],
                            policy = parts[2].uppercase(),
                            rawLine = trimmed,
                            isEnabled = !isCommented
                        )
                    )
                }
            } else if (parts.size == 2 && parts[0].equals("final", ignoreCase = true)) {
                result.add(
                    ParsedFilterRule(
                        id = "FINAL::$cleanLine:$index",
                        type = "FINAL",
                        param = "",
                        policy = parts[1].uppercase(),
                        rawLine = trimmed,
                        isEnabled = !isCommented
                    )
                )
            }
        }
        return result
    }
}
