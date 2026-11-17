package com.example.core.xray

import com.example.core.crypto.RealityKeyGenerator
import com.example.core.model.ConfigValidationIssue
import com.example.core.model.FlowType
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.RouteAction
import com.example.core.model.RoutingRule
import com.example.core.model.SecurityType
import com.example.core.model.ServiceMode
import com.example.core.model.SshAuthType
import com.example.core.model.TransportType
import com.example.core.model.XRAY_CORE_VERSION
import com.example.core.model.XraySettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Generates Xray-core v26.10.31 JSON configuration from ProxyProfile models.
 * Includes full REALITY, XTLS-RPRX-Vision, XHTTP (SplitHTTP), TLS Fragment, DNS, and Routing.
 */
object XrayConfigBuilder {

    private val prettyJson = Json { prettyPrint = true }

    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "fc00::/7",
        "fe80::/10",
        "::1/128",
    )

    fun buildConfigJson(
        profile: ProxyProfile,
        settings: XraySettings,
        rules: List<RoutingRule>,
        includeTunInbound: Boolean = settings.serviceMode == ServiceMode.VPN,
        sshLocalBridgePort: Int = 0,
        tuicLocalBridgePort: Int = 0,
        hasGeoIp: Boolean = true,
        hasGeoSite: Boolean = true,
    ): String {
        if (profile.protocol == ProxyProtocol.CUSTOM_JSON && profile.customConfigJson.isNotBlank()) {
            return runCatching {
                val parsed = prettyJson.parseToJsonElement(profile.customConfigJson)
                prettyJson.encodeToString(JsonElement.serializer(), parsed)
            }.getOrDefault(profile.customConfigJson)
        }

        val root = buildJsonObject {
            putJsonObject("log") {
                put("loglevel", settings.logLevel)
            }

            putJsonObject("stats") {}
            putJsonObject("api") {
                put("tag", "api")
                putJsonArray("services") {
                    add("StatsService")
                }
            }
            putJsonObject("policy") {
                putJsonObject("levels") {
                    putJsonObject("0") {
                        put("statsUserUplink", true)
                        put("statsUserDownlink", true)
                    }
                }
                putJsonObject("system") {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                }
            }

            val directDnsServer = settings.directDns.trim()
                .takeIf { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
                ?: "8.8.8.8"
            val effectiveDirectDnsAddress = resolveEffectiveDirectDnsAddress(
                raw = directDnsServer,
                protectDoh = settings.protectDirectDns,
            )
            val remoteDnsServer = settings.remoteDns.ifBlank { "https://1.1.1.1/dns-query" }
            val directRuleDomains = rules
                .filter { it.enabled && it.action == RouteAction.DIRECT }
                .flatMap { splitItems(it.domains) }
                .map { normalizeDomainPattern(it) }
                .filter { it.isNotBlank() && (hasGeoSite || !it.startsWith("geosite:", ignoreCase = true)) }
                .distinct()

            val effectiveQueryStrategy = if (
                profile.protocol == ProxyProtocol.SSH &&
                settings.ipv6Mode != com.example.core.model.IPv6Mode.ONLY
            ) {
                "UseIPv4"
            } else {
                settings.ipv6Mode.wireStrategy
            }

            val serverHost = profile.serverAddress.trim()
            val needsServerBootstrapDns = serverHost.isNotBlank() && !isIpLiteral(serverHost)

            putJsonObject("dns") {
                put("queryStrategy", effectiveQueryStrategy)
                putJsonArray("servers") {
                    if (profile.protocol == ProxyProtocol.SSH) {
                        add("tcp://1.1.1.1:53")
                        add("tcp://8.8.8.8:53")
                    }
                    add(remoteDnsServer)
                    if (settings.directDnsEnabled) {
                        addJsonObject {
                            put("address", effectiveDirectDnsAddress)
                            putJsonArray("domains") {
                                if (hasGeoSite) {
                                    add("geosite:private")
                                } else {
                                    add("domain:local")
                                }
                                if (needsServerBootstrapDns) {
                                    add("full:$serverHost")
                                }
                                directRuleDomains.forEach { add(it) }
                            }
                            put("skipFallback", true)
                        }
                    } else if (needsServerBootstrapDns) {
                        // When Direct DNS is disabled for user traffic, still bootstrap the proxy server's own hostname
                        // so Xray can establish the tunnel without a circular DNS dependency.
                        addJsonObject {
                            put("address", effectiveDirectDnsAddress)
                            putJsonArray("domains") {
                                add("full:$serverHost")
                            }
                            put("skipFallback", true)
                        }
                    }
                }
            }

            putJsonArray("inbounds") {
                if (includeTunInbound) {
                    addJsonObject {
                        put("tag", "tun-in")
                        put("port", 0)
                        put("protocol", "tun")
                        putJsonObject("settings") {
                            put("name", "xray0")
                            put("MTU", settings.mtu.coerceIn(1280, 9000))
                            put("userLevel", 0)
                        }
                        putJsonObject("sniffing") {
                            put("enabled", settings.sniffingEnabled)
                            put("routeOnly", settings.routeOnly)
                            putJsonArray("destOverride") {
                                add("http")
                                add("tls")
                                add("quic")
                            }
                        }
                    }
                }
                addJsonObject {
                    put("tag", "socks-in")
                    put("listen", "127.0.0.1")
                    put("port", settings.socksPort)
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        put("auth", "noauth")
                        put("udp", true)
                    }
                    putJsonObject("sniffing") {
                        put("enabled", settings.sniffingEnabled)
                        put("routeOnly", settings.routeOnly)
                        putJsonArray("destOverride") {
                            add("http")
                            add("tls")
                            add("quic")
                        }
                    }
                }
                addJsonObject {
                    put("tag", "http-in")
                    put("listen", "127.0.0.1")
                    put("port", settings.httpPort)
                    put("protocol", "http")
                    putJsonObject("sniffing") {
                        put("enabled", settings.sniffingEnabled)
                        put("routeOnly", settings.routeOnly)
                        putJsonArray("destOverride") {
                            add("http")
                            add("tls")
                        }
                    }
                }
            }

            putJsonArray("outbounds") {
                add(buildPrimaryOutbound(profile, settings, sshLocalBridgePort, tuicLocalBridgePort))

                if (settings.fragmentEnabled) {
                    addJsonObject {
                        put("tag", "fragment")
                        put("protocol", "freedom")
                        putJsonObject("settings") {
                            putJsonObject("fragment") {
                                put("packets", settings.fragmentPackets)
                                put("length", settings.fragmentLength)
                                put("interval", settings.fragmentInterval)
                            }
                        }
                        putJsonObject("streamSettings") {
                            putJsonObject("sockopt") {
                                put("TcpNoDelay", true)
                            }
                        }
                    }
                }

                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    putJsonObject("settings") {
                        put("domainStrategy", settings.ipv6Mode.wireStrategy)
                    }
                }

                addJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                    putJsonObject("settings") {
                        putJsonObject("response") {
                            put("type", "http")
                        }
                    }
                }

                addJsonObject {
                    put("tag", "dns-out")
                    put("protocol", "dns")
                    putJsonObject("settings") {
                        put("nonIPQuery", "reject")
                    }
                }
            }

            putJsonObject("routing") {
                put("domainStrategy", settings.domainStrategy)
                putJsonArray("rules") {
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("inboundTag") {
                            if (includeTunInbound) add("tun-in")
                            add("socks-in")
                            add("http-in")
                        }
                        put("port", "53")
                        put("outboundTag", "dns-out")
                    }

                    val remoteDnsHost = extractHostFromUrl(remoteDnsServer)
                    val directDnsHost = extractHostFromUrl(directDnsServer)
                    if (
                        (settings.directDnsEnabled || needsServerBootstrapDns) &&
                        isIpLiteral(directDnsHost) &&
                        !directDnsHost.equals(remoteDnsHost, ignoreCase = true)
                    ) {
                        addJsonObject {
                            put("type", "field")
                            putJsonArray("ip") { add(directDnsHost) }
                            put("port", "53")
                            put("outboundTag", "direct")
                        }
                        if (effectiveDirectDnsAddress.startsWith("https", ignoreCase = true)) {
                            addJsonObject {
                                put("type", "field")
                                putJsonArray("ip") { add(directDnsHost) }
                                put("port", "443")
                                put("outboundTag", "direct")
                            }
                        }
                    }

                    if (settings.bypassLan) {
                        addJsonObject {
                            put("type", "field")
                            putJsonArray("ip") {
                                if (hasGeoIp) {
                                    add("geoip:private")
                                } else {
                                    PRIVATE_CIDRS.forEach { add(it) }
                                }
                            }
                            put("outboundTag", "direct")
                        }
                    }

                    rules.filter { it.enabled }.sortedBy { it.priority }.forEach { rule ->
                        val domainList = splitItems(rule.domains)
                            .map { normalizeDomainPattern(it) }
                            .filter { it.isNotBlank() && (hasGeoSite || !it.startsWith("geosite:", ignoreCase = true)) }
                            .distinct()
                        val ipList = splitItems(rule.ips)
                            .filter { hasGeoIp || !it.startsWith("geoip:", ignoreCase = true) }
                        val protoList = splitItems(rule.protocols)

                        if (domainList.isNotEmpty() && ipList.isNotEmpty()) {
                            // Emit separate domain and ip rules so matching either triggers the action (OR semantics)
                            addJsonObject {
                                put("type", "field")
                                putJsonArray("domain") { domainList.forEach { add(it) } }
                                if (rule.ports.isNotBlank()) put("port", rule.ports.trim())
                                if (protoList.isNotEmpty()) putJsonArray("protocol") { protoList.forEach { add(it) } }
                                put("outboundTag", rule.action.outboundTag)
                            }
                            addJsonObject {
                                put("type", "field")
                                putJsonArray("ip") { ipList.forEach { add(it) } }
                                if (rule.ports.isNotBlank()) put("port", rule.ports.trim())
                                if (protoList.isNotEmpty()) putJsonArray("protocol") { protoList.forEach { add(it) } }
                                put("outboundTag", rule.action.outboundTag)
                            }
                        } else if (domainList.isNotEmpty() || ipList.isNotEmpty() ||
                            rule.ports.isNotBlank() || protoList.isNotEmpty()
                        ) {
                            addJsonObject {
                                put("type", "field")
                                if (domainList.isNotEmpty()) {
                                    putJsonArray("domain") { domainList.forEach { add(it) } }
                                }
                                if (ipList.isNotEmpty()) {
                                    putJsonArray("ip") { ipList.forEach { add(it) } }
                                }
                                if (rule.ports.isNotBlank()) {
                                    put("port", rule.ports.trim())
                                }
                                if (protoList.isNotEmpty()) {
                                    putJsonArray("protocol") { protoList.forEach { add(it) } }
                                }
                                put("outboundTag", rule.action.outboundTag)
                            }
                        }
                    }

                    addJsonObject {
                        put("type", "field")
                        put("network", "tcp,udp")
                        put("outboundTag", "proxy")
                    }
                }
            }
        }

        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    private fun buildPrimaryOutbound(
        profile: ProxyProfile,
        settings: XraySettings,
        sshLocalBridgePort: Int = 0,
        tuicLocalBridgePort: Int = 0,
    ): JsonObject {
        return buildJsonObject {
            put("tag", "proxy")
            when (profile.protocol) {
                ProxyProtocol.VLESS -> {
                    put("protocol", "vless")
                    putJsonObject("settings") {
                        putJsonArray("vnext") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                putJsonArray("users") {
                                    addJsonObject {
                                        put("id", profile.uuid)
                                        put("encryption", profile.encryption.ifBlank { "none" })
                                        if (profile.flow != FlowType.NONE) {
                                            put("flow", profile.flow.wireValue)
                                        }
                                        put("level", 0)
                                    }
                                }
                            }
                        }
                    }
                    put("streamSettings", buildStreamSettings(profile, settings))
                }

                ProxyProtocol.VMESS -> {
                    put("protocol", "vmess")
                    putJsonObject("settings") {
                        putJsonArray("vnext") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                putJsonArray("users") {
                                    addJsonObject {
                                        put("id", profile.uuid)
                                        put("alterId", profile.alterId)
                                        put("security", profile.encryption.ifBlank { "auto" })
                                        put("level", 0)
                                    }
                                }
                            }
                        }
                    }
                    put("streamSettings", buildStreamSettings(profile, settings))
                }

                ProxyProtocol.TROJAN -> {
                    put("protocol", "trojan")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                put("password", profile.password)
                                put("level", 0)
                            }
                        }
                    }
                    put("streamSettings", buildStreamSettings(profile, settings))
                }

                ProxyProtocol.SHADOWSOCKS -> {
                    put("protocol", "shadowsocks")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                put("method", profile.encryption.ifBlank { "2022-blake3-aes-128-gcm" })
                                put("password", profile.password)
                                put("uot", true)
                            }
                        }
                    }
                }

                ProxyProtocol.SOCKS5 -> {
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                if (profile.username.isNotBlank()) {
                                    putJsonArray("users") {
                                        addJsonObject {
                                            put("user", profile.username)
                                            put("pass", profile.password)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ProxyProtocol.HTTP -> {
                    put("protocol", "http")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                if (profile.username.isNotBlank()) {
                                    putJsonArray("users") {
                                        addJsonObject {
                                            put("user", profile.username)
                                            put("pass", profile.password)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (profile.security != SecurityType.NONE) {
                        put("streamSettings", buildStreamSettings(profile, settings))
                    }
                }

                ProxyProtocol.HYSTERIA2 -> {
                    put("protocol", "hysteria")
                    putJsonObject("settings") {
                        put("version", 2)
                        put("address", profile.serverAddress)
                        put("port", profile.serverPort)
                    }
                    put("streamSettings", buildHysteria2StreamSettings(profile, settings))
                }

                ProxyProtocol.TUIC -> {
                    val bridgePort = if (tuicLocalBridgePort > 0) tuicLocalBridgePort else settings.socksPort + 101
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", "127.0.0.1")
                                put("port", bridgePort)
                            }
                        }
                    }
                }

                ProxyProtocol.SSH -> {
                    val bridgePort = if (sshLocalBridgePort > 0) sshLocalBridgePort else settings.socksPort + 100
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", "127.0.0.1")
                                put("port", bridgePort)
                            }
                        }
                    }
                }

                ProxyProtocol.WIREGUARD -> {
                    put("protocol", "wireguard")
                    putJsonObject("settings") {
                        put("secretKey", profile.wgPrivateKey)
                        putJsonArray("address") {
                            splitItems(profile.wgLocalAddress.ifBlank { "10.0.0.2/32" }).forEach { add(it) }
                        }
                        put("mtu", profile.wgMtu)
                        if (profile.wgReserved.isNotBlank()) {
                            val ints = profile.wgReserved.split(",").mapNotNull { it.trim().toIntOrNull() }
                            if (ints.size == 3) {
                                putJsonArray("reserved") { ints.forEach { add(it) } }
                            }
                        }
                        putJsonArray("peers") {
                            addJsonObject {
                                put("publicKey", profile.wgPeerPublicKey)
                                if (profile.wgPreSharedKey.isNotBlank()) {
                                    put("preSharedKey", profile.wgPreSharedKey)
                                }
                                put("endpoint", "${profile.serverAddress}:${profile.serverPort}")
                                putJsonArray("allowedIPs") {
                                    add("0.0.0.0/0")
                                    add("::/0")
                                }
                            }
                        }
                    }
                }

                ProxyProtocol.CUSTOM_JSON -> Unit
            }

            if (profile.muxEnabled && profile.flow == FlowType.NONE &&
                profile.protocol in listOf(ProxyProtocol.VLESS, ProxyProtocol.VMESS, ProxyProtocol.TROJAN)
            ) {
                putJsonObject("mux") {
                    put("enabled", true)
                    put("concurrency", profile.muxConcurrency.coerceIn(1, 128))
                    put("xudpConcurrency", 16)
                    put("xudpProxyUDP443", "reject")
                }
            }
        }
    }

    private fun buildStreamSettings(profile: ProxyProfile, settings: XraySettings): JsonObject {
        return buildJsonObject {
            put("network", profile.transport.wireValue)
            put("security", profile.security.wireValue)

            when (profile.security) {
                SecurityType.REALITY -> {
                    putJsonObject("realitySettings") {
                        put("show", false)
                        put("fingerprint", profile.fingerprint.ifBlank { "chrome" })
                        put("serverName", profile.sni)
                        put("publicKey", profile.realityPublicKey)
                        put("shortId", profile.realityShortId)
                        put("spiderX", profile.realitySpiderX.ifBlank { "/" })
                        if (profile.realityMldsa65Verify.isNotBlank()) {
                            put("mldsa65Verify", profile.realityMldsa65Verify)
                        }
                    }
                }

                SecurityType.TLS -> {
                    putJsonObject("tlsSettings") {
                        if (profile.sni.isNotBlank()) put("serverName", profile.sni)
                        if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
                        if (profile.allowInsecure || settings.globalAllowInsecure) {
                            put("allowInsecure", true)
                        }
                        if (profile.alpn.isNotBlank()) {
                            putJsonArray("alpn") {
                                splitItems(profile.alpn).forEach { add(it) }
                            }
                        }
                    }
                }

                SecurityType.NONE -> Unit
            }

            when (profile.transport) {
                TransportType.TCP -> {
                    if (profile.headerType == "http") {
                        putJsonObject("tcpSettings") {
                            putJsonObject("header") {
                                put("type", "http")
                                if (profile.host.isNotBlank()) {
                                    putJsonObject("request") {
                                        putJsonObject("headers") {
                                            putJsonArray("Host") {
                                                splitItems(profile.host).forEach { add(it) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                TransportType.XHTTP -> {
                    putJsonObject("xhttpSettings") {
                        if (profile.host.isNotBlank()) put("host", profile.host)
                        put("path", profile.path.ifBlank { "/" })
                        put("mode", profile.xhttpMode.ifBlank { "auto" })
                        if (profile.xhttpExtra.isNotBlank()) {
                            runCatching {
                                put("extra", prettyJson.parseToJsonElement(profile.xhttpExtra))
                            }
                        }
                    }
                }

                TransportType.WS -> {
                    putJsonObject("wsSettings") {
                        put("path", profile.path.ifBlank { "/" })
                        if (profile.host.isNotBlank()) {
                            put("host", profile.host)
                            putJsonObject("headers") {
                                put("Host", profile.host)
                            }
                        }
                    }
                }

                TransportType.GRPC -> {
                    putJsonObject("grpcSettings") {
                        put("serviceName", profile.path)
                        if (profile.grpcAuthority.isNotBlank()) {
                            put("authority", profile.grpcAuthority)
                        }
                    }
                }

                TransportType.HTTPUPGRADE -> {
                    putJsonObject("httpupgradeSettings") {
                        put("path", profile.path.ifBlank { "/" })
                        if (profile.host.isNotBlank()) put("host", profile.host)
                    }
                }

                TransportType.H2 -> {
                    putJsonObject("httpSettings") {
                        put("path", profile.path.ifBlank { "/" })
                        if (profile.host.isNotBlank()) {
                            putJsonArray("host") {
                                splitItems(profile.host).forEach { add(it) }
                            }
                        }
                    }
                }
            }

            putJsonObject("sockopt") {
                put("domainStrategy", settings.ipv6Mode.wireStrategy)
                if (settings.fragmentEnabled && profile.security != SecurityType.NONE) {
                    put("dialerProxy", "fragment")
                }
            }
        }
    }

    private fun buildHysteria2StreamSettings(profile: ProxyProfile, settings: XraySettings): JsonObject {
        return buildJsonObject {
            put("network", "hysteria")
            put("security", "tls")

            putJsonObject("tlsSettings") {
                val sni = profile.sni.ifBlank { profile.serverAddress }
                if (sni.isNotBlank()) put("serverName", sni)
                if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
                val alpns = splitItems(profile.alpn).ifEmpty { listOf("h3") }
                putJsonArray("alpn") { alpns.forEach { add(it) } }
                if (profile.allowInsecure || settings.globalAllowInsecure) {
                    put("allowInsecure", true)
                }
                if (profile.hy2PinSha256.isNotBlank()) {
                    put("pinnedPeerCertSha256", profile.hy2PinSha256.trim())
                }
            }

            putJsonObject("hysteriaSettings") {
                put("version", 2)
                if (profile.password.isNotBlank()) {
                    put("auth", profile.password)
                }
            }

            val hasSalamander = profile.hy2Obfs.equals("salamander", ignoreCase = true) &&
                profile.hy2ObfsPassword.isNotBlank()
            val hasUdpHop = profile.hy2Ports.isNotBlank()
            val hasBrutal = profile.hy2UpMbps > 0 || profile.hy2DownMbps > 0
            if (hasSalamander || hasUdpHop || hasBrutal) {
                putJsonObject("finalmask") {
                    if (hasSalamander || hasUdpHop) {
                        putJsonArray("udp") {
                            if (hasSalamander) {
                                addJsonObject {
                                    put("type", "salamander")
                                    putJsonObject("settings") {
                                        put("password", profile.hy2ObfsPassword)
                                    }
                                }
                            }
                            if (hasUdpHop) {
                                addJsonObject {
                                    put("type", "udphop")
                                    putJsonObject("settings") {
                                        put("port", profile.hy2Ports.trim())
                                        put("interval", profile.hy2HopInterval.coerceIn(5, 3600).toString())
                                    }
                                }
                            }
                        }
                    }
                    if (hasBrutal) {
                        putJsonObject("quicParams") {
                            put("congestion", "brutal")
                            if (profile.hy2UpMbps > 0) {
                                put("brutalUp", "${profile.hy2UpMbps} mbps")
                            }
                            if (profile.hy2DownMbps > 0) {
                                put("brutalDown", "${profile.hy2DownMbps} mbps")
                            }
                        }
                    }
                }
            }

            putJsonObject("sockopt") {
                put("domainStrategy", settings.ipv6Mode.wireStrategy)
            }
        }
    }

    fun buildRealityServerSnippet(
        uuid: String,
        privateKey: String,
        shortId: String,
        dest: String = "www.microsoft.com:443",
        serverName: String = "www.microsoft.com",
    ): String {
        val obj = buildJsonObject {
            put("core", "Xray-core $XRAY_CORE_VERSION")
            putJsonArray("inbounds") {
                addJsonObject {
                    put("listen", "0.0.0.0")
                    put("port", 443)
                    put("protocol", "vless")
                    putJsonObject("settings") {
                        putJsonArray("clients") {
                            addJsonObject {
                                put("id", uuid)
                                put("flow", "xtls-rprx-vision")
                            }
                        }
                        put("decryption", "none")
                    }
                    putJsonObject("streamSettings") {
                        put("network", "tcp")
                        put("security", "reality")
                        putJsonObject("realitySettings") {
                            put("show", false)
                            put("dest", dest)
                            put("xver", 0)
                            putJsonArray("serverNames") { add(serverName) }
                            put("privateKey", privateKey)
                            putJsonArray("shortIds") { add(shortId) }
                        }
                    }
                }
            }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("protocol", "freedom")
                    put("tag", "direct")
                }
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), obj)
    }

    fun validateProfile(profile: ProxyProfile): List<ConfigValidationIssue> {
        val issues = mutableListOf<ConfigValidationIssue>()
        if (profile.protocol == ProxyProtocol.CUSTOM_JSON) {
            if (profile.customConfigJson.isBlank()) {
                issues += ConfigValidationIssue(true, "Custom Xray JSON config is empty.")
            } else {
                val ok = runCatching { prettyJson.parseToJsonElement(profile.customConfigJson) }.isSuccess
                if (!ok) issues += ConfigValidationIssue(true, "Custom JSON contains syntax errors.")
            }
            return issues
        }

        if (profile.serverAddress.isBlank()) {
            issues += ConfigValidationIssue(true, "Server address is required.")
        }
        if (profile.serverPort !in 1..65535) {
            issues += ConfigValidationIssue(true, "Server port must be between 1 and 65535.")
        }
        if (profile.protocol in listOf(ProxyProtocol.VLESS, ProxyProtocol.VMESS) && profile.uuid.isBlank()) {
            issues += ConfigValidationIssue(true, "UUID is required for ${profile.protocol.displayName}.")
        }
        if (profile.protocol == ProxyProtocol.TROJAN && profile.password.isBlank()) {
            issues += ConfigValidationIssue(true, "Password is required for Trojan.")
        }
        if (profile.protocol == ProxyProtocol.SSH) {
            if (profile.username.isBlank()) {
                issues += ConfigValidationIssue(true, "SSH username is required (e.g. root).")
            }
            when (profile.sshAuthType) {
                SshAuthType.PASSWORD -> if (profile.password.isBlank()) {
                    issues += ConfigValidationIssue(true, "SSH password is required for Password authentication.")
                }
                SshAuthType.PRIVATE_KEY -> if (profile.sshPrivateKey.isBlank()) {
                    issues += ConfigValidationIssue(true, "SSH Private Key (PEM / OpenSSH) is required.")
                }
                SshAuthType.NONE -> Unit
            }
        }
        if (profile.protocol == ProxyProtocol.HYSTERIA2) {
            if (profile.password.isBlank()) {
                issues += ConfigValidationIssue(false, "Hysteria 2 auth password is empty.")
            }
            if (profile.hy2Obfs.equals("salamander", ignoreCase = true) && profile.hy2ObfsPassword.isBlank()) {
                issues += ConfigValidationIssue(true, "Salamander obfs password is required when Salamander obfuscation is enabled.")
            }
            if (profile.sni.isBlank() && isIpLiteral(profile.serverAddress.trim()) &&
                !profile.allowInsecure && profile.hy2PinSha256.isBlank()
            ) {
                issues += ConfigValidationIssue(
                    false,
                    "Connecting by IP without SNI or pinSHA256 usually requires 'Allow Insecure TLS' if the certificate doesn't match the IP.",
                )
            }
        }
        if (profile.protocol == ProxyProtocol.TUIC) {
            if (profile.uuid.isBlank()) {
                issues += ConfigValidationIssue(true, "UUID is required for TUIC v5.")
            } else if (runCatching { java.util.UUID.fromString(profile.uuid.trim()) }.isFailure) {
                issues += ConfigValidationIssue(true, "TUIC v5 UUID must be a valid RFC 4122 UUID.")
            }
            if (profile.password.isBlank()) {
                issues += ConfigValidationIssue(false, "TUIC v5 password is empty.")
            }
        }

        if (profile.security == SecurityType.REALITY) {
            if (!RealityKeyGenerator.isValidRealityPublicKey(profile.realityPublicKey)) {
                issues += ConfigValidationIssue(true, "REALITY Public Key (pbk) must be a valid 32-byte base64url X25519 key.")
            }
            if (!RealityKeyGenerator.isValidShortId(profile.realityShortId)) {
                issues += ConfigValidationIssue(true, "REALITY Short ID (sid) must be 0-16 hex characters (even length).")
            }
            if (profile.sni.isBlank()) {
                issues += ConfigValidationIssue(true, "REALITY Server Name (SNI) is required (e.g. www.microsoft.com).")
            }
            if (profile.fingerprint.isBlank()) {
                issues += ConfigValidationIssue(false, "uTLS fingerprint is blank; Xray recommends 'chrome' or 'randomized' for REALITY.")
            }
            if (profile.flow != FlowType.NONE && profile.transport != TransportType.TCP) {
                issues += ConfigValidationIssue(false, "XTLS Vision flow is only valid with TCP/Raw transport; Xray ignores or rejects it on ${profile.transport.displayName}.")
            }
        }
        return issues
    }

    private fun splitItems(raw: String): List<String> =
        raw.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun normalizeDomainPattern(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("geosite:") || lower.startsWith("regexp:") || lower.startsWith("ext:") -> trimmed
            lower.startsWith("domain:") -> {
                val host = trimmed.substringAfter(":").trim().trimStart('*', '.')
                if (host.isNotEmpty()) "domain:$host" else ""
            }
            lower.startsWith("full:") -> {
                val host = trimmed.substringAfter(":").trim().trimStart('.')
                if (host.isNotEmpty()) "full:$host" else ""
            }
            lower.startsWith("keyword:") -> {
                val kw = trimmed.substringAfter(":").trim()
                kw
            }
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                val host = extractHostFromUrl(trimmed).trimStart('*', '.')
                if (host.isNotEmpty()) "domain:$host" else ""
            }
            trimmed.startsWith("*.") || trimmed.startsWith(".") -> {
                val host = trimmed.trimStart('*', '.')
                if (host.isNotEmpty()) "domain:$host" else ""
            }
            trimmed.contains('.') && !trimmed.contains(':') && !trimmed.contains('/') -> {
                "domain:${trimmed.lowercase()}"
            }
            else -> trimmed
        }
    }

    fun resolveEffectiveDirectDnsAddress(raw: String, protectDoh: Boolean): String {
        val cleaned = raw.trim().takeIf { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
            ?: "8.8.8.8"
        if (!protectDoh) {
            return if (cleaned.startsWith("https+local://", ignoreCase = true)) {
                val host = extractHostFromUrl(cleaned)
                if (isIpLiteral(host)) host else "https://" + cleaned.substringAfter("://")
            } else {
                cleaned
            }
        }
        val lower = cleaned.lowercase()
        return when {
            lower.startsWith("https+local://") || lower.startsWith("quic+local://") -> cleaned
            lower.startsWith("https://") -> "https+local://" + cleaned.substringAfter("://")
            lower.startsWith("tcp://") || lower.startsWith("tcp+local://") -> cleaned
            isIpLiteral(cleaned) -> {
                if (isPrivateOrLoopbackIp(cleaned)) {
                    cleaned
                } else {
                    val hostLiteral = if (cleaned.contains(':') && !cleaned.startsWith("[")) "[$cleaned]" else cleaned
                    "https+local://$hostLiteral/dns-query"
                }
            }
            else -> cleaned
        }
    }

    private fun isPrivateOrLoopbackIp(ip: String): Boolean {
        val normalized = ip.trim().removePrefix("[").removeSuffix("]").lowercase()
        return normalized.startsWith("10.") ||
            normalized.startsWith("192.168.") ||
            normalized.startsWith("127.") ||
            normalized.startsWith("169.254.") ||
            normalized == "::1" ||
            normalized.startsWith("fe80:") ||
            normalized.startsWith("fc") ||
            normalized.startsWith("fd") ||
            Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\..*").matches(normalized)
    }

    private fun extractHostFromUrl(urlOrHost: String): String {
        val noScheme = urlOrHost.substringAfter("://", urlOrHost)
        val authority = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }.trim()
    }

    private fun isIpLiteral(host: String): Boolean {
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4.matches(host) || host.contains(':')
    }
}
