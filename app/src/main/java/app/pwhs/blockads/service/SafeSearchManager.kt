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
 *
 * YouTube Restricted Mode is handled separately via its own toggle.
 * Search engines that don't support DNS-level SafeSearch are left unchanged.
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

        return SafeSearchResult(action = SafeSearchResult.Action.NONE)
    }

    /**
     * Check if a domain should be redirected for YouTube Restricted Mode.
     *
     * @param domain The queried domain name (lowercase)
     * @return true if the domain is a YouTube domain that should be redirected
     */
    fun isYoutubeDomain(domain: String): Boolean {
        return domain == "youtube.com" || domain.endsWith(".youtube.com")
    }

    /** The CNAME target for YouTube Restricted Mode. */
    const val YOUTUBE_RESTRICT_DOMAIN = "restrict.youtube.com"

    /**
     * Match a domain against a pattern. Supports both exact and subdomain matching.
     * For example, pattern "google." matches "google.com", "www.google.com",
     * "www.google.co.uk", etc., but not "accounts.google.com" or "mail.google.com".
     * Pattern "bing.com" matches "bing.com" and "www.bing.com".
     */
    private fun matchesDomain(domain: String, pattern: String): Boolean {
        if (pattern.endsWith(".")) {
            // Pattern like "google." is treated specially: it matches only search hostnames
            // where the base label is the left-most label, optionally prefixed by "www".
            // Examples that match: google.com, www.google.com, google.co.uk, www.google.co.uk
            // Examples that do NOT match: accounts.google.com, mail.google.com, play.google.com
            val baseLabel = pattern.removeSuffix(".") // e.g., "google"
            val parts = domain.split('.')
            if (parts.isEmpty()) return false
            val idx = parts.indexOf(baseLabel)
            if (idx == -1) return false
            return idx == 0 || (idx == 1 && parts[0] == "www")
        }
        // Exact domain or subdomain match
        return domain == pattern || domain.endsWith(".$pattern")
    }
}
