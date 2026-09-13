package app.pwhs.blockads.ui.browser.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.URLEncoder

interface SearchSuggestionRepository {
    suspend fun getSuggestions(query: String): List<String>
}

class SearchSuggestionRepositoryImpl(
    private val client: HttpClient
) : SearchSuggestionRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // In-memory LRU cache to avoid redundant network calls for identical queries
    private val cache = object : LinkedHashMap<String, List<String>>(30, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
            return size > 50
        }
    }

    override suspend fun getSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        synchronized(cache) {
            cache[trimmed]?.let { return@withContext it }
        }

        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=chrome&q=$encodedQuery"
            val response = client.get(url)
            val body = response.bodyAsText()

            val jsonElement = json.parseToJsonElement(body)
            val suggestionsArray = jsonElement.jsonArray.getOrNull(1)?.jsonArray
            val suggestions = suggestionsArray?.mapNotNull {
                it.jsonPrimitive.content
            }?.take(8) ?: emptyList()

            synchronized(cache) {
                cache[trimmed] = suggestions
            }
            suggestions
        } catch (e: Exception) {
            Timber.d(e, "Could not fetch search suggestions for query: %s", trimmed)
            emptyList()
        }
    }
}
