package com.example.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.model.IPv6Mode
import com.example.core.model.PerAppProxyMode
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.RoutingRule
import com.example.core.model.XRAY_CORE_VERSION
import com.example.core.model.XraySettings
import com.example.core.widget.NekoWidgetManager
import com.example.core.xray.XrayAssetHelper
import com.example.core.xray.XrayConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONObject
import java.util.Locale

class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.action.START_VPN"
        const val ACTION_STOP = "com.example.action.STOP_VPN"
        const val ACTION_RECONNECT = "com.example.action.RECONNECT_VPN"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_PROTOCOL_LABEL = "protocol_label"
        const val EXTRA_IS_REALITY = "is_reality"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_PROFILE_JSON = "profile_json"
        const val EXTRA_SETTINGS_JSON = "settings_json"
        const val EXTRA_RULES_JSON = "rules_json"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_BYPASS_LAN = "bypass_lan"
        const val EXTRA_PROXY_ONLY = "proxy_only"
        const val EXTRA_IPV6_MODE = "ipv6_mode"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_HTTP_PORT = "http_port"
        const val EXTRA_PER_APP_MODE = "per_app_mode"
        const val EXTRA_PER_APP_PACKAGES = "per_app_packages"

        private const val CHANNEL_ID = "nekoxray_vpn_channel"
        private const val NOTIFICATION_ID = 1042
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun buildStartIntent(
            context: Context,
            profile: ProxyProfile,
            protocolLabel: String,
            configJson: String,
            mtu: Int,
            bypassLan: Boolean,
            proxyOnly: Boolean,
            ipv6Mode: IPv6Mode = IPv6Mode.ENABLE,
            socksPort: Int = 10808,
            httpPort: Int = 10809,
            perAppMode: PerAppProxyMode = PerAppProxyMode.ALL,
            perAppPackages: Set<String> = emptySet(),
            settings: XraySettings = XraySettings(),
            rules: List<RoutingRule> = emptyList(),
        ): Intent = Intent(context, XrayVpnService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PROFILE_ID, profile.id)
            putExtra(EXTRA_PROFILE_NAME, profile.displayTitle())
            putExtra(EXTRA_PROTOCOL_LABEL, protocolLabel)
            putExtra(EXTRA_IS_REALITY, profile.isReality)
            putExtra(EXTRA_CONFIG_JSON, configJson)
            putExtra(EXTRA_PROFILE_JSON, json.encodeToString(ProxyProfile.serializer(), profile))
            putExtra(EXTRA_SETTINGS_JSON, json.encodeToString(XraySettings.serializer(), settings))
            putExtra(EXTRA_RULES_JSON, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(RoutingRule.serializer()), rules))
            putExtra(EXTRA_MTU, mtu)
            putExtra(EXTRA_BYPASS_LAN, bypassLan)
            putExtra(EXTRA_PROXY_ONLY, proxyOnly)
            putExtra(EXTRA_IPV6_MODE, ipv6Mode.name)
            putExtra(EXTRA_SOCKS_PORT, socksPort)
            putExtra(EXTRA_HTTP_PORT, httpPort)
            putExtra(EXTRA_PER_APP_MODE, perAppMode.name)
            putStringArrayListExtra(EXTRA_PER_APP_PACKAGES, ArrayList(perAppPackages))
        }

        fun buildStopIntent(context: Context): Intent =
            Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_STOP
            }

        fun buildReconnectIntent(context: Context): Intent =
            Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_RECONNECT
            }

        fun formatBytes(bytes: Long): String {
            val safe = bytes.coerceAtLeast(0L)
            return when {
                safe < 1024L -> "$safe B"
                safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
                safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
                else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
            }
        }

        fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

        fun parseOutboundStatsJson(raw: String): Pair<Long, Long>? {
            if (raw.isBlank()) return null
            return runCatching {
                val root = JSONObject(raw)
                var totalUp = 0L
                var totalDown = 0L
                var foundAny = false
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = root.optJSONObject(key)
                    if (obj != null && (obj.has("uplink") || obj.has("downlink"))) {
                        totalUp += obj.optLong("uplink", 0L).coerceAtLeast(0L)
                        totalDown += obj.optLong("downlink", 0L).coerceAtLeast(0L)
                        foundAny = true
                    }
                }
                if (foundAny) totalUp to totalDown else null
            }.getOrNull()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()
    private val activeSessionToken = java.util.concurrent.atomic.AtomicLong(0L)
    private var activeStartJob: Job? = null
    private var trafficMonitorJob: Job? = null
    private var lastStartIntent: Intent? = null
    private var notificationLargeIcon: Bitmap? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var coreControllerInstance: CoreController? = null
    private var localProxyServer: LocalProxyServer? = null
    private var kotlinTunForwarder: KotlinTunForwarder? = null
    private var sshTunnelBridge: SshTunnelBridge? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        notificationLargeIcon = buildCircularLargeIcon()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, null -> {
                stopTunnel()
                return START_NOT_STICKY
            }

            ACTION_RECONNECT -> {
                val prev = lastStartIntent
                if (prev != null) {
                    handleStartIntent(prev)
                } else {
                    stopTunnel()
                }
            }

            ACTION_START -> {
                lastStartIntent = Intent(intent)
                handleStartIntent(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStartIntent(intent: Intent) {
        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty().ifBlank { "Xray Profile" }
        val protocolLabel = intent.getStringExtra(EXTRA_PROTOCOL_LABEL).orEmpty()
        val isReality = intent.getBooleanExtra(EXTRA_IS_REALITY, false)
        val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
        val profileJson = intent.getStringExtra(EXTRA_PROFILE_JSON).orEmpty()
        val profile = runCatching {
            json.decodeFromString(ProxyProfile.serializer(), profileJson)
        }.getOrDefault(ProxyProfile(id = profileId, name = profileName))
        val settingsJson = intent.getStringExtra(EXTRA_SETTINGS_JSON).orEmpty()
        val settings = runCatching {
            if (settingsJson.isNotBlank()) json.decodeFromString(XraySettings.serializer(), settingsJson) else XraySettings()
        }.getOrDefault(XraySettings())
        val rulesJson = intent.getStringExtra(EXTRA_RULES_JSON).orEmpty()
        val rules = runCatching {
            if (rulesJson.isNotBlank()) {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RoutingRule.serializer()), rulesJson)
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
        val mtu = intent.getIntExtra(EXTRA_MTU, 1500)
        val bypassLan = intent.getBooleanExtra(EXTRA_BYPASS_LAN, true)
        val proxyOnly = intent.getBooleanExtra(EXTRA_PROXY_ONLY, false)
        val ipv6Mode = runCatching {
            IPv6Mode.valueOf(intent.getStringExtra(EXTRA_IPV6_MODE) ?: IPv6Mode.ENABLE.name)
        }.getOrDefault(IPv6Mode.ENABLE)
        val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 10808)
        val httpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 10809)
        val perAppMode = runCatching {
            PerAppProxyMode.valueOf(intent.getStringExtra(EXTRA_PER_APP_MODE) ?: PerAppProxyMode.ALL.name)
        }.getOrDefault(PerAppProxyMode.ALL)
        val perAppPackages = intent.getStringArrayListExtra(EXTRA_PER_APP_PACKAGES)?.toSet() ?: emptySet()

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                profile = profile,
                profileName = profileName,
                protocolLabel = protocolLabel,
                proxyOnly = proxyOnly,
                connected = false,
                connectedSinceMs = 0L,
            )
        )
        startTunnel(
            profile = profile,
            protocolLabel = protocolLabel,
            configJson = configJson,
            settings = settings,
            rules = rules,
            mtu = mtu,
            bypassLan = bypassLan,
            proxyOnly = proxyOnly,
            ipv6Mode = ipv6Mode,
            socksPort = socksPort,
            httpPort = httpPort,
            perAppMode = perAppMode,
            perAppPackages = perAppPackages,
        )
    }

    private fun startTunnel(
        profile: ProxyProfile,
        protocolLabel: String,
        configJson: String,
        settings: XraySettings,
        rules: List<RoutingRule>,
        mtu: Int,
        bypassLan: Boolean,
        proxyOnly: Boolean,
        ipv6Mode: IPv6Mode,
        socksPort: Int,
        httpPort: Int,
        perAppMode: PerAppProxyMode,
        perAppPackages: Set<String>,
    ) {
        val profileName = profile.displayTitle()
        val sessionToken = activeSessionToken.incrementAndGet()
        XrayEngineState.updateStatus(
            status = TunnelStatus.CONNECTING,
            profileId = profile.id,
            profileName = profileName,
            protocolLabel = protocolLabel,
            isReality = profile.isReality,
            configJson = configJson,
        )
        NekoWidgetManager.refreshAll(this)
        XrayEngineState.appendLog("INFO", "Starting Xray session for '$profileName' ($protocolLabel)...")

        activeStartJob?.cancel()
        activeStartJob = serviceScope.launch {
            engineMutex.withLock {
                if (sessionToken != activeSessionToken.get()) return@withLock
                runCatching {
                    stopPreviousEnginesOnly()
                val hasNativeCore = XrayEngineState.state.value.isNativeCoreLinked

                if (!proxyOnly) {
                    val effectiveMtu = mtu.coerceIn(1280, 9000)
                    val builder = Builder()
                        .setSession(profileName)
                        .setMtu(effectiveMtu)
                        .addAddress("172.19.0.1", 30)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8")
                        .addRoute("0.0.0.0", 0)
                        .setConfigureIntent(mainPendingIntent())

                    val enableTunIpv6 = if (profile.protocol == ProxyProtocol.SSH) {
                        ipv6Mode == IPv6Mode.ONLY || ipv6Mode == IPv6Mode.PREFER
                    } else {
                        ipv6Mode != IPv6Mode.DISABLE
                    }
                    if (enableTunIpv6) {
                        builder.addAddress("fdfe:dcba:9876::1", 126)
                        builder.addRoute("::", 0)
                    }

                    when (perAppMode) {
                        PerAppProxyMode.ALL -> {
                            runCatching { builder.addDisallowedApplication(packageName) }
                        }
                        PerAppProxyMode.BYPASS_SELECTED -> {
                            runCatching { builder.addDisallowedApplication(packageName) }
                            perAppPackages.forEach { pkg ->
                                if (pkg != packageName) {
                                    runCatching { builder.addDisallowedApplication(pkg) }
                                }
                            }
                            XrayEngineState.appendLog("INFO", "Per-App VPN: bypassing ${perAppPackages.size} app(s).")
                        }
                        PerAppProxyMode.PROXY_SELECTED -> {
                            val targets = perAppPackages.filter { it != packageName }
                            if (targets.isEmpty()) {
                                runCatching { builder.addDisallowedApplication(packageName) }
                            } else {
                                targets.forEach { pkg ->
                                    runCatching { builder.addAllowedApplication(pkg) }
                                }
                                XrayEngineState.appendLog("INFO", "Per-App VPN: routing ${targets.size} selected app(s).")
                            }
                        }
                    }

                    if (bypassLan) {
                        XrayEngineState.appendLog("INFO", "LAN bypass rule configured in Xray routing.")
                    }

                    tunInterface = builder.establish()
                    val pfd = tunInterface
                        ?: error("VPN interface could not be established (permission missing or revoked).")
                    XrayEngineState.appendLog("INFO", "TUN interface established (172.19.0.1/30, fd=${pfd.fd}, MTU $effectiveMtu).")

                    val effectiveConfigJson = if (profile.protocol == ProxyProtocol.SSH) {
                        val bridge = SshTunnelBridge(profile = profile, vpnService = this@XrayVpnService)
                        val bridgePort = bridge.start()
                        sshTunnelBridge = bridge
                        XrayConfigBuilder.buildConfigJson(
                            profile = profile,
                            settings = settings,
                            rules = rules,
                            includeTunInbound = true,
                            sshLocalBridgePort = bridgePort,
                        )
                    } else {
                        configJson
                    }

                    if (hasNativeCore) {
                        startNativeCoreOrThrow(effectiveConfigJson, pfd.fd)
                        XrayEngineState.appendLog("INFO", "Native Xray-core TUN loop active via libv2ray (${Libv2ray.checkVersionX()}).")
                    } else {
                        val dialer = KotlinOutboundDialer(profile = profile, vpnService = this@XrayVpnService)
                        kotlinTunForwarder = KotlinTunForwarder(
                            tunPfd = pfd,
                            dialer = dialer,
                            scope = serviceScope,
                        ).also { it.start() }

                        localProxyServer = LocalProxyServer(
                            socksPort = socksPort,
                            httpPort = httpPort,
                            dialer = dialer,
                        ).also { it.start(serviceScope) }
                    }

                    if (sessionToken != activeSessionToken.get()) {
                        stopPreviousEnginesOnly()
                        return@withLock
                    }
                    XrayEngineState.updateStatus(
                        status = TunnelStatus.CONNECTED,
                        profileId = profile.id,
                        profileName = profileName,
                        protocolLabel = protocolLabel,
                        isReality = profile.isReality,
                        configJson = effectiveConfigJson,
                    )
                    NekoWidgetManager.refreshAll(this@XrayVpnService)
                } else {
                    val effectiveConfigJson = if (profile.protocol == ProxyProtocol.SSH) {
                        val bridge = SshTunnelBridge(profile = profile, vpnService = this@XrayVpnService)
                        val bridgePort = bridge.start()
                        sshTunnelBridge = bridge
                        XrayConfigBuilder.buildConfigJson(
                            profile = profile,
                            settings = settings,
                            rules = rules,
                            includeTunInbound = false,
                            sshLocalBridgePort = bridgePort,
                        )
                    } else {
                        configJson
                    }

                    if (hasNativeCore) {
                        // In AndroidLibXrayLite, tunFd == 0 means Proxy-Only (no TUN)
                        startNativeCoreOrThrow(effectiveConfigJson, 0)
                        XrayEngineState.appendLog("INFO", "Native Xray-core Proxy-Only active (SOCKS5:$socksPort HTTP:$httpPort).")
                    } else {
                        val dialer = KotlinOutboundDialer(profile = profile, vpnService = this@XrayVpnService)
                        localProxyServer = LocalProxyServer(
                            socksPort = socksPort,
                            httpPort = httpPort,
                            dialer = dialer,
                        ).also { it.start(serviceScope) }
                    }

                    if (sessionToken != activeSessionToken.get()) {
                        stopPreviousEnginesOnly()
                        return@withLock
                    }
                    XrayEngineState.updateStatus(
                        status = TunnelStatus.CONNECTED,
                        profileId = profile.id,
                        profileName = profileName,
                        protocolLabel = protocolLabel,
                        isReality = profile.isReality,
                        configJson = effectiveConfigJson,
                    )
                    NekoWidgetManager.refreshAll(this@XrayVpnService)
                }
                startTrafficMonitor(
                    sessionToken = sessionToken,
                    profile = profile,
                    profileName = profileName,
                    protocolLabel = protocolLabel,
                    proxyOnly = proxyOnly,
                )
            }.onFailure { err ->
                if (sessionToken == activeSessionToken.get()) {
                    XrayEngineState.appendLog("ERROR", "Tunnel failed: ${err.message ?: err.javaClass.simpleName}")
                    XrayEngineState.updateStatus(
                        status = TunnelStatus.ERROR,
                        error = err.message ?: "VPN start failed",
                    )
                    stopTunnelInternal()
                    NekoWidgetManager.refreshAll(this@XrayVpnService)
                } else {
                    stopPreviousEnginesOnly()
                }
            }
            }
        }
    }

    private fun startTrafficMonitor(
        sessionToken: Long,
        profile: ProxyProfile,
        profileName: String,
        protocolLabel: String,
        proxyOnly: Boolean,
    ) {
        trafficMonitorJob?.cancel()
        val connectedAt = System.currentTimeMillis()
        val uid = Process.myUid()
        val baseUidTx = TrafficStats.getUidTxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
        val baseUidRx = TrafficStats.getUidRxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }

        trafficMonitorJob = serviceScope.launch {
            var prevUp = 0L
            var prevDown = 0L
            var prevAt = connectedAt

            updateLiveNotification(
                profile = profile,
                profileName = profileName,
                protocolLabel = protocolLabel,
                proxyOnly = proxyOnly,
                connectedSinceMs = connectedAt,
                upSpeedBps = 0L,
                downSpeedBps = 0L,
                totalUpBytes = 0L,
                totalDownBytes = 0L,
            )

            var tickCount = 0
            while (isActive && sessionToken == activeSessionToken.get()) {
                delay(1000L)
                if (sessionToken != activeSessionToken.get()) break
                tickCount++
                val now = System.currentTimeMillis()
                val xrayStats = runCatching {
                    coreControllerInstance?.takeIf { it.isRunning }?.queryAllOutboundTrafficStats()
                }.getOrNull()?.let { parseOutboundStatsJson(it) }

                val (totalUp, totalDown) = if (xrayStats != null && (xrayStats.first > 0L || xrayStats.second > 0L)) {
                    xrayStats
                } else {
                    val curTx = TrafficStats.getUidTxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) baseUidTx else it }
                    val curRx = TrafficStats.getUidRxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) baseUidRx else it }
                    (curTx - baseUidTx).coerceAtLeast(0L) to (curRx - baseUidRx).coerceAtLeast(0L)
                }

                val elapsedMs = (now - prevAt).coerceAtLeast(250L)
                val upBps = ((totalUp - prevUp).coerceAtLeast(0L) * 1000L) / elapsedMs
                val downBps = ((totalDown - prevDown).coerceAtLeast(0L) * 1000L) / elapsedMs
                prevUp = totalUp
                prevDown = totalDown
                prevAt = now

                XrayEngineState.updateTraffic(
                    upSpeedBps = upBps,
                    downSpeedBps = downBps,
                    totalUpBytes = totalUp,
                    totalDownBytes = totalDown,
                )

                updateLiveNotification(
                    profile = profile,
                    profileName = profileName,
                    protocolLabel = protocolLabel,
                    proxyOnly = proxyOnly,
                    connectedSinceMs = connectedAt,
                    upSpeedBps = upBps,
                    downSpeedBps = downBps,
                    totalUpBytes = totalUp,
                    totalDownBytes = totalDown,
                )

                if (tickCount % 2 == 0) {
                    NekoWidgetManager.refreshAll(this@XrayVpnService)
                }
            }
        }
    }

    private fun updateLiveNotification(
        profile: ProxyProfile,
        profileName: String,
        protocolLabel: String,
        proxyOnly: Boolean,
        connectedSinceMs: Long,
        upSpeedBps: Long,
        downSpeedBps: Long,
        totalUpBytes: Long,
        totalDownBytes: Long,
    ) {
        if (XrayEngineState.state.value.status != TunnelStatus.CONNECTED) return
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(
                NOTIFICATION_ID,
                buildNotification(
                    profile = profile,
                    profileName = profileName,
                    protocolLabel = protocolLabel,
                    proxyOnly = proxyOnly,
                    connected = true,
                    connectedSinceMs = connectedSinceMs,
                    upSpeedBps = upSpeedBps,
                    downSpeedBps = downSpeedBps,
                    totalUpBytes = totalUpBytes,
                    totalDownBytes = totalDownBytes,
                )
            )
        }
    }

    private fun startNativeCoreOrThrow(configJson: String, tunFd: Int) {
        val assetPath = XrayAssetHelper.ensureInitialized(this@XrayVpnService)
        XrayEngineState.appendLog("INFO", "Initialized Xray core env ($assetPath), starting core loop (tunFd=$tunFd)...")
        val callbackHandler = object : CoreCallbackHandler {
            override fun startup(): Long {
                XrayEngineState.appendLog("CORE", "Xray-core startup callback OK.")
                return 0L
            }

            override fun shutdown(): Long {
                XrayEngineState.appendLog("CORE", "Xray-core shutdown callback.")
                return 0L
            }

            override fun onEmitStatus(status: Long, msg: String?): Long {
                if (!msg.isNullOrBlank()) {
                    XrayEngineState.appendLog("CORE", msg)
                }
                return 0L
            }
        }
        val controller = Libv2ray.newCoreController(callbackHandler)
        coreControllerInstance = controller
        controller.startLoop(configJson, tunFd)
    }

    private fun stopPreviousEnginesOnly() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        runCatching { kotlinTunForwarder?.stop() }
        kotlinTunForwarder = null
        runCatching { localProxyServer?.stop() }
        localProxyServer = null
        runCatching {
            coreControllerInstance?.let { ctrl ->
                if (ctrl.isRunning) ctrl.stopLoop()
            }
        }
        coreControllerInstance = null
        runCatching { sshTunnelBridge?.stop() }
        sshTunnelBridge = null
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun stopTunnel() {
        val stopToken = activeSessionToken.incrementAndGet()
        activeStartJob?.cancel()
        activeStartJob = null
        XrayEngineState.appendLog("INFO", "Stopping Xray session...")
        XrayEngineState.updateStatus(status = TunnelStatus.IDLE)
        NekoWidgetManager.refreshAll(this)
        serviceScope.launch {
            engineMutex.withLock {
                if (stopToken == activeSessionToken.get()) {
                    stopTunnelInternal()
                    NekoWidgetManager.refreshAll(this@XrayVpnService)
                }
            }
        }
    }

    private fun stopTunnelInternal() {
        stopPreviousEnginesOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        XrayEngineState.appendLog("WARN", "VPN permission revoked by system.")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        activeSessionToken.incrementAndGet()
        activeStartJob?.cancel()
        stopPreviousEnginesOnly()
        if (XrayEngineState.state.value.status != TunnelStatus.ERROR) {
            XrayEngineState.updateStatus(status = TunnelStatus.IDLE)
        }
        NekoWidgetManager.refreshAll(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        profile: ProxyProfile,
        profileName: String,
        protocolLabel: String,
        proxyOnly: Boolean,
        connected: Boolean,
        connectedSinceMs: Long,
        upSpeedBps: Long = 0L,
        downSpeedBps: Long = 0L,
        totalUpBytes: Long = 0L,
        totalDownBytes: Long = 0L,
    ): Notification {
        val stopPending = PendingIntent.getService(
            this,
            1,
            buildStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reconnectPending = PendingIntent.getService(
            this,
            2,
            buildReconnectIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val modeBadge = if (proxyOnly) "Proxy Only" else "VPN · TUN"
        val cleanProto = protocolLabel.ifBlank { profile.protocol.displayName }
        val serverInfo = if (profile.serverAddress.isNotBlank()) {
            "${profile.serverAddress}:${profile.serverPort}"
        } else {
            cleanProto
        }

        val title = if (connected) {
            "$profileName · $modeBadge"
        } else {
            "Connecting · $profileName"
        }

        val upSpeedStr = formatSpeed(upSpeedBps)
        val downSpeedStr = formatSpeed(downSpeedBps)
        val totalUpStr = formatBytes(totalUpBytes)
        val totalDownStr = formatBytes(totalDownBytes)

        val collapsedText = if (connected) {
            "↑ $upSpeedStr  ·  ↓ $downSpeedStr   ($cleanProto)"
        } else {
            "Establishing $cleanProto tunnel..."
        }

        val expandedText = if (connected) {
            buildString {
                appendLine("Speed:  ↑ $upSpeedStr   ↓ $downSpeedStr")
                appendLine("Total:   ↑ $totalUpStr   ↓ $totalDownStr")
                append("Node:   $serverInfo · $cleanProto")
            }
        } else {
            "Connecting to $serverInfo ($cleanProto)..."
        }

        val subText = if (connected) {
            "↑ $totalUpStr · ↓ $totalDownStr"
        } else {
            modeBadge
        }

        val nextNodePending = NekoWidgetManager.actionBroadcastPendingIntent(
            this,
            NekoWidgetManager.ACTION_NEXT_PROFILE,
            3,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nekoxray)
            .setColor(0xFF00B8D4.toInt())
            .setContentTitle(title)
            .setContentText(collapsedText)
            .setSubText(subText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(if (connected) "$profileName · Connected" else "Connecting · $profileName")
                    .bigText(expandedText)
                    .setSummaryText("NekoXray $XRAY_CORE_VERSION · $modeBadge")
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(mainPendingIntent())
            .addAction(R.drawable.ic_notif_reconnect, getString(R.string.action_reconnect), reconnectPending)
            .addAction(R.drawable.ic_widget_next, getString(R.string.action_next_node), nextNodePending)
            .addAction(R.drawable.ic_notif_stop, getString(R.string.action_disconnect), stopPending)

        notificationLargeIcon?.let { builder.setLargeIcon(it) }

        if (connected && connectedSinceMs > 0L) {
            builder.setWhen(connectedSinceMs)
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
            builder.setUsesChronometer(false)
        }

        return builder.build()
    }

    private fun buildCircularLargeIcon(): Bitmap? = runCatching {
        val raw = BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_lain) ?: return null
        val size = 128
        val scaled = Bitmap.createScaledBitmap(raw, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size / 2f

        canvas.drawCircle(radius, radius, radius - 3f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, Rect(0, 0, size, size), Rect(0, 0, size, size), paint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0xFF22D3EE.toInt()
        }
        canvas.drawCircle(radius, radius, radius - 2.5f, ringPaint)
        if (scaled != raw) scaled.recycle()
        raw.recycle()
        out
    }.getOrNull()

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
