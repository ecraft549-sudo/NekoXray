package com.example.core.model

import kotlinx.serialization.Serializable

const val XRAY_CORE_VERSION = "v26.10.31"

@Serializable
enum class ProxyProtocol(val displayName: String, val scheme: String) {
    VLESS("VLESS", "vless"),
    VMESS("VMess", "vmess"),
    TROJAN("Trojan", "trojan"),
    SHADOWSOCKS("Shadowsocks", "ss"),
    SOCKS5("SOCKS5", "socks"),
    HTTP("HTTP", "http"),
    SSH("SSH", "ssh"),
    WIREGUARD("WireGuard", "wg"),
    CUSTOM_JSON("Xray JSON", "xray"),
}

@Serializable
enum class SshAuthType(val code: Int, val displayName: String) {
    NONE(0, "None"),
    PASSWORD(1, "Password"),
    PRIVATE_KEY(2, "Private Key"),
}

@Serializable
enum class SecurityType(val wireValue: String, val displayName: String) {
    NONE("none", "None"),
    TLS("tls", "TLS"),
    REALITY("reality", "REALITY"),
}

@Serializable
enum class TransportType(val wireValue: String, val displayName: String) {
    TCP("tcp", "TCP / Raw"),
    XHTTP("xhttp", "XHTTP (SplitHTTP)"),
    WS("ws", "WebSocket"),
    GRPC("grpc", "gRPC"),
    HTTPUPGRADE("httpupgrade", "HTTPUpgrade"),
    H2("http", "HTTP/2"),
}

@Serializable
enum class FlowType(val wireValue: String, val displayName: String) {
    NONE("", "None"),
    VISION("xtls-rprx-vision", "xtls-rprx-vision"),
    VISION_UDP443("xtls-rprx-vision-udp443", "xtls-rprx-vision-udp443"),
}

@Serializable
enum class UtlsFingerprint(val wireValue: String) {
    CHROME("chrome"),
    FIREFOX("firefox"),
    SAFARI("safari"),
    IOS("ios"),
    ANDROID("android"),
    EDGE("edge"),
    QQ("qq"),
    RANDOM("random"),
    RANDOMIZED("randomized"),
}

@Serializable
enum class ServiceMode(val displayName: String) {
    VPN("VPN (TUN)"),
    PROXY_ONLY("Proxy Only"),
}

@Serializable
enum class IPv6Mode(val wireStrategy: String, val displayName: String) {
    DISABLE("UseIPv4", "IPv4 Only"),
    ENABLE("UseIP", "Dual Stack (Auto)"),
    PREFER("UseIPv6", "Prefer IPv6"),
    ONLY("UseIPv6", "IPv6 Only"),
}

@Serializable
enum class RouteAction(val outboundTag: String, val displayName: String) {
    PROXY("proxy", "Proxy"),
    DIRECT("direct", "Direct"),
    BLOCK("block", "Block"),
}

@Serializable
enum class AppLanguage(val code: String, val nativeName: String) {
    SYSTEM("system", "System / Системный"),
    EN("en", "English"),
    RU("ru", "Русский"),
}

@Serializable
enum class PerAppProxyMode(val displayName: String, val description: String) {
    ALL("All Apps", "Route all device apps through Xray"),
    BYPASS_SELECTED("Bypass Selected", "Exclude chosen apps from VPN"),
    PROXY_SELECTED("Only Selected", "Only chosen apps use Xray VPN"),
}

@Serializable
data class ProxyProfile(
    val id: Long = 0L,
    val groupId: Long = 1L,
    val name: String = "",
    val protocol: ProxyProtocol = ProxyProtocol.VLESS,
    val serverAddress: String = "",
    val serverPort: Int = 443,

    // Auth / Identity
    val uuid: String = "",
    val password: String = "",
    val username: String = "",
    val alterId: Int = 0,
    val encryption: String = "none", // VLESS encryption ("none" or ML-KEM) or VMess/SS cipher
    val flow: FlowType = FlowType.VISION,

    // Transport
    val transport: TransportType = TransportType.TCP,
    val host: String = "",
    val path: String = "",
    val grpcAuthority: String = "",
    val xhttpMode: String = "auto", // auto, packet-up, stream-up, stream-one
    val xhttpExtra: String = "",
    val headerType: String = "none",

    // Security & TLS
    val security: SecurityType = SecurityType.REALITY,
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = UtlsFingerprint.CHROME.wireValue,
    val allowInsecure: Boolean = false,

    // REALITY (Xray-core latest)
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "/",
    val realityMldsa65Verify: String = "",

    // WireGuard fields
    val wgPrivateKey: String = "",
    val wgPeerPublicKey: String = "",
    val wgPreSharedKey: String = "",
    val wgLocalAddress: String = "10.0.0.2/32",
    val wgMtu: Int = 1420,
    val wgReserved: String = "",

    // SSH fields (NekoBox SSHBean parity)
    val sshAuthType: SshAuthType = SshAuthType.PASSWORD,
    val sshPrivateKey: String = "",
    val sshPrivateKeyPassphrase: String = "",
    val sshPublicKey: String = "",

    // Mux & Sockopt
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 8,
    val packetEncoding: String = "xudp",

    // Custom Raw Xray Config JSON
    val customConfigJson: String = "",

    // Telemetry
    val latencyMs: Int? = null,
    val tlsLatencyMs: Int? = null,
    val lastTestedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isReality: Boolean
        get() = security == SecurityType.REALITY

    fun displayTitle(): String = name.ifBlank {
        if (serverAddress.isNotBlank()) "$serverAddress:$serverPort" else protocol.displayName
    }
}

@Serializable
data class ProxyGroup(
    val id: Long = 0L,
    val name: String = "Default",
    val isSubscription: Boolean = false,
    val subscriptionUrl: String = "",
    val userAgent: String = "NekoXray/$XRAY_CORE_VERSION",
    val autoUpdate: Boolean = false,
    val lastUpdatedAt: Long? = null,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestampSec: Long = 0L,
)

@Serializable
data class RoutingRule(
    val id: Long = 0L,
    val name: String = "",
    val enabled: Boolean = true,
    val domains: String = "", // comma or newline separated
    val ips: String = "",     // comma or newline separated
    val ports: String = "",   // e.g. 53,443,8000-9000
    val protocols: String = "", // http,tls,bittorrent
    val action: RouteAction = RouteAction.DIRECT,
    val priority: Int = 0,
)

@Serializable
data class XraySettings(
    val serviceMode: ServiceMode = ServiceMode.VPN,
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val mtu: Int = 1500,
    val ipv6Mode: IPv6Mode = IPv6Mode.ENABLE,
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "8.8.8.8",
    val bypassLan: Boolean = true,
    val sniffingEnabled: Boolean = true,
    val routeOnly: Boolean = false,
    val domainStrategy: String = "IPIfNonMatch",
    val fragmentEnabled: Boolean = false,
    val fragmentPackets: String = "tlshello",
    val fragmentLength: String = "100-200",
    val fragmentInterval: String = "10-20",
    val globalAllowInsecure: Boolean = false,
    val logLevel: String = "warning",
    val darkTheme: Boolean = true,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val trayWidgetEnabled: Boolean = false,
    val perAppMode: PerAppProxyMode = PerAppProxyMode.ALL,
    val perAppPackages: Set<String> = emptySet(),
)

data class ConfigValidationIssue(
    val isError: Boolean,
    val message: String,
)
