package com.example.core.vpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.MainActivity
import com.example.NekoXrayApp
import com.example.R
import com.example.core.model.ServiceMode
import com.example.core.model.XraySettings
import com.example.core.widget.NekoWidgetManager
import com.example.ui.i18n.resolveAppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class XrayTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refreshTile(XrayEngineState.state.value)
        stateObserverJob?.cancel()
        stateObserverJob = tileScope.launch {
            XrayEngineState.state.collect { runtime ->
                refreshTile(runtime)
            }
        }
    }

    override fun onStopListening() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val current = XrayEngineState.state.value
        if (current.status == TunnelStatus.CONNECTED || current.status == TunnelStatus.CONNECTING) {
            // Update state immediately so the QS tile transitions to INACTIVE right away without race
            XrayEngineState.updateStatus(status = TunnelStatus.IDLE)
            refreshTile(XrayEngineState.state.value)
            val sent = runCatching { startService(XrayVpnService.buildStopIntent(this)) }.isSuccess
            if (!sent) {
                runCatching { stopService(Intent(this, XrayVpnService::class.java)) }
            }
            NekoWidgetManager.refreshAll(this)
            return
        }

        tileScope.launch(Dispatchers.IO) {
            val app = applicationContext as? NekoXrayApp
            val repo = app?.repository
            val profiles = runCatching { repo?.profilesFlow?.first() }.getOrNull().orEmpty()
            val selectedId = runCatching { repo?.selectedProfileIdFlow?.first() }.getOrNull() ?: 0L
            val settings = runCatching { repo?.settingsFlow?.first() }.getOrNull() ?: XraySettings()
            val rules = runCatching { repo?.routingRulesFlow?.first() }.getOrNull().orEmpty()
            val target = profiles.find { it.id == selectedId } ?: profiles.firstOrNull()

            if (target == null || (settings.serviceMode == ServiceMode.VPN && VpnService.prepare(this@XrayTileService) != null)) {
                launchAppFromTile(autoConnect = target != null)
            } else {
                XrayEngineState.updateStatus(
                    status = TunnelStatus.CONNECTING,
                    profileId = target.id,
                    profileName = target.displayTitle(),
                    protocolLabel = NekoWidgetManager.formatProtocolLabel(target),
                    isReality = target.isReality,
                )
                val started = NekoWidgetManager.startTunnelFromContext(
                    context = this@XrayTileService,
                    profile = target,
                    settings = settings,
                    rules = rules,
                    openActivityIfPermissionNeeded = false,
                )
                if (!started) {
                    XrayEngineState.updateStatus(status = TunnelStatus.IDLE)
                    launchAppFromTile(autoConnect = true)
                }
            }
            NekoWidgetManager.refreshAll(this@XrayTileService)
        }
    }

    private fun launchAppFromTile(autoConnect: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (autoConnect) {
                putExtra(NekoWidgetManager.EXTRA_AUTO_CONNECT, true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                501,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { startActivityAndCollapse(pending) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { startActivityAndCollapse(intent) }
        }
    }

    private fun refreshTile(runtime: EngineRuntimeState = XrayEngineState.state.value) {
        val tile = qsTile ?: return
        tile.state = when (runtime.status) {
            TunnelStatus.CONNECTED -> Tile.STATE_ACTIVE
            TunnelStatus.CONNECTING -> Tile.STATE_ACTIVE
            TunnelStatus.IDLE, TunnelStatus.ERROR -> Tile.STATE_INACTIVE
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_nekoxray)
        tile.label = runtime.activeProfileName.ifBlank { "NekoXray" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val strings = resolveAppStrings(NekoWidgetManager.lastKnownLanguage)
            tile.subtitle = when (runtime.status) {
                TunnelStatus.CONNECTED -> {
                    val speed = "↓ ${XrayVpnService.formatSpeed(runtime.downSpeedBps)}"
                    val proto = runtime.activeProtocolLabel.ifBlank {
                        if (runtime.isRealityActive) "REALITY" else strings.statusConnected
                    }
                    "$proto · $speed"
                }
                TunnelStatus.CONNECTING -> "${strings.connecting}..."
                TunnelStatus.IDLE, TunnelStatus.ERROR -> strings.widgetTapToConnect
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        tileScope.cancel()
        super.onDestroy()
    }
}
