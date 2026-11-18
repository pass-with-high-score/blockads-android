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

    private val modulesFile: File
        get() = rulesDir.resolve("modules.json")

    /**
     * Atomically writes raw modules.json to disk.
     */
    fun saveModulesJson(content: String): Boolean {
        return try {
            val tempFile = rulesDir.resolve("modules.json.tmp")
            tempFile.writeText(content)
            if (tempFile.renameTo(modulesFile)) {
                Timber.i("Successfully saved modules.json")
                true
            } else {
                tempFile.copyTo(modulesFile, overwrite = true)
                tempFile.delete()
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save modules.json")
            false
        }
    }

    /**
     * Constructs the baseline package directly from bundled browser_rules.json asset.
     */
    fun loadDefaultPackage(): BrowserRulePackage {
        val basePkg = try {
            val content = loadAssetString("browser/browser_rules.json").ifBlank {
                loadAssetString("browser_rules.json")
            }
            if (content.isNotBlank()) {
                json.decodeFromString<BrowserRulePackage>(content)
            } else {
                BrowserRulePackage()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode default BrowserRulePackage from assets")
            BrowserRulePackage()
        }

        val modules = if (basePkg.modules.isEmpty()) {
            try {
                val modContent = loadAssetString("browser/modules.json").ifBlank {
                    loadAssetString("modules.json")
                }
                if (modContent.isNotBlank()) {
                    val pkg = json.decodeFromString<app.pwhs.blockads.data.models.ModulesPackage>(modContent)
                    pkg.modules.map { webMod ->
                        BrowserRuleModule(
                            id = webMod.id,
                            domains = webMod.domains,
                            intercepts = webMod.mockPaths.map {
                                PathInterceptRule(
                                    pathPattern = it.pattern,
                                    mockStatus = it.status,
                                    contentType = it.contentType ?: "",
                                    mockBody = it.body ?: ""
                                )
                            } + webMod.blockPaths.map {
                                PathInterceptRule(
                                    pathPattern = it,
                                    action = "reject"
                                )
                            },
                            cssRules = webMod.cosmeticCss,
                            scripts = webMod.scripts
                        )
                    }
                } else emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Failed to decode default modules from assets")
                emptyList()
            }
        } else {
            basePkg.modules
        }

        val cosmeticCss = if (basePkg.cosmeticCss.isNullOrBlank() || basePkg.cosmeticCss.trim().startsWith("http")) {
            loadAssetString("browser/adblock_cosmetic.css")
        } else {
            basePkg.cosmeticCss
        }

        val scriptletsJs = if (basePkg.scriptletsJs.isNullOrBlank() || basePkg.scriptletsJs.trim().startsWith("http")) {
            loadAssetString("browser/adguard_scriptlets.js")
        } else {
            basePkg.scriptletsJs
        }

        return basePkg.copy(
            cosmeticCss = cosmeticCss,
            scriptletsJs = scriptletsJs,
            modules = modules
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
