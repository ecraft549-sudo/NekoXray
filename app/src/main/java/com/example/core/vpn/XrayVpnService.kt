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
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NekoXrayApp
import com.example.R
import com.example.core.model.IPv6Mode
import com.example.core.model.PerAppProxyMode
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.RoutingRule
import com.example.core.model.ServiceMode
import com.example.core.model.XRAY_CORE_VERSION
import com.example.core.model.XraySettings
import com.example.core.widget.NekoWidgetManager
import com.example.core.xray.XrayAssetHelper
import com.example.core.xray.XrayConfigBuilder
import com.example.data.local.NekoDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.NekoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.action.START_VPN"
        const val ACTION_STOP = "com.example.action.STOP_VPN"
        const val ACTION_RECONNECT = "com.example.action.RECONNECT_VPN"
        const val EXTRA_SELF_INITIATED = "self_initiated"
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
        const val EXTRA_CHAIN_PROFILES_JSON = "chain_profiles_json"

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
            chainProfiles: List<ProxyProfile> = emptyList(),
        ): Intent = Intent(context, XrayVpnService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_SELF_INITIATED, true)
            putExtra(EXTRA_PROFILE_ID, profile.id)
            putExtra(EXTRA_PROFILE_NAME, profile.displayTitle())
            putExtra(EXTRA_PROTOCOL_LABEL, protocolLabel)
            putExtra(EXTRA_IS_REALITY, profile.isReality)
            putExtra(EXTRA_CONFIG_JSON, configJson)
            putExtra(EXTRA_PROFILE_JSON, json.encodeToString(ProxyProfile.serializer(), profile))
            putExtra(EXTRA_SETTINGS_JSON, json.encodeToString(XraySettings.serializer(), settings))
            putExtra(EXTRA_RULES_JSON, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(RoutingRule.serializer()), rules))
            if (chainProfiles.isNotEmpty()) {
                putExtra(
                    EXTRA_CHAIN_PROFILES_JSON,
                    json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProxyProfile.serializer()), chainProfiles),
                )
            }
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

        @Volatile
        private var activeServiceInstance: XrayVpnService? = null

        fun requestImmediateStop() {
            activeServiceInstance?.stopTunnel()
        }

        fun isSystemAlwaysOnInvocation(intent: Intent?): Boolean {
            if (intent == null) return true
            val act = intent.action
            if (act == SERVICE_INTERFACE) return true
            if (act == ACTION_STOP || act == ACTION_RECONNECT) return false
            return !intent.getBooleanExtra(EXTRA_SELF_INITIATED, false)
        }

        fun formatNetworkCapabilitiesLabel(caps: NetworkCapabilities?): String {
            if (caps == null) return "Offline"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular (LTE/5G)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                else -> "Network"
            }
        }

        fun buildLinkSignature(lp: LinkProperties?): Pair<String, Boolean> {
            if (lp == null) return "" to false
            val usableAddrs = lp.linkAddresses
                .asSequence()
                .mapNotNull { it.address }
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                .mapNotNull { it.hostAddress }
                .sorted()
                .toList()
            val dnsHosts = lp.dnsServers
                .asSequence()
                .mapNotNull { it.hostAddress }
                .sorted()
                .toList()
            val iface = lp.interfaceName.orEmpty()
            val sig = "$iface|${usableAddrs.joinToString(",")}|${dnsHosts.joinToString(",")}"
            return sig to usableAddrs.isNotEmpty()
        }

        fun rankPhysicalNetworks(
            networks: Map<Network, NetworkCapabilities>,
            activeDefaultNetwork: Network? = null,
            linkProperties: Map<Network, LinkProperties> = emptyMap(),
        ): List<Network> {
            return networks.entries
                .filter { (_, caps) ->
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                .sortedWith(
                    compareByDescending<Map.Entry<Network, NetworkCapabilities>> { (_, caps) ->
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    }.thenByDescending { (net, _) ->
                        val lp = linkProperties[net]
                        if (lp == null) 1 else if (buildLinkSignature(lp).second) 2 else 0
                    }.thenByDescending { (net, _) ->
                        net == activeDefaultNetwork
                    }.thenByDescending { (_, caps) ->
                        when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 4
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 1
                            else -> 0
                        }
                    }.thenByDescending { (_, caps) ->
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }
                )
                .map { it.key }
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

        private const val GOOGLE_PLAY_STORE_PKG = "com.android.vending"

        private val DOWNLOAD_MANAGER_COMPANIONS = setOf(
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
        )

        private val PLAY_SERVICES_COMPANIONS = setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
        )

        /**
         * Ensures that when Google Play Store (`com.android.vending`) is included in Per-App Proxy
         * (`PROXY_SELECTED` or `BYPASS_SELECTED`), Android's system `DownloadManager`
         * (`com.android.providers.downloads`) and Play Services (`com.google.android.gms`) are routed on the same
         * side of the tunnel so app updates never stall due to split-UID token/IP mismatches.
         */
        fun expandPerAppCompanionPackages(
            packages: Set<String>,
            mode: PerAppProxyMode,
            selfPackage: String = "",
        ): Set<String> {
            if (mode == PerAppProxyMode.ALL || packages.isEmpty()) return packages
            val expanded = LinkedHashSet<String>(packages.size + 6)
            packages.forEach { pkg ->
                val trimmed = pkg.trim()
                if (trimmed.isNotEmpty() && trimmed != selfPackage) {
                    expanded.add(trimmed)
                }
            }
            if (GOOGLE_PLAY_STORE_PKG in expanded) {
                expanded.addAll(DOWNLOAD_MANAGER_COMPANIONS)
                if (mode == PerAppProxyMode.PROXY_SELECTED) {
                    expanded.addAll(PLAY_SERVICES_COMPANIONS)
                }
            }
            if (selfPackage.isNotEmpty()) {
                expanded.remove(selfPackage)
            }
            return expanded
        }
    }

    private data class ActiveTunnelSpec(
        val profile: ProxyProfile,
        val protocolLabel: String,
        val effectiveConfigJson: String,
        val settings: XraySettings,
        val rules: List<RoutingRule>,
        val mtu: Int,
        val bypassLan: Boolean,
        val proxyOnly: Boolean,
        val ipv6Mode: IPv6Mode,
        val socksPort: Int,
        val httpPort: Int,
        val perAppMode: PerAppProxyMode,
        val perAppPackages: Set<String>,
        val chainProfiles: List<ProxyProfile> = emptyList(),
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jniControlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutexRef = AtomicReference(Mutex())
    private val activeSessionToken = AtomicLong(0L)
    private val handoffGeneration = AtomicLong(0L)
    private var bootstrapJob: Job? = null
    private var activeStartJob: Job? = null
    private var trafficMonitorJob: Job? = null
    private var networkHandoffJob: Job? = null
    private var lastStartIntent: Intent? = null
    private var activeTunnelSpec: ActiveTunnelSpec? = null
    private var notificationLargeIcon: Bitmap? = null
    private val activeTunDescriptors: MutableSet<ParcelFileDescriptor> = ConcurrentHashMap.newKeySet()
    private val inHandoffCriticalSection = AtomicBoolean(false)
    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null
    @Volatile
    private var coreControllerInstance: CoreController? = null
    @Volatile
    private var localProxyServer: LocalProxyServer? = null
    @Volatile
    private var kotlinTunForwarder: KotlinTunForwarder? = null
    @Volatile
    private var sshTunnelBridge: SshTunnelBridge? = null
    @Volatile
    private var tuicTunnelBridge: TuicTunnelBridge? = null

    private data class PhysicalNetworkSnapshot(
        val networkHandle: Long,
        val label: String,
        val linkSignature: String,
        val hasUsableIp: Boolean,
        val isValidated: Boolean,
    )

    private var connectivityManager: ConnectivityManager? = null
    private val availableNetworks = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val networkLinkProperties = ConcurrentHashMap<Network, LinkProperties>()
    @Volatile
    private var currentPrimaryNetwork: Network? = null
    @Volatile
    private var lastHandedOffSnapshot: PhysicalNetworkSnapshot? = null
    @Volatile
    private var pendingHandoffSnapshot: PhysicalNetworkSnapshot? = null
    @Volatile
    private var lastHandoffDownBytes: Long = 0L
    @Volatile
    private var systemStartedAlwaysOn: Boolean = false
    @Volatile
    private var networkCallbackRegistered: Boolean = false

    private val underlyingNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = runCatching { connectivityManager?.getNetworkCapabilities(network) }.getOrNull()
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                availableNetworks[network] = caps
                runCatching { connectivityManager?.getLinkProperties(network) }.getOrNull()?.let { lp ->
                    networkLinkProperties[network] = lp
                }
            }
            onPhysicalNetworksUpdated(reason = "onAvailable")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                availableNetworks[network] = networkCapabilities
                if (!networkLinkProperties.containsKey(network)) {
                    runCatching { connectivityManager?.getLinkProperties(network) }.getOrNull()?.let { lp ->
                        networkLinkProperties[network] = lp
                    }
                }
            } else {
                availableNetworks.remove(network)
                networkLinkProperties.remove(network)
            }
            onPhysicalNetworksUpdated(reason = "onCapabilitiesChanged")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networkLinkProperties[network] = linkProperties
            if (!availableNetworks.containsKey(network)) {
                val caps = runCatching { connectivityManager?.getNetworkCapabilities(network) }.getOrNull()
                if (caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    availableNetworks[network] = caps
                }
            }
            onPhysicalNetworksUpdated(reason = "onLinkPropertiesChanged")
        }

        override fun onLost(network: Network) {
            availableNetworks.remove(network)
            networkLinkProperties.remove(network)
            onPhysicalNetworksUpdated(reason = "onLost")
        }
    }

    internal val networkCallbackForTest: ConnectivityManager.NetworkCallback
        get() = underlyingNetworkCallback

    fun bindSocketToUnderlyingNetwork(socket: Socket): Boolean {
        val protectedOk = protect(socket)
        currentPrimaryNetwork?.let { net ->
            runCatching { net.bindSocket(socket) }
        }
        return protectedOk
    }

    fun bindDatagramSocketToUnderlyingNetwork(socket: DatagramSocket): Boolean {
        val protectedOk = protect(socket)
        currentPrimaryNetwork?.let { net ->
            runCatching { net.bindSocket(socket) }
        }
        return protectedOk
    }

    private fun bindProcessToUnderlyingNetwork(network: Network?) {
        val cm = connectivityManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.bindProcessToNetwork(network)
            } else {
                @Suppress("DEPRECATION")
                ConnectivityManager.setProcessDefaultNetwork(network)
            }
        }
    }

    fun isAlwaysOnActive(): Boolean {
        val osAlwaysOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { isAlwaysOn }.getOrDefault(false)
        } else {
            false
        }
        return systemStartedAlwaysOn || osAlwaysOn
    }

    fun isLockdownActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { isLockdownEnabled }.getOrDefault(false)
        } else {
            false
        }
    }

    @Volatile
    private var packageReceiverRegistered = false

    private val packageUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_PACKAGE_REPLACED && action != Intent.ACTION_PACKAGE_ADDED) return
            val updatedPkg = intent.data?.schemeSpecificPart?.trim().orEmpty()
            if (updatedPkg.isEmpty() || updatedPkg == packageName) return
            val spec = activeTunnelSpec ?: return
            if (spec.perAppMode == PerAppProxyMode.ALL) return
            val effective = expandPerAppCompanionPackages(spec.perAppPackages, spec.perAppMode, packageName)
            if (updatedPkg in effective && XrayEngineState.state.value.status == TunnelStatus.CONNECTED) {
                XrayEngineState.appendLog(
                    "INFO",
                    "Installed package '$updatedPkg' updated; refreshing Per-App VPN routing rules.",
                )
                val snap = buildSnapshotFor(currentPrimaryNetwork) ?: lastHandedOffSnapshot
                if (snap != null) {
                    scheduleSeamlessNetworkHandoff(
                        targetSnapshot = snap,
                        debounceMs = 350L,
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        ensureNotificationChannel()
        notificationLargeIcon = buildCircularLargeIcon()
        registerUnderlyingNetworkMonitor()
        registerPackageUpdateReceiver()
    }

    private fun registerPackageUpdateReceiver() {
        if (packageReceiverRegistered) return
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(packageUpdateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(packageUpdateReceiver, filter)
            }
            packageReceiverRegistered = true
        }
    }

    private fun unregisterPackageUpdateReceiver() {
        if (!packageReceiverRegistered) return
        runCatching { unregisterReceiver(packageUpdateReceiver) }
        packageReceiverRegistered = false
    }

    private fun registerUnderlyingNetworkMonitor() {
        val cm = runCatching { getSystemService(ConnectivityManager::class.java) }.getOrNull()
        connectivityManager = cm
        if (cm == null) return

        // Seed all physical networks (Wi-Fi, Cellular, Ethernet) before callbacks arrive
        runCatching {
            @Suppress("DEPRECATION")
            val candidates = buildList {
                cm.allNetworks.forEach { add(it) }
                cm.activeNetwork?.let { if (it !in this) add(it) }
            }
            for (net in candidates) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    availableNetworks[net] = caps
                    cm.getLinkProperties(net)?.let { networkLinkProperties[net] = it }
                }
            }
            val ordered = getOrderedUnderlyingNetworks()
            val primary = ordered.firstOrNull()
            currentPrimaryNetwork = primary
            if (primary != null) {
                val caps = availableNetworks[primary]
                XrayEngineState.updateNetworkState(
                    underlyingNetworkLabel = formatNetworkCapabilitiesLabel(caps),
                    isAlwaysOn = isAlwaysOnActive(),
                    isLockdown = isLockdownActive(),
                )
            }
        }

        if (!networkCallbackRegistered) {
            runCatching {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                cm.registerNetworkCallback(req, underlyingNetworkCallback)
                networkCallbackRegistered = true
            }.onFailure { err ->
                XrayEngineState.appendLog(
                    "WARN",
                    "ConnectivityManager callback registration failed: ${err.message ?: err.javaClass.simpleName}",
                )
            }
        }
    }

    private fun unregisterUnderlyingNetworkMonitor() {
        if (networkCallbackRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(underlyingNetworkCallback) }
            networkCallbackRegistered = false
        }
        bindProcessToUnderlyingNetwork(null)
        availableNetworks.clear()
        networkLinkProperties.clear()
        currentPrimaryNetwork = null
        lastHandedOffSnapshot = null
        pendingHandoffSnapshot = null
    }

    private fun getOrderedUnderlyingNetworks(): List<Network> {
        val activeDef = runCatching { connectivityManager?.activeNetwork }.getOrNull()
        return rankPhysicalNetworks(availableNetworks, activeDef, networkLinkProperties)
    }

    private fun networkHandleOf(net: Network): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { net.networkHandle }.getOrElse { net.hashCode().toLong() }
        } else {
            net.hashCode().toLong()
        }
    }

    private fun buildSnapshotFor(net: Network?): PhysicalNetworkSnapshot? {
        net ?: return null
        val caps = availableNetworks[net]
        val lp = networkLinkProperties[net]
        val (linkSig, hasUsableIp) = buildLinkSignature(lp)
        val isValidated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        return PhysicalNetworkSnapshot(
            networkHandle = networkHandleOf(net),
            label = formatNetworkCapabilitiesLabel(caps),
            linkSignature = linkSig,
            hasUsableIp = hasUsableIp,
            isValidated = isValidated,
        )
    }

    internal fun onPhysicalNetworksUpdated(reason: String, debounceMs: Long? = null) {
        val ordered = getOrderedUnderlyingNetworks()
        val newPrimary = ordered.firstOrNull()
        val status = XrayEngineState.state.value.status
        val isTunnelActive = status == TunnelStatus.CONNECTED || status == TunnelStatus.CONNECTING

        if (isTunnelActive) {
            runCatching {
                setUnderlyingNetworks(if (ordered.isNotEmpty()) ordered.toTypedArray() else emptyArray())
            }
            bindProcessToUnderlyingNetwork(newPrimary)
        }

        val newSnapshot = buildSnapshotFor(newPrimary)
        val newLabel = newSnapshot?.label ?: "Offline"
        val prevPrimary = currentPrimaryNetwork
        val prevLabel = XrayEngineState.state.value.underlyingNetworkLabel
        currentPrimaryNetwork = newPrimary

        XrayEngineState.updateNetworkState(
            underlyingNetworkLabel = newLabel,
            isAlwaysOn = isAlwaysOnActive(),
            isLockdown = isLockdownActive(),
        )

        if (newPrimary == null || newSnapshot == null) {
            if (!inHandoffCriticalSection.get()) {
                networkHandoffJob?.cancel()
            }
            pendingHandoffSnapshot = null
            lastHandedOffSnapshot = null
            if (prevPrimary != null && isTunnelActive) {
                XrayEngineState.appendLog(
                    "WARN",
                    "Underlying network lost ($prevLabel -> Offline); waiting for Wi-Fi/LTE reconnect...",
                )
            }
            return
        }

        if (!isTunnelActive) {
            return
        }

        val baseline = if (networkHandoffJob?.isActive == true) {
            pendingHandoffSnapshot ?: lastHandedOffSnapshot
        } else {
            lastHandedOffSnapshot
        }
        val shouldHandoff = when {
            baseline == null -> true
            baseline.networkHandle != newSnapshot.networkHandle -> true
            baseline.linkSignature != newSnapshot.linkSignature && newSnapshot.hasUsableIp -> true
            !baseline.isValidated && newSnapshot.isValidated && (
                coreControllerInstance?.isRunning != true ||
                    XrayEngineState.state.value.totalDownBytes <= lastHandoffDownBytes
                ) -> true
            else -> false
        }

        if (shouldHandoff) {
            val effectiveDelay = debounceMs ?: when {
                newSnapshot.isValidated && newSnapshot.hasUsableIp -> 500L
                newSnapshot.hasUsableIp -> 650L
                else -> 1100L
            }
            val fromDesc = prevLabel.ifBlank { baseline?.label ?: "Offline" }
            XrayEngineState.appendLog(
                "INFO",
                "Network transition ($fromDesc -> $newLabel via $reason, ip=${newSnapshot.hasUsableIp}, validated=${newSnapshot.isValidated}); scheduling Xray handoff (${effectiveDelay}ms)...",
            )
            scheduleSeamlessNetworkHandoff(newSnapshot, effectiveDelay)
        } else if (lastHandedOffSnapshot != null && !lastHandedOffSnapshot!!.isValidated && newSnapshot.isValidated) {
            lastHandedOffSnapshot = newSnapshot
        }
    }

    private fun scheduleSeamlessNetworkHandoff(
        targetSnapshot: PhysicalNetworkSnapshot,
        debounceMs: Long = 500L,
    ) {
        val gen = handoffGeneration.incrementAndGet()
        pendingHandoffSnapshot = targetSnapshot
        // Never cancel a handoff coroutine while it is inside the mutex critical section tearing down/rebuilding TUN
        if (inHandoffCriticalSection.get() && networkHandoffJob?.isActive == true) {
            return
        }
        networkHandoffJob?.cancel()
        networkHandoffJob = serviceScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val sessionAtLaunch = activeSessionToken.get()
            if (!isActive || gen != handoffGeneration.get()) return@launch
            if (XrayEngineState.state.value.status != TunnelStatus.CONNECTED) return@launch

            val lock = engineMutexRef.get()
            val acquired = withTimeoutOrNull(2500L) {
                lock.lock()
                true
            } ?: false
            if (!acquired) {
                XrayEngineState.appendLog("WARN", "Engine lock busy during network handoff; forcing recovery reset.")
                forceReleaseNativeResources()
                engineMutexRef.compareAndSet(lock, Mutex())
                return@launch
            }
            inHandoffCriticalSection.set(true)
            try {
                withContext(NonCancellable) {
                    if (sessionAtLaunch != activeSessionToken.get()) return@withContext
                    if (XrayEngineState.state.value.status != TunnelStatus.CONNECTED) return@withContext
                    val spec = activeTunnelSpec ?: return@withContext

                    val initialOrdered = getOrderedUnderlyingNetworks()
                    val initialTarget = initialOrdered.firstOrNull() ?: return@withContext
                    val initialSnapshot = buildSnapshotFor(initialTarget) ?: targetSnapshot

                    runCatching {
                        setUnderlyingNetworks(initialOrdered.toTypedArray())
                    }
                    bindProcessToUnderlyingNetwork(initialTarget)

                    XrayEngineState.updateNetworkState(
                        underlyingNetworkLabel = initialSnapshot.label,
                        isAlwaysOn = isAlwaysOnActive(),
                        isLockdown = isLockdownActive(),
                        incrementHandoff = true,
                    )

                    runCatching {
                        sshTunnelBridge?.reconnectSession("network handoff to ${initialSnapshot.label}")
                        tuicTunnelBridge?.reconnectSession("network handoff to ${initialSnapshot.label}")
                        val hasNativeCore = XrayEngineState.state.value.isNativeCoreLinked

                        if (hasNativeCore) {
                            // 1. Stop old CoreController & close old tunInterface cleanly (non-cancellable)
                            stopNativeCoreAndTunSafely()
                            delay(200L)

                            if (sessionAtLaunch != activeSessionToken.get() ||
                                XrayEngineState.state.value.status != TunnelStatus.CONNECTED
                            ) {
                                stopPreviousEnginesSafely()
                                return@withContext
                            }

                            // Re-query freshest physical network in case another callback arrived during the 200ms settle
                            val latestOrdered = getOrderedUnderlyingNetworks()
                            val latestTarget = latestOrdered.firstOrNull() ?: initialTarget
                            runCatching {
                                setUnderlyingNetworks(
                                    if (latestOrdered.isNotEmpty()) latestOrdered.toTypedArray() else null
                                )
                            }
                            bindProcessToUnderlyingNetwork(latestTarget)

                            if (!spec.proxyOnly) {
                                val freshPfd = establishVpnInterface(spec)
                                    ?: error("VPN interface could not be re-established during network handoff")
                                tunInterface = freshPfd
                                if (sessionAtLaunch != activeSessionToken.get()) {
                                    stopPreviousEnginesSafely()
                                    return@withContext
                                }
                                startNativeCoreWithRetry(spec.effectiveConfigJson, freshPfd.fd, sessionAtLaunch)
                            } else {
                                startNativeCoreWithRetry(spec.effectiveConfigJson, 0, sessionAtLaunch)
                            }
                        } else if (!spec.proxyOnly) {
                            // Kotlin fallback forwarder restart on network change
                            runCatching { kotlinTunForwarder?.stop() }
                            runCatching { localProxyServer?.stop() }
                            val pfd = tunInterface ?: establishVpnInterface(spec)?.also { tunInterface = it }
                            if (pfd != null) {
                                val dialer = KotlinOutboundDialer(profile = spec.profile, vpnService = this@XrayVpnService)
                                kotlinTunForwarder = KotlinTunForwarder(
                                    tunPfd = pfd,
                                    dialer = dialer,
                                    scope = serviceScope,
                                ).also { it.start() }
                                localProxyServer = LocalProxyServer(
                                    socksPort = spec.socksPort,
                                    httpPort = spec.httpPort,
                                    dialer = dialer,
                                ).also { it.start(serviceScope) }
                            }
                        }

                        if (sessionAtLaunch == activeSessionToken.get()) {
                            val finalNet = getOrderedUnderlyingNetworks().firstOrNull() ?: initialTarget
                            val finalSnapshot = buildSnapshotFor(finalNet) ?: initialSnapshot
                            lastHandedOffSnapshot = finalSnapshot
                            pendingHandoffSnapshot = null
                            lastHandoffDownBytes = XrayEngineState.state.value.totalDownBytes
                            XrayEngineState.appendLog(
                                "INFO",
                                "Seamless handoff to ${finalSnapshot.label} complete (process & VPN bound to physical net).",
                            )
                            updateLiveNotification(
                                profile = spec.profile,
                                profileName = spec.profile.displayTitle(),
                                protocolLabel = spec.protocolLabel,
                                proxyOnly = spec.proxyOnly,
                                connectedSinceMs = System.currentTimeMillis(),
                                upSpeedBps = XrayEngineState.state.value.upSpeedBps,
                                downSpeedBps = XrayEngineState.state.value.downSpeedBps,
                                totalUpBytes = XrayEngineState.state.value.totalUpBytes,
                                totalDownBytes = XrayEngineState.state.value.totalDownBytes,
                            )
                        } else {
                            stopPreviousEnginesSafely()
                        }
                    }.onFailure { err ->
                        pendingHandoffSnapshot = null
                        if (err is CancellationException) {
                            if (sessionAtLaunch != activeSessionToken.get()) {
                                stopPreviousEnginesSafely()
                            }
                            return@onFailure
                        }
                        lastHandedOffSnapshot = null
                        if (sessionAtLaunch == activeSessionToken.get()) {
                            XrayEngineState.appendLog(
                                "ERROR",
                                "Network handoff failed: ${err.message ?: err.javaClass.simpleName}",
                            )
                            XrayEngineState.updateStatus(
                                status = TunnelStatus.ERROR,
                                error = "Network handoff failed: ${err.message ?: err.javaClass.simpleName}",
                            )
                            stopTunnelInternal()
                            NekoWidgetManager.refreshAll(this@XrayVpnService)
                        } else {
                            stopPreviousEnginesSafely()
                        }
                    }
                }
            } finally {
                inHandoffCriticalSection.set(false)
                runCatching { if (lock.isLocked) lock.unlock() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            systemStartedAlwaysOn = false
            stopTunnel()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RECONNECT) {
            val prev = lastStartIntent
            if (prev != null) {
                handleStartIntent(prev)
            } else {
                handleAlwaysOnOrBootstrapStart(isSystemAlwaysOn = isAlwaysOnActive())
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_START && intent.hasExtra(EXTRA_PROFILE_JSON)) {
            if (isSystemAlwaysOnInvocation(intent)) {
                systemStartedAlwaysOn = true
            }
            lastStartIntent = Intent(intent)
            handleStartIntent(intent)
            return START_STICKY
        }

        // System Always-On VPN launch (action == "android.net.VpnService" or null intent on sticky restart)
        systemStartedAlwaysOn = true
        handleAlwaysOnOrBootstrapStart(isSystemAlwaysOn = true)
        return START_STICKY
    }

    private fun handleAlwaysOnOrBootstrapStart(isSystemAlwaysOn: Boolean) {
        val bootstrapToken = activeSessionToken.incrementAndGet()
        bootstrapJob?.cancel()
        activeStartJob?.cancel()
        networkHandoffJob?.cancel()
        val placeholderProfile = ProxyProfile(name = "Always-On VPN")
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                profile = placeholderProfile,
                profileName = "Always-On VPN",
                protocolLabel = "Xray-core",
                proxyOnly = false,
                connected = false,
                connectedSinceMs = 0L,
            ),
        )
        XrayEngineState.updateNetworkState(
            isAlwaysOn = isSystemAlwaysOn || isAlwaysOnActive(),
            isLockdown = isLockdownActive(),
        )

        bootstrapJob = serviceScope.launch {
            runCatching {
                val geo = XrayAssetHelper.ensureInitialized(this@XrayVpnService)
                val app = applicationContext as? NekoXrayApp
                val repo = app?.repository ?: NekoRepository(
                    db = NekoDatabase.getInstance(applicationContext),
                    settingsStore = SettingsDataStore(applicationContext),
                    appContext = applicationContext,
                )
                repo.ensureDefaults()
                val profiles = repo.profilesFlow.first()
                val selectedId = repo.selectedProfileIdFlow.first()
                val savedSettings = repo.settingsFlow.first()
                val rules = repo.routingRulesFlow.first()
                val profile = profiles.find { it.id == selectedId } ?: profiles.firstOrNull()

                if (bootstrapToken != activeSessionToken.get()) return@launch

                if (profile == null) {
                    XrayEngineState.appendLog(
                        "WARN",
                        "Always-On VPN triggered by Android system, but no proxy profile is configured yet.",
                    )
                    XrayEngineState.updateStatus(
                        status = TunnelStatus.ERROR,
                        error = "Always-On VPN: add a profile in NekoXray first.",
                    )
                    stopTunnelInternal()
                    NekoWidgetManager.refreshAll(this@XrayVpnService)
                    return@launch
                }

                // Always-On VPN requires a TUN interface to satisfy Android VpnService & Lockdown mode
                val effectiveSettings = if (isSystemAlwaysOn || isAlwaysOnActive() || isLockdownActive()) {
                    savedSettings.copy(serviceMode = ServiceMode.VPN)
                } else {
                    savedSettings
                }
                val proxyOnly = effectiveSettings.serviceMode == ServiceMode.PROXY_ONLY
                val chainHops = XrayConfigBuilder.resolveChain(profile, profiles).hops
                val protocolLabel = NekoWidgetManager.formatProtocolLabel(profile, profiles)
                val configJson = XrayConfigBuilder.buildConfigJson(
                    profile = profile,
                    settings = effectiveSettings,
                    rules = rules,
                    includeTunInbound = !proxyOnly,
                    hasGeoIp = geo.hasGeoIp,
                    hasGeoSite = geo.hasGeoSite,
                    allProfiles = chainHops,
                )

                if (bootstrapToken != activeSessionToken.get()) return@launch

                val constructedIntent = buildStartIntent(
                    context = this@XrayVpnService,
                    profile = profile,
                    protocolLabel = protocolLabel,
                    configJson = configJson,
                    mtu = effectiveSettings.mtu,
                    bypassLan = effectiveSettings.bypassLan,
                    proxyOnly = proxyOnly,
                    ipv6Mode = effectiveSettings.ipv6Mode,
                    socksPort = effectiveSettings.socksPort,
                    httpPort = effectiveSettings.httpPort,
                    perAppMode = effectiveSettings.perAppMode,
                    perAppPackages = effectiveSettings.perAppPackages,
                    settings = effectiveSettings,
                    rules = rules,
                    chainProfiles = chainHops,
                )
                lastStartIntent = constructedIntent
                XrayEngineState.appendLog(
                    "INFO",
                    "Always-On VPN bootstrap loaded profile '${profile.displayTitle()}' (lockdown=${isLockdownActive()}).",
                )
                startTunnel(
                    profile = profile,
                    protocolLabel = protocolLabel,
                    configJson = configJson,
                    settings = effectiveSettings,
                    rules = rules,
                    mtu = effectiveSettings.mtu,
                    bypassLan = effectiveSettings.bypassLan,
                    proxyOnly = proxyOnly,
                    ipv6Mode = effectiveSettings.ipv6Mode,
                    socksPort = effectiveSettings.socksPort,
                    httpPort = effectiveSettings.httpPort,
                    perAppMode = effectiveSettings.perAppMode,
                    perAppPackages = effectiveSettings.perAppPackages,
                    chainProfiles = chainHops,
                )
            }.onFailure { err ->
                if (err !is CancellationException && bootstrapToken == activeSessionToken.get()) {
                    XrayEngineState.appendLog(
                        "ERROR",
                        "Always-On VPN bootstrap failed: ${err.message ?: err.javaClass.simpleName}",
                    )
                    XrayEngineState.updateStatus(
                        status = TunnelStatus.ERROR,
                        error = err.message ?: "Always-On VPN bootstrap failed",
                    )
                    stopTunnelInternal()
                }
            }
        }
    }

    private fun handleStartIntent(intent: Intent) {
        bootstrapJob?.cancel()
        bootstrapJob = null
        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty().ifBlank { "Xray Profile" }
        val protocolLabel = intent.getStringExtra(EXTRA_PROTOCOL_LABEL).orEmpty()
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
        val proxyOnly = intent.getBooleanExtra(EXTRA_PROXY_ONLY, false) && !isAlwaysOnActive() && !isLockdownActive()
        val ipv6Mode = runCatching {
            IPv6Mode.valueOf(intent.getStringExtra(EXTRA_IPV6_MODE) ?: IPv6Mode.ENABLE.name)
        }.getOrDefault(IPv6Mode.ENABLE)
        val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 10808)
        val httpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 10809)
        val perAppMode = runCatching {
            PerAppProxyMode.valueOf(intent.getStringExtra(EXTRA_PER_APP_MODE) ?: PerAppProxyMode.ALL.name)
        }.getOrDefault(PerAppProxyMode.ALL)
        val perAppPackages = intent.getStringArrayListExtra(EXTRA_PER_APP_PACKAGES)?.toSet() ?: emptySet()
        val chainProfilesJson = intent.getStringExtra(EXTRA_CHAIN_PROFILES_JSON).orEmpty()
        val chainProfiles = runCatching {
            if (chainProfilesJson.isNotBlank()) {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ProxyProfile.serializer()),
                    chainProfilesJson,
                )
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

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
            chainProfiles = chainProfiles,
        )
    }

    private fun establishVpnInterface(spec: ActiveTunnelSpec): ParcelFileDescriptor? {
        val profileName = spec.profile.displayTitle()
        val effectiveMtu = spec.mtu.coerceIn(1280, 9000)
        val builder = Builder()
            .setSession(profileName)
            .setMtu(effectiveMtu)
            .addAddress("172.19.0.1", 30)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .setConfigureIntent(mainPendingIntent())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { builder.setMetered(false) }
        }

        val orderedNetworks = getOrderedUnderlyingNetworks()
        runCatching {
            builder.setUnderlyingNetworks(
                if (orderedNetworks.isNotEmpty()) orderedNetworks.toTypedArray() else null
            )
        }

        val hasSshInChain = spec.profile.protocol == ProxyProtocol.SSH ||
            spec.chainProfiles.any { it.protocol == ProxyProtocol.SSH }
        val enableTunIpv6 = if (hasSshInChain) {
            spec.ipv6Mode == IPv6Mode.ONLY || spec.ipv6Mode == IPv6Mode.PREFER
        } else {
            spec.ipv6Mode != IPv6Mode.DISABLE
        }
        if (enableTunIpv6) {
            builder.addAddress("fdfe:dcba:9876::1", 126)
            builder.addRoute("::", 0)
        }

        if (isLockdownActive() && spec.perAppMode != PerAppProxyMode.ALL) {
            XrayEngineState.appendLog(
                "WARN",
                "Android VPN Lockdown ('Block connections without VPN') is active: apps excluded from VPN will have no internet access by OS policy.",
            )
        }

        val effectivePerAppPackages = expandPerAppCompanionPackages(
            packages = spec.perAppPackages,
            mode = spec.perAppMode,
            selfPackage = packageName,
        )

        when (spec.perAppMode) {
            PerAppProxyMode.ALL -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
            PerAppProxyMode.BYPASS_SELECTED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                var appliedCount = 0
                effectivePerAppPackages.forEach { pkg ->
                    if (pkg != packageName) {
                        if (runCatching { builder.addDisallowedApplication(pkg) }.isSuccess) {
                            appliedCount++
                        }
                    }
                }
                XrayEngineState.appendLog(
                    "INFO",
                    "Per-App VPN: bypassing $appliedCount package(s) (selected=${spec.perAppPackages.size}, with store/download companions=${effectivePerAppPackages.size}).",
                )
            }
            PerAppProxyMode.PROXY_SELECTED -> {
                val targets = effectivePerAppPackages.filter { it != packageName }
                if (targets.isEmpty()) {
                    runCatching { builder.addDisallowedApplication(packageName) }
                } else {
                    var appliedCount = 0
                    targets.forEach { pkg ->
                        if (runCatching { builder.addAllowedApplication(pkg) }.isSuccess) {
                            appliedCount++
                        }
                    }
                    XrayEngineState.appendLog(
                        "INFO",
                        "Per-App VPN: routing $appliedCount package(s) (selected=${spec.perAppPackages.size}, with store/download companions=${targets.size}).",
                    )
                }
            }
        }

        if (spec.bypassLan) {
            XrayEngineState.appendLog("INFO", "LAN bypass rule configured in Xray routing.")
        }

        val pfd = builder.establish()
        if (pfd != null) {
            activeTunDescriptors.add(pfd)
            runCatching {
                setUnderlyingNetworks(
                    if (orderedNetworks.isNotEmpty()) orderedNetworks.toTypedArray() else null
                )
            }
        }
        return pfd
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
        chainProfiles: List<ProxyProfile> = emptyList(),
    ) {
        val profileName = profile.displayTitle()
        val sessionToken = activeSessionToken.incrementAndGet()
        handoffGeneration.incrementAndGet()
        XrayEngineState.updateNetworkState(
            isAlwaysOn = isAlwaysOnActive(),
            isLockdown = isLockdownActive(),
        )
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

        networkHandoffJob?.cancel()
        networkHandoffJob = null
        activeStartJob?.cancel()
        activeStartJob = serviceScope.launch {
            var lock = engineMutexRef.get()
            val acquired = withTimeoutOrNull(2500L) {
                lock.lock()
                true
            } ?: false
            if (!acquired) {
                XrayEngineState.appendLog("WARN", "Previous tunnel operation stalled; resetting engine lock.")
                forceReleaseNativeResources()
                val freshLock = Mutex(locked = true)
                engineMutexRef.set(freshLock)
                lock = freshLock
            }
            try {
                if (sessionToken != activeSessionToken.get()) return@launch
                runCatching {
                    stopPreviousEnginesSafely()
                    delay(100L)
                    if (sessionToken != activeSessionToken.get()) return@launch
                    val hasNativeCore = XrayEngineState.state.value.isNativeCoreLinked

                    if (!proxyOnly) {
                        val effectiveMtu = mtu.coerceIn(1280, 9000)
                        val effectiveConfigJson = prepareEffectiveConfigWithBridges(
                            profile = profile,
                            chainProfiles = chainProfiles,
                            settings = settings,
                            rules = rules,
                            includeTunInbound = true,
                            fallbackConfigJson = configJson,
                        )

                        val spec = ActiveTunnelSpec(
                            profile = profile,
                            protocolLabel = protocolLabel,
                            effectiveConfigJson = effectiveConfigJson,
                            settings = settings,
                            rules = rules,
                            mtu = effectiveMtu,
                            bypassLan = bypassLan,
                            proxyOnly = false,
                            ipv6Mode = ipv6Mode,
                            socksPort = socksPort,
                            httpPort = httpPort,
                            perAppMode = perAppMode,
                            perAppPackages = perAppPackages,
                            chainProfiles = chainProfiles,
                        )
                        activeTunnelSpec = spec

                        val pfd = establishVpnInterface(spec)
                            ?: error("VPN interface could not be established (permission missing or revoked).")
                        if (sessionToken != activeSessionToken.get()) {
                            runCatching { pfd.close() }
                            return@launch
                        }
                        tunInterface = pfd
                        val netLabel = XrayEngineState.state.value.underlyingNetworkLabel.ifBlank { "default" }
                        XrayEngineState.appendLog(
                            "INFO",
                            "TUN interface established (172.19.0.1/30, fd=${pfd.fd}, MTU $effectiveMtu, network=$netLabel, alwaysOn=${isAlwaysOnActive()}, lockdown=${isLockdownActive()}).",
                        )

                        val primaryAtStart = getOrderedUnderlyingNetworks().firstOrNull()
                        bindProcessToUnderlyingNetwork(primaryAtStart)

                        if (hasNativeCore) {
                            startNativeCoreWithRetry(effectiveConfigJson, pfd.fd, sessionToken)
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
                            stopPreviousEnginesSafely()
                            return@launch
                        }
                        lastHandedOffSnapshot = buildSnapshotFor(primaryAtStart ?: currentPrimaryNetwork)
                        lastHandoffDownBytes = XrayEngineState.state.value.totalDownBytes
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
                        val effectiveConfigJson = prepareEffectiveConfigWithBridges(
                            profile = profile,
                            chainProfiles = chainProfiles,
                            settings = settings,
                            rules = rules,
                            includeTunInbound = false,
                            fallbackConfigJson = configJson,
                        )

                        activeTunnelSpec = ActiveTunnelSpec(
                            profile = profile,
                            protocolLabel = protocolLabel,
                            effectiveConfigJson = effectiveConfigJson,
                            settings = settings,
                            rules = rules,
                            mtu = mtu,
                            bypassLan = bypassLan,
                            proxyOnly = true,
                            ipv6Mode = ipv6Mode,
                            socksPort = socksPort,
                            httpPort = httpPort,
                            perAppMode = perAppMode,
                            perAppPackages = perAppPackages,
                            chainProfiles = chainProfiles,
                        )

                        val primaryAtStart = getOrderedUnderlyingNetworks().firstOrNull()
                        bindProcessToUnderlyingNetwork(primaryAtStart)

                        if (hasNativeCore) {
                            // In AndroidLibXrayLite, tunFd == 0 means Proxy-Only (no TUN)
                            startNativeCoreWithRetry(effectiveConfigJson, 0, sessionToken)
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
                            stopPreviousEnginesSafely()
                            return@launch
                        }
                        lastHandedOffSnapshot = buildSnapshotFor(primaryAtStart ?: currentPrimaryNetwork)
                        lastHandoffDownBytes = XrayEngineState.state.value.totalDownBytes
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
                    if (err is CancellationException) {
                        if (sessionToken != activeSessionToken.get()) {
                            stopPreviousEnginesSafely()
                        }
                        return@onFailure
                    }
                    if (sessionToken == activeSessionToken.get()) {
                        XrayEngineState.appendLog("ERROR", "Tunnel failed: ${err.message ?: err.javaClass.simpleName}")
                        XrayEngineState.updateStatus(
                            status = TunnelStatus.ERROR,
                            error = err.message ?: "VPN start failed",
                        )
                        stopTunnelInternal()
                        NekoWidgetManager.refreshAll(this@XrayVpnService)
                    } else {
                        stopPreviousEnginesSafely()
                    }
                }
            } finally {
                runCatching { if (lock.isLocked) lock.unlock() }
            }
        }
    }

    private fun prepareEffectiveConfigWithBridges(
        profile: ProxyProfile,
        chainProfiles: List<ProxyProfile>,
        settings: XraySettings,
        rules: List<RoutingRule>,
        includeTunInbound: Boolean,
        fallbackConfigJson: String,
    ): String {
        val chain = XrayConfigBuilder.resolveChain(profile, chainProfiles)
        val bridgeHops = chain.hops.filter {
            it.protocol == ProxyProtocol.SSH || it.protocol == ProxyProtocol.TUIC
        }
        if (bridgeHops.isEmpty()) {
            return fallbackConfigJson
        }
        val geo = XrayAssetHelper.currentStatus(this@XrayVpnService)
        val bridgePorts = mutableMapOf<Long, Int>()
        var primarySshPort = 0
        var primaryTuicPort = 0
        for (hop in bridgeHops) {
            when (hop.protocol) {
                ProxyProtocol.SSH -> {
                    val bridge = SshTunnelBridge(profile = hop, vpnService = this@XrayVpnService)
                    val port = bridge.start()
                    sshTunnelBridge = bridge
                    bridgePorts[hop.id] = port
                    if (hop.id == profile.id) primarySshPort = port
                }
                ProxyProtocol.TUIC -> {
                    val bridge = TuicTunnelBridge(profile = hop, vpnService = this@XrayVpnService)
                    val port = bridge.start()
                    tuicTunnelBridge = bridge
                    bridgePorts[hop.id] = port
                    if (hop.id == profile.id) primaryTuicPort = port
                }
                else -> Unit
            }
        }
        return XrayConfigBuilder.buildConfigJson(
            profile = profile,
            settings = settings,
            rules = rules,
            includeTunInbound = includeTunInbound,
            sshLocalBridgePort = primarySshPort,
            tuicLocalBridgePort = primaryTuicPort,
            hasGeoIp = geo.hasGeoIp,
            hasGeoSite = geo.hasGeoSite,
            allProfiles = chainProfiles,
            chainBridgePorts = bridgePorts,
        )
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

    private suspend fun startNativeCoreWithRetry(configJson: String, tunFd: Int, expectedSessionToken: Long) {
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            if (expectedSessionToken != activeSessionToken.get()) {
                throw CancellationException("Tunnel session superseded before core start")
            }
            val res = runCatching {
                startNativeCoreOrThrow(configJson, tunFd)
            }
            if (res.isSuccess) return
            lastError = res.exceptionOrNull()
            XrayEngineState.appendLog(
                "WARN",
                "Xray start attempt $attempt/3 failed: ${lastError?.message ?: lastError?.javaClass?.simpleName}",
            )
            if (attempt < 3) delay(200L * attempt)
        }
        throw (lastError ?: IllegalStateException("Failed to start Xray core"))
    }

    private fun startNativeCoreOrThrow(configJson: String, tunFd: Int) {
        val assetStatus = XrayAssetHelper.ensureInitialized(this@XrayVpnService)
        XrayEngineState.appendLog("INFO", "Initialized Xray core env (${assetStatus.assetDir}), starting core loop (tunFd=$tunFd)...")
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
        try {
            controller.startLoop(configJson, tunFd)
        } catch (t: Throwable) {
            coreControllerInstance = null
            throw t
        }
    }

    private fun closeAllTunDescriptors() {
        val primary = tunInterface
        tunInterface = null
        if (primary != null) {
            activeTunDescriptors.remove(primary)
            runCatching { primary.close() }
        }
        val iterator = activeTunDescriptors.iterator()
        while (iterator.hasNext()) {
            val fd = iterator.next()
            iterator.remove()
            runCatching { fd.close() }
        }
    }

    private suspend fun stopNativeCoreAndTunSafely() = withContext(NonCancellable) {
        val ctrl = coreControllerInstance
        coreControllerInstance = null
        val pfd = tunInterface
        tunInterface = null
        if (pfd != null) {
            activeTunDescriptors.remove(pfd)
        }

        try {
            if (ctrl != null) {
                val stopJob = jniControlScope.launch {
                    runCatching {
                        if (ctrl.isRunning) ctrl.stopLoop()
                    }
                }
                val finishedEarly = withTimeoutOrNull(250L) {
                    stopJob.join()
                    true
                } ?: false
                runCatching { pfd?.close() }
                closeAllTunDescriptors()
                if (!finishedEarly) {
                    withTimeoutOrNull(950L) { stopJob.join() }
                }
            }
        } finally {
            runCatching { pfd?.close() }
            closeAllTunDescriptors()
        }
    }

    private fun forceReleaseNativeResources() {
        networkHandoffJob?.cancel()
        networkHandoffJob = null
        pendingHandoffSnapshot = null
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        runCatching { kotlinTunForwarder?.stop() }
        kotlinTunForwarder = null
        runCatching { localProxyServer?.stop() }
        localProxyServer = null
        runCatching { sshTunnelBridge?.stop() }
        sshTunnelBridge = null
        runCatching { tuicTunnelBridge?.stop() }
        tuicTunnelBridge = null
        closeAllTunDescriptors()
        val ctrl = coreControllerInstance
        coreControllerInstance = null
        if (ctrl != null) {
            jniControlScope.launch {
                runCatching { if (ctrl.isRunning) ctrl.stopLoop() }
            }
        }
    }

    private suspend fun stopPreviousEnginesSafely() = withContext(NonCancellable) {
        networkHandoffJob?.cancel()
        networkHandoffJob = null
        pendingHandoffSnapshot = null
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        runCatching { kotlinTunForwarder?.stop() }
        kotlinTunForwarder = null
        runCatching { localProxyServer?.stop() }
        localProxyServer = null
        stopNativeCoreAndTunSafely()
        runCatching { sshTunnelBridge?.stop() }
        sshTunnelBridge = null
        runCatching { tuicTunnelBridge?.stop() }
        tuicTunnelBridge = null
    }

    private fun stopTunnel() {
        val stopToken = activeSessionToken.incrementAndGet()
        handoffGeneration.incrementAndGet()
        bootstrapJob?.cancel()
        bootstrapJob = null
        activeStartJob?.cancel()
        activeStartJob = null
        if (!inHandoffCriticalSection.get()) {
            networkHandoffJob?.cancel()
            networkHandoffJob = null
        }
        pendingHandoffSnapshot = null
        activeTunnelSpec = null
        XrayEngineState.appendLog("INFO", "Stopping Xray session...")
        XrayEngineState.updateStatus(status = TunnelStatus.IDLE)
        NekoWidgetManager.refreshAll(this)
        serviceScope.launch {
            val lock = engineMutexRef.get()
            val acquired = withTimeoutOrNull(800L) {
                lock.lock()
                true
            } ?: false
            if (acquired) {
                try {
                    if (stopToken == activeSessionToken.get()) {
                        stopTunnelInternal()
                        NekoWidgetManager.refreshAll(this@XrayVpnService)
                    }
                } finally {
                    runCatching { if (lock.isLocked) lock.unlock() }
                }
            } else {
                // Mutex was held by a stalled native/handoff operation: force immediate TUN teardown & reset mutex
                XrayEngineState.appendLog("WARN", "Force-closing stalled VPN interface on stop.")
                if (stopToken == activeSessionToken.get()) {
                    forceReleaseNativeResources()
                    lastHandedOffSnapshot = null
                    pendingHandoffSnapshot = null
                    bindProcessToUnderlyingNetwork(null)
                    runCatching { setUnderlyingNetworks(null) }
                    engineMutexRef.compareAndSet(lock, Mutex())
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    runCatching { stopSelf() }
                    NekoWidgetManager.refreshAll(this@XrayVpnService)
                }
            }
        }
    }

    private suspend fun stopTunnelInternal() = withContext(NonCancellable) {
        stopPreviousEnginesSafely()
        lastHandedOffSnapshot = null
        pendingHandoffSnapshot = null
        bindProcessToUnderlyingNetwork(null)
        runCatching { setUnderlyingNetworks(null) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        XrayEngineState.appendLog("WARN", "VPN permission revoked by system.")
        systemStartedAlwaysOn = false
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (activeServiceInstance === this) {
            activeServiceInstance = null
        }
        activeSessionToken.incrementAndGet()
        handoffGeneration.incrementAndGet()
        bootstrapJob?.cancel()
        activeStartJob?.cancel()
        networkHandoffJob?.cancel()
        forceReleaseNativeResources()
        unregisterUnderlyingNetworkMonitor()
        unregisterPackageUpdateReceiver()
        if (XrayEngineState.state.value.status != TunnelStatus.ERROR) {
            XrayEngineState.updateStatus(status = TunnelStatus.IDLE)
        }
        NekoWidgetManager.refreshAll(this)
        serviceScope.cancel()
        jniControlScope.cancel()
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

        val alwaysOn = isAlwaysOnActive()
        val lockdown = isLockdownActive()
        val netLabel = XrayEngineState.state.value.underlyingNetworkLabel
        val modeBadge = buildString {
            append(if (proxyOnly) "Proxy Only" else "VPN · TUN")
            if (lockdown) append(" · Lockdown")
            else if (alwaysOn) append(" · Always-On")
            if (netLabel.isNotBlank()) append(" · $netLabel")
        }
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

        if (!alwaysOn) {
            builder.addAction(R.drawable.ic_notif_stop, getString(R.string.action_disconnect), stopPending)
        } else {
            val vpnSettingsPending = PendingIntent.getActivity(
                this,
                4,
                Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_notif_reconnect, "Always-On", vpnSettingsPending)
        }

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
