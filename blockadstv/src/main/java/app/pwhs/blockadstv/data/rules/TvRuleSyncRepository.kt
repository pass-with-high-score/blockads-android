package app.pwhs.blockadstv.data.rules

import android.content.Context
import app.pwhs.blockadstv.data.datastore.RulesPreferences
import app.pwhs.blockadstv.data.models.ModulesPackage
import app.pwhs.blockadstv.data.models.RuleManifestPackage
import app.pwhs.blockadstv.data.models.RuleUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import timber.log.Timber
import java.io.File

/**
 * Fetches and caches dynamic rule manifest, modular rules, and remote ruleset files.
 */
class TvRuleSyncRepository(
    private val context: Context,
    private val storage: TvRuleStorage,
    private val client: HttpClient,
    private val rulesPrefs: RulesPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val DEFAULT_RULES_URL = "https://filter.pwhs.app/rules/browser_rules.json"
        const val DEFAULT_MODULES_URL = "https://filter.pwhs.app/rules/modules.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _currentRules = MutableStateFlow(storage.getActivePackage())
    val currentRules: StateFlow<RuleManifestPackage> = _currentRules.asStateFlow()

    private val _updateStatus = MutableStateFlow<RuleUpdateStatus>(RuleUpdateStatus.Idle)
    val updateStatus: StateFlow<RuleUpdateStatus> = _updateStatus.asStateFlow()

    suspend fun checkAndUpdate(customUrl: String? = null): Result<Boolean> = withContext(ioDispatcher) {
        val targetUrl = customUrl ?: DEFAULT_RULES_URL
        _updateStatus.value = RuleUpdateStatus.Checking

        try {
            val response = client.get(targetUrl)
            if (response.status.value !in 200..299) {
                val err = "Server returned HTTP ${response.status.value}"
                _updateStatus.value = RuleUpdateStatus.Error(err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            val responseText = response.bodyAsText()
            if (responseText.isBlank()) {
                val err = "Empty response received from rule server"
                _updateStatus.value = RuleUpdateStatus.Error(err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            val remotePackage = json.decodeFromString<RuleManifestPackage>(responseText)
            val active = _currentRules.value

            if (remotePackage.version <= active.version && remotePackage.adDomains.size <= active.adDomains.size) {
                _updateStatus.value = RuleUpdateStatus.UpToDate(active.version)
                return@withContext Result.success(false)
            }

            var finalPackage = remotePackage
            finalPackage = resolveRemoteTextField(finalPackage.cosmeticCss) {
                finalPackage = finalPackage.copy(cosmeticCss = it)
            }.let { finalPackage }
            finalPackage = resolveRemoteTextField(finalPackage.scriptletsJs) {
                finalPackage = finalPackage.copy(scriptletsJs = it)
            }.let { finalPackage }

            val saved = storage.savePackage(finalPackage)
            if (saved) {
                _currentRules.value = finalPackage
                rulesPrefs.setLastRulesSync(System.currentTimeMillis())
                _updateStatus.value = RuleUpdateStatus.Updated(
                    version = finalPackage.version,
                    domainsCount = finalPackage.adDomains.size,
                )
                Result.success(true)
            } else {
                val err = "Failed to save updated rules to local storage"
                _updateStatus.value = RuleUpdateStatus.Error(err)
                Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating rule manifest")
            _updateStatus.value = RuleUpdateStatus.Error(e.localizedMessage ?: "Network error")
            Result.failure(e)
        }
    }

    suspend fun checkAndUpdateModules(customUrl: String? = null): Result<Boolean> = withContext(ioDispatcher) {
        val targetUrl = customUrl ?: DEFAULT_MODULES_URL
        try {
            val response = client.get(targetUrl)
            if (response.status.value !in 200..299) {
                val err = "Server returned HTTP ${response.status.value}"
                return@withContext Result.failure(IllegalStateException(err))
            }
            val text = response.bodyAsText()
            if (text.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Empty modules response"))
            }
            json.decodeFromString<ModulesPackage>(text)
            val saved = storage.saveModulesJson(text)
            if (saved) rulesPrefs.setLastRulesSync(System.currentTimeMillis())
            Result.success(saved)
        } catch (e: Exception) {
            Timber.e(e, "Error updating modules.json")
            Result.failure(e)
        }
    }

    /**
     * Downloads remote [filter_remote] ruleset files declared in the active ruleset JSON
     * into filesDir/remote_rulesets/<tag>.list so appendRemoteFilterRules can load them.
     */
    suspend fun syncRemoteRulesets(remoteFiltersJson: String): Int = withContext(ioDispatcher) {
        if (remoteFiltersJson.isBlank() || remoteFiltersJson == "[]") return@withContext 0
        val remoteDir = File(context.filesDir, "remote_rulesets").apply { if (!exists()) mkdirs() }
        var downloaded = 0
        runCatching {
            val array = JSONArray(remoteFiltersJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url").trim()
                val tag = obj.optString("tag").ifBlank { "remote_$i" }
                if (url.isBlank()) continue
                runCatching {
                    val res = client.get(url)
                    if (res.status.value in 200..299) {
                        val content = res.bodyAsText()
                        if (content.isNotBlank()) {
                            File(remoteDir, "$tag.list").writeText(content)
                            downloaded++
                        }
                    }
                }.onFailure { Timber.w(it, "Failed to download remote ruleset [$tag] from $url") }
            }
        }.onFailure { Timber.w(it, "Failed to parse remote rulesets JSON") }
        downloaded
    }

    private suspend inline fun resolveRemoteTextField(
        rawUrl: String?,
        onResolved: (String) -> Unit,
    ) {
        val trimmed = rawUrl?.trim() ?: return
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            runCatching {
                val res = client.get(trimmed)
                if (res.status.value in 200..299) {
                    onResolved(res.bodyAsText())
                }
            }.onFailure { Timber.w(it, "Failed to resolve remote rule asset: %s", trimmed) }
        }
    }
}
