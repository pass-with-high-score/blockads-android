package app.pwhs.blockads.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.pwhs.blockads.R

class AdBlockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        if (AdBlockVpnService.isRunning) {
            // Stop VPN
            val intent = Intent(this, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
            startService(intent)
        } else {
            // Start VPN
            val intent = Intent(this, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        // Update tile after a short delay to reflect new state
        qsTile?.let { tile ->
            tile.state = if (AdBlockVpnService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            if (AdBlockVpnService.isRunning) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.app_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Protected"
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
