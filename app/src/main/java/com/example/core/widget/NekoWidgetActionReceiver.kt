package com.example.core.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.NekoXrayApp
import com.example.core.model.XraySettings
import com.example.core.vpn.TunnelStatus
import com.example.core.vpn.XrayEngineState
import com.example.core.vpn.XrayVpnService
import com.example.core.xray.XrayConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NekoWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    NekoWidgetManager.ACTION_TOGGLE_VPN -> handleToggleVpn(appContext)
                    NekoWidgetManager.ACTION_NEXT_PROFILE -> handleNextProfile(appContext)
                }
            } finally {
                NekoWidgetManager.refreshAll(appContext)
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleToggleVpn(context: Context) {
        val runtime = XrayEngineState.state.value
        if (runtime.status == TunnelStatus.CONNECTED || runtime.status == TunnelStatus.CONNECTING) {
            XrayEngineState.updateStatus(status = TunnelStatus.IDLE)
            XrayVpnService.requestImmediateStop()
            val sent = runCatching { context.startService(XrayVpnService.buildStopIntent(context)) }.isSuccess
            if (!sent) {
                runCatching { context.stopService(Intent(context, XrayVpnService::class.java)) }
            }
            return
        }
        val app = context.applicationContext as? NekoXrayApp ?: run {
            NekoWidgetManager.launchMainActivity(context, autoConnect = true)
            return
        }
        val repo = app.repository
        val profiles = runCatching { repo.profilesFlow.first() }.getOrDefault(emptyList())
        val selectedId = runCatching { repo.selectedProfileIdFlow.first() }.getOrDefault(0L)
        val settings = runCatching { repo.settingsFlow.first() }.getOrDefault(XraySettings())
        val rules = runCatching { repo.routingRulesFlow.first() }.getOrDefault(emptyList())

        val target = profiles.find { it.id == selectedId } ?: profiles.firstOrNull()
        if (target == null) {
            NekoWidgetManager.launchMainActivity(context, autoConnect = false)
            return
        }
        NekoWidgetManager.startTunnelFromContext(
            context = context,
            profile = target,
            settings = settings,
            rules = rules,
            openActivityIfPermissionNeeded = true,
            allProfiles = profiles,
        )
    }

    private suspend fun handleNextProfile(context: Context) {
        val app = context.applicationContext as? NekoXrayApp ?: return
        val repo = app.repository
        val profiles = runCatching { repo.profilesFlow.first() }.getOrDefault(emptyList())
        if (profiles.isEmpty()) return

        val selectedId = runCatching { repo.selectedProfileIdFlow.first() }.getOrDefault(0L)
        val currentIndex = profiles.indexOfFirst { it.id == selectedId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % profiles.size
        val nextProfile = profiles[nextIndex]

        repo.setSelectedProfileId(nextProfile.id)

        val runtime = XrayEngineState.state.value
        val isRunning = runtime.status == TunnelStatus.CONNECTED || runtime.status == TunnelStatus.CONNECTING
        if (isRunning && runtime.activeProfileId != nextProfile.id) {
            val valid = XrayConfigBuilder.validateProfile(nextProfile, profiles).none { it.isError }
            if (valid) {
                val settings = runCatching { repo.settingsFlow.first() }.getOrDefault(XraySettings())
                val rules = runCatching { repo.routingRulesFlow.first() }.getOrDefault(emptyList())
                NekoWidgetManager.startTunnelFromContext(
                    context = context,
                    profile = nextProfile,
                    settings = settings,
                    rules = rules,
                    openActivityIfPermissionNeeded = false,
                    allProfiles = profiles,
                )
            }
        }
    }
}
