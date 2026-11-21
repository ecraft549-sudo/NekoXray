package com.example.core.network

import android.content.Context
import com.example.core.model.ProxyProfile
import com.example.core.parser.UriParser
import com.example.core.vpn.XrayEngineState
import com.example.core.vpn.XrayVpnService
import com.example.core.xray.XrayAssetHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class TlsProbeResult(
    val success: Boolean,
    val latencyMs: Int? = null,
    val tlsProtocol: String = "",
    val cipherSuite: String = "",
    val subject: String = "",
    val isRealityCompatible: Boolean = false,
    val error: String? = null,
)

data class Socks5ProbeResult(
    val reachable: Boolean,
    val authOk: Boolean,
    val ipv4Exit: Boolean = false,
    val ipv6Exit: Boolean = false,
    val latencyMs: Int? = null,
    val error: String? = null,
) {
    fun summaryLabel(): String = when {
        !reachable -> error ?: "Unreachable"
        !authOk -> error ?: "Auth failed"
        ipv4Exit && ipv6Exit -> "IPv4+IPv6"
        ipv6Exit -> "IPv6 only"
        ipv4Exit -> "IPv4 only"
        else -> error ?: "No egress"
    }
}

data class SubscriptionFetchResult(
    val profiles: List<ProxyProfile>,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestampSec: Long = 0L,
    val profileTitle: String? = null,
)

object NetworkTester {

    private val tempCoreMutex = Mutex()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun findFreeLoopbackPort(exclude: Set<Int> = emptySet()): Int {
        repeat(12) {
            val port = runCatching {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            }.getOrDefault(0)
            if (port in 1..65535 && port !in exclude) return port
        }
        return 0
    }

    suspend fun resolveHostToIp(
        host: String,
        preferIpv6: Boolean = false,
        vpnService: XrayVpnService? = null,
    ): String? = withContext(Dispatchers.IO) {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        if (clean.isEmpty()) return@withContext null
        val isIpv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(clean)
        if (isIpv4 || clean.contains(':')) return@withContext null

        val addresses = vpnService?.resolveHostOnUnderlyingNetwork(clean)
            ?: runCatching { InetAddress.getAllByName(clean) }.getOrNull()
        if (addresses.isNullOrEmpty()) return@withContext null

        val chosen = if (preferIpv6) {
            addresses.firstOrNull { it is Inet6Address } ?: addresses.firstOrNull()
        } else {
            addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        }
        chosen?.hostAddress
    }

