package app.pwhs.blockads.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.common.SupportErrorInfo
import timber.log.Timber

object SupportIntentHelper {
    const val TELEGRAM_URL = "https://t.me/blockads_android_chat"
    const val GITHUB_ISSUES_URL = "https://github.com/pass-with-high-score/blockads-android/issues/new"
    const val SUPPORT_EMAIL = "support@pwhs.app"

    fun buildDiagnosticInfo(errorInfo: SupportErrorInfo): String = buildString {
        appendLine("--- Device & App Diagnostics ---")
        appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        if (!errorInfo.errorType.isNullOrBlank()) {
            appendLine("Category: ${errorInfo.errorType}")
        }
        appendLine("Error Title: ${errorInfo.title}")
        appendLine("Description: ${errorInfo.message}")
        if (!errorInfo.details.isNullOrBlank()) {
            appendLine("\n--- Error Log / Context ---")
            appendLine(errorInfo.details)
        }
    }

    fun openTelegram(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, TELEGRAM_URL.toUri()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        tryStartActivity(context, intent)
    }

    fun openGitHubIssue(context: Context, errorInfo: SupportErrorInfo) {
        val diagnostics = buildDiagnosticInfo(errorInfo)
        val body = """
            ### Description
            ${errorInfo.message}

            ### Diagnostics
            ```text
            $diagnostics
            ```
        """.trimIndent()

        val uri = GITHUB_ISSUES_URL.toUri().buildUpon()
            .appendQueryParameter("title", "[Bug]: ${errorInfo.title}")
            .appendQueryParameter("body", body)
            .build()

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        tryStartActivity(context, intent)
    }

    fun sendEmail(context: Context, errorInfo: SupportErrorInfo) {
        val diagnostics = buildDiagnosticInfo(errorInfo)
        val subject = "[Bug Report] ${errorInfo.title}"
        val body = """
            Hello BlockAds Team,

            I encountered the following issue in the app:
            ${errorInfo.message}

            $diagnostics
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:$SUPPORT_EMAIL".toUri()
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        tryStartActivity(context, intent)
    }

    fun copyDiagnostics(context: Context, errorInfo: SupportErrorInfo) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("BlockAds_Error_Diagnostics", buildDiagnosticInfo(errorInfo))
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.support_copied), Toast.LENGTH_SHORT).show()
    }

    fun openPlayStore(context: Context) {
        val packageName = context.packageName
        val marketIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(marketIntent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            tryStartActivity(context, webIntent)
        }
    }

    private fun tryStartActivity(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "SupportIntentHelper: failed to launch activity")
        }
    }
}
