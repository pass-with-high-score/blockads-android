package app.pwhs.blockads.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.pwhs.blockads.BuildConfig
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Manages Shizuku integration for executing privileged shell commands
 * as an alternative to Magisk/KernelSU root.
 *
 * Shizuku only provides root-level access when the device has
 * "ADB root debugging" enabled (common on custom ROMs where adbd
 * runs as uid 0).
 *
 * Uses [ShizukuShellService] (AIDL user service) to run commands
 * in Shizuku's privileged process.
 */
object ShizukuManager {

    private const val REQUEST_PERMISSION_CODE = 10001
    private const val BIND_TIMEOUT_SEC = 5L

    @Volatile
    private var shellService: IShizukuShellService? = null

    private var serviceConnection: ServiceConnection? = null

    /** True when Shizuku service is running and reachable. */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /** True when this app already has Shizuku permission. */
    fun isPermissionGranted(): Boolean {
        return try {
            isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /** Shows the Shizuku permission dialog. */
    fun requestPermission() {
        if (isAvailable()) {
            Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
        }
    }

    /**
     * Check if Shizuku can provide root-level access.
     * Returns true only when Shizuku is running, permission is granted,
     * AND the server runs as uid 0 (ADB root debugging enabled).
     */
    fun isRootAvailable(): Boolean {
        if (!isPermissionGranted()) return false
        val result = exec("id")
        return result.isSuccess && result.out.any { it.contains("uid=0") }
    }

    /**
     * Bind the Shizuku user service synchronously (blocks up to [BIND_TIMEOUT_SEC] seconds).
     * Returns true if the service is ready.
     */
    private fun ensureServiceBound(): Boolean {
        if (shellService != null) return true

        val future = CompletableFuture<IShizukuShellService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val svc = IShizukuShellService.Stub.asInterface(service)
                shellService = svc
                future.complete(svc)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                shellService = null
            }
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name)
        ).daemon(false).processNameSuffix("shell")

        return try {
            Shizuku.bindUserService(args, conn)
            serviceConnection = conn
            future.get(BIND_TIMEOUT_SEC, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to bind Shizuku shell service")
            false
        }
    }

    /**
     * Execute a shell command through Shizuku's privileged process.
     */
    fun exec(command: String): ShellResult {
        if (!ensureServiceBound()) {
            return ShellResult(false, emptyList(), listOf("Shizuku service bind failed"))
        }

        return try {
            val raw = shellService!!.execCommand(command)
            val lines = raw.lines()
            val exitCode = lines.firstOrNull()?.toIntOrNull() ?: -1
            val output = lines.drop(1).filter { it.isNotEmpty() }
            ShellResult(exitCode == 0, output, emptyList())
        } catch (e: Exception) {
            Timber.e(e, "Shizuku exec failed: %s", command)
            shellService = null // Force rebind on next call
            ShellResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }

    /** Unbind the shell service. Called when root proxy is stopped. */
    fun unbindService() {
        serviceConnection?.let {
            try {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name)
                )
                Shizuku.unbindUserService(args, it, true)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unbind Shizuku service")
            }
        }
        serviceConnection = null
        shellService = null
    }

    data class ShellResult(
        val isSuccess: Boolean,
        val out: List<String>,
        val err: List<String>,
    )
}
