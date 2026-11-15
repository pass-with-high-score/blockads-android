package app.pwhs.blockads.ui.tailscale

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.tailscale.ITailscaleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

class TailscaleServiceClient(
    private val context: Context,
    var onConnected: () -> Unit = {},
    var onDisconnected: () -> Unit = {},
) {
    var isBound: Boolean = false
        private set

    private var tailscaleService: ITailscaleService? = null
    private val rpcMutex = Mutex()

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
            val shouldRebind = isBound
            unbind()
            if (shouldRebind) {
                bind()
            }
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

    suspend fun stopTailscale(): Boolean {
        val service = tailscaleService
        return withContext(Dispatchers.IO) {
            rpcMutex.withLock {
                try {
                    (tailscaleService ?: service)?.stopTailscale()
                    true
                } catch (e: Exception) {
                    Timber.w(e, "Failed to stop Tailscale session via service")
                    false
                }
            }
        }
    }

    suspend fun setExitNode(nodeId: Long): Boolean = withContext(Dispatchers.IO) {
        rpcMutex.withLock {
            try {
                tailscaleService?.setTailscaleExitNode(nodeId)
                true
            } catch (e: Exception) {
                Timber.w(e, "Failed to set Tailscale exit node via service")
                false
            }
        }
    }

    suspend fun setAdvertiseExitNode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        rpcMutex.withLock {
            try {
                val service = tailscaleService ?: return@withLock false
                service.setTailscaleAdvertiseExitNode(enabled)
                true
            } catch (e: Exception) {
                Timber.w(e, "Failed to set Tailscale advertise exit node via service")
                false
            }
        }
    }

    suspend fun setAdBlockEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        rpcMutex.withLock {
            try {
                val service = tailscaleService ?: return@withLock false
                service.setTailscaleAdBlockEnabled(enabled)
                true
            } catch (e: Exception) {
                Timber.w(e, "Failed to set Tailscale adblock via service")
                false
            }
        }
    }

    suspend fun sendFile(target: String, fileName: String, filePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val service = tailscaleService
                    ?: return@withContext Result.failure(IllegalStateException("Tunnel not active"))
                val err = service.sendTailscaleFile(target, fileName, filePath)
                if (err.isNullOrEmpty()) {
                    Result.success(Unit)
                } else {
                    Result.failure(RuntimeException(err))
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to send Tailscale file via service")
                Result.failure(e)
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
        advertiseExitNode: Boolean = false,
        adblockEnabled: Boolean = true,
        hostname: String = "blockads-android"
    ): String? = withContext(Dispatchers.IO) {
        rpcMutex.withLock {
            val service = tailscaleService ?: return@withLock null
            try {
                coroutineContext.ensureActive()
                val updatedState = service.configureTailscale(stateJson, controlUrl, authKey)
                coroutineContext.ensureActive()
                if (exitNodeId != 0L) {
                    service.setTailscaleExitNode(exitNodeId)
                }
                service.setTailscaleAdvertiseExitNode(advertiseExitNode)
                service.setTailscaleAdBlockEnabled(adblockEnabled)
                coroutineContext.ensureActive()
                service.startTailscaleSession(hostname)
                updatedState
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to sync active Tailscale session via service")
                null
            }
        }
    }
}
