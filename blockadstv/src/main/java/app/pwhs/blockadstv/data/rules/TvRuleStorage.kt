package app.pwhs.blockadstv.data.rules

import android.content.Context
import app.pwhs.blockadstv.data.models.ModulesPackage
import app.pwhs.blockadstv.data.models.PathInterceptRule
import app.pwhs.blockadstv.data.models.RuleManifestModule
import app.pwhs.blockadstv.data.models.RuleManifestPackage
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Local storage manager for dynamic rule manifest and modular rules in :blockadstv.
 */
class TvRuleStorage(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val rulesDir: File
        get() = context.filesDir.resolve("browser_rules").apply { if (!exists()) mkdirs() }

    private val packageFile: File
        get() = rulesDir.resolve("browser_rules.json")

    private val modulesFile: File
        get() = rulesDir.resolve("modules.json")

    fun getActivePackage(): RuleManifestPackage {
        return loadCachedPackage() ?: loadDefaultPackage()
    }

    fun loadCachedPackage(): RuleManifestPackage? {
        val file = packageFile
        if (!file.exists() || file.length() == 0L) return null
        return try {
            json.decodeFromString<RuleManifestPackage>(file.readText())
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode cached RuleManifestPackage, falling back to assets")
            null
        }
    }

    fun savePackage(rulePackage: RuleManifestPackage): Boolean {
        return try {
            val tempFile = rulesDir.resolve("browser_rules.json.tmp")
            val content = json.encodeToString(RuleManifestPackage.serializer(), rulePackage)
            tempFile.writeText(content)
            if (tempFile.renameTo(packageFile)) {
                Timber.i("Saved RuleManifestPackage v%d", rulePackage.version)
                true
            } else {
                tempFile.copyTo(packageFile, overwrite = true)
                tempFile.delete()
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save RuleManifestPackage")
            false
        }
    }

    fun saveModulesJson(content: String): Boolean {
        return try {
            val tempFile = rulesDir.resolve("modules.json.tmp")
            tempFile.writeText(content)
            if (tempFile.renameTo(modulesFile)) {
                Timber.i("Saved modules.json")
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

    fun loadDefaultPackage(): RuleManifestPackage {
        val basePkg = try {
            val content = loadAssetString("browser/browser_rules.json").ifBlank {
                loadAssetString("browser_rules.json")
            }
            if (content.isNotBlank()) json.decodeFromString<RuleManifestPackage>(content)
            else RuleManifestPackage()
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode default RuleManifestPackage from assets")
            RuleManifestPackage()
        }

        val modules = if (basePkg.modules.isEmpty()) {
            loadModulesFromAssets()
        } else {
            basePkg.modules
        }

        val cosmeticCss = basePkg.cosmeticCss.takeIf { !it.isNullOrBlank() }
            ?: loadAssetString("browser/adblock_cosmetic.css")
        val scriptletsJs = basePkg.scriptletsJs.takeIf { !it.isNullOrBlank() }
            ?: loadAssetString("browser/adguard_scriptlets.js")

        return basePkg.copy(
            cosmeticCss = cosmeticCss,
            scriptletsJs = scriptletsJs,
            modules = modules,
        )
    }

    private fun loadModulesFromAssets(): List<RuleManifestModule> {
        return try {
            val modContent = loadAssetString("browser/modules.json").ifBlank {
                loadAssetString("modules.json")
            }
            if (modContent.isBlank()) return emptyList()
            val pkg = json.decodeFromString<ModulesPackage>(modContent)
            pkg.modules.map { webMod ->
                RuleManifestModule(
                    id = webMod.id,
                    domains = webMod.domains,
                    intercepts = webMod.mockPaths.map {
                        PathInterceptRule(
                            pathPattern = it.pattern,
                            mockStatus = it.status,
                            contentType = it.contentType ?: "",
                            mockBody = it.body ?: "",
                        )
                    } + webMod.blockPaths.map {
                        PathInterceptRule(
                            pathPattern = it,
                            action = "reject",
                        )
                    },
                    cssRules = webMod.cosmeticCss,
                    scripts = webMod.scripts,
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode default modules from assets")
            emptyList()
        }
    }

    private fun loadAssetString(path: String): String {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }
}
