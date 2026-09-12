package app.pwhs.blockads.ui.browser.rules

import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Repository interface for managing browser ad blocking rules dynamically.
 */
interface BrowserRuleRepository {
    val currentRules: StateFlow<BrowserRulePackage>
    val updateStatus: StateFlow<BrowserRuleUpdateStatus>

    suspend fun checkAndUpdate(customUrl: String? = null): Result<Boolean>
    suspend fun resetToDefaults()
}

/**
 * Implementation of [BrowserRuleRepository].
 */
class BrowserRuleRepositoryImpl(
    private val storage: BrowserRuleStorage,
    private val client: HttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BrowserRuleRepository {

    companion object {
        const val DEFAULT_RULES_URL =
            "https://raw.githubusercontent.com/pass-with-high-score/blockads-android/main/rules/browser_rules.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _currentRules = MutableStateFlow(storage.getActivePackage())
    override val currentRules: StateFlow<BrowserRulePackage> = _currentRules.asStateFlow()

    private val _updateStatus = MutableStateFlow<BrowserRuleUpdateStatus>(BrowserRuleUpdateStatus.Idle)
    override val updateStatus: StateFlow<BrowserRuleUpdateStatus> = _updateStatus.asStateFlow()

    init {
        // Initialize BrowserAdBlocker with the active rule set immediately
        BrowserAdBlocker.applyRulePackage(_currentRules.value)
    }

    override suspend fun checkAndUpdate(customUrl: String?): Result<Boolean> = withContext(ioDispatcher) {
        val targetUrl = customUrl ?: DEFAULT_RULES_URL
        _updateStatus.value = BrowserRuleUpdateStatus.Checking

        try {
            Timber.d("Checking for browser rule updates from: %s", targetUrl)
            val response = client.get(targetUrl)
            if (response.status.value !in 200..299) {
                val err = "Server returned HTTP ${response.status.value}"
                Timber.w("Failed to fetch browser rules: %s", err)
                _updateStatus.value = BrowserRuleUpdateStatus.Error(err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            val responseText = response.bodyAsText()
            if (responseText.isBlank()) {
                val err = "Empty response received from rule server"
                _updateStatus.value = BrowserRuleUpdateStatus.Error(err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            val remotePackage = json.decodeFromString<BrowserRulePackage>(responseText)
            val active = _currentRules.value

            if (remotePackage.version <= active.version && remotePackage.adDomains.size <= active.adDomains.size) {
                Timber.i("Browser rules are already up to date (v%d)", active.version)
                _updateStatus.value = BrowserRuleUpdateStatus.UpToDate(active.version)
                return@withContext Result.success(false)
            }

            // Save and hot-reload in-memory rules
            val saved = storage.savePackage(remotePackage)
            if (saved) {
                _currentRules.value = remotePackage
                BrowserAdBlocker.applyRulePackage(remotePackage)
                _updateStatus.value = BrowserRuleUpdateStatus.Updated(
                    version = remotePackage.version,
                    domainsCount = remotePackage.adDomains.size
                )
                Timber.i("Updated browser rules to v%d with %d domains", remotePackage.version, remotePackage.adDomains.size)
                Result.success(true)
            } else {
                val err = "Failed to save updated rules to local storage"
                _updateStatus.value = BrowserRuleUpdateStatus.Error(err)
                Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating browser rules")
            _updateStatus.value = BrowserRuleUpdateStatus.Error(e.localizedMessage ?: "Network error")
            Result.failure(e)
        }
    }

    override suspend fun resetToDefaults(): Unit = withContext(ioDispatcher) {
        val defaults = storage.resetToDefaults()
        _currentRules.value = defaults
        BrowserAdBlocker.applyRulePackage(defaults)
        _updateStatus.value = BrowserRuleUpdateStatus.Idle
        Timber.i("Reset browser rules to baseline defaults (v%d)", defaults.version)
    }
}
