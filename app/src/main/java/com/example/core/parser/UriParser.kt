package com.example.core.parser

import android.net.Uri
import android.util.Base64
import com.example.core.model.FlowType
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.SecurityType
import com.example.core.model.SshAuthType
import com.example.core.model.TransportType
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class ParsedSubscription(
    val url: String,
    val name: String,
)

/**
 * Clean-room Kotlin URI parser and exporter compatible with V2Ray, NekoBox, Clash, and Xray
 * share links for Xray-core v26.10.31 with REALITY & XHTTP (SplitHTTP) URI support.
 */
object UriParser {

    private val explicitProxyPorts = setOf(1080, 1081, 3128, 8080, 8081, 8118, 8888, 9050)

    fun parseSubscriptions(rawInput: String): List<ParsedSubscription> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || (trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            return emptyList()
        }
        return trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { parseSubscriptionLink(it) }
            .distinctBy { it.url }
    }

    fun parseSubscriptionLink(link: String): ParsedSubscription? {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            when {
                trimmed.startsWith("sub://", ignoreCase = true) -> {
                    val withoutScheme = trimmed.substring(6)
                    val mainPart = withoutScheme.substringBefore("#")
                    val fragName = withoutScheme.substringAfter("#", "").let { urlDecode(it) }.trim()
                    val decoded = if (mainPart.startsWith("http://", true) || mainPart.startsWith("https://", true)) {
                        mainPart
                    } else {
                        tryDecodeBase64(mainPart)?.trim()
                    }
                    if (!decoded.isNullOrBlank() &&
                        (decoded.startsWith("http://", true) || decoded.startsWith("https://", true))
                    ) {
                        val inner = Uri.parse(decoded)
                        val innerFrag = inner.fragment?.let { urlDecode(it) }.orEmpty().trim()
                        val cleanUrl = decoded.substringBefore("#").trim()
                        val finalName = fragName.ifBlank { innerFrag }.ifBlank { inner.host.orEmpty() }.ifBlank { "Subscription" }
                        ParsedSubscription(url = cleanUrl, name = finalName)
                    } else {
                        null
                    }
                }

                trimmed.startsWith("v2rayng://", ignoreCase = true) ||
                    trimmed.startsWith("clash://", ignoreCase = true) ||
                    trimmed.startsWith("sing-box://", ignoreCase = true) ||
                    trimmed.startsWith("nekoray://", ignoreCase = true) ||
                    trimmed.startsWith("nekobox://", ignoreCase = true) -> {
                    val uri = Uri.parse(trimmed)
                    val rawTarget = uri.getQueryParameter("url").orEmpty().trim()
                    if (rawTarget.startsWith("http://", true) || rawTarget.startsWith("https://", true)) {
                        val targetUri = Uri.parse(rawTarget)
                        val name = uri.getQueryParameter("name")?.trim().orEmpty()
                            .ifBlank { uri.fragment?.let { urlDecode(it) }?.trim().orEmpty() }
                            .ifBlank { targetUri.fragment?.let { urlDecode(it) }?.trim().orEmpty() }
                            .ifBlank { targetUri.host.orEmpty() }
                            .ifBlank { "Subscription" }
                        ParsedSubscription(url = rawTarget.substringBefore("#").trim(), name = name)
                    } else {
                        null
                    }
                }

                trimmed.startsWith("happ://add/", ignoreCase = true) -> {
                    val rawSegment = trimmed.substringAfter("happ://add/", "").trim()
                    val rawTarget = if (rawSegment.startsWith("http://", true) || rawSegment.startsWith("https://", true)) {
                        rawSegment
                    } else {
                        urlDecode(rawSegment).trim()
                    }
                    if (rawTarget.startsWith("http://", true) || rawTarget.startsWith("https://", true)) {
                        val targetUri = Uri.parse(rawTarget)
                        val name = targetUri.fragment?.let { urlDecode(it) }?.trim().orEmpty()
                            .ifBlank { targetUri.host.orEmpty() }
                            .ifBlank { "Subscription" }
                        ParsedSubscription(url = rawTarget.substringBefore("#").trim(), name = name)
                    } else {
                        null
                    }
                }

                trimmed.startsWith("https://", ignoreCase = true) ||
                    trimmed.startsWith("http://", ignoreCase = true) -> {
                    val uri = Uri.parse(trimmed)
                    val host = uri.host.orEmpty()
                    if (host.isBlank()) return@runCatching null

                    val isHttps = trimmed.startsWith("https://", ignoreCase = true)
                    val path = uri.encodedPath.orEmpty()
                    val query = uri.encodedQuery.orEmpty()
                    val hasRootOnlyPath = path.isEmpty() || path == "/"
                    val hasUserInfo = !uri.userInfo.isNullOrBlank()
                    val isProxyPortOnHttp = !isHttps && uri.port in explicitProxyPorts

                    // Treat bare http(s)://user:pass@host:port or http://host:3128|8080 (with no path/query) as HTTP proxy
                    if (hasRootOnlyPath && query.isEmpty() && (hasUserInfo || isProxyPortOnHttp)) {
                        return@runCatching null
                    }

                    val fragName = uri.fragment?.let { urlDecode(it) }.orEmpty().trim()
                    val cleanUrl = trimmed.substringBefore("#").trim()
                    ParsedSubscription(
                        url = cleanUrl,
                        name = fragName.ifBlank { host },
                    )
                }

                else -> null
            }
        }.getOrNull()
    }

    fun parseBatch(rawInput: String, groupId: Long = 1L): List<ProxyProfile> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()

        // If the entire payload is raw Xray JSON config
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return listOfNotNull(parseRawJsonProfile(trimmed, groupId))
        }

        val decodedText = if (!trimmed.contains("://")) {
            tryDecodeBase64(trimmed) ?: trimmed
        } else {
            trimmed
        }

        return decodedText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line -> parseSingle(line, groupId) }
    }

    fun parseSingle(link: String, groupId: Long = 1L): ProxyProfile? {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return null
        if (parseSubscriptionLink(trimmed) != null) return null
        return runCatching {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed, groupId)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed, groupId)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed, groupId)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed, groupId)
                trimmed.startsWith("hysteria2://", ignoreCase = true) ||
                    trimmed.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(trimmed, groupId)
                trimmed.startsWith("tuic://", ignoreCase = true) -> parseTuic(trimmed, groupId)
                trimmed.startsWith("socks://", ignoreCase = true) ||
                    trimmed.startsWith("socks5://", ignoreCase = true) -> parseSocks(trimmed, groupId)
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> parseHttp(trimmed, groupId)
                trimmed.startsWith("ssh://", ignoreCase = true) -> parseSsh(trimmed, groupId)
                trimmed.startsWith("wg://", ignoreCase = true) ||
                    trimmed.startsWith("wireguard://", ignoreCase = true) -> parseWireGuard(trimmed, groupId)
                trimmed.startsWith("{") -> parseRawJsonProfile(trimmed, groupId)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseVless(link: String, groupId: Long): ProxyProfile {
        val uri = Uri.parse(link)
        val host = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 443
        val uuid = uri.userInfo.orEmpty().let { urlDecode(it) }
        val name = uri.fragment?.let { urlDecode(it) }.orEmpty()

        val rawType = (uri.getQueryParameter("type") ?: "tcp").lowercase()
        val headerType = uri.getQueryParameter("headerType").orEmpty()
        val transport = when {
            rawType == "splithttp" || rawType == "xhttp" -> TransportType.XHTTP
            rawType == "ws" -> TransportType.WS
            rawType == "grpc" -> TransportType.GRPC
            rawType == "httpupgrade" -> TransportType.HTTPUPGRADE
            rawType == "h2" || rawType == "http" -> TransportType.H2
            else -> TransportType.TCP
        }

        val pbk = uri.getQueryParameter("pbk").orEmpty()
        val rawSecurity = (uri.getQueryParameter("security") ?: if (pbk.isNotBlank()) "reality" else "none").lowercase()
        val security = when {
            rawSecurity == "reality" || pbk.isNotBlank() -> SecurityType.REALITY
            rawSecurity == "tls" || rawSecurity == "xtls" -> SecurityType.TLS
            else -> SecurityType.NONE
        }

        val flowStr = uri.getQueryParameter("flow").orEmpty()
        val flow = FlowType.entries.find { it.wireValue == flowStr } ?: FlowType.NONE

        val sni = uri.getQueryParameter("sni")
            ?: uri.getQueryParameter("peer")
            ?: ""
        val alpn = uri.getQueryParameter("alpn").orEmpty()
        val fp = uri.getQueryParameter("fp").orEmpty().ifBlank {
            if (security == SecurityType.REALITY) "chrome" else ""
        }
        val sid = uri.getQueryParameter("sid").orEmpty()
        val spx = uri.getQueryParameter("spx").orEmpty().ifBlank { "/" }
        val pqv = uri.getQueryParameter("pqv").orEmpty()
        val encryption = uri.getQueryParameter("encryption").orEmpty().ifBlank { "none" }
        val allowInsecure = uri.getQueryParameter("allowInsecure")?.let { it == "1" || it == "true" } ?: false

        val path = when (transport) {
            TransportType.GRPC -> uri.getQueryParameter("serviceName") ?: uri.getQueryParameter("path") ?: ""
            else -> uri.getQueryParameter("path").orEmpty()
        }
        val hostHeader = uri.getQueryParameter("host").orEmpty()
        val grpcAuthority = uri.getQueryParameter("authority").orEmpty()
        val xhttpMode = uri.getQueryParameter("mode").orEmpty().ifBlank { "auto" }
        val xhttpExtra = uri.getQueryParameter("extra").orEmpty()

        return ProxyProfile(
            groupId = groupId,
            name = name.ifBlank { "$host:$port" },
            protocol = ProxyProtocol.VLESS,
            serverAddress = host,
            serverPort = port,
            uuid = uuid,
            encryption = encryption,
            flow = flow,
            transport = transport,
            host = hostHeader,
            path = path,
            grpcAuthority = grpcAuthority,
            xhttpMode = xhttpMode,
            xhttpExtra = xhttpExtra,
            headerType = headerType.ifBlank { "none" },
            security = security,
            sni = sni,
            alpn = alpn,
            fingerprint = fp,
            allowInsecure = allowInsecure,
            realityPublicKey = pbk,
            realityShortId = sid,
            realitySpiderX = spx,
            realityMldsa65Verify = pqv,
            packetEncoding = uri.getQueryParameter("packetEncoding").orEmpty().ifBlank { "xudp" },
        )
    }

    private fun parseVmess(link: String, groupId: Long): ProxyProfile? {
        val body = link.removePrefix("vmess://").trim()
        if (!body.contains("?") && !body.contains("@")) {
            val jsonStr = tryDecodeBase64(body) ?: return null
            val obj = JSONObject(jsonStr)
            val net = obj.optString("net", "tcp").lowercase()
            val transport = when (net) {
                "ws" -> TransportType.WS
                "grpc" -> TransportType.GRPC
                "h2", "http" -> TransportType.H2
                "httpupgrade" -> TransportType.HTTPUPGRADE
                "xhttp", "splithttp" -> TransportType.XHTTP
                else -> TransportType.TCP
            }
            val tlsFlag = obj.optString("tls", "").lowercase()
            val security = when (tlsFlag) {
                "tls" -> SecurityType.TLS
                "reality" -> SecurityType.REALITY
                else -> SecurityType.NONE
            }
            val add = obj.optString("add")
            val port = obj.optString("port").toIntOrNull() ?: 443
            return ProxyProfile(
                groupId = groupId,
                name = obj.optString("ps").ifBlank { "$add:$port" },
                protocol = ProxyProtocol.VMESS,
                serverAddress = add,
                serverPort = port,
                uuid = obj.optString("id"),
                alterId = obj.optString("aid", "0").toIntOrNull() ?: 0,
                encryption = obj.optString("scy", "auto").ifBlank { "auto" },
                flow = FlowType.NONE,
                transport = transport,
                host = obj.optString("host"),
                path = obj.optString("path"),
                headerType = obj.optString("type", "none"),
                security = security,
                sni = obj.optString("sni").ifBlank { obj.optString("host") },
                alpn = obj.optString("alpn"),
                fingerprint = obj.optString("fp"),
            )
        }
        // Standard URL VMess format
        val parsed = parseVless(link.replaceFirst("vmess://", "vless://", ignoreCase = true), groupId)
        return parsed.copy(
            protocol = ProxyProtocol.VMESS,
            flow = FlowType.NONE,
            encryption = "auto",
        )
    }

    private fun parseTrojan(link: String, groupId: Long): ProxyProfile {
        val uri = Uri.parse(link)
        val host = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 443
        val password = uri.userInfo.orEmpty().let { urlDecode(it) }
        val name = uri.fragment?.let { urlDecode(it) }.orEmpty()
        val rawType = (uri.getQueryParameter("type") ?: "tcp").lowercase()
        val transport = when (rawType) {
            "ws" -> TransportType.WS
            "grpc" -> TransportType.GRPC
            "httpupgrade" -> TransportType.HTTPUPGRADE
            "xhttp", "splithttp" -> TransportType.XHTTP
            "h2", "http" -> TransportType.H2
            else -> TransportType.TCP
        }
        val pbk = uri.getQueryParameter("pbk").orEmpty()
        val rawSec = (uri.getQueryParameter("security") ?: if (pbk.isNotBlank()) "reality" else "tls").lowercase()
        val security = when (rawSec) {
            "reality" -> SecurityType.REALITY
            "none" -> SecurityType.NONE
            else -> SecurityType.TLS
        }
        return ProxyProfile(
            groupId = groupId,
            name = name.ifBlank { "$host:$port" },
            protocol = ProxyProtocol.TROJAN,
            serverAddress = host,
            serverPort = port,
            password = password,
            flow = FlowType.NONE,
            transport = transport,
            host = uri.getQueryParameter("host").orEmpty(),
            path = uri.getQueryParameter("serviceName") ?: uri.getQueryParameter("path").orEmpty(),
            security = security,
            sni = uri.getQueryParameter("sni").orEmpty().ifBlank { host },
            alpn = uri.getQueryParameter("alpn").orEmpty(),
            fingerprint = uri.getQueryParameter("fp").orEmpty(),
            allowInsecure = uri.getQueryParameter("allowInsecure")?.let { it == "1" || it == "true" } ?: false,
            realityPublicKey = pbk,
            realityShortId = uri.getQueryParameter("sid").orEmpty(),
            realitySpiderX = uri.getQueryParameter("spx").orEmpty().ifBlank { "/" },
        )
    }

    private fun parseShadowsocks(link: String, groupId: Long): ProxyProfile? {
        val withoutScheme = link.removePrefix("ss://")
        val fragmentSplit = withoutScheme.split("#", limit = 2)
        val name = fragmentSplit.getOrNull(1)?.let { urlDecode(it) }.orEmpty()
        val mainPart = fragmentSplit[0].substringBefore("?")

        val (userInfoPart, hostPortPart) = if (mainPart.contains("@")) {
            val parts = mainPart.split("@", limit = 2)
            val decodedUser = if (parts[0].contains(":")) parts[0] else (tryDecodeBase64(parts[0]) ?: parts[0])
            decodedUser to parts[1]
        } else {
            val decoded = tryDecodeBase64(mainPart) ?: return null
            val parts = decoded.split("@", limit = 2)
            if (parts.size != 2) return null
            parts[0] to parts[1]
        }

        val method = urlDecode(userInfoPart.substringBefore(":"))
        val password = urlDecode(userInfoPart.substringAfter(":", ""))
        val (host, port) = splitHostPort(hostPortPart, 8388)

        return ProxyProfile(
            groupId = groupId,
            name = name.ifBlank { "$host:$port" },
            protocol = ProxyProtocol.SHADOWSOCKS,
            serverAddress = host,
            serverPort = port,
            encryption = method.ifBlank { "2022-blake3-aes-128-gcm" },
            password = password,
            security = SecurityType.NONE,
            transport = TransportType.TCP,
            flow = FlowType.NONE,
        )
    }

    private fun parseSocks(link: String, groupId: Long): ProxyProfile {
        val uri = Uri.parse(link)
        val host = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 1080
        val userInfo = uri.userInfo.orEmpty()
        val (user, pass) = if (userInfo.contains(":")) {
            urlDecode(userInfo.substringBefore(":")) to urlDecode(userInfo.substringAfter(":"))
        } else {
            "" to ""
        }
        val name = uri.fragment?.let { urlDecode(it) }.orEmpty()
        return ProxyProfile(
            groupId = groupId,
            name = name.ifBlank { "$host:$port" },
            protocol = ProxyProtocol.SOCKS5,
            serverAddress = host,
            serverPort = port,
            username = user,
            password = pass,
            security = SecurityType.NONE,
            transport = TransportType.TCP,
            flow = FlowType.NONE,
        )
    }

    private fun parseHttp(link: String, groupId: Long): ProxyProfile {
        val uri = Uri.parse(link)
        val isHttps = link.startsWith("https://", ignoreCase = true)
        val host = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: if (isHttps) 443 else 8080
        val userInfo = uri.userInfo.orEmpty()
        val (user, pass) = if (userInfo.contains(":")) {
            urlDecode(userInfo.substringBefore(":")) to urlDecode(userInfo.substringAfter(":"))
        } else {
            "" to ""
        }
        val name = uri.fragment?.let { urlDecode(it) }.orEmpty()
        return ProxyProfile(
            groupId = groupId,
            name = name.ifBlank { "$host:$port" },
            protocol = ProxyProtocol.HTTP,
            serverAddress = host,
            serverPort = port,
            username = user,
            password = pass,
            security = if (isHttps) SecurityType.TLS else SecurityType.NONE,
            transport = TransportType.TCP,
            flow = FlowType.NONE,
        )
    }

    private fun parseSsh(link: String, groupId: Long): ProxyProfile {
        val uri = Uri.parse(link)
        val host = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 22
        val userInfo = uri.userInfo.orEmpty()
        val (user, pass) = if (userInfo.contains(":")) {
            urlDecode(userInfo.substringBefore(":")) to urlDecode(userInfo.substringAfter(":"))
        } else {
            urlDecode(userInfo).ifBlank { "root" } to ""
        }
        val privateKey = uri.getQueryParameter("privateKey").orEmpty()
        val passphrase = uri.getQueryParameter("passphrase").orEmpty()
        val publicKey = uri.getQueryParameter("publicKey").orEmpty()
        val authParam = uri.getQueryParameter("authType").orEmpty().lowercase()
        val authType = when {
            authParam == "none" -> SshAuthType.NONE
            authParam == "private_key" || authParam == "key" || privateKey.isNotBlank() -> SshAuthType.PRIVATE_KEY
            authParam == "password" || pass.isNotBlank() -> SshAuthType.PASSWORD
            else -> SshAuthType.PASSWORD
        }
        val name = uri.fragment?.let { urlDecode(it) }.orEmpty()
        return ProxyProfile(
            groupId = groupId,
            name = name.ifBlank { "SSH $host:$port" },
            protocol = ProxyProtocol.SSH,
            serverAddress = host,
            serverPort = port,
            username = user.ifBlank { "root" },
            password = pass,
            sshAuthType = authType,
            sshPrivateKey = privateKey,
            sshPrivateKeyPassphrase = passphrase,
            sshPublicKey = publicKey,
            security = SecurityType.NONE,
            transport = TransportType.TCP,
            flow = FlowType.NONE,
        )
    }

    private fun parseWireGuard(link: String, groupId: Long): ProxyProfile {
        val uri = Uri.parse(link)
        val host = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 51820
        val priv = uri.userInfo.orEmpty().let { urlDecode(it) }
        val name = uri.fragment?.let { urlDecode(it) }.orEmpty()
        return ProxyProfile(
            groupId = groupId,
            name = name.ifBlank { "WG $host:$port" },
            protocol = ProxyProtocol.WIREGUARD,
            serverAddress = host,
            serverPort = port,
            wgPrivateKey = priv,
            wgPeerPublicKey = uri.getQueryParameter("publickey").orEmpty(),
            wgLocalAddress = uri.getQueryParameter("address").orEmpty().ifBlank { "10.0.0.2/32" },
            wgMtu = uri.getQueryParameter("mtu")?.toIntOrNull() ?: 1420,
            wgReserved = uri.getQueryParameter("reserved").orEmpty(),
            security = SecurityType.NONE,
            flow = FlowType.NONE,
        )
    }

    private fun parseHysteria2(link: String, groupId: Long): ProxyProfile {
        val normalized = if (link.startsWith("hy2://", ignoreCase = true)) {
            "hysteria2://" + link.substring(6)
        } else {
            link
        }
        val uri = Uri.parse(normalized)
        val host = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 443
        val rawUserInfo = uri.userInfo.orEmpty().let { urlDecode(it) }
        val password = rawUserInfo.ifBlank { uri.getQueryParameter("auth").orEmpty() }
        val name = uri.fragment?.let { urlDecode(it) }.orEmpty()

        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: ""
        val alpn = uri.getQueryParameter("alpn").orEmpty()
        val fp = uri.getQueryParameter("fp").orEmpty()
        val insecure = (uri.getQueryParameter("insecure")
            ?: uri.getQueryParameter("allowInsecure")
            ?: uri.getQueryParameter("allow_insecure"))
            ?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
        val obfsRaw = uri.getQueryParameter("obfs").orEmpty().lowercase()
        val obfs = if (obfsRaw == "none") "" else obfsRaw
        val obfsPassword = uri.getQueryParameter("obfs-password")
            ?: uri.getQueryParameter("obfs_password")
            ?: ""
        val upMbps = parseBandwidthMbps(uri.getQueryParameter("upmbps") ?: uri.getQueryParameter("up"))
        val downMbps = parseBandwidthMbps(uri.getQueryParameter("downmbps") ?: uri.getQueryParameter("down"))
        val mport = uri.getQueryParameter("mport").orEmpty()
        val hopInterval = (uri.getQueryParameter("hop-interval")
            ?: uri.getQueryParameter("hopinterval")
            ?: uri.getQueryParameter("hop_interval")
            ?: uri.getQueryParameter("interval"))
            ?.trim()?.toIntOrNull()?.coerceIn(5, 3600) ?: 30
        val pinSha256 = uri.getQueryParameter("pinSHA256")
            ?: uri.getQueryParameter("pin_sha256")
            ?: ""

        return ProxyProfile(
            groupId = groupId,
            name = name.ifBlank { "Hy2 $host:$port" },
            protocol = ProxyProtocol.HYSTERIA2,
            serverAddress = host,
            serverPort = port,
            password = password,
            flow = FlowType.NONE,
            transport = TransportType.TCP,
            security = SecurityType.TLS,
            sni = sni,
            alpn = alpn,
            fingerprint = fp,
            allowInsecure = insecure,
            hy2Obfs = obfs,
            hy2ObfsPassword = obfsPassword,
            hy2UpMbps = upMbps,
            hy2DownMbps = downMbps,
            hy2Ports = mport,
            hy2HopInterval = hopInterval,
            hy2PinSha256 = pinSha256,
        )
    }

    private fun parseTuic(link: String, groupId: Long): ProxyProfile {
        val uri = Uri.parse(link)
        val host = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 443
        val userInfo = uri.userInfo.orEmpty()
        val (uuid, passFromUserInfo) = if (userInfo.contains(":")) {
            urlDecode(userInfo.substringBefore(":")) to urlDecode(userInfo.substringAfter(":"))
        } else {
            urlDecode(userInfo) to ""
        }
        val password = passFromUserInfo.ifBlank { uri.getQueryParameter("password").orEmpty() }
        val name = uri.fragment?.let { urlDecode(it) }.orEmpty()

        val congestion = (uri.getQueryParameter("congestion_control")
            ?: uri.getQueryParameter("congestion")
            ?: "bbr").lowercase()
        val udpMode = (uri.getQueryParameter("udp_relay_mode") ?: "native").lowercase()
            .let { if (it == "quic") "quic" else "native" }
        val sni = uri.getQueryParameter("sni").orEmpty()
        val alpn = uri.getQueryParameter("alpn").orEmpty()
        val fp = uri.getQueryParameter("fp").orEmpty()
        val insecure = (uri.getQueryParameter("allow_insecure")
            ?: uri.getQueryParameter("allowInsecure")
            ?: uri.getQueryParameter("insecure"))
            ?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
        val disableSni = (uri.getQueryParameter("disable_sni") ?: uri.getQueryParameter("disableSni"))
            ?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
        val zeroRtt = (uri.getQueryParameter("zero_rtt_handshake")
            ?: uri.getQueryParameter("reduce_rtt")
            ?: uri.getQueryParameter("0rtt"))
            ?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
        val heartbeat = (uri.getQueryParameter("heartbeat") ?: uri.getQueryParameter("heartbeat_interval"))
            ?.trim()?.toIntOrNull()?.coerceIn(1000, 120_000) ?: 10000

        return ProxyProfile(
            groupId = groupId,
            name = name.ifBlank { "TUIC $host:$port" },
            protocol = ProxyProtocol.TUIC,
            serverAddress = host,
            serverPort = port,
            uuid = uuid,
            password = password,
            flow = FlowType.NONE,
            transport = TransportType.TCP,
            security = SecurityType.TLS,
            sni = sni,
            alpn = alpn,
            fingerprint = fp,
            allowInsecure = insecure,
            tuicCongestionControl = congestion.ifBlank { "bbr" },
            tuicUdpRelayMode = udpMode,
            tuicZeroRttHandshake = zeroRtt,
            tuicDisableSni = disableSni,
            tuicHeartbeatIntervalMs = heartbeat,
        )
    }

    private fun parseBandwidthMbps(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val clean = raw.trim().lowercase()
        val num = Regex("(\\d+)").find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return 0
        return if (clean.contains("gbps") || clean.contains("g")) {
            (num * 1000).coerceAtLeast(0)
        } else {
            num.coerceAtLeast(0)
        }
    }

    private fun parseRawJsonProfile(jsonText: String, groupId: Long): ProxyProfile? {
        return runCatching {
            val root = JSONObject(jsonText)
            val outbounds = root.optJSONArray("outbounds")
            val firstOutbound = if (outbounds != null && outbounds.length() > 0) {
                outbounds.getJSONObject(0)
            } else {
                root
            }
            val remarks = root.optString("remarks").ifBlank {
                firstOutbound.optString("tag").ifBlank { "Custom Xray JSON" }
            }
            ProxyProfile(
                groupId = groupId,
                name = remarks,
                protocol = ProxyProtocol.CUSTOM_JSON,
                serverAddress = "xray-json",
                serverPort = 443,
                security = if (jsonText.contains("realitySettings")) SecurityType.REALITY else SecurityType.NONE,
                customConfigJson = jsonText,
            )
        }.getOrNull()
    }

    fun toShareUri(profile: ProxyProfile): String {
        return when (profile.protocol) {
            ProxyProtocol.VLESS -> buildVlessLikeUri("vless", profile)
            ProxyProtocol.TROJAN -> buildVlessLikeUri("trojan", profile)
            ProxyProtocol.VMESS -> buildVmessUri(profile)
            ProxyProtocol.SHADOWSOCKS -> {
                val creds = Base64.encodeToString(
                    "${profile.encryption}:${profile.password}".toByteArray(),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                )
                val label = if (profile.name.isNotBlank()) "#${urlEncode(profile.name)}" else ""
                "ss://$creds@${profile.serverAddress}:${profile.serverPort}$label"
            }
            ProxyProtocol.HYSTERIA2 -> buildHysteria2Uri(profile)
            ProxyProtocol.TUIC -> buildTuicUri(profile)
            ProxyProtocol.SOCKS5 -> {
                val auth = if (profile.username.isNotBlank()) {
                    "${urlEncode(profile.username)}:${urlEncode(profile.password)}@"
                } else ""
                val label = if (profile.name.isNotBlank()) "#${urlEncode(profile.name)}" else ""
                "socks://$auth${profile.serverAddress}:${profile.serverPort}$label"
            }
            ProxyProtocol.HTTP -> {
                val scheme = if (profile.security == SecurityType.TLS) "https" else "http"
                val auth = if (profile.username.isNotBlank()) {
                    "${urlEncode(profile.username)}:${urlEncode(profile.password)}@"
                } else ""
                val label = if (profile.name.isNotBlank()) "#${urlEncode(profile.name)}" else ""
                "$scheme://$auth${profile.serverAddress}:${profile.serverPort}$label"
            }
            ProxyProtocol.SSH -> {
                val userPart = urlEncode(profile.username.ifBlank { "root" })
                val auth = if (profile.sshAuthType == SshAuthType.PASSWORD && profile.password.isNotBlank()) {
                    "$userPart:${urlEncode(profile.password)}@"
                } else {
                    "$userPart@"
                }
                val params = mutableListOf<String>()
                if (profile.sshAuthType != SshAuthType.PASSWORD) {
                    params += "authType=${profile.sshAuthType.name.lowercase()}"
                }
                if (profile.sshPublicKey.isNotBlank()) {
                    params += "publicKey=${urlEncode(profile.sshPublicKey)}"
                }
                val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
                val hostPart = if (profile.serverAddress.contains(":") && !profile.serverAddress.startsWith("[")) {
                    "[${profile.serverAddress}]"
                } else {
                    profile.serverAddress
                }
                val label = if (profile.name.isNotBlank()) "#${urlEncode(profile.name)}" else ""
                "ssh://$auth$hostPart:${profile.serverPort}$query$label"
            }
            ProxyProtocol.WIREGUARD -> {
                val params = mutableListOf<String>()
                if (profile.wgPeerPublicKey.isNotBlank()) params += "publickey=${urlEncode(profile.wgPeerPublicKey)}"
                if (profile.wgLocalAddress.isNotBlank()) params += "address=${urlEncode(profile.wgLocalAddress)}"
                params += "mtu=${profile.wgMtu}"
                if (profile.wgReserved.isNotBlank()) params += "reserved=${urlEncode(profile.wgReserved)}"
                val query = params.joinToString("&")
                val label = if (profile.name.isNotBlank()) "#${urlEncode(profile.name)}" else ""
                "wg://${urlEncode(profile.wgPrivateKey)}@${profile.serverAddress}:${profile.serverPort}?$query$label"
            }
            ProxyProtocol.CUSTOM_JSON -> profile.customConfigJson
        }
    }

    private fun buildVlessLikeUri(scheme: String, p: ProxyProfile): String {
        val user = if (scheme == "trojan") p.password else p.uuid
        val params = linkedMapOf<String, String>()
        if (scheme == "vless") {
            params["encryption"] = p.encryption.ifBlank { "none" }
            if (p.flow != FlowType.NONE) {
                params["flow"] = p.flow.wireValue
            }
        }
        params["type"] = p.transport.wireValue
        when (p.transport) {
            TransportType.XHTTP -> {
                if (p.host.isNotBlank()) params["host"] = p.host
                if (p.path.isNotBlank()) params["path"] = p.path
                if (p.xhttpMode.isNotBlank()) params["mode"] = p.xhttpMode
            }
            TransportType.WS, TransportType.HTTPUPGRADE, TransportType.H2 -> {
                if (p.host.isNotBlank()) params["host"] = p.host
                if (p.path.isNotBlank()) params["path"] = p.path
            }
            TransportType.GRPC -> {
                if (p.path.isNotBlank()) params["serviceName"] = p.path
                if (p.grpcAuthority.isNotBlank()) params["authority"] = p.grpcAuthority
            }
            TransportType.TCP -> {
                if (p.headerType.isNotBlank() && p.headerType != "none") {
                    params["headerType"] = p.headerType
                }
            }
        }
        params["security"] = p.security.wireValue
        when (p.security) {
            SecurityType.REALITY -> {
                if (p.sni.isNotBlank()) params["sni"] = p.sni
                params["fp"] = p.fingerprint.ifBlank { "chrome" }
                if (p.realityPublicKey.isNotBlank()) params["pbk"] = p.realityPublicKey
                if (p.realityShortId.isNotBlank()) params["sid"] = p.realityShortId
                if (p.realitySpiderX.isNotBlank()) params["spx"] = p.realitySpiderX
                if (p.realityMldsa65Verify.isNotBlank()) params["pqv"] = p.realityMldsa65Verify
            }
            SecurityType.TLS -> {
                if (p.sni.isNotBlank()) params["sni"] = p.sni
                if (p.alpn.isNotBlank()) params["alpn"] = p.alpn
                if (p.fingerprint.isNotBlank()) params["fp"] = p.fingerprint
                if (p.allowInsecure) params["allowInsecure"] = "1"
            }
            SecurityType.NONE -> Unit
        }

        val query = params.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        val hostPart = if (p.serverAddress.contains(":") && !p.serverAddress.startsWith("[")) {
            "[${p.serverAddress}]"
        } else {
            p.serverAddress
        }
        val fragment = if (p.name.isNotBlank()) "#${urlEncode(p.name)}" else ""
        return "$scheme://${urlEncode(user)}@$hostPart:${p.serverPort}?$query$fragment"
    }

    private fun buildHysteria2Uri(p: ProxyProfile): String {
        val params = linkedMapOf<String, String>()
        if (p.sni.isNotBlank()) params["sni"] = p.sni
        if (p.alpn.isNotBlank()) params["alpn"] = p.alpn
        if (p.fingerprint.isNotBlank()) params["fp"] = p.fingerprint
        if (p.allowInsecure) params["insecure"] = "1"
        if (p.hy2Obfs.isNotBlank()) params["obfs"] = p.hy2Obfs
        if (p.hy2ObfsPassword.isNotBlank()) params["obfs-password"] = p.hy2ObfsPassword
        if (p.hy2UpMbps > 0) params["upmbps"] = p.hy2UpMbps.toString()
        if (p.hy2DownMbps > 0) params["downmbps"] = p.hy2DownMbps.toString()
        if (p.hy2Ports.isNotBlank()) params["mport"] = p.hy2Ports
        if (p.hy2Ports.isNotBlank() && p.hy2HopInterval != 30) params["hop-interval"] = p.hy2HopInterval.toString()
        if (p.hy2PinSha256.isNotBlank()) params["pinSHA256"] = p.hy2PinSha256
        val query = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        } else ""
        val hostPart = if (p.serverAddress.contains(":") && !p.serverAddress.startsWith("[")) {
            "[${p.serverAddress}]"
        } else {
            p.serverAddress
        }
        val authPart = if (p.password.isNotBlank()) "${urlEncode(p.password)}@" else ""
        val fragment = if (p.name.isNotBlank()) "#${urlEncode(p.name)}" else ""
        return "hysteria2://$authPart$hostPart:${p.serverPort}$query$fragment"
    }

    private fun buildTuicUri(p: ProxyProfile): String {
        val params = linkedMapOf<String, String>()
        params["congestion_control"] = p.tuicCongestionControl.ifBlank { "bbr" }
        params["udp_relay_mode"] = p.tuicUdpRelayMode.ifBlank { "native" }
        if (p.alpn.isNotBlank()) params["alpn"] = p.alpn
        if (p.sni.isNotBlank()) params["sni"] = p.sni
        if (p.fingerprint.isNotBlank()) params["fp"] = p.fingerprint
        if (p.allowInsecure) params["allow_insecure"] = "1"
        if (p.tuicDisableSni) params["disable_sni"] = "1"
        if (p.tuicZeroRttHandshake) params["zero_rtt_handshake"] = "1"
        if (p.tuicHeartbeatIntervalMs != 10000) params["heartbeat"] = p.tuicHeartbeatIntervalMs.toString()
        val query = params.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        val hostPart = if (p.serverAddress.contains(":") && !p.serverAddress.startsWith("[")) {
            "[${p.serverAddress}]"
        } else {
            p.serverAddress
        }
        val userPart = if (p.password.isNotBlank()) {
            "${urlEncode(p.uuid)}:${urlEncode(p.password)}"
        } else {
            urlEncode(p.uuid)
        }
        val fragment = if (p.name.isNotBlank()) "#${urlEncode(p.name)}" else ""
        return "tuic://$userPart@$hostPart:${p.serverPort}?$query$fragment"
    }

    private fun buildVmessUri(p: ProxyProfile): String {
        val obj = JSONObject().apply {
            put("v", "2")
            put("ps", p.name)
            put("add", p.serverAddress)
            put("port", p.serverPort.toString())
            put("id", p.uuid)
            put("aid", p.alterId.toString())
            put("scy", p.encryption.ifBlank { "auto" })
            put("net", p.transport.wireValue)
            put("type", p.headerType.ifBlank { "none" })
            put("host", p.host)
            put("path", p.path)
            put("tls", if (p.security == SecurityType.TLS) "tls" else "")
            put("sni", p.sni)
            put("alpn", p.alpn)
            put("fp", p.fingerprint)
        }
        val b64 = Base64.encodeToString(obj.toString().toByteArray(), Base64.NO_WRAP)
        return "vmess://$b64"
    }

    private fun splitHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        return if (hostPort.startsWith("[")) {
            val endBracket = hostPort.indexOf(']')
            val host = hostPort.substring(1, endBracket.coerceAtLeast(1))
            val port = hostPort.substringAfter("]:", "").toIntOrNull() ?: defaultPort
            host to port
        } else {
            val lastColon = hostPort.lastIndexOf(':')
            if (lastColon > 0) {
                hostPort.substring(0, lastColon) to (hostPort.substring(lastColon + 1).toIntOrNull() ?: defaultPort)
            } else {
                hostPort to defaultPort
            }
        }
    }

    private fun tryDecodeBase64(input: String): String? {
        val clean = input.trim().replace("\r", "").replace("\n", "")
        if (clean.isEmpty()) return null
        val flags = listOf(
            Base64.DEFAULT,
            Base64.URL_SAFE,
            Base64.URL_SAFE or Base64.NO_PADDING,
        )
        for (flag in flags) {
            try {
                val bytes = Base64.decode(clean, flag)
                val str = String(bytes, Charsets.UTF_8)
                if (str.any { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }) {
                    return str
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun urlEncode(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
    private fun urlDecode(v: String): String = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
}
