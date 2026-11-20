package com.example.core.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.NekoXrayApp
import com.example.R
import com.example.core.model.ProxyProfile
import com.example.core.model.RoutingRule
import com.example.core.model.ServiceMode
import com.example.core.model.XraySettings
import com.example.core.vpn.EngineRuntimeState
import com.example.core.vpn.TunnelStatus
import com.example.core.vpn.XrayEngineState
import com.example.core.vpn.XrayTileService
import com.example.core.vpn.XrayVpnService
import com.example.core.xray.XrayConfigBuilder
import com.example.ui.i18n.AppStrings
import com.example.ui.i18n.resolveAppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object NekoWidgetManager {

    const val ACTION_TOGGLE_VPN = "com.example.action.WIDGET_TOGGLE_VPN"
    const val ACTION_NEXT_PROFILE = "com.example.action.WIDGET_NEXT_PROFILE"
    const val EXTRA_AUTO_CONNECT = "extra_auto_connect"

    private const val TRAY_CHANNEL_ID = "nekoxray_tray_widget_channel"
    private const val TRAY_NOTIFICATION_ID = 1043

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    @Volatile
    var lastKnownLanguage: com.example.core.model.AppLanguage = com.example.core.model.AppLanguage.SYSTEM
        private set

    fun formatProtocolLabel(
        profile: ProxyProfile,
        allProfiles: List<ProxyProfile> = emptyList(),
    ): String {
        val baseLabel = when (profile.protocol) {
            com.example.core.model.ProxyProtocol.HYSTERIA2 -> "Hysteria 2 · QUIC · TLS"
            com.example.core.model.ProxyProtocol.TUIC -> "TUIC v5 · QUIC · TLS"
            com.example.core.model.ProxyProtocol.SSH -> "SSH"
            com.example.core.model.ProxyProtocol.WIREGUARD -> "WireGuard · UDP"
            com.example.core.model.ProxyProtocol.SOCKS5 -> "SOCKS5"
            else -> buildString {
                append(profile.protocol.displayName)
                if (profile.isReality) append(" · REALITY")
                else if (profile.security.wireValue != "none") append(" · ${profile.security.displayName}")
                append(" · ${profile.transport.wireValue.uppercase()}")
            }
        }
        if (profile.chainProxyId <= 0L || allProfiles.isEmpty()) return baseLabel
        val chain = XrayConfigBuilder.resolveChain(profile, allProfiles)
        if (chain.hops.size <= 1) return baseLabel
        val entry = chain.hops.last()
        return if (chain.hops.size == 2) {
            "${entry.protocol.displayName} → ${profile.protocol.displayName}"
        } else {
            "${entry.protocol.displayName} → ${chain.hops.size - 2}× → ${profile.protocol.displayName}"
        }
    }

    fun buildStartServiceIntent(
        context: Context,
        profile: ProxyProfile,
        settings: XraySettings,
        rules: List<RoutingRule>,
        forceProxyOnly: Boolean = false,
        allProfiles: List<ProxyProfile> = emptyList(),
    ): Intent {
        val isProxyOnly = forceProxyOnly || settings.serviceMode == ServiceMode.PROXY_ONLY
        val geo = com.example.core.xray.XrayAssetHelper.currentStatus(context)
        val chainHops = XrayConfigBuilder.resolveChain(profile, allProfiles).hops
        val configJson = XrayConfigBuilder.buildConfigJson(
            profile = profile,
            settings = settings,
            rules = rules,
            includeTunInbound = !isProxyOnly,
            hasGeoIp = geo.hasGeoIp,
            hasGeoSite = geo.hasGeoSite,
            allProfiles = chainHops,
        )
        return XrayVpnService.buildStartIntent(
            context = context,
            profile = profile,
            protocolLabel = formatProtocolLabel(profile, allProfiles),
            configJson = configJson,
            mtu = settings.mtu,
            bypassLan = settings.bypassLan,
            proxyOnly = isProxyOnly,
            ipv6Mode = settings.ipv6Mode,
            socksPort = settings.socksPort,
            httpPort = settings.httpPort,
            perAppMode = settings.perAppMode,
            perAppPackages = settings.perAppPackages,
            settings = settings,
            rules = rules,
            chainProfiles = chainHops,
        )
    }

    fun startTunnelFromContext(
        context: Context,
        profile: ProxyProfile,
        settings: XraySettings,
        rules: List<RoutingRule>,
        openActivityIfPermissionNeeded: Boolean = true,
        allProfiles: List<ProxyProfile> = emptyList(),
    ): Boolean {
        val issues = XrayConfigBuilder.validateProfile(profile, allProfiles).filter { it.isError }
        if (issues.isNotEmpty()) {
            if (openActivityIfPermissionNeeded) {
                launchMainActivity(context, autoConnect = false)
            }
            return false
        }
        if (settings.serviceMode == ServiceMode.VPN && VpnService.prepare(context) != null) {
            if (openActivityIfPermissionNeeded) {
                launchMainActivity(context, autoConnect = true)
            }
            return false
        }
        val intent = buildStartServiceIntent(
            context = context,
            profile = profile,
            settings = settings,
            rules = rules,
            allProfiles = allProfiles,
        )
        val started = runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.isSuccess
        if (!started && openActivityIfPermissionNeeded) {
            launchMainActivity(context, autoConnect = true)
        }
        return started
    }

    fun launchMainActivity(context: Context, autoConnect: Boolean = false) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (autoConnect) {
                putExtra(EXTRA_AUTO_CONNECT, true)
            }
        }
        context.startActivity(intent)
    }

    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            refreshMutex.withLock {
                val app = appContext as? NekoXrayApp ?: return@withLock
                val repo = app.repository
                val profiles = runCatching { repo.profilesFlow.first() }.getOrDefault(emptyList())
                val selectedId = runCatching { repo.selectedProfileIdFlow.first() }.getOrDefault(0L)
                val settings = runCatching { repo.settingsFlow.first() }.getOrDefault(XraySettings())
                lastKnownLanguage = settings.appLanguage
                val runtime = XrayEngineState.state.value

                val targetProfile = when (runtime.status) {
                    TunnelStatus.CONNECTED, TunnelStatus.CONNECTING -> {
                        profiles.find { it.id == runtime.activeProfileId }
                            ?: profiles.find { it.id == selectedId }
                            ?: profiles.firstOrNull()
                    }
                    TunnelStatus.IDLE, TunnelStatus.ERROR -> {
                        profiles.find { it.id == selectedId } ?: profiles.firstOrNull()
                    }
                }
                val strings = resolveAppStrings(settings.appLanguage)

                updateStatusWidgets(appContext, runtime, targetProfile, strings, allProfiles = profiles)
                updateCompactWidgets(appContext, runtime, targetProfile, strings)
                updateMiniWidgets(appContext, runtime, targetProfile, strings)
                updateTrayNotification(appContext, runtime, targetProfile, settings, strings, allProfiles = profiles)
                requestTileRefresh(appContext)
            }
        }
    }

    fun updateStatusWidgets(
        context: Context,
        runtime: EngineRuntimeState,
        profile: ProxyProfile?,
        strings: AppStrings,
        specificIds: IntArray? = null,
        allProfiles: List<ProxyProfile> = emptyList(),
    ) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = specificIds ?: mgr.getAppWidgetIds(ComponentName(context, NekoStatusWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget_nekoxray_status)

        val (bgRes, pillRes, dotRes, btnRes, statusLabel, statusColor) = when (runtime.status) {
            TunnelStatus.CONNECTED -> WidgetVisualSpec(
                bgRes = R.drawable.widget_card_bg_connected,
                pillRes = R.drawable.widget_pill_connected,
                dotRes = R.drawable.ic_widget_dot_connected,
                btnRes = R.drawable.widget_power_btn_connected,
                statusLabel = strings.statusConnected,
                statusColor = 0xFF34D399.toInt(),
            )
            TunnelStatus.CONNECTING -> WidgetVisualSpec(
                bgRes = R.drawable.widget_card_bg_connecting,
                pillRes = R.drawable.widget_pill_connecting,
                dotRes = R.drawable.ic_widget_dot_connecting,
                btnRes = R.drawable.widget_power_btn_connecting,
                statusLabel = strings.statusConnecting,
                statusColor = 0xFFFBBF24.toInt(),
            )
            TunnelStatus.ERROR -> WidgetVisualSpec(
                bgRes = R.drawable.widget_card_bg_idle,
                pillRes = R.drawable.widget_pill_idle,
                dotRes = R.drawable.ic_widget_dot_idle,
                btnRes = R.drawable.widget_power_btn_idle,
                statusLabel = strings.statusError,
                statusColor = 0xFFF87171.toInt(),
            )
            TunnelStatus.IDLE -> WidgetVisualSpec(
                bgRes = R.drawable.widget_card_bg_idle,
                pillRes = R.drawable.widget_pill_idle,
                dotRes = R.drawable.ic_widget_dot_idle,
                btnRes = R.drawable.widget_power_btn_idle,
                statusLabel = strings.statusReady,
                statusColor = 0xFF94A3B8.toInt(),
            )
        }

        views.setInt(R.id.widget_root, "setBackgroundResource", bgRes)
        views.setInt(R.id.widget_status_pill, "setBackgroundResource", pillRes)
        views.setImageViewResource(R.id.widget_status_dot, dotRes)
        views.setTextViewText(R.id.widget_status_text, statusLabel)
        views.setTextColor(R.id.widget_status_text, statusColor)
        views.setInt(R.id.widget_btn_toggle, "setBackgroundResource", btnRes)

        val title = when {
            runtime.status == TunnelStatus.CONNECTED || runtime.status == TunnelStatus.CONNECTING ->
                runtime.activeProfileName.ifBlank { profile?.displayTitle() ?: "NekoXray" }
            profile != null -> profile.displayTitle()
            else -> strings.widgetNoProfileTitle
        }
        val subtitle = when {
            runtime.status == TunnelStatus.CONNECTED || runtime.status == TunnelStatus.CONNECTING ->
                runtime.activeProtocolLabel.ifBlank { profile?.let { formatProtocolLabel(it, allProfiles) } ?: "Xray-core" }
            profile != null -> formatProtocolLabel(profile, allProfiles)
            else -> strings.widgetNoProfileSubtitle
        }
        val telemetry = when (runtime.status) {
            TunnelStatus.CONNECTED -> {
                val up = XrayVpnService.formatSpeed(runtime.upSpeedBps)
                val down = XrayVpnService.formatSpeed(runtime.downSpeedBps)
                val total = XrayVpnService.formatBytes(runtime.totalUpBytes + runtime.totalDownBytes)
                "↑ $up  ↓ $down  ·  $total"
            }
            TunnelStatus.CONNECTING -> "${strings.connecting}..."
            TunnelStatus.ERROR -> runtime.lastError?.take(48) ?: strings.statusError
            TunnelStatus.IDLE -> {
                val ping = profile?.tlsLatencyMs ?: profile?.latencyMs
                if (ping != null) "${strings.widgetTapToConnect} · ${ping}ms" else strings.widgetTapToConnect
            }
        }

        views.setTextViewText(R.id.widget_profile_name, title)
        views.setTextViewText(R.id.widget_protocol_subtitle, subtitle)
        views.setTextViewText(R.id.widget_telemetry, telemetry)

        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context, 201))
        views.setOnClickPendingIntent(R.id.widget_btn_toggle, actionBroadcastPendingIntent(context, ACTION_TOGGLE_VPN, 202))
        views.setOnClickPendingIntent(R.id.widget_btn_next, actionBroadcastPendingIntent(context, ACTION_NEXT_PROFILE, 203))

        mgr.updateAppWidget(ids, views)
    }

    fun updateCompactWidgets(
        context: Context,
        runtime: EngineRuntimeState,
        profile: ProxyProfile?,
        strings: AppStrings,
        specificIds: IntArray? = null,
    ) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = specificIds ?: mgr.getAppWidgetIds(ComponentName(context, NekoCompactWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget_nekoxray_compact)

        val (bgRes, dotRes, btnRes) = when (runtime.status) {
            TunnelStatus.CONNECTED -> Triple(
                R.drawable.widget_card_bg_connected,
                R.drawable.ic_widget_dot_connected,
                R.drawable.widget_power_btn_connected,
            )
            TunnelStatus.CONNECTING -> Triple(
                R.drawable.widget_card_bg_connecting,
                R.drawable.ic_widget_dot_connecting,
                R.drawable.widget_power_btn_connecting,
            )
            TunnelStatus.IDLE, TunnelStatus.ERROR -> Triple(
                R.drawable.widget_card_bg_idle,
                R.drawable.ic_widget_dot_idle,
                R.drawable.widget_power_btn_idle,
            )
        }

        views.setInt(R.id.compact_widget_root, "setBackgroundResource", bgRes)
        views.setImageViewResource(R.id.compact_status_dot, dotRes)
        views.setInt(R.id.compact_btn_toggle, "setBackgroundResource", btnRes)

        val title = when {
            runtime.status == TunnelStatus.CONNECTED || runtime.status == TunnelStatus.CONNECTING ->
                runtime.activeProfileName.ifBlank { profile?.displayTitle() ?: "NekoXray" }
            profile != null -> profile.displayTitle()
            else -> "NekoXray"
        }
        val subtitle = when (runtime.status) {
            TunnelStatus.CONNECTED -> "↓ ${XrayVpnService.formatSpeed(runtime.downSpeedBps)}"
            TunnelStatus.CONNECTING -> "${strings.connecting}..."
            TunnelStatus.ERROR -> strings.statusError
            TunnelStatus.IDLE -> profile?.protocol?.displayName ?: strings.statusReady
        }

        views.setTextViewText(R.id.compact_profile_name, title)
        views.setTextViewText(R.id.compact_subtitle, subtitle)

        views.setOnClickPendingIntent(R.id.compact_widget_root, openAppPendingIntent(context, 301))
        views.setOnClickPendingIntent(R.id.compact_btn_toggle, actionBroadcastPendingIntent(context, ACTION_TOGGLE_VPN, 302))

        mgr.updateAppWidget(ids, views)
    }

    fun updateMiniWidgets(
        context: Context,
        runtime: EngineRuntimeState,
        profile: ProxyProfile?,
        strings: AppStrings,
        specificIds: IntArray? = null,
    ) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = specificIds ?: mgr.getAppWidgetIds(ComponentName(context, NekoMiniWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val views = buildMiniRemoteViews(context, runtime, profile, strings)
        mgr.updateAppWidget(ids, views)
    }

    fun buildMiniRemoteViews(
        context: Context,
        runtime: EngineRuntimeState,
        profile: ProxyProfile?,
        strings: AppStrings,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_nekoxray_mini)

        val (bgRes, dotRes, btnRes) = when (runtime.status) {
            TunnelStatus.CONNECTED -> Triple(
                R.drawable.widget_card_bg_connected,
                R.drawable.ic_widget_dot_connected,
                R.drawable.widget_power_btn_connected,
            )
            TunnelStatus.CONNECTING -> Triple(
                R.drawable.widget_card_bg_connecting,
                R.drawable.ic_widget_dot_connecting,
                R.drawable.widget_power_btn_connecting,
            )
            TunnelStatus.IDLE, TunnelStatus.ERROR -> Triple(
                R.drawable.widget_card_bg_idle,
                R.drawable.ic_widget_dot_idle,
                R.drawable.widget_power_btn_idle,
            )
        }

        val (labelText, labelColor) = when (runtime.status) {
            TunnelStatus.CONNECTED -> strings.miniWidgetOn to 0xFF34D399.toInt()
            TunnelStatus.CONNECTING -> "..." to 0xFFFBBF24.toInt()
            TunnelStatus.ERROR -> "ERR" to 0xFFF87171.toInt()
            TunnelStatus.IDLE -> (profile?.name?.take(7)?.ifBlank { strings.miniWidgetOff } ?: strings.miniWidgetOff) to 0xFF94A3B8.toInt()
        }

        views.setInt(R.id.mini_widget_root, "setBackgroundResource", bgRes)
        views.setImageViewResource(R.id.mini_status_dot, dotRes)
        views.setInt(R.id.mini_power_icon, "setBackgroundResource", btnRes)
        views.setTextViewText(R.id.mini_status_label, labelText)
        views.setTextColor(R.id.mini_status_label, labelColor)
        views.setOnClickPendingIntent(
            R.id.mini_widget_root,
            actionBroadcastPendingIntent(context, ACTION_TOGGLE_VPN, 350),
        )
        return views
    }

    fun updateTrayNotification(
        context: Context,
        runtime: EngineRuntimeState,
        profile: ProxyProfile?,
        settings: XraySettings,
        strings: AppStrings,
        allProfiles: List<ProxyProfile> = emptyList(),
    ) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val isTunnelRunning = runtime.status == TunnelStatus.CONNECTED || runtime.status == TunnelStatus.CONNECTING
        if (!settings.trayWidgetEnabled || isTunnelRunning) {
            mgr.cancel(TRAY_NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TRAY_CHANNEL_ID,
                context.getString(R.string.tray_widget_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.tray_widget_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            mgr.createNotificationChannel(channel)
        }

        val remote = RemoteViews(context.packageName, R.layout.notification_tray_widget)
        val profileTitle = profile?.displayTitle() ?: strings.widgetNoProfileTitle
        val proto = profile?.let { formatProtocolLabel(it, allProfiles) } ?: strings.widgetNoProfileSubtitle

        remote.setTextViewText(R.id.tray_title, "$profileTitle · ${strings.statusReady}")
        remote.setTextViewText(R.id.tray_subtitle, "$proto · ${strings.widgetTapToConnect}")
        remote.setInt(R.id.tray_btn_toggle, "setBackgroundResource", R.drawable.widget_power_btn_idle)
        remote.setOnClickPendingIntent(R.id.tray_btn_toggle, actionBroadcastPendingIntent(context, ACTION_TOGGLE_VPN, 401))
        remote.setOnClickPendingIntent(R.id.tray_btn_next, actionBroadcastPendingIntent(context, ACTION_NEXT_PROFILE, 402))

        val notification = NotificationCompat.Builder(context, TRAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nekoxray)
            .setColor(0xFF00B8D4.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remote)
            .setCustomBigContentView(remote)
            .setContentIntent(openAppPendingIntent(context, 400))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        runCatching { mgr.notify(TRAY_NOTIFICATION_ID, notification) }
    }

    fun requestTileRefresh(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, XrayTileService::class.java),
                )
            }
        }
    }

    fun isPinWidgetSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
        }.getOrDefault(false)
    }

    fun requestPinStatusWidget(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val mgr = AppWidgetManager.getInstance(context)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        val provider = ComponentName(context, NekoStatusWidgetProvider::class.java)
        return runCatching {
            mgr.requestPinAppWidget(provider, null, null)
        }.getOrDefault(false)
    }

    fun requestPinCompactWidget(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val mgr = AppWidgetManager.getInstance(context)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        val provider = ComponentName(context, NekoCompactWidgetProvider::class.java)
        return runCatching {
            mgr.requestPinAppWidget(provider, null, null)
        }.getOrDefault(false)
    }

    fun requestPinMiniWidget(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val mgr = AppWidgetManager.getInstance(context)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        val provider = ComponentName(context, NekoMiniWidgetProvider::class.java)
        return runCatching {
            mgr.requestPinAppWidget(provider, null, null)
        }.getOrDefault(false)
    }

    fun actionBroadcastPendingIntent(context: Context, actionName: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NekoWidgetActionReceiver::class.java).apply {
            action = actionName
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private data class WidgetVisualSpec(
        val bgRes: Int,
        val pillRes: Int,
        val dotRes: Int,
        val btnRes: Int,
        val statusLabel: String,
        val statusColor: Int,
    )
}
