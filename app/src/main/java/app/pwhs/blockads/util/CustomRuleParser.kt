package app.pwhs.blockads.util

import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType

object CustomRuleParser {
    
    /**
     * Parse a custom DNS rule string and create a CustomDnsRule object.
     * 
     * Supported formats:
     * - Block: `||example.com^` or `example.com`
     * - Allow: `@@||example.com^`
     * - Comment: `! This is a comment`
     * 
     * @param ruleText The raw rule text
     * @return CustomDnsRule or null if the rule is invalid
     */
    fun parseRule(ruleText: String): CustomDnsRule? {
        val trimmed = ruleText.trim()
        if (trimmed.isEmpty()) return null
        
        return when {
            // Comment line
            trimmed.startsWith("!") -> {
                CustomDnsRule(
                    rule = trimmed,
                    ruleType = RuleType.COMMENT,
                    domain = "",
                    isEnabled = true
                )
            }
            
            // Allow rule: @@||example.com^
            trimmed.startsWith("@@||") && trimmed.endsWith("^") -> {
                val domain = trimmed.removePrefix("@@||").removeSuffix("^").trim()
                if (domain.isEmpty() || !isValidDomain(domain)) return null
                CustomDnsRule(
                    rule = trimmed,
                    ruleType = RuleType.ALLOW,
                    domain = domain.lowercase(),
                    isEnabled = true
                )
            }
            
            // Allow rule: @@example.com
            trimmed.startsWith("@@") -> {
                val domain = trimmed.removePrefix("@@").trim()
                if (domain.isEmpty() || !isValidDomain(domain)) return null
                CustomDnsRule(
                    rule = trimmed,
                    ruleType = RuleType.ALLOW,
                    domain = domain.lowercase(),
                    isEnabled = true
                )
            }
            
            // Block rule: ||example.com^
            trimmed.startsWith("||") && trimmed.endsWith("^") -> {
                val domain = trimmed.removePrefix("||").removeSuffix("^").trim()
                if (domain.isEmpty() || !isValidDomain(domain)) return null
                CustomDnsRule(
                    rule = trimmed,
                    ruleType = RuleType.BLOCK,
                    domain = domain.lowercase(),
                    isEnabled = true
                )
            }
            
            // Block rule: example.com (simple format)
            else -> {
                val domain = trimmed
                if (domain.isEmpty() || !isValidDomain(domain)) return null
                CustomDnsRule(
                    rule = trimmed,
                    ruleType = RuleType.BLOCK,
                    domain = domain.lowercase(),
                    isEnabled = true
                )
            }
        }
    }
    
    /**
     * Parse multiple rules from text (one rule per line).
     * 
     * @param rulesText Multi-line text with one rule per line
     * @return List of parsed CustomDnsRule objects (invalid rules are skipped)
     */
    fun parseRules(rulesText: String): List<CustomDnsRule> {
        return rulesText.lines()
            .mapNotNull { line -> parseRule(line) }
    }
    
    /**
     * Basic domain validation.
     * Checks if the string looks like a valid domain name.
     */
    private fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty()) return false
        if (domain.startsWith(".") || domain.endsWith(".")) return false
        if (domain.contains("..")) return false
        
        // Basic regex for domain validation
        val domainRegex = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$")
        return domainRegex.matches(domain)
    }
    
    /**
     * Format a domain into a block rule.
     * 
     * @param domain The domain to block
     * @param useAdblockFormat If true, returns "||domain^", else returns "domain"
     */
    fun formatBlockRule(domain: String, useAdblockFormat: Boolean = true): String {
        return if (useAdblockFormat) {
            "||${domain.lowercase()}^"
        } else {
            domain.lowercase()
        }
    }
    
    /**
     * Format a domain into an allow rule.
     * 
     * @param domain The domain to allow
     */
    fun formatAllowRule(domain: String): String {
        return "@@||${domain.lowercase()}^"
    }
}
