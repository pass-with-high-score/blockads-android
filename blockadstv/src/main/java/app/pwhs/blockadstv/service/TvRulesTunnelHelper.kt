package app.pwhs.blockadstv.service

import android.content.Context
import app.pwhs.blockadstv.data.dao.ConfigDao
import app.pwhs.blockadstv.data.datastore.RulesPreferences
import app.pwhs.blockadstv.data.models.ModulesPackage
import app.pwhs.blockadstv.data.models.ModuleRulesPackage
import app.pwhs.blockadstv.data.models.RuleManifestPackage
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.data.rules.TvRuleStorage
import app.pwhs.blockadstv.data.rules.TvRuleSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.koin.core.Koin
import timber.log.Timber
import java.io.File

/**
 * Loads rule manifest, modular rules, cosmetic CSS, scriptlets, and remote rulesets
 * into the Go tunnel engine for :blockadstv.
 */
object TvRulesTunnelHelper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun loadCosmeticCss(context: Context, filterCosmeticCssPath: String? = null): String {
        val baseCss = runCatching {
            val pkg = TvRuleStorage(context).getActivePackage()
            val raw = pkg.cosmeticCss?.trim()
            if (raw.isNullOrBlank() || raw.startsWith("http://") || raw.startsWith("https://")) {
                readAsset(context, "browser/adblock_cosmetic.css")
            } else {
                raw
            }
        }.getOrElse {
            Timber.w(it, "Failed to read cosmetic CSS from storage, falling back to asset")
            readAsset(context, "browser/adblock_cosmetic.css")
        }

        val filterCss = if (!filterCosmeticCssPath.isNullOrBlank()) {
            runCatching {
                val f = File(filterCosmeticCssPath)
                if (f.exists() && f.length() > 0) f.readText() else ""
            }.getOrDefault("")
        } else {
            ""
        }

        return listOf(baseCss, filterCss).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun loadAdPathPatterns(context: Context): String {
        return runCatching {
            TvRuleStorage(context).getActivePackage().adPathPatterns.joinToString("\n")
        }.getOrElse {
            Timber.w(it, "Failed to load ad path patterns")
            ""
        }
    }

    fun loadScriptletsJs(context: Context): String {
        val swKiller = readAsset(context, "browser/service_worker_killer.js")
        val baseScriptlets = runCatching {
            val pkg = TvRuleStorage(context).getActivePackage()
            val raw = pkg.scriptletsJs?.trim()
            if (raw.isNullOrBlank() || raw.startsWith("http://") || raw.startsWith("https://")) {
                readAsset(context, "browser/adguard_scriptlets.js")
            } else {
                raw
            }
        }.getOrElse {
            Timber.w(it, "Failed to read scriptlets JS from storage, falling back to asset")
            readAsset(context, "browser/adguard_scriptlets.js")
        }

        return listOf(swKiller, baseScriptlets).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    fun loadRuleManifest(context: Context): String {
        return runCatching {
            val pkg = TvRuleStorage(context).getActivePackage()
            val ytSanitizer = readAsset(context, "browser/youtube_sanitizer.js")
            val modules = pkg.modules.map { mod ->
                val resolvedScripts = mod.scripts.map { scriptRef ->
                    if (scriptRef.endsWith(".js")) {
                        readAsset(context, "browser/$scriptRef").ifBlank { scriptRef }
                    } else {
                        scriptRef
                    }
                }
                if (mod.id == "youtube" &&
                    resolvedScripts.none { it.contains("__blockads_yt_sanitizer_injected") } &&
                    ytSanitizer.isNotBlank()
                ) {
                    mod.copy(scripts = listOf(ytSanitizer) + resolvedScripts)
                } else {
                    mod.copy(scripts = resolvedScripts)
                }
            }
            json.encodeToString(RuleManifestPackage.serializer(), pkg.copy(modules = modules))
        }.getOrElse {
            Timber.w(it, "Failed to serialize rule manifest")
            "{}"
        }
    }

    fun loadModulesJson(context: Context, filterModulesPath: String? = null): String {
        return runCatching {
            val cachedFile = File(context.filesDir, "browser_rules/modules.json")
            val raw = if (cachedFile.exists() && cachedFile.length() > 0) {
                cachedFile.readText()
            } else {
                readAsset(context, "browser/modules.json")
            }
            if (raw.isBlank()) return ""

            val pkg = json.decodeFromString<ModulesPackage>(raw)
            val extPkg = if (!filterModulesPath.isNullOrBlank()) {
                runCatching {
                    val f = File(filterModulesPath)
                    if (f.exists() && f.length() > 0) {
                        json.decodeFromString<ModuleRulesPackage>(f.readText())
                    } else null
                }.getOrNull()
            } else null

            val resolvedModules = pkg.modules.map { mod ->
                val resolvedScripts = mod.scripts.map { scriptRef ->
                    if (scriptRef.endsWith(".js")) {
                        readAsset(context, "browser/$scriptRef").ifBlank { scriptRef }
                    } else {
                        scriptRef
                    }
                }
                mod.copy(scripts = resolvedScripts)
            }

            val mergedGlobalPaths = if (extPkg != null && extPkg.globalBlockPaths.isNotEmpty()) {
                (pkg.globalBlockPaths + extPkg.globalBlockPaths).distinct()
            } else {
                pkg.globalBlockPaths
            }

            json.encodeToString(
                ModulesPackage.serializer(),
                pkg.copy(
                    modules = resolvedModules,
                    globalBlockPaths = mergedGlobalPaths,
                    domains = extPkg?.domains ?: pkg.domains,
                ),
            )
        }.getOrElse {
            Timber.w(it, "Failed to load modules.json")
            ""
        }
    }

    fun loadModules(context: Context, engine: tunnel.Engine, filterModulesPath: String? = null) {
        val jsonStr = loadModulesJson(context, filterModulesPath)
        if (jsonStr.isNotBlank()) {
            engine.setModulesConfig(jsonStr)
            Timber.d("Modular rules loaded into Go engine (%d bytes)", jsonStr.length)
        }
    }

    fun loadCachedRemoteRulesets(context: Context, engine: tunnel.Engine) {
        runCatching {
            val jsonStr = engine.rulesetRemoteFiltersJSON
            if (jsonStr.isBlank() || jsonStr == "[]") return

            val remoteDir = File(context.filesDir, "remote_rulesets")
            if (!remoteDir.exists()) return

            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val tag = obj.optString("tag").ifBlank { "remote_$i" }
                val targetFile = File(remoteDir, "$tag.list")
                if (targetFile.exists() && targetFile.length() > 0) {
                    val added = engine.appendRemoteFilterRules(tag, targetFile.readText())
                    Timber.d("Loaded cached remote ruleset [%s]: %d rules", tag, added)
                }
            }
        }.onFailure { Timber.w(it, "Failed to load cached remote rulesets") }
    }

