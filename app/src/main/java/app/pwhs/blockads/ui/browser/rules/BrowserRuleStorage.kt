package app.pwhs.blockads.ui.browser.rules

import android.content.Context
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Local storage manager for dynamic browser ad blocking rules.
 * Handles persistence, atomic writes, and asset fallback.
 */
class BrowserRuleStorage(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val rulesDir: File
        get() = context.filesDir.resolve("browser_rules").apply { if (!exists()) mkdirs() }

    private val packageFile: File
        get() = rulesDir.resolve("browser_rules.json")

    /**
     * Loads the cached dynamic rule package if it exists and is valid.
     */
    fun loadCachedPackage(): BrowserRulePackage? {
        val file = packageFile
        if (!file.exists() || file.length() == 0L) return null

        return try {
            val content = file.readText()
            json.decodeFromString<BrowserRulePackage>(content)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode cached BrowserRulePackage, falling back to assets")
            null
        }
    }

    /**
     * Atomically writes a new BrowserRulePackage to disk.
     */
    fun savePackage(rulePackage: BrowserRulePackage): Boolean {
        return try {
            val tempFile = rulesDir.resolve("browser_rules.json.tmp")
            val content = json.encodeToString(BrowserRulePackage.serializer(), rulePackage)
            tempFile.writeText(content)
            if (tempFile.renameTo(packageFile)) {
                Timber.i("Successfully saved BrowserRulePackage v%d", rulePackage.version)
                true
            } else {
                tempFile.copyTo(packageFile, overwrite = true)
                tempFile.delete()
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save BrowserRulePackage")
            false
        }
    }

    /**
     * Constructs the baseline package from bundled assets and defaults.
     */
    fun loadDefaultPackage(): BrowserRulePackage {
        val cosmeticCss = loadAssetString("browser/adblock_cosmetic.css")
        val scriptletsJs = loadAssetString("browser/adguard_scriptlets.js")

        return BrowserRulePackage(
            version = BrowserRuleDefaults.INITIAL_VERSION,
            updatedAt = System.currentTimeMillis(),
            adDomains = BrowserRuleDefaults.AD_HOST_SUFFIXES,
            gamblingKeywords = BrowserRuleDefaults.GAMBLING_POPUNDER_KEYWORDS,
            adPathPatterns = BrowserRuleDefaults.AD_PATH_PATTERNS,
            cosmeticCss = cosmeticCss,
            scriptletsJs = scriptletsJs,
        )
    }

    /**
     * Returns the currently active package (cached dynamic package or baseline defaults).
     */
    fun getActivePackage(): BrowserRulePackage {
        val cached = loadCachedPackage()
        val defaultPkg = loadDefaultPackage()
        if (cached == null || defaultPkg.version > cached.version) {
            return defaultPkg
        }
        return cached
    }

    /**
     * Resets rules to bundled defaults and clears cached files.
     */
    fun resetToDefaults(): BrowserRulePackage {
        try {
            if (packageFile.exists()) {
                packageFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete cached rules")
        }
        return loadDefaultPackage()
    }

    private fun loadAssetString(path: String): String {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read asset: %s", path)
            ""
        }
    }
}
