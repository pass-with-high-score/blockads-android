package app.pwhs.blockads.service

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.app.PendingIntent
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.utils.VpnUtils

import org.koin.android.ext.android.inject
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.runBlocking

class AdBlockTileService : TileService() {

    private val appPrefs: AppPreferences by inject()

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

        // Don't allow toggling while VPN is still tearing down
        if (AdBlockVpnService.isStopping) return

        val isRootProxyRunning = RootProxyService.isRunning
        val isVpnRunning = AdBlockVpnService.isRunning

        if (isRootProxyRunning) {
            RootProxyService.stop(this)
        } else if (isVpnRunning) {
            AdBlockVpnService.stop(this)
        } else {
            val routingMode = runBlocking { appPrefs.getRoutingModeSnapshot() }
            if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                RootProxyService.start(this)
            } else {
                if (VpnUtils.isOtherVpnActive(this)) {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_SHOW_VPN_CONFLICT_DIALOG, true)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startActivityAndCollapse(pendingIntent)
                    } else {
                        startActivityAndCollapse(intent)
                    }
                    return
                }

                AdBlockVpnService.start(this)
            }
        }

        // Update tile after a short delay to reflect new state
        qsTile?.let { tile ->
            val isRunning = AdBlockVpnService.isRunning || RootProxyService.isRunning
            tile.state = if (isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
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