    fun applyProfileDnsIfPresent(engine: tunnel.Engine, onApply: (primary: String, fallback: String) -> Unit) {
        val csv = engine.rulesetDNSServersCSV
        if (csv.isBlank()) return
        val servers = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (servers.isNotEmpty()) {
            val primary = servers[0]
            val fallback = servers.getOrNull(1) ?: "8.8.8.8"
            onApply(primary, fallback)
        }
    }

    fun updateCosmeticRules(
        context: Context,
        engine: tunnel.Engine,
        filterRepo: FilterListRepository,
        ruleManifestEnabled: Boolean = true,
        modulesEnabled: Boolean = true,
    ) {
        runCatching {
            engine.setCosmeticCSS(loadCosmeticCss(context, filterRepo.getCosmeticCssPath()))
        }.onFailure {
            Timber.e(it, "Failed to load cosmetic CSS")
            engine.setCosmeticCSS("")
        }

        runCatching {
            engine.setScriptletsRuntime(loadScriptletsJs(context))
        }.onFailure { Timber.e(it, "Failed to load scriptlets JS") }

        runCatching {
            val manifest = if (ruleManifestEnabled) loadRuleManifest(context) else "{}"
            engine.setRuleManifestJSON(manifest)
        }.onFailure { Timber.e(it, "Failed to load rule manifest") }

        runCatching {
            if (modulesEnabled) {
                loadModules(context, engine, filterRepo.getFilterModulesPath())
            } else {
                engine.setModulesConfig("{}")
            }
        }.onFailure { Timber.e(it, "Failed to load modular rules") }

        runCatching {
            val sp = filterRepo.getScriptletsPath()
            if (sp != null && File(sp).exists()) engine.setScriptletRulesFromFile(sp)
            else engine.setScriptletRules("")
        }.onFailure {
            Timber.e(it, "Failed to load scriptlet rules")
            engine.setScriptletRules("")
        }

        runCatching {
            engine.setAdPathPatterns(loadAdPathPatterns(context))
        }.onFailure {
            Timber.e(it, "Failed to load ad path patterns")
            engine.setAdPathPatterns("")
        }
    }

    private fun readAsset(context: Context, path: String): String {
        return runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    fun observeRules(scope: CoroutineScope, goTunnelAdapter: GoTunnelAdapter, koin: Koin) {
        val rulesPrefs: RulesPreferences = koin.get()
        val configDao: ConfigDao = koin.get()
        val ruleSyncRepo: TvRuleSyncRepository = koin.get()

        scope.launch {
            combine(
                rulesPrefs.ruleManifestEnabled,
                rulesPrefs.modulesEnabled,
            ) { manifestEnabled, modulesEnabled ->
                manifestEnabled to modulesEnabled
            }.collect { (manifestEnabled, modulesEnabled) ->
                if (goTunnelAdapter.isEngineRunning()) {
                    goTunnelAdapter.updateCosmeticRules(manifestEnabled, modulesEnabled)
                }
            }
        }

        scope.launch {
            combine(
                rulesPrefs.rulesetEnabled,
                configDao.getActiveFlow(),
            ) { rulesetEnabled, activeConfig ->
                rulesetEnabled to activeConfig
            }.collect { (rulesetEnabled, activeConfig) ->
                if (rulesetEnabled && activeConfig != null && activeConfig.content.isNotBlank()) {
                    goTunnelAdapter.setRulesetConfig(activeConfig.content)
                    val remoteJson = goTunnelAdapter.getRulesetRemoteFiltersJson()
                    val synced = ruleSyncRepo.syncRemoteRulesets(remoteJson)
                    if (synced > 0) {
                        goTunnelAdapter.setRulesetConfig(activeConfig.content)
                    }
                } else {
                    goTunnelAdapter.clearRulesetConfig()
                }
            }
        }
    }
}
