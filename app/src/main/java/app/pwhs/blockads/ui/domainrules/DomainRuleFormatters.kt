package app.pwhs.blockads.ui.domainrules

import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.utils.ParsedFilterRule

internal object DomainRuleFormatters {

    fun sanitizeDomain(input: String): String {
        var d = input.trim().lowercase()
        if (d.startsWith("http://")) d = d.removePrefix("http://")
        if (d.startsWith("https://")) d = d.removePrefix("https://")
        val slash = d.indexOf('/')
        if (slash != -1) d = d.substring(0, slash)
        val colon = d.indexOf(':')
        if (colon != -1) d = d.substring(0, colon)
        return d.trim()
    }

    fun sanitizeParam(type: String, param: String): String {
        return when (type.uppercase()) {
            "HOST", "DOMAIN", "HOST-SUFFIX", "DOMAIN-SUFFIX" -> sanitizeDomain(param)
            "HOST-KEYWORD", "DOMAIN-KEYWORD", "HOST-WILDCARD", "DOMAIN-WILDCARD", "GEOSITE" -> param.trim().lowercase()
            else -> param.trim()
        }
    }

    fun toParsedFilterRule(rule: CustomDnsRule): ParsedFilterRule {
        val raw = rule.rule.trim()
        val firstComma = raw.indexOf(',')
        val defaultPolicy = if (rule.ruleType == RuleType.ALLOW) "DIRECT" else "REJECT"
        val (type, param, policy) = when {
            firstComma > 0 -> {
                val head = raw.substring(0, firstComma).trim().uppercase()
                val rest = raw.substring(firstComma + 1).trim()
                if (head == "FINAL" || head == "MATCH") {
                    Triple(head, "", rest.uppercase())
                } else {
                    // Strip trailing ", no-resolve" option if present
                    val withoutOpt = if (rest.lowercase().endsWith(", no-resolve") || rest.lowercase().endsWith(",no-resolve")) {
                        rest.substringBeforeLast(',').trim()
                    } else {
                        rest
                    }
                    val lastComma = withoutOpt.lastIndexOf(',')
                    if (lastComma > 0) {
                        Triple(
                            head,
                            withoutOpt.substring(0, lastComma).trim(),
                            withoutOpt.substring(lastComma + 1).trim().uppercase()
                        )
                    } else {
                        Triple(head, withoutOpt, defaultPolicy)
                    }
                }
            }
            raw.startsWith("||") && raw.endsWith("^") ->
                Triple("HOST-SUFFIX", rule.domain, defaultPolicy)
            raw.startsWith("@@||") && raw.endsWith("^") ->
                Triple("HOST-SUFFIX", rule.domain, "DIRECT")
            else -> Triple("HOST", rule.domain, defaultPolicy)
        }
        return ParsedFilterRule(
            id = "custom:${rule.id}",
            type = type,
            param = param,
            policy = policy,
            isEnabled = rule.isEnabled,
            rawLine = "custom:${rule.id}",
            packageName = rule.packageName
        )
    }

    fun formatRuleLine(type: String, param: String, policy: String, option: String = ""): String {
        val upperType = type.uppercase()
        if (upperType == "FINAL" || upperType == "MATCH") {
            return "${upperType.lowercase()}, ${policy.lowercase()}"
        }
        val base = "$upperType, $param, ${policy.uppercase()}"
        return if (option.isNotBlank()) "$base, $option" else base
    }

    fun normalizePackageNames(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(",")
    }
}
