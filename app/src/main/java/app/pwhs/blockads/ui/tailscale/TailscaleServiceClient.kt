package app.pwhs.blockads.ui.tailscale

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.tailscale.ITailscaleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class TailscaleServiceClient(
    private val context: Context,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
) {
    var isBound: Boolean = false
        private set

    private var tailscaleService: ITailscaleService? = null

    val isConnected: Boolean
        get() = tailscaleService != null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            tailscaleService = ITailscaleService.Stub.asInterface(service)
            onConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            tailscaleService = null
            // R4 Requirement: Do NOT set isBound = false here.
            // Android maintains the binding; only clear tailscaleService.
            onDisconnected()
        }

        override fun onBindingDied(name: ComponentName?) {
            unbind()
        }
    }

    fun bind() {
        if (isBound) return
        val intent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_TAILSCALE_BIND
        }
        try {
            isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Timber.e(e, "Failed to bind Tailscale service")
        }
    }

    fun unbind() {
        if (!isBound) return
        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            Timber.w(e, "Failed to unbind Tailscale service")
        } finally {
            isBound = false
            tailscaleService = null
        }
    }

    suspend fun getStatus(): String = withContext(Dispatchers.IO) {
        try {
            tailscaleService?.tailscaleStatus ?: ""
        } catch (e: Exception) {
            Timber.w(e, "Failed to get Tailscale status via service")
            ""
        }
    }

    suspend fun configureTailscale(stateJson: String, controlUrl: String, authKey: String): String =
        withContext(Dispatchers.IO) {
            try {
                tailscaleService?.configureTailscale(stateJson, controlUrl, authKey) ?: ""
            } catch (e: Exception) {
                Timber.w(e, "Failed to configure Tailscale via service")
                ""
            }
        }

    suspend fun startSession(hostname: String = "blockads-android"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                tailscaleService?.startTailscaleSession(hostname)
                true
            } catch (e: Exception) {
                Timber.w(e, "Failed to start Tailscale session via service")
                false
            }
        }

    fun stopTailscale(): Boolean {
        return try {
            tailscaleService?.stopTailscale()
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to stop Tailscale session via service")
            false
        }
    }

    suspend fun setExitNode(nodeId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            tailscaleService?.setTailscaleExitNode(nodeId)
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to set Tailscale exit node via service")
            false
        }
    }

    suspend fun pingPeer(target: String): String = withContext(Dispatchers.IO) {
        try {
            tailscaleService?.pingTailscalePeer(target) ?: ""
        } catch (e: Exception) {
            Timber.w(e, "Failed to ping Tailscale peer")
            ""
        }
    }

    suspend fun syncSession(
        stateJson: String,
        controlUrl: String,
        authKey: String,
        exitNodeId: Long,
        hostname: String = "blockads-android"
    ): String? = withContext(Dispatchers.IO) {
        val service = tailscaleService ?: return@withContext null
        try {
            val updatedState = service.configureTailscale(stateJson, controlUrl, authKey)
            if (exitNodeId != 0L) {
                service.setTailscaleExitNode(exitNodeId)
            }
            service.startTailscaleSession(hostname)
            updatedState
        } catch (e: Exception) {
            Timber.w(e, "Failed to sync active Tailscale session via service")
            null
        }
    }
}
