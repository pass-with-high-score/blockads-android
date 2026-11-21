package app.pwhs.blockads.service

import android.content.Context
import app.pwhs.blockads.ui.browser.rules.BrowserRuleStorage
import app.pwhs.blockads.utils.BlocklistInfo
import timber.log.Timber

/**
 * Loads cosmetic rules, ad path patterns, and scriptlets for the Go tunnel engine
 * directly from browser_rules.json (via BrowserRuleStorage).
 */
object TunnelRuleLoader {

    /**
     * Loads the built-in browser cosmetic CSS (adblock_cosmetic.css / remote updates).
     */
    private fun loadBaseCosmeticCss(context: Context): String {
        return try {
            val storage = BrowserRuleStorage(context)
            val pkg = storage.getActivePackage()
            val raw = pkg.cosmeticCss?.trim()
            if (raw.isNullOrBlank() || raw.startsWith("http://") || raw.startsWith("https://")) {
                context.assets.open("browser/adblock_cosmetic.css").bufferedReader().use { it.readText() }
            } else {
                raw
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read browser cosmetic CSS from storage, trying assets directly")
            runCatching {
                context.assets.open("browser/adblock_cosmetic.css").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }

    /**
     * Prepares a single cosmetic CSS file on disk for the Go engine so large filter-list CSS
     * files can be loaded via [tunnel.Engine.setCosmeticCSSFromFile] without allocating on the JVM heap.
     */
    internal fun prepareEngineCosmeticCssFile(
        context: Context,
        filterCosmeticCssPath: String,
        useBuiltIn: Boolean
    ): java.io.File? {
        val filterFile = java.io.File(filterCosmeticCssPath)
        if (!filterFile.exists() || filterFile.length() == 0L) return null
        if (!useBuiltIn) return filterFile

        val baseCss = loadBaseCosmeticCss(context)
        if (baseCss.isBlank()) return filterFile

        val outDir = filterFile.parentFile ?: java.io.File(context.filesDir, "filter_cache").apply { mkdirs() }
        val mergedFile = java.io.File(outDir, "cosmetic_engine_merged.css")
        val tmpFile = java.io.File(outDir, "cosmetic_engine_merged.css.tmp")
        return try {
            tmpFile.outputStream().use { out ->
                out.write(baseCss.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                filterFile.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read > 0) out.write(buffer, 0, read)
                    }
                }
            }
            if (!tmpFile.renameTo(mergedFile)) {
                tmpFile.copyTo(mergedFile, overwrite = true)
                tmpFile.delete()
            }
            mergedFile
        } catch (t: Throwable) {
            tmpFile.delete()
            throw t
        }
    }

    /**
     * Loads cosmetic CSS from the active browser rule package (adblock_cosmetic.css / remote updates)
     * combined with compiled filter-list cosmetic rules (cosmetic_rules.css).
     */
    fun loadCosmeticCss(context: Context, filterCosmeticCssPath: String? = null): String {
        val baseCss = loadBaseCosmeticCss(context)

        val filterCss = if (!filterCosmeticCssPath.isNullOrBlank()) {
            runCatching {
                val f = java.io.File(filterCosmeticCssPath)
                if (f.exists() && f.length() > 0) f.readText() else ""
            }.getOrDefault("")
        } else {
            ""
        }

        return listOf(baseCss, filterCss)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    /**
     * Loads ad path patterns from the active browser rule package.
     */
    fun loadAdPathPatterns(context: Context): String {
        return try {
            val storage = BrowserRuleStorage(context)
            val pkg = storage.getActivePackage()
            pkg.adPathPatterns.joinToString("\n")
        } catch (e: Exception) {
            Timber.w(e, "Failed to load ad path patterns from storage")
            ""
        }
    }

    /**
     * Loads scriptlets JS from the active browser rule package (adguard_scriptlets.js / remote updates).
     * Global scriptlets only contain universal runtimes; domain-specific scripts (e.g. YouTube)
     * are isolated to RuleManifest modules.
     */
    fun loadScriptletsJs(context: Context): String {
        val swKiller = runCatching {
            context.assets.open("browser/service_worker_killer.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val baseScriptlets = try {
            val storage = BrowserRuleStorage(context)
            val pkg = storage.getActivePackage()
            val raw = pkg.scriptletsJs?.trim()
            if (raw.isNullOrBlank() || raw.startsWith("http://") || raw.startsWith("https://")) {
                context.assets.open("browser/adguard_scriptlets.js").bufferedReader().use { it.readText() }
            } else {
                raw
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load scriptlets JS from storage, trying assets directly")
            runCatching {
                context.assets.open("browser/adguard_scriptlets.js").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }

        return listOf(swKiller, baseScriptlets)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    /**
     * Loads dynamic Rule Manifest JSON for Go tunnel engine (TrafficInterceptor & ManifestRegistry).
     * Domain-specific scripts like YouTube sanitizer are scoped into the appropriate module.
     */
    fun loadRuleManifest(context: Context): String {
        return try {
            val storage = BrowserRuleStorage(context)
            val pkg = storage.getActivePackage()
            val ytSanitizer = runCatching {
                context.assets.open("browser/youtube_sanitizer.js").bufferedReader().use { it.readText() }
            }.getOrDefault("")

            val modules = pkg.modules.map { mod ->
                val resolvedScripts = mod.scripts.map { scriptRef ->
                    if (scriptRef.endsWith(".js")) {
                        runCatching {
                            context.assets.open("browser/$scriptRef").bufferedReader().use { it.readText() }
                        }.getOrDefault(scriptRef)
                    } else {
                        scriptRef
                    }
                }
                if (mod.id == "youtube" && resolvedScripts.none { it.contains("__blockads_yt_sanitizer_injected") } && ytSanitizer.isNotBlank()) {
                    mod.copy(scripts = listOf(ytSanitizer) + resolvedScripts)
                } else {
                    mod.copy(scripts = resolvedScripts)
                }
            }
            val manifestPkg = pkg.copy(modules = modules)
            kotlinx.serialization.json.Json.encodeToString(
                app.pwhs.blockads.ui.browser.rules.BrowserRulePackage.serializer(),
                manifestPkg
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to serialize rule manifest")
            "{}"
        }
    }

    /**
     * Loads modular rules from modules.json (cached OTA update or local asset),
     * merges any external filter module rules, resolves asset script references,
     * and passes the compiled JSON to the Go engine.
     */
    fun loadModules(context: Context, engine: tunnel.Engine, filterModulesPath: String? = null) {
        try {
            val jsonStr = loadModulesJson(context, filterModulesPath)
            if (jsonStr.isNotBlank()) {
                engine.setModulesConfig(jsonStr)
                Timber.d("Modular rules loaded successfully into Go engine (%d bytes)", jsonStr.length)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load modular rules into Go engine")
        }
    }

    /**
     * Reads modules.json, resolves any bundled script names,
     * and returns the JSON string.
     */
    fun loadModulesJson(context: Context, filterModulesPath: String? = null): String {
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            val assetRaw = runCatching {
                context.assets.open("browser/modules.json").bufferedReader().use { it.readText() }
            }.getOrDefault("")
            val cachedFile = java.io.File(context.filesDir, "browser_rules/modules.json")
            val cachedRaw = if (cachedFile.exists() && cachedFile.length() > 0) {
                runCatching { cachedFile.readText() }.getOrDefault("")
            } else {
                ""
            }

            val assetPkg = if (assetRaw.isNotBlank()) {
                runCatching { json.decodeFromString<app.pwhs.blockads.data.models.ModulesPackage>(assetRaw) }.getOrNull()
            } else null
            val cachedPkg = if (cachedRaw.isNotBlank()) {
                runCatching { json.decodeFromString<app.pwhs.blockads.data.models.ModulesPackage>(cachedRaw) }.getOrNull()
            } else null

            val basePkg = when {
                cachedPkg != null && assetPkg != null -> if (assetPkg.version >= cachedPkg.version) assetPkg else cachedPkg
                assetPkg != null -> assetPkg
                cachedPkg != null -> cachedPkg
                else -> app.pwhs.blockads.data.models.ModulesPackage()
            }

            val filterModPkg = if (!filterModulesPath.isNullOrBlank()) {
                val f = java.io.File(filterModulesPath)
                if (f.exists() && f.length() > 0L) {
                    runCatching {
                        json.decodeFromString<app.pwhs.blockads.data.models.ModuleRulesPackage>(f.readText())
                    }.getOrNull()
                } else null
            } else null

            val resolvedModules = basePkg.modules.map { mod ->
                val resolvedScripts = mod.scripts.map { scriptRef ->
                    if (scriptRef.endsWith(".js")) {
                        runCatching {
                            context.assets.open("browser/$scriptRef").bufferedReader().use { it.readText() }
                        }.getOrDefault(scriptRef)
                    } else {
                        scriptRef
                    }
                }
                mod.copy(scripts = resolvedScripts)
            }

            val mergedPkg = if (filterModPkg != null) {
                val mergedDomains = basePkg.domains.toMutableMap()
                for ((domain, rules) in filterModPkg.domains) {
                    val existing = mergedDomains[domain]
                    mergedDomains[domain] = if (existing == null) {
                        rules
                    } else {
                        app.pwhs.blockads.data.models.DomainModuleRules(
                            css = (existing.css + rules.css).distinct(),
                            cssExceptions = (existing.cssExceptions + rules.cssExceptions).distinct(),
                            injectCss = (existing.injectCss + rules.injectCss).distinct(),
                            blockPaths = (existing.blockPaths + rules.blockPaths).distinct(),
                            removeParams = (existing.removeParams + rules.removeParams).distinct()
                        )
                    }
                }
                basePkg.copy(
                    modules = resolvedModules,
                    domains = mergedDomains,
                    globalPathsSnake = (basePkg.globalPathsSnake + filterModPkg.globalBlockPaths).distinct(),
                    globalCssExceptions = (basePkg.globalCssExceptions + filterModPkg.globalCssExceptions).distinct(),
                    globalInjectCss = (basePkg.globalInjectCss + filterModPkg.globalInjectCss).distinct(),
                    globalRemoveParams = (basePkg.globalRemoveParams + filterModPkg.globalRemoveParams).distinct()
                )
            } else {
                basePkg.copy(modules = resolvedModules)
            }

            json.encodeToString(
                app.pwhs.blockads.data.models.ModulesPackage.serializer(),
                mergedPkg
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to load and resolve modules.json")
            ""
        }
    }

    fun loadPresetBrowsers(context: Context): Set<String> = runCatching {
        context.assets.open("preset/browsers.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        }
    }.getOrDefault(setOf("com.android.chrome", "org.mozilla.firefox", "com.brave.browser"))

    fun loadGeoIPDatabase(context: Context, engine: tunnel.Engine) {
        try {
            val localFile = java.io.File(context.filesDir, "geoip_ipv4.bin")
            if (localFile.exists() && localFile.length() > 0) {
                val data = localFile.readBytes()
                engine.setGeoIPDatabaseBytes(data)
                Timber.d("GeoIP database loaded into Go engine from local storage (${data.size} bytes)")
                return
            }

            val loaded = BlocklistInfo.fromAsset(context, "preset/geoip_ipv4.bin")?.use { info ->
                engine.setGeoIPDatabaseFromFd(info.fd, info.startOffset, info.length)
                true
            } ?: false
            if (!loaded) {
                val data = context.assets.open("preset/geoip_ipv4.bin").use { it.readBytes() }
                engine.setGeoIPDatabaseBytes(data)
            }
            Timber.d("GeoIP database loaded into Go engine from asset")
        } catch (_: java.io.FileNotFoundException) {
            Timber.d("GeoIP database not yet downloaded, engine running without GeoIP ruleset")
        } catch (e: Exception) {
            Timber.w(e, "Failed to load GeoIP database into Go engine")
        }
    }

    private const val KEY_DISABLED_PASSTHROUGH = "disabled_builtin_passthrough"

    fun getDisabledBuiltInPassthrough(context: Context): Set<String> =
        context.getSharedPreferences(HTTPS_RULE_PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DISABLED_PASSTHROUGH, emptySet()).orEmpty()

    fun setBuiltInPassthroughEnabled(context: Context, domain: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(HTTPS_RULE_PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_DISABLED_PASSTHROUGH, emptySet()).orEmpty().toMutableSet()
        val key = domain.trim().lowercase(java.util.Locale.US)
        if (enabled) current.remove(key) else current.add(key)
        prefs.edit().putStringSet(KEY_DISABLED_PASSTHROUGH, current).apply()
    }

    fun loadExtraPassthrough(context: Context, engine: tunnel.Engine) {
        try {
            val disabled = getDisabledBuiltInPassthrough(context)
            if (disabled.isEmpty()) {
                val loaded = BlocklistInfo.fromAsset(context, "https_passthrough.txt")?.use { info ->
                    engine.setExtraPassthroughSuffixesFromFd(info.fd, info.startOffset, info.length)
                    true
                } ?: false
                if (!loaded) {
                    val passthrough = context.assets.open("https_passthrough.txt")
                        .bufferedReader().use { it.readText() }
                    engine.setExtraPassthroughSuffixes(passthrough)
                }
            } else {
                val filtered = context.assets.open("https_passthrough.txt")
                    .bufferedReader()
                    .useLines { lines ->
                        lines.filter { line ->
                            val t = line.trim().lowercase(java.util.Locale.US)
                            t.isEmpty() || t.startsWith("#") || t.startsWith("//") || !disabled.contains(t)
                        }.joinToString("\n")
                    }
                engine.setExtraPassthroughSuffixes(filtered)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load https_passthrough.txt asset")
        }
    }

    /**
     * Loads any cached remote ruleset files declared in [filter_remote] of the active profile.
     */
    fun loadCachedRemoteRulesets(context: Context, engine: tunnel.Engine) {
        try {
            val jsonStr = engine.rulesetRemoteFiltersJSON
            if (jsonStr.isBlank() || jsonStr == "[]") return

            val remoteDir = java.io.File(context.filesDir, "remote_rulesets")
            if (!remoteDir.exists()) return

            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val tag = obj.optString("tag").ifBlank { "remote_$i" }
                val targetFile = java.io.File(remoteDir, "$tag.list")
                if (targetFile.exists() && targetFile.length() > 0) {
                    val content = targetFile.readText()
                    val added = engine.appendRemoteFilterRules(tag, content)
                    Timber.d("Loaded cached remote ruleset [$tag]: $added rules")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load cached remote rulesets")
        }
    }

    /**
     * Returns upstream DNS servers declared in [dns] section of active profile.
     */
    fun getProfileDNSServers(engine: tunnel.Engine): List<String> {
        val csv = engine.rulesetDNSServersCSV
        if (csv.isBlank()) return emptyList()
        return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Returns DNS exclusion list declared in [general] (dns_exclusion_list) of active profile.
     */
    fun getProfileDNSExclusions(engine: tunnel.Engine): List<String> {
        val csv = engine.rulesetDNSExclusionListCSV
        if (csv.isBlank()) return emptyList()
        return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Applies profile [dns] upstream servers if declared in active ruleset.
     */
    fun applyProfileDnsIfPresent(engine: tunnel.Engine, onApply: (primary: String, fallback: String) -> Unit) {
        val servers = getProfileDNSServers(engine)
        if (servers.isNotEmpty()) {
            val primary = servers[0]
            val fallback = servers.getOrNull(1) ?: "8.8.8.8"
            onApply(primary, fallback)
            Timber.d("Profile [dns] applied: primary=$primary, fallback=$fallback")
        }
    }

    private const val HTTPS_RULE_PREFS = "https_rule_prefs"
    private const val KEY_USE_BUILTIN = "use_builtin_https_rules"

    fun isBuiltInRulesEnabled(context: Context): Boolean =
        context.getSharedPreferences(HTTPS_RULE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_BUILTIN, true)

    fun setBuiltInRulesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(HTTPS_RULE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_BUILTIN, enabled).apply()
    }

    fun updateCosmeticRules(
        context: Context,
        engine: tunnel.Engine,
        filterRepo: app.pwhs.blockads.data.repository.FilterListRepository
    ) {
        val useBuiltIn = isBuiltInRulesEnabled(context)

        runCatching {
            val filterCssPath = filterRepo.getCosmeticCssPath()
            val cssFile = if (!filterCssPath.isNullOrBlank()) {
                prepareEngineCosmeticCssFile(context, filterCssPath, useBuiltIn)
            } else {
                null
            }
            if (cssFile != null) {
                engine.setCosmeticCSSFromFile(cssFile.absolutePath)
                Timber.d("Cosmetic CSS loaded from file for engine (builtIn=%b): %d bytes", useBuiltIn, cssFile.length())
            } else {
                val css = if (useBuiltIn) loadBaseCosmeticCss(context) else ""
                engine.setCosmeticCSS(css)
                Timber.d("Cosmetic CSS updated for engine (builtIn=%b): %d bytes", useBuiltIn, css.length)
            }
        }.onFailure {
            Timber.e(it, "Failed to load cosmetic CSS for engine")
            engine.setCosmeticCSS("")
        }

        runCatching {
            val js = if (useBuiltIn) loadScriptletsJs(context) else ""
            engine.setScriptletsRuntime(js)
            Timber.d("Scriptlets runtime updated for engine: %d bytes", js.length)
        }.onFailure { Timber.e(it, "Failed to load scriptlets JS for engine") }

        runCatching {
            val manifest = if (useBuiltIn) loadRuleManifest(context) else ""
            engine.setRuleManifestJSON(manifest)
            Timber.d("Rule manifest updated for engine: %d bytes", manifest.length)
        }.onFailure { Timber.e(it, "Failed to load rule manifest for engine") }

        runCatching {
            if (useBuiltIn) {
                loadModules(context, engine, filterRepo.getFilterModulesPath())
            } else {
                val fp = filterRepo.getFilterModulesPath()
                val modJson = if (!fp.isNullOrBlank()) java.io.File(fp).takeIf { it.exists() }?.readText().orEmpty() else ""
                engine.setModulesConfig(modJson.ifBlank { """{"modules":[]}""" })
            }
        }.onFailure { Timber.e(it, "Failed to load modular rules for engine") }

        runCatching {
            val sp = filterRepo.getScriptletsPath()
            if (sp != null && java.io.File(sp).exists()) engine.setScriptletRulesFromFile(sp) else engine.setScriptletRules("")
        }.onFailure {
            Timber.e(it, "Failed to load scriptlet rules for engine")
            engine.setScriptletRules("")
        }

        runCatching {
            val patterns = if (useBuiltIn) loadAdPathPatterns(context) else ""
            engine.setAdPathPatterns(patterns)
            if (patterns.isNotEmpty()) Timber.d("Ad path patterns loaded: ${patterns.lines().size} patterns")
        }.onFailure {
            Timber.e(it, "Failed to load ad path patterns for engine")
            engine.setAdPathPatterns("")
        }

        loadIpBlocklists(engine, filterRepo)
    }

    /**
     * Loads `.ips` sidecar blocklists from enabled filters into the Go engine.
     * Uses reflective lookup so the app continues compiling against older prebuilt tunnel.aar
     * releases until the next tunnel AAR containing SetIPBlocklistFromFiles is published.
     */
    fun loadIpBlocklists(
        engine: tunnel.Engine,
        filterRepo: app.pwhs.blockads.data.repository.FilterListRepository
    ) {
        val paths = filterRepo.getIpBlocklistPaths()
        runCatching {
            val method = engine.javaClass.getMethod("setIPBlocklistFromFiles", String::class.java)
            method.invoke(engine, paths)
            if (paths.isNotEmpty()) {
                Timber.d("Loaded IP blocklist files into engine: ${paths.lines().size} file(s)")
            }
        }.onFailure { err ->
            if (err is NoSuchMethodException) {
                Timber.d("Go engine AAR does not yet expose setIPBlocklistFromFiles; skipping .ips load")
            } else {
                Timber.w(err, "Failed to load IP blocklist files into engine")
            }
        }
    }
}
