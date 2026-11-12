package app.pwhs.blockadstv.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import app.pwhs.blockadstv.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collects and formats detailed TV device environment info, Timber file logs,
 * and system logcat output for offline debugging and diagnostic sharing.
 */
object LogcatExporter {

    private const val MAX_LOGCAT_LINES = 2500
    private const val MAX_FILE_LOG_LINES = 1000

    fun collectDeviceInfo(context: Context): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        val now = dateFormat.format(Date())

        sb.appendLine("================================================================================")
        sb.appendLine("  BLOCKADS TV DIAGNOSTIC & LOGCAT REPORT")
        sb.appendLine("  Generated: $now")
        sb.appendLine("================================================================================")
        sb.appendLine()

        sb.appendLine("--- [1] APPLICATION INFO ---")
        sb.appendLine("  App Name:         BlockAds TV")
        sb.appendLine("  Package:          ${context.packageName}")
        sb.appendLine("  Version Name:     ${BuildConfig.VERSION_NAME}")
        sb.appendLine("  Version Code:     ${BuildConfig.VERSION_CODE}")
        sb.appendLine("  Build Type:       ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("  Process ID (PID): ${Process.myPid()}")
        sb.appendLine("  Target SDK:       ${context.applicationInfo.targetSdkVersion}")
        sb.appendLine()

        sb.appendLine("--- [2] DEVICE & OS ---")
        sb.appendLine("  Manufacturer:     ${Build.MANUFACTURER}")
        sb.appendLine("  Brand / Model:    ${Build.BRAND} ${Build.MODEL}")
        sb.appendLine("  Device / Product: ${Build.DEVICE} (${Build.PRODUCT})")
        sb.appendLine("  Hardware / Board: ${Build.HARDWARE} / ${Build.BOARD}")
        sb.appendLine("  Android Version:  ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("  Security Patch:   ${Build.VERSION.SECURITY_PATCH}")
        sb.appendLine("  Supported ABIs:   ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("  Locale / TZ:      ${Locale.getDefault()} / ${TimeZone.getDefault().id}")
        sb.appendLine()

        sb.appendLine("--- [3] SYSTEM RESOURCES ---")
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        sb.appendLine("  RAM Available:    $availMb MB / $totalMb MB (LowMem: ${memInfo.lowMemory})")

        val rt = Runtime.getRuntime()
        val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxHeapMb = rt.maxMemory() / (1024 * 1024)
        sb.appendLine("  JVM Heap:         $usedHeapMb MB used / $maxHeapMb MB max")
        sb.appendLine()

        sb.appendLine("--- [4] NETWORK & PROTECTION STATUS ---")
        val vpnPrepared = VpnService.prepare(context) == null
        sb.appendLine("  VPN Permission:   ${if (vpnPrepared) "Prepared (Granted)" else "Not Prepared"}")

        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = connMgr?.activeNetwork
        val caps = connMgr?.getNetworkCapabilities(activeNet)
        val networkType = when {
            caps == null -> "None / Disconnected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN Active"
            else -> "Other"
        }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        sb.appendLine("  Active Network:   $networkType (Internet: $hasInternet, Validated: $isValidated)")
        sb.appendLine()

        return sb.toString()
    }

    fun captureLogcat(maxLines: Int = MAX_LOGCAT_LINES): List<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
            val lines = mutableListOf<String>()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    lines.add(line)
                    line = reader.readLine()
                }
            }
            if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        } catch (e: Exception) {
            listOf("Logcat capture unavailable: ${e.message}")
        }
    }

    fun readAppFileLogs(context: Context, maxLines: Int = MAX_FILE_LOG_LINES): String {
        val logFile = File(context.cacheDir, "logs/blockads_logs.txt")
        if (!logFile.exists() || logFile.length() == 0L) {
            return "(No app event logs recorded yet)\n"
        }
        return try {
            val lines = logFile.readLines()
            val trimmed = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
            trimmed.joinToString("\n") + "\n"
        } catch (e: Exception) {
            "(Failed to read file logs: ${e.message})\n"
        }
    }

    fun generateReport(
        context: Context,
        logcatProvider: () -> List<String> = { captureLogcat() },
    ): String {
        val sb = StringBuilder()
        sb.append(collectDeviceInfo(context))

        sb.appendLine("================================================================================")
        sb.appendLine("  [5] APPLICATION FILE LOGS (Timber Event History)")
        sb.appendLine("================================================================================")
        sb.append(readAppFileLogs(context))
        sb.appendLine()

        sb.appendLine("================================================================================")
        sb.appendLine("  [6] SYSTEM LOGCAT DUMP (Go Tunnel Engine & Runtime)")
        sb.appendLine("================================================================================")
        val logcatLines = logcatProvider()
        if (logcatLines.isEmpty()) {
            sb.appendLine("(Logcat buffer empty)")
        } else {
            logcatLines.forEach { sb.appendLine(it) }
        }
        sb.appendLine()

        sb.appendLine("================================================================================")
        sb.appendLine("  END OF DIAGNOSTIC REPORT")
        sb.appendLine("================================================================================")

        return sb.toString()
    }

    fun createReportFile(context: Context, reportContent: String = generateReport(context)): File {
        val logDir = File(context.cacheDir, "logs").apply { if (!exists()) mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(logDir, "blockadstv_logcat_$timeStamp.txt")
        exportFile.writeText(reportContent)
        return exportFile
    }

    fun exportAndShare(context: Context, chooserTitle: String) {
        val reportFile = createReportFile(context)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            reportFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "BlockAds TV Logcat & Diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
