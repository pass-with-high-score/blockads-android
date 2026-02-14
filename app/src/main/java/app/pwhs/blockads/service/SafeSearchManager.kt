package app.pwhs.blockads.service

import android.util.Log

/**
 * Manages SafeSearch DNS enforcement by mapping search engine domains
 * to their SafeSearch equivalents.
 *
 * When SafeSearch is enabled, DNS queries for supported search engines are
 * redirected to their SafeSearch-enforced domains:
 * - Google → forcesafesearch.google.com
 * - Bing → strict.bing.com
 * - YouTube → restrict.youtube.com
 *
 * Search engines that don't support DNS-level SafeSearch are blocked.
 */
object SafeSearchManager {

    private const val TAG = "SafeSearchManager"

    /**
     * Mapping of search engine domain suffixes to their SafeSearch CNAME.
     * When a DNS query matches one of these patterns, it is redirected
     * to the corresponding SafeSearch domain.
     */
    private val safeSearchRedirects = listOf(
        SearchRedirect("google.", "forcesafesearch.google.com"),
        SearchRedirect("bing.com", "strict.bing.com"),
        SearchRedirect("youtube.com", "restrict.youtube.com"),
    )

    /**
     * Search engine domains to block when SafeSearch is enabled,
     * because they don't support DNS-level SafeSearch enforcement.
     */
    private val unsupportedSearchEngines = setOf(
        "duckduckgo.com",
    )

    data class SearchRedirect(
        val domainPattern: String,
        val safeSearchDomain: String
    )

    data class SafeSearchResult(
        val action: Action,
        val redirectDomain: String? = null
    ) {
        enum class Action {
            NONE,       // Not a search engine, proceed normally
            REDIRECT,   // Redirect to SafeSearch domain
            BLOCK       // Block unsupported search engine
        }
    }

    /**
     * Check if a domain should be handled by SafeSearch.
     *
     * @param domain The queried domain name (lowercase)
     * @return SafeSearchResult indicating the action to take
     */
    fun check(domain: String): SafeSearchResult {
        // Check if domain should be redirected to SafeSearch
        for (redirect in safeSearchRedirects) {
            if (matchesDomain(domain, redirect.domainPattern)) {
                Log.d(TAG, "SafeSearch redirect: $domain → ${redirect.safeSearchDomain}")
                return SafeSearchResult(
                    action = SafeSearchResult.Action.REDIRECT,
                    redirectDomain = redirect.safeSearchDomain
                )
            }
        }

        // Check if domain is an unsupported search engine that should be blocked
        for (engine in unsupportedSearchEngines) {
            if (domain == engine || domain.endsWith(".$engine")) {
                Log.d(TAG, "SafeSearch block (unsupported): $domain")
                return SafeSearchResult(action = SafeSearchResult.Action.BLOCK)
            }
        }

        return SafeSearchResult(action = SafeSearchResult.Action.NONE)
    }

    /**
     * Match a domain against a pattern. Supports both exact and subdomain matching.
     * For example, pattern "google." matches "google.com", "www.google.com",
     * "www.google.co.uk", etc.
     * Pattern "bing.com" matches "bing.com" and "www.bing.com".
     */
    private fun matchesDomain(domain: String, pattern: String): Boolean {
        if (pattern.endsWith(".")) {
            // Pattern like "google." matches any domain containing ".google." or starting with "google."
            return domain.contains(".$pattern") || domain.startsWith(pattern)
        }
        // Exact domain or subdomain match
        return domain == pattern || domain.endsWith(".$pattern")
    }
}
