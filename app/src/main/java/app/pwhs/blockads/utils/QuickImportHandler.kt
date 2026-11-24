package app.pwhs.blockads.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.service.ServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

sealed interface QuickImportResult {
    data class Success(val message: String) : QuickImportResult
    data class Error(val message: String) : QuickImportResult
    data object NotApplicable : QuickImportResult
}

/**
 * Handles incoming ACTION_VIEW / ACTION_SEND intents for `.conf` and `.txt` files
 * (or shared text) to quickly import WireGuard/AmneziaWG configs or proxy links.
 */
object QuickImportHandler {
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024 // 2 MB

    fun isImportIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data != null
            Intent.ACTION_SEND -> extractSendUri(intent) != null || !intent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()
            else -> false
        }
    }

    suspend fun handleIntent(
        context: Context,
        intent: Intent?,
        appPrefs: AppPreferences,
    ): QuickImportResult = withContext(Dispatchers.IO) {
        if (intent == null) return@withContext QuickImportResult.NotApplicable

        val (rawContent, derivedName) = when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return@withContext QuickImportResult.NotApplicable
                val text = readTextFromUri(context, uri)
                    ?: return@withContext QuickImportResult.Error("Could not read file")
                text to deriveDisplayName(context, uri)
            }
            Intent.ACTION_SEND -> {
                val uri = extractSendUri(intent)
                if (uri != null) {
                    val text = readTextFromUri(context, uri)
                        ?: return@withContext QuickImportResult.Error("Could not read shared file")
                    text to deriveDisplayName(context, uri)
                } else {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                    if (sharedText.isNullOrEmpty()) return@withContext QuickImportResult.NotApplicable
                    sharedText to null
                }
            }
            else -> return@withContext QuickImportResult.NotApplicable
        }

        importTextContent(context, rawContent, derivedName, appPrefs)
    }

    internal suspend fun importTextContent(
        context: Context,
        rawContent: String,
        derivedName: String?,
        appPrefs: AppPreferences,
    ): QuickImportResult {
        val content = rawContent.trim()
        if (content.isEmpty()) {
            return QuickImportResult.Error("File is empty")
        }

        // 1. Try WireGuard / AmneziaWG INI config ([Interface] + [Peer])
        if (looksLikeWireGuardIni(content)) {
            return try {
                val parsed = WireGuardConfigParser.parse(content)
                val name = derivedName?.takeIf { it.isNotBlank() }
                    ?: "WireGuard #${System.currentTimeMillis() % 1000}"
                val profile = WireGuardProfile(
                    id = WireGuardProfile.newId(),
                    name = name,
                    config = parsed,
                )
                val makeActive = appPrefs.getActiveWgProfileSnapshot() == null && profile.configIssue == null
                appPrefs.addOrUpdateWgProfile(profile, makeActive = makeActive)
                if (makeActive) {
                    ServiceController.requestRestart(context)
                }
                Timber.i("Quick-imported WireGuard profile: %s (active=%b)", name, makeActive)
                QuickImportResult.Success("Imported WireGuard profile: $name")
            } catch (e: Exception) {
                Timber.w(e, "Quick import failed to parse WireGuard config")
                QuickImportResult.Error(e.message ?: "Invalid WireGuard configuration")
            }
        }

        // 2. Try Proxy links / WireGuard URIs / Base64 / JSON subscription
        val subResult = SubscriptionImporter.parseSubscriptionContent(content)
        if (subResult.totalParsed > 0) {
            if (subResult.proxyProfiles.isNotEmpty()) {
                appPrefs.addProxyProfiles(subResult.proxyProfiles)
            }
            var hadNoActiveWg = appPrefs.getActiveWgProfileSnapshot() == null
            for (wg in subResult.wireGuardProfiles) {
                val makeActive = hadNoActiveWg && wg.configIssue == null
                appPrefs.addOrUpdateWgProfile(wg, makeActive = makeActive)
                if (makeActive) hadNoActiveWg = false
            }
            val total = subResult.totalParsed
            Timber.i(
                "Quick-imported %d profile(s) (proxy=%d, wg=%d)",
                total,
                subResult.proxyProfiles.size,
                subResult.wireGuardProfiles.size
            )
            return QuickImportResult.Success("Imported $total proxy/VPN profile(s)")
        }

        return QuickImportResult.Error("No valid WireGuard or proxy configuration found in file")
    }

    private fun looksLikeWireGuardIni(content: String): Boolean {
        return content.contains("[Interface]", ignoreCase = true) &&
            content.contains("[Peer]", ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun extractSendUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun readTextFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_FILE_BYTES) {
                        Timber.w("QuickImport: file exceeds 2MB limit")
                        return null
                    }
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: Exception) {
            Timber.w(e, "QuickImport: failed to read URI %s", uri)
            null
        }
    }

    private fun deriveDisplayName(context: Context, uri: Uri): String? {
        val raw = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment

        return raw
            ?.substringAfterLast('/')
            ?.removeSuffix(".conf")
            ?.removeSuffix(".txt")
            ?.removeSuffix(".json")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
