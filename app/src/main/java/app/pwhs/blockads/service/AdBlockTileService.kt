package app.pwhs.blockads.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.utils.VpnUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AdBlockTileService : TileService() {

    private val appPrefs: AppPreferences by inject()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

        // Don't allow toggling while VPN is still tearing down
        if (AdBlockVpnService.isStopping) return

        serviceScope.launch {
            val isRootProxyRunning = RootProxyService.isRunning
            val isVpnRunning = AdBlockVpnService.isRunning

            val isLocked = appPrefs.lockdownEnabled.first()
            if (isLocked && (isRootProxyRunning || isVpnRunning)) {
                updateTileState()
                return@launch
            }

            if (isRootProxyRunning) {
                RootProxyService.stop(this@AdBlockTileService)
            } else if (isVpnRunning) {
                AdBlockVpnService.stop(this@AdBlockTileService)
            } else {
                val routingMode = appPrefs.getRoutingModeSnapshot()
                if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                    RootProxyService.start(this@AdBlockTileService)
                } else {
                    if (VpnUtils.isOtherVpnActive(this@AdBlockTileService)) {
                        val intent = Intent(this@AdBlockTileService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(MainActivity.EXTRA_SHOW_VPN_CONFLICT_DIALOG, true)
                        }
                        val pendingIntent = PendingIntent.getActivity(
                            this@AdBlockTileService, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            startActivityAndCollapse(pendingIntent)
                        } else {
                            startActivityAndCollapse(intent)
                        }
                        return@launch
                    }

                    AdBlockVpnService.start(this@AdBlockTileService)
                }
            }

            // Update tile after initiating state change
            qsTile?.let { tile ->
                val isRunning = AdBlockVpnService.isRunning || RootProxyService.isRunning
                tile.state = if (isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
                tile.updateTile()
            }
        }
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            val isRunning = AdBlockVpnService.isRunning || RootProxyService.isRunning
            if (isRunning) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.app_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val isRoot = RootProxyService.isRunning
                    tile.subtitle = if (isRoot) "Root Proxy" else "Protected"
                }
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Disabled"
                }
            }
            tile.updateTile()
        }
    }
}
