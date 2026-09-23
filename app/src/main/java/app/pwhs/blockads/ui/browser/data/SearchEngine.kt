package app.pwhs.blockads.ui.browser.data

import android.net.Uri

/**
 * Search engines supported by the in-app browser with direct query formatting.
 */
enum class SearchEngine(
    val id: String,
    val displayName: String,
    val baseUrl: String
) {
    GOOGLE("google", "Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
    BING("bing", "Bing", "https://www.bing.com/search?q="),
    BRAVE("brave", "Brave", "https://search.brave.com/search?q=");

    fun buildSearchUrl(query: String): String {
        return baseUrl + Uri.encode(query.trim())
    }

    companion object {
        fun fromId(id: String?): SearchEngine {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: GOOGLE
        }
    }
}
