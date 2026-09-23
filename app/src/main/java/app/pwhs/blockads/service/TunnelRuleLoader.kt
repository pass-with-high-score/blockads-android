package app.pwhs.blockads.service

import android.content.Context
import app.pwhs.blockads.ui.browser.rules.BrowserRuleStorage
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
}
