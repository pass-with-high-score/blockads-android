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
     * Loads cosmetic CSS from the active browser rule package (adblock_cosmetic.css / remote updates).
     */
    fun loadCosmeticCss(context: Context): String {
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
     * Loads ad path patterns from the active browser rule package.
     */
    fun loadAdPathPatterns(context: Context): String {
        return try {
            val storage = BrowserRuleStorage(context)
            val pkg = storage.getActivePackage()
            val patterns = pkg.adPathPatterns.ifEmpty {
                app.pwhs.blockads.ui.browser.rules.BrowserRuleDefaults.AD_PATH_PATTERNS
            }
            patterns.joinToString("\n")
        } catch (e: Exception) {
            Timber.w(e, "Failed to load ad path patterns from storage")
            app.pwhs.blockads.ui.browser.rules.BrowserRuleDefaults.AD_PATH_PATTERNS.joinToString("\n")
        }
    }

    /**
     * Loads scriptlets JS from the active browser rule package (adguard_scriptlets.js / remote updates).
     */
    fun loadScriptletsJs(context: Context): String {
        val swKiller = runCatching {
            context.assets.open("browser/service_worker_killer.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val ytSanitizer = runCatching {
            context.assets.open("browser/youtube_sanitizer.js").bufferedReader().use { it.readText() }
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

        return listOf(swKiller, baseScriptlets, ytSanitizer)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    fun loadPresetBrowsers(context: Context): Set<String> = runCatching {
        context.assets.open("preset/browsers.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        }
    }.getOrDefault(setOf("com.android.chrome", "org.mozilla.firefox", "com.brave.browser"))

    fun loadGeoIPDatabase(context: Context, engine: tunnel.Engine) {
        try {
            val loaded = BlocklistInfo.fromAsset(context, "preset/geoip_ipv4.bin")?.use { info ->
                engine.setGeoIPDatabaseFromFd(info.fd, info.startOffset, info.length)
                true
            } ?: false
            if (!loaded) {
                val data = context.assets.open("preset/geoip_ipv4.bin").use { it.readBytes() }
                engine.setGeoIPDatabaseBytes(data)
            }
            Timber.d("GeoIP database loaded into Go engine")
        } catch (e: Exception) {
            Timber.w(e, "Failed to load GeoIP database asset into Go engine")
        }
    }

    fun loadExtraPassthrough(context: Context, engine: tunnel.Engine) {
        try {
            val loaded = BlocklistInfo.fromAsset(context, "https_passthrough.txt")?.use { info ->
                engine.setExtraPassthroughSuffixesFromFd(info.fd, info.startOffset, info.length)
                true
            } ?: false
            if (!loaded) {
                val passthrough = context.assets.open("https_passthrough.txt")
                    .bufferedReader().use { it.readText() }
                engine.setExtraPassthroughSuffixes(passthrough)
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
}
