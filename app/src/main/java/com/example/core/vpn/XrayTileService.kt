package com.example.core.vpn

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.MainActivity
import com.example.R

@RequiresApi(Build.VERSION_CODES.N)
class XrayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val current = XrayEngineState.state.value
        if (current.status == TunnelStatus.CONNECTED || current.status == TunnelStatus.CONNECTING) {
            startService(XrayVpnService.buildStopIntent(this))
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val runtime = XrayEngineState.state.value
        tile.state = when (runtime.status) {
            TunnelStatus.CONNECTED -> Tile.STATE_ACTIVE
            TunnelStatus.CONNECTING -> Tile.STATE_ACTIVE
            TunnelStatus.IDLE, TunnelStatus.ERROR -> Tile.STATE_INACTIVE
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_nekoxray)
        tile.label = runtime.activeProfileName.ifBlank { "NekoXray" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (runtime.status) {
                TunnelStatus.CONNECTED -> runtime.activeProtocolLabel.ifBlank {
                    if (runtime.isRealityActive) "REALITY" else "Connected"
                }
                TunnelStatus.CONNECTING -> "Connecting..."
                TunnelStatus.IDLE, TunnelStatus.ERROR -> "Idle"
            }
        }
        tile.updateTile()
    }
}
