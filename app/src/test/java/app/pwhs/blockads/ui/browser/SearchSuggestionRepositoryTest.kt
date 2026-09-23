package app.pwhs.blockads.ui.browser

import app.pwhs.blockads.ui.browser.data.SearchSuggestionRepositoryImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SearchSuggestionRepositoryTest {

    private val requests = mutableListOf<String>()
    private var body: () -> String = { """["cat",["cat food","cats","cat memes"]]""" }

    private val repo = SearchSuggestionRepositoryImpl(
        HttpClient(MockEngine { request ->
            requests += request.url.toString()
            respond(body())
        })
    )

    @Test
    fun `queries under two characters skip the network`() = runTest {
        assertTrue(repo.getSuggestions("c").isEmpty())
        assertTrue(repo.getSuggestions("  a  ").isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `parses the second array element and encodes the query`() = runTest {
        assertEquals(listOf("cat food", "cats", "cat memes"), repo.getSuggestions(" cat & dog "))
        assertEquals(
            "https://suggestqueries.google.com/complete/search?client=chrome&q=cat+%26+dog",
            requests.single()
        )
    }

    @Test
    fun `caps at eight suggestions`() = runTest {
        body = { """["q",[${(1..12).joinToString(",") { "\"s$it\"" }}]]""" }
        assertEquals(8, repo.getSuggestions("query").size)
    }

    @Test
    fun `repeat queries hit the cache`() = runTest {
        repo.getSuggestions("cat")
        repo.getSuggestions(" cat ")
        assertEquals(1, requests.size)
    }

    @Test
    fun `malformed or unexpected json yields nothing`() = runTest {
        body = { "not json" }
        assertTrue(repo.getSuggestions("cat").isEmpty())
        body = { """{"a":1}""" }
        assertTrue(repo.getSuggestions("cat").isEmpty())
        body = { """["cat"]""" }
        assertTrue(repo.getSuggestions("cat").isEmpty())
        assertEquals(3, requests.size)
    }

    @Test
    fun `network errors yield nothing`() = runTest {
        body = { throw IOException("offline") }
        assertTrue(repo.getSuggestions("cat").isEmpty())
    }
}
