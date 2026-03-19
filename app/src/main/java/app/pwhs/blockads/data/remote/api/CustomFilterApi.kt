package app.pwhs.blockads.data.remote.api

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Response from the backend filter compiler API.
 */
data class BuildResponse(
    val downloadUrl: String,
    val ruleCount: Int,
    val fileSize: Long
)

/**
 * API service for calling the backend filter compiler.
 * Endpoint: POST https://complier.pwhs.app/api/build
 */
class CustomFilterApi(
    private val client: HttpClient
) {
    companion object {
        private const val BASE_URL = "https://complier.pwhs.app"
        private const val BUILD_ENDPOINT = "$BASE_URL/api/build"
    }

    /**
     * Calls the backend to compile a raw filter URL into optimized binary files.
     *
     * @param filterUrl The raw filter list URL to compile (e.g., "https://example.com/filter.txt")
     * @param force If true, forces recompilation even if cached
     * @return [BuildResponse] containing the download URL and metadata
     * @throws CustomFilterException on API errors
     */
    suspend fun buildFilter(filterUrl: String): BuildResponse =
        withContext(Dispatchers.IO) {
            try {
                val endpoint = BUILD_ENDPOINT
                val requestBody = """{"url":"$filterUrl"}"""

                Timber.d("Calling build API: $endpoint with url=$filterUrl")

                val response = client.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                val responseText = response.bodyAsText()
                Timber.d("Build API response: $responseText")

                if (response.status.value != 200) {
                    val errorMsg = extractString(responseText, "error")
                        ?: "Server returned ${response.status.value}"
                    throw CustomFilterException("Build failed: $errorMsg")
                }

                val status = extractString(responseText, "status")
                if (status != "success") {
                    throw CustomFilterException("Build failed with status: $status")
                }

                val downloadUrl = extractString(responseText, "downloadUrl")
                    ?: throw CustomFilterException("Missing downloadUrl in response")
                val ruleCount = extractInt(responseText, "ruleCount")
                val fileSize = extractLong(responseText, "fileSize")

                BuildResponse(
                    downloadUrl = downloadUrl,
                    ruleCount = ruleCount,
                    fileSize = fileSize
                )
            } catch (e: CustomFilterException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to call build API")
                throw CustomFilterException("Network error: ${e.message}", e)
            }
        }

    // ── Manual JSON parsing (no serialization plugin) ──────────────────

    private fun extractString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"(.*?)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractInt(json: String, key: String): Int {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractLong(json: String, key: String): Long {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}

/**
 * Exception thrown by custom filter operations.
 */
class CustomFilterException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
