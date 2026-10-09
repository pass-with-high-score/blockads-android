package app.pwhs.blockads.utils

data class ParsedFilterRule(
    val type: String,
    val param: String,
    val policy: String,
    val rawLine: String
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

        lines.add(insertIndex, ruleLine)
        return lines.joinToString("\n")
    }

    fun removeRuleFromContent(content: String, rawLine: String): String {
        val trimmedTarget = rawLine.trim()
        val lines = content.lines().filter { it.trim() != trimmedTarget }
        return lines.joinToString("\n")
    }

    fun parseFilterRules(content: String): List<ParsedFilterRule> {
        val result = mutableListOf<ParsedFilterRule>()
        val lines = content.lines()
        var inFilterSection = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inFilterSection = trimmed.equals("[filter_local]", ignoreCase = true)
                continue
            }
            if (inFilterSection && trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith(";") && !trimmed.startsWith("//")) {
                val parts = trimmed.split(",").map { it.trim() }
                if (parts.size >= 3) {
                    result.add(
                        ParsedFilterRule(
                            type = parts[0].uppercase(),
                            param = parts[1],
                            policy = parts[2].uppercase(),
                            rawLine = trimmed
                        )
                    )
                } else if (parts.size == 2 && parts[0].equals("final", ignoreCase = true)) {
                    result.add(
                        ParsedFilterRule(
                            type = "FINAL",
                            param = "",
                            policy = parts[1].uppercase(),
                            rawLine = trimmed
                        )
                    )
                }
            }
        }
        return result
    }
}