    suspend fun testTcpLatency(host: String, port: Int, timeoutMs: Int = 4500): Int? =
        withContext(Dispatchers.IO) {
            if (host.isBlank() || port !in 1..65535) return@withContext null
            val start = System.nanoTime()
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host.trim(), port), timeoutMs)
                    ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
                }
            }.getOrNull()
        }

    suspend fun testXrayOutboundDelay(
        context: Context,
        configJson: String,
        testUrl: String = "https://www.gstatic.com/generate_204",
    ): Int? = withContext(Dispatchers.IO) {
        runCatching {
            XrayAssetHelper.ensureInitialized(context)
            val delayMs = Libv2ray.measureOutboundDelay(configJson, testUrl)
            if (delayMs > 0L) delayMs.toInt() else null
        }.onFailure { err ->
            XrayEngineState.appendLog(
                "WARN",
                "Xray outbound delay test failed: ${err.message ?: err.javaClass.simpleName}",
            )
        }.getOrNull()
    }

    /**
     * Runs a full temporary Proxy-Only Xray-core instance (preserving `inbounds`, `routing`,
     * `dns`, and local loopback chain bridges that `Libv2ray.measureOutboundDelay` strips) to
     * accurately measure end-to-end latency for multi-hop proxy chains.
     */
    suspend fun testXrayConfigWithTempCore(
        context: Context,
        configJson: String,
        localSocksPort: Int = 0,
        testUrl: String = "https://www.gstatic.com/generate_204",
        timeoutMs: Long = 10_000L,
    ): Int? = tempCoreMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                XrayAssetHelper.ensureInitialized(context)
                var lastCoreMsg: String? = null
                val callback = object : CoreCallbackHandler {
                    override fun startup(): Long = 0L
                    override fun shutdown(): Long = 0L
                    override fun onEmitStatus(status: Long, msg: String?): Long {
                        if (!msg.isNullOrBlank()) lastCoreMsg = msg
                        return 0L
                    }
                }
                val controller = Libv2ray.newCoreController(callback)
                try {
                    controller.startLoop(configJson, 0)
                    val directDelay = withTimeoutOrNull(timeoutMs) {
                        runCatching {
                            val d = controller.measureDelay(testUrl)
                            if (d > 0L) d.toInt() else null
                        }.onFailure { err ->
                            XrayEngineState.appendLog(
                                "WARN",
                                "Chain temp-core measureDelay error: ${err.message ?: err.javaClass.simpleName}",
                            )
                        }.getOrNull()
                    }
                    if (directDelay != null && directDelay > 0) {
                        return@runCatching directDelay
                    }

                    // Fallback: probe through the temp core's local SOCKS5 inbound if Go measureDelay failed
                    if (localSocksPort in 1..65535) {
                        val socksDelay = probeHttpThroughLocalSocks(localSocksPort, testUrl)
                        if (socksDelay != null && socksDelay > 0) {
                            return@runCatching socksDelay
                        }
                    }

                    if (!lastCoreMsg.isNullOrBlank()) {
                        XrayEngineState.appendLog("WARN", "Chain ping failed (core: $lastCoreMsg)")
                    }
                    null
                } finally {
                    runCatching {
                        if (controller.isRunning) controller.stopLoop()
                    }
                }
            }.onFailure { err ->
                XrayEngineState.appendLog(
                    "WARN",
                    "Chain temp-core ping failed: ${err.message ?: err.javaClass.simpleName}",
                )
            }.getOrNull()
        }
    }

    private fun probeHttpThroughLocalSocks(socksPort: Int, testUrl: String): Int? {
        return runCatching {
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val req = Request.Builder().url(testUrl).get().build()
            val start = System.nanoTime()
            client.newCall(req).execute().use { resp ->
                if (resp.code in 200..399) {
                    ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * Performs a lightweight RFC 1928 / RFC 1929 SOCKS5 handshake + authentication check and
     * probes both IPv4 (`1.1.1.1:443`) and IPv6 (`[2606:4700:4700::1111]:443`) egress in parallel.
     */
    suspend fun probeSocks5Proxy(
        host: String,
        port: Int,
        username: String = "",
        password: String = "",
        timeoutMs: Int = 4500,
    ): Socks5ProbeResult = withContext(Dispatchers.IO) {
        val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
        if (cleanHost.isEmpty() || port !in 1..65535) {
            return@withContext Socks5ProbeResult(reachable = false, authOk = false, error = "Invalid host/port")
        }
        val ipv4Target = byteArrayOf(1, 1, 1, 1) // 1.1.1.1
        val ipv6Target = byteArrayOf(
            0x26, 0x06, 0x47, 0x00,
            0x47, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x11, 0x11,
        ) // 2606:4700:4700::1111

        coroutineScope {
            val v4Deferred = async {
                runSingleSocks5ConnectProbe(cleanHost, port, username, password, atyp = 0x01, addrBytes = ipv4Target, targetPort = 443, timeoutMs = timeoutMs)
            }
            val v6Deferred = async {
                runSingleSocks5ConnectProbe(cleanHost, port, username, password, atyp = 0x04, addrBytes = ipv6Target, targetPort = 443, timeoutMs = timeoutMs)
            }
            val v4 = v4Deferred.await()
            val v6 = v6Deferred.await()

            val reachable = v4.reachable || v6.reachable
            val authOk = v4.authOk || v6.authOk
            val bestMs = listOfNotNull(
                v4.latencyMs?.takeIf { v4.connected },
                v6.latencyMs?.takeIf { v6.connected },
            ).minOrNull()
            val err = when {
                !reachable -> v4.error ?: v6.error ?: "Unreachable"
                !authOk -> v4.error ?: v6.error ?: "Auth failed"
                !v4.connected && !v6.connected -> v6.error ?: v4.error ?: "Connect refused"
                else -> null
            }
            Socks5ProbeResult(
                reachable = reachable,
                authOk = authOk,
                ipv4Exit = v4.connected,
                ipv6Exit = v6.connected,
                latencyMs = bestMs,
                error = err,
            )
        }
    }

    private data class SingleSocksProbe(
        val reachable: Boolean = false,
        val authOk: Boolean = false,
        val connected: Boolean = false,
        val latencyMs: Int? = null,
        val error: String? = null,
    )

    private fun runSingleSocks5ConnectProbe(
        host: String,
        port: Int,
        username: String,
        password: String,
        atyp: Int,
        addrBytes: ByteArray,
        targetPort: Int,
        timeoutMs: Int,
    ): SingleSocksProbe {
        return runCatching {
            val start = System.nanoTime()
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.soTimeout = timeoutMs
                val out = sock.getOutputStream()
                val inp = DataInputStream(sock.getInputStream())

                // 1. Greeting
                val hasCreds = username.isNotBlank()
                val greeting = if (hasCreds) {
                    byteArrayOf(0x05, 0x02, 0x00, 0x02)
                } else {
                    byteArrayOf(0x05, 0x01, 0x00)
                }
                out.write(greeting)
                out.flush()

                val ver = inp.readUnsignedByte()
                val method = inp.readUnsignedByte()
                if (ver != 0x05) {
                    return SingleSocksProbe(reachable = true, authOk = false, error = "Not SOCKS5 (ver=$ver)")
                }
                if (method == 0xFF) {
                    return SingleSocksProbe(reachable = true, authOk = false, error = "Auth required / rejected")
                }

                // 2. RFC 1929 Username/Password auth
                if (method == 0x02) {
                    val uBytes = username.toByteArray(Charsets.UTF_8)
                    val pBytes = password.toByteArray(Charsets.UTF_8)
                    if (uBytes.size > 255 || pBytes.size > 255) {
                        return SingleSocksProbe(reachable = true, authOk = false, error = "Credentials too long")
                    }
                    val authPacket = ByteArray(3 + uBytes.size + pBytes.size)
                    authPacket[0] = 0x01
                    authPacket[1] = uBytes.size.toByte()
                    System.arraycopy(uBytes, 0, authPacket, 2, uBytes.size)
                    authPacket[2 + uBytes.size] = pBytes.size.toByte()
                    System.arraycopy(pBytes, 0, authPacket, 3 + uBytes.size, pBytes.size)
                    out.write(authPacket)
                    out.flush()

                    val authVer = inp.readUnsignedByte()
                    val authStatus = inp.readUnsignedByte()
                    if (authVer != 0x01 || authStatus != 0x00) {
                        return SingleSocksProbe(reachable = true, authOk = false, error = "Auth failed")
                    }
                } else if (method != 0x00) {
                    return SingleSocksProbe(reachable = true, authOk = false, error = "Unsupported SOCKS5 auth $method")
                }

                // 3. CONNECT request
                val req = ByteArray(4 + addrBytes.size + 2)
                req[0] = 0x05
                req[1] = 0x01 // CONNECT
                req[2] = 0x00
                req[3] = atyp.toByte()
                System.arraycopy(addrBytes, 0, req, 4, addrBytes.size)
                req[4 + addrBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
                req[5 + addrBytes.size] = (targetPort and 0xFF).toByte()
                out.write(req)
                out.flush()

                val repVer = inp.readUnsignedByte()
                val repCode = inp.readUnsignedByte()
                inp.readUnsignedByte() // RSV
                inp.readUnsignedByte() // ATYP
                val elapsed = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)

                if (repVer == 0x05 && repCode == 0x00) {
                    SingleSocksProbe(reachable = true, authOk = true, connected = true, latencyMs = elapsed)
                } else {
                    SingleSocksProbe(reachable = true, authOk = true, connected = false, error = "SOCKS5 reply 0x${repCode.toString(16)}")
                }
            }
        }.getOrElse { err ->
            SingleSocksProbe(reachable = false, authOk = false, error = err.message ?: err.javaClass.simpleName)
        }
    }

    suspend fun probeTlsSni(
        host: String,
        port: Int = 443,
        sni: String = host,
        timeoutMs: Int = 5000,
    ): TlsProbeResult = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        val cleanSni = sni.trim().ifBlank { cleanHost }
        if (cleanHost.isBlank() || port !in 1..65535) {
            return@withContext TlsProbeResult(success = false, error = "Invalid host or port")
        }

        val start = System.nanoTime()
        runCatching {
            val baseSocket = Socket()
            baseSocket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
            baseSocket.soTimeout = timeoutMs

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = factory.createSocket(baseSocket, cleanSni, port, true) as SSLSocket
            sslSocket.use { sock ->
                val params: SSLParameters = sock.sslParameters
                if (cleanSni.isNotBlank() && !cleanSni.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                    params.serverNames = listOf(SNIHostName(cleanSni))
                }
                params.applicationProtocols = arrayOf("h2", "http/1.1")
                sock.sslParameters = params
                sock.startHandshake()

                val rtt = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
                val session = sock.session
                val proto = session.protocol.orEmpty()
                val cipher = session.cipherSuite.orEmpty()
                val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                val subject = cert?.subjectX500Principal?.name.orEmpty()
                val isTls13 = proto.equals("TLSv1.3", ignoreCase = true)

                TlsProbeResult(
                    success = true,
                    latencyMs = rtt,
                    tlsProtocol = proto,
                    cipherSuite = cipher,
                    subject = subject,
                    isRealityCompatible = isTls13,
                )
            }
        }.getOrElse { err ->
            TlsProbeResult(
                success = false,
                error = err.message ?: err.javaClass.simpleName,
            )
        }
    }

    suspend fun fetchSubscription(
        url: String,
        groupId: Long,
        userAgent: String,
    ): Result<SubscriptionFetchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url.trim())
                .header("User-Agent", userAgent.ifBlank { "NekoXray/v26.10.31" })
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${resp.message}")
                }
                val body = resp.body?.string().orEmpty()
                val profiles = UriParser.parseBatch(body, groupId)
                if (profiles.isEmpty()) {
                    error("No valid proxy links found in subscription response.")
                }

                val userInfo = resp.header("Subscription-Userinfo").orEmpty()
                val infoMap = userInfo.split(";")
                    .mapNotNull { part ->
                        val kv = part.trim().split("=", limit = 2)
                        if (kv.size == 2) kv[0].lowercase() to (kv[1].toLongOrNull() ?: 0L) else null
                    }
                    .toMap()

                val rawTitle = resp.header("profile-title")?.trim().orEmpty()
                val decodedTitle = when {
                    rawTitle.startsWith("base64:", ignoreCase = true) -> {
                        runCatching {
                            String(
                                android.util.Base64.decode(rawTitle.substring(7).trim(), android.util.Base64.DEFAULT),
                                Charsets.UTF_8,
                            ).trim()
                        }.getOrNull()
                    }
                    rawTitle.isNotBlank() -> rawTitle
                    else -> null
                }?.takeIf { it.isNotBlank() }

                SubscriptionFetchResult(
                    profiles = profiles,
                    uploadBytes = infoMap["upload"] ?: 0L,
                    downloadBytes = infoMap["download"] ?: 0L,
                    totalBytes = infoMap["total"] ?: 0L,
                    expireTimestampSec = infoMap["expire"] ?: 0L,
                    profileTitle = decodedTitle,
                )
            }
        }
    }
}
