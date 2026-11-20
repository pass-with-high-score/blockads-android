package app.pwhs.blockads.data.repository

import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType

class CustomRuleMatcher private constructor(
    private val global: RuleBucket,
    private val perApp: Map<String, RuleBucket>
) {
    private class RuleBucket(
        val exactAllow: Set<String>,
        val suffixAllow: Set<String>,
        val keywordAllow: List<String>,
        val regexAllow: List<Regex>,
        val exactBlock: Set<String>,
        val suffixBlock: Set<String>,
        val keywordBlock: List<String>,
        val regexBlock: List<Regex>
    ) {
        fun match(normalizedDomain: String): Int {
            if (matchesAny(normalizedDomain, exactAllow, suffixAllow, keywordAllow, regexAllow)) {
                return MATCH_ALLOW
            }
            if (matchesAny(normalizedDomain, exactBlock, suffixBlock, keywordBlock, regexBlock)) {
                return MATCH_BLOCK
            }
            return MATCH_NONE
        }

        private fun matchesAny(
            domain: String,
            exact: Set<String>,
            suffix: Set<String>,
            keywords: List<String>,
            regexes: List<Regex>
        ): Boolean {
            if (exact.contains(domain)) return true
            if (suffix.isNotEmpty() && matchesDomainOrParent(domain, suffix)) return true
            for (kw in keywords) {
                if (domain.contains(kw)) return true
            }
            for (re in regexes) {
                if (re.containsMatchIn(domain)) return true
            }
            return false
        }

        private fun matchesDomainOrParent(domain: String, set: Set<String>): Boolean {
            if (set.contains(domain)) return true
            var d = domain
            while (d.contains('.')) {
                d = d.substringAfter('.')
                if (set.contains(d) || set.contains("*.$d")) return true
            }
            return false
        }
    }

    private class MutableBucket {
        val exactAllow = HashSet<String>()
        val suffixAllow = HashSet<String>()
        val keywordAllow = ArrayList<String>()
        val regexAllow = ArrayList<Regex>()
        val exactBlock = HashSet<String>()
        val suffixBlock = HashSet<String>()
        val keywordBlock = ArrayList<String>()
        val regexBlock = ArrayList<Regex>()

        fun add(kind: MatchKind, pattern: String, isAllow: Boolean) {
            when (kind) {
                MatchKind.EXACT -> if (isAllow) exactAllow.add(pattern) else exactBlock.add(pattern)
                MatchKind.SUFFIX -> if (isAllow) suffixAllow.add(pattern) else suffixBlock.add(pattern)
                MatchKind.KEYWORD -> if (isAllow) keywordAllow.add(pattern) else keywordBlock.add(pattern)
                MatchKind.WILDCARD -> {
                    val compiled = compileWildcard(pattern) ?: return
                    if (isAllow) regexAllow.add(compiled) else regexBlock.add(compiled)
                }
                MatchKind.REGEX -> {
                    val compiled = compileRegex(pattern) ?: return
                    if (isAllow) regexAllow.add(compiled) else regexBlock.add(compiled)
                }
            }
        }

        fun freeze() = RuleBucket(
            exactAllow = exactAllow,
            suffixAllow = suffixAllow,
            keywordAllow = keywordAllow,
            regexAllow = regexAllow,
            exactBlock = exactBlock,
            suffixBlock = suffixBlock,
            keywordBlock = keywordBlock,
            regexBlock = regexBlock
        )
    }

    private enum class MatchKind { EXACT, SUFFIX, KEYWORD, WILDCARD, REGEX }

    fun matchGlobal(domain: String): Int {
        val normalized = domain.trim().lowercase()
        if (normalized.isEmpty()) return MATCH_NONE
        return global.match(normalized)
    }

    fun matchApp(domain: String, packageName: String): Int {
        if (packageName.isEmpty()) return MATCH_NONE
        val bucket = perApp[packageName] ?: return MATCH_NONE
        val normalized = domain.trim().lowercase()
        if (normalized.isEmpty()) return MATCH_NONE
        return bucket.match(normalized)
    }

    val globalBlockCount: Int
        get() = global.exactBlock.size + global.suffixBlock.size + global.keywordBlock.size + global.regexBlock.size

    val globalAllowCount: Int
        get() = global.exactAllow.size + global.suffixAllow.size + global.keywordAllow.size + global.regexAllow.size

    val appRuleSetCount: Int
        get() = perApp.size

    companion object {
        const val MATCH_NONE = -1
        const val MATCH_ALLOW = 0
        const val MATCH_BLOCK = 1

        val EMPTY = CustomRuleMatcher(
            global = MutableBucket().freeze(),
            perApp = emptyMap()
        )

        fun compile(rules: List<CustomDnsRule>): CustomRuleMatcher {
            val globalBucket = MutableBucket()
            val appBuckets = HashMap<String, MutableBucket>()

            for (rule in rules) {
                if (!rule.isEnabled || rule.ruleType == RuleType.COMMENT) continue
                val isAllow = rule.ruleType == RuleType.ALLOW
                val (kind, pattern) = extractMatchSpec(rule) ?: continue

                if (rule.packageName.isBlank()) {
                    globalBucket.add(kind, pattern, isAllow)
                } else {
                    val pkgs = rule.packageName.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    for (pkg in pkgs) {
                        appBuckets.getOrPut(pkg) { MutableBucket() }.add(kind, pattern, isAllow)
                    }
                }
            }

            return CustomRuleMatcher(
                global = globalBucket.freeze(),
                perApp = appBuckets.mapValues { it.value.freeze() }
            )
        }

        private fun extractMatchSpec(rule: CustomDnsRule): Pair<MatchKind, String>? {
            val raw = rule.rule.trim()
            val parts = raw.split(",").map { it.trim() }
            if (parts.size >= 3) {
                val type = parts[0].uppercase()
                val param = parts.subList(1, parts.size - 1).joinToString(",").trim()
                if (param.isEmpty()) return null
                return when (type) {
                    "HOST", "DOMAIN" -> MatchKind.EXACT to param.lowercase()
                    "HOST-SUFFIX", "DOMAIN-SUFFIX" -> MatchKind.SUFFIX to param.lowercase().removePrefix("*.")
                    "HOST-KEYWORD", "DOMAIN-KEYWORD" -> MatchKind.KEYWORD to param.lowercase()
                    "HOST-WILDCARD", "DOMAIN-WILDCARD" -> MatchKind.WILDCARD to param.lowercase()
                    "HOST-REGEX", "DOMAIN-REGEX" -> MatchKind.REGEX to param
                    else -> null
                }
            }

            val domain = rule.domain.trim().lowercase()
            if (domain.isEmpty()) return null
            return if (domain.contains('*') || domain.contains('?')) {
                MatchKind.WILDCARD to domain
            } else {
                MatchKind.SUFFIX to domain
            }
        }

        fun compileWildcard(glob: String): Regex? {
            if (glob.isBlank()) return null
            val sb = StringBuilder("^")
            for (ch in glob) {
                when (ch) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> {
                        sb.append('\\').append(ch)
                    }
                    else -> sb.append(ch)
                }
            }
            sb.append('$')
            return runCatching { Regex(sb.toString(), RegexOption.IGNORE_CASE) }.getOrNull()
        }

        fun compileRegex(pattern: String): Regex? {
            if (pattern.isBlank()) return null
            return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        }
    }
}
