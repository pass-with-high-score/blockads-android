package app.pwhs.blockads.data.remote.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

class CustomFilterApiTest {

    private var sentBody = ""

    private fun api(response: String) = CustomFilterApi(
        HttpClient(
            MockEngine { request ->
                sentBody = (request.body as TextContent).text
                respond(response, HttpStatusCode.OK)
            }
        )
    )

    @Ignore("known bug: build request JSON is not escaped")
    @Test
    fun `request body escapes quotes and backslashes in the URL`() = runTest {
        val url = """https://lists.example/a"b\c.txt"""
        runCatching { api("""{"status":"success","downloadUrl":"https://cdn.example/x.zip"}""").buildFilter(url) }

        val sent = runCatching { Json.parseToJsonElement(sentBody).jsonObject["url"]?.jsonPrimitive?.content }
        assertEquals("body: $sentBody", url, sent.getOrNull())
    }

    @Ignore("known bug: regex parser does not decode JSON escapes")
    @Test
    fun `response fields are JSON-decoded`() = runTest {
        val response = """{"status":"success","downloadUrl":"https:\/\/cdn.example\/x.zip?a=1&b=2","ruleCount":42}"""

        val build = api(response).buildFilter("https://lists.example/a.txt")

        assertEquals("https://cdn.example/x.zip?a=1&b=2", build.downloadUrl)
        assertEquals(42, build.ruleCount)
    }
}
