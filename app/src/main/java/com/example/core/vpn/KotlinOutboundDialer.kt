package com.example.core.vpn

import android.net.VpnService
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.SecurityType
import com.example.core.model.TransportType
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Pure-Kotlin outbound dialer for VLESS (plain TCP/WS/TLS), Trojan, SOCKS5, HTTP, and direct TCP/UDP sockets.
 * Protects all outbound sockets with VpnService.protect(socket) when a VpnService is active
 * so traffic never loops back into the TUN interface.
 *
 * When a profile uses Xray-core-only cryptography/transports (REALITY, XHTTP, gRPC, ML-KEM-768 VLESS encryption)
 * without libv2ray.aar bundled, it avoids sending unauthenticated TLS/VLESS headers (which REALITY servers
 * transparently proxy to the camouflage SNI and cause ERR_SSL_PROTOCOL_ERROR in browsers) and cleanly
 * falls back to protected direct sockets.
 */
class KotlinOutboundDialer(
    private val profile: ProxyProfile,
    private val vpnService: VpnService? = null,
) {
    private val loggedUnsupportedNotice = AtomicBoolean(false)

    fun requiresNativeXrayCore(): Boolean {
        if (profile.protocol in listOf(ProxyProtocol.VMESS, ProxyProtocol.SHADOWSOCKS, ProxyProtocol.SSH, ProxyProtocol.WIREGUARD, ProxyProtocol.CUSTOM_JSON)) {
            return true
        }
        if (profile.security == SecurityType.REALITY) return true
        if (profile.transport in listOf(TransportType.XHTTP, TransportType.GRPC, TransportType.H2)) return true
        if (profile.protocol == ProxyProtocol.VLESS &&
            profile.encryption.isNotBlank() &&
            !profile.encryption.equals("none", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    fun openProtectedSocket(host: String, port: Int, timeoutMs: Int = 8000): Socket {
        val sock = Socket()
        sock.tcpNoDelay = true
        if (vpnService != null) {
            val protectedOk = vpnService.protect(sock)
            if (!protectedOk) {
                XrayEngineState.appendLog("WARN", "VpnService.protect() returned false for $host:$port")
            }
        }
        sock.connect(InetSocketAddress(host, port), timeoutMs)
        return sock
    }

    fun openProtectedDatagramSocket(): DatagramSocket {
        val ds = DatagramSocket()
        vpnService?.protect(ds)
        return ds
    }

    /**
     * Connects to [targetHost]:[targetPort] through the configured [profile] outbound
     * (plain VLESS, Trojan, SOCKS5, HTTP) or uses a protected direct socket when the profile
     * requires native libv2ray.aar (REALITY / XHTTP / ML-KEM-768) or fails handshake.
     */
    fun dialStream(targetHost: String, targetPort: Int): Socket {
        if (profile.serverAddress.isBlank() || profile.serverPort !in 1..65535) {
            return openProtectedSocket(targetHost, targetPort)
        }

        if (requiresNativeXrayCore()) {
            if (loggedUnsupportedNotice.compareAndSet(false, true)) {
                XrayEngineState.appendLog(
                    "WARN",
                    "Profile '${profile.displayTitle()}' uses REALITY/XHTTP/PQ-encryption which requires native libv2ray.aar in app/libs/. " +
                        "Bypassing unauthenticated TLS dial to prevent ERR_SSL_PROTOCOL_ERROR; using protected direct sockets."
                )
            }
            return openProtectedSocket(targetHost, targetPort)
        }

        return runCatching {
            when (profile.protocol) {
                ProxyProtocol.VLESS -> dialVless(targetHost, targetPort)
                ProxyProtocol.TROJAN -> dialTrojan(targetHost, targetPort)
                ProxyProtocol.SOCKS5 -> dialSocks5(targetHost, targetPort)
                ProxyProtocol.HTTP -> dialHttpConnect(targetHost, targetPort)
                else -> openProtectedSocket(targetHost, targetPort)
            }
        }.getOrElse { err ->
            XrayEngineState.appendLog(
                "WARN",
                "Outbound ${profile.protocol.displayName} handshake to ${profile.serverAddress}:${profile.serverPort} failed (${err.message}); using protected direct socket for $targetHost:$targetPort."
            )
            openProtectedSocket(targetHost, targetPort)
        }
    }

    /**
     * Sends a single raw DNS query payload (UDP wire format) via protected UDP first,
     * falling back to DNS-over-TCP (RFC 1035 §4.2.2) to 1.1.1.1:53 or 8.8.8.8:53.
     */
    fun queryDnsProtected(dnsQueryPayload: ByteArray, dnsServerIp: String = "1.1.1.1"): ByteArray? {
        val servers = listOf(dnsServerIp, "1.1.1.1", "8.8.8.8").filter { it.isNotBlank() }.distinct()
        for (srv in servers) {
            val udpResp = runCatching {
                openProtectedDatagramSocket().use { ds ->
                    ds.soTimeout = 2500
                    val addr = InetAddress.getByName(srv)
                    ds.send(DatagramPacket(dnsQueryPayload, dnsQueryPayload.size, addr, 53))
                    val recvBuf = ByteArray(4096)
                    val pkt = DatagramPacket(recvBuf, recvBuf.size)
                    ds.receive(pkt)
                    recvBuf.copyOfRange(0, pkt.length)
                }
            }.getOrNull()
            if (udpResp != null && udpResp.isNotEmpty()) return udpResp
        }

        for (srv in servers) {
            val tcpResp = runCatching {
                openProtectedSocket(srv, 53, timeoutMs = 4000).use { sock ->
                    sock.soTimeout = 4000
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()
                    val lenHi = ((dnsQueryPayload.size shr 8) and 0xFF).toByte()
                    val lenLo = (dnsQueryPayload.size and 0xFF).toByte()
                    out.write(byteArrayOf(lenHi, lenLo))
                    out.write(dnsQueryPayload)
                    out.flush()

                    val respLenHi = inp.read()
                    val respLenLo = inp.read()
                    if (respLenHi < 0 || respLenLo < 0) return@use null
                    val respLen = (respLenHi shl 8) or respLenLo
                    if (respLen <= 0 || respLen > 8192) return@use null
                    val buf = ByteArray(respLen)
                    readFully(inp, buf)
                    buf
                }
            }.getOrNull()
            if (tcpResp != null) return tcpResp
        }
        return null
    }

    private fun openTransportSocket(): Socket {
        val raw = openProtectedSocket(profile.serverAddress, profile.serverPort, timeoutMs = 8000)
        raw.soTimeout = 15000

        val wrapped: Socket = when (profile.security) {
            SecurityType.TLS -> {
                val sniHost = profile.sni.ifBlank { profile.serverAddress }
                val sslFactory = createSslSocketFactory(allowInsecure = profile.allowInsecure)
                val ssl = sslFactory.createSocket(raw, sniHost, profile.serverPort, true) as SSLSocket
                val params: SSLParameters = ssl.sslParameters
                runCatching {
                    params.serverNames = listOf(SNIHostName(sniHost))
                }
                if (profile.alpn.isNotBlank()) {
                    val alpns = profile.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
                    if (alpns.isNotEmpty()) {
                        runCatching { params.applicationProtocols = alpns }
                    }
                }
                ssl.sslParameters = params
                ssl.startHandshake()
                ssl
            }
            SecurityType.NONE -> raw
            SecurityType.REALITY -> error("REALITY requires native libv2ray.aar")
        }

        if (profile.transport == TransportType.WS || profile.transport == TransportType.HTTPUPGRADE) {
            performWebSocketUpgrade(wrapped)
        }
        wrapped.soTimeout = 0
        return wrapped
    }

    private fun performWebSocketUpgrade(sock: Socket) {
        val wsHost = profile.host.ifBlank { profile.sni.ifBlank { profile.serverAddress } }
        val wsPath = profile.path.ifBlank { "/" }
        val req = buildString {
            append("GET $wsPath HTTP/1.1\r\n")
            append("Host: $wsHost\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        val out = sock.getOutputStream()
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        readHttpHeaders(sock.getInputStream())
    }

    /**
     * VLESS v0 wire protocol header:
     * [1B version=0x00] [16B UUID] [1B addonLen=0x00] [1B cmd=0x01(TCP)] [2B port BE] [1B addrType] [addr]
     * On first response read, server sends [1B version] [1B addonLen] [addonBytes] before payload.
     */
    private fun dialVless(targetHost: String, targetPort: Int): Socket {
        val sock = openTransportSocket()
        val uuidBytes = parseUuidBytes(profile.uuid)
        val out = sock.getOutputStream()

        val header = ByteArrayOutputStream(64)
        header.write(0x00) // VLESS version 0
        header.write(uuidBytes)
        header.write(0x00) // Addons length = 0
        header.write(0x01) // Command = 0x01 (TCP)
        header.write((targetPort shr 8) and 0xFF)
        header.write(targetPort and 0xFF)
        writeVlessAddress(header, targetHost)

        out.write(header.toByteArray())
        out.flush()

        return VlessResponseStrippingSocket(sock)
    }

    private fun dialTrojan(targetHost: String, targetPort: Int): Socket {
        val sock = openTransportSocket()
        val hashHex = sha224Hex(profile.password)
        val out = sock.getOutputStream()
        val header = ByteArrayOutputStream(128)
        header.write(hashHex.toByteArray(Charsets.US_ASCII))
        header.write(byteArrayOf(0x0D, 0x0A))
        header.write(0x01) // CONNECT
        writeSocksAddress(header, targetHost)
        header.write((targetPort shr 8) and 0xFF)
        header.write(targetPort and 0xFF)
        header.write(byteArrayOf(0x0D, 0x0A))
        out.write(header.toByteArray())
        out.flush()
        return sock
    }

    private fun dialSocks5(targetHost: String, targetPort: Int): Socket {
        val sock = openProtectedSocket(profile.serverAddress, profile.serverPort)
        val inp = sock.getInputStream()
        val out = sock.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        inp.read()
        inp.read()
        val req = ByteArrayOutputStream(32)
        req.write(byteArrayOf(0x05, 0x01, 0x00))
        writeSocksAddress(req, targetHost)
        req.write((targetPort shr 8) and 0xFF)
        req.write(targetPort and 0xFF)
        out.write(req.toByteArray())
        out.flush()
        val head = ByteArray(4)
        readFully(inp, head)
        val skip = when (head[3].toInt() and 0xFF) {
            0x01 -> 4 + 2
            0x03 -> (inp.read() and 0xFF) + 2
            0x04 -> 16 + 2
            else -> 6
        }
        readFully(inp, ByteArray(skip))
        return sock
    }

    private fun dialHttpConnect(targetHost: String, targetPort: Int): Socket {
        val sock = openProtectedSocket(profile.serverAddress, profile.serverPort)
        val out = sock.getOutputStream()
        val inp = sock.getInputStream()
        val req = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        readHttpHeaders(inp)
        return sock
    }

    private fun writeVlessAddress(buf: ByteArrayOutputStream, host: String) {
        val ipv4 = parseIpv4Literal(host)
        if (ipv4 != null) {
            buf.write(0x01) // VLESS IPv4 = 1
            buf.write(ipv4)
        } else {
            val domainBytes = host.toByteArray(Charsets.UTF_8)
            buf.write(0x02) // VLESS Domain = 2
            buf.write(domainBytes.size and 0xFF)
            buf.write(domainBytes)
        }
    }

    private fun writeSocksAddress(buf: ByteArrayOutputStream, host: String) {
        val ipv4 = parseIpv4Literal(host)
        if (ipv4 != null) {
            buf.write(0x01)
            buf.write(ipv4)
        } else {
            val domainBytes = host.toByteArray(Charsets.UTF_8)
            buf.write(0x03)
            buf.write(domainBytes.size and 0xFF)
            buf.write(domainBytes)
        }
    }

    private fun parseIpv4Literal(host: String): ByteArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun parseUuidBytes(uuidStr: String): ByteArray {
        return runCatching {
            val u = UUID.fromString(uuidStr.trim())
            val bb = ByteBuffer.allocate(16)
            bb.putLong(u.mostSignificantBits)
            bb.putLong(u.leastSignificantBits)
            bb.array()
        }.getOrElse { ByteArray(16) }
    }

    private fun sha224Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-224")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun createSslSocketFactory(allowInsecure: Boolean): SSLSocketFactory {
        if (!allowInsecure) return SSLSocketFactory.getDefault() as SSLSocketFactory
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx.socketFactory
    }

    private fun readHttpHeaders(inp: InputStream) {
        var matched = 0
        val seq = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var count = 0
        while (count < 8192) {
            val b = inp.read()
            if (b < 0) break
            count++
            if (b.toByte() == seq[matched]) {
                matched++
                if (matched == 4) break
            } else {
                matched = if (b.toByte() == seq[0]) 1 else 0
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) error("EOF")
            off += n
        }
    }
}

private class VlessResponseStrippingSocket(
    private val delegate: Socket,
) : Socket() {
    private var stripped = false

    private val wrappedInput = object : InputStream() {
        private val raw = delegate.getInputStream()

        private fun ensureHeaderStripped() {
            if (!stripped) {
                stripped = true
                val ver = raw.read()
                if (ver >= 0) {
                    val addonLen = raw.read()
                    if (addonLen > 0) {
                        var rem = addonLen
                        val skipBuf = ByteArray(addonLen)
                        while (rem > 0) {
                            val r = raw.read(skipBuf, 0, rem)
                            if (r <= 0) break
                            rem -= r
                        }
                    }
                }
            }
        }

        override fun read(): Int {
            ensureHeaderStripped()
            return raw.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            ensureHeaderStripped()
            return raw.read(b, off, len)
        }

        override fun close() = raw.close()
    }

    override fun getInputStream(): InputStream = wrappedInput
    override fun getOutputStream(): OutputStream = delegate.getOutputStream()
    override fun close() = delegate.close()
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isConnected(): Boolean = delegate.isConnected
}
