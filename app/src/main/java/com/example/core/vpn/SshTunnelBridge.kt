package com.example.core.vpn

import android.net.VpnService
import com.example.core.model.ProxyProfile
import com.example.core.model.SshAuthType
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local SOCKS5 (TCP CONNECT + UDP ASSOCIATE DNS-over-TCP) -> SSH direct-tcpip bridge powered by mwiede/jsch.
 * Allows Xray-core (tun-in / socks-in / http-in) to proxy all device traffic and DNS over an SSH server
 * just like NekoBoxForAndroid's native SSH outbound.
 */
class SshTunnelBridge(
    private val profile: ProxyProfile,
    private val vpnService: VpnService? = null,
    private val directTcpConnector: ((String, Int) -> Socket)? = null,
) {
    private val running = AtomicBoolean(false)
    private val sessionLock = Any()
    @Volatile
    private var session: Session? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ExecutorService? = null

    val localPort: Int
        get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        if (running.get() && (directTcpConnector != null || session?.isConnected == true) && localPort > 0) {
            return localPort
        }

        if (directTcpConnector == null) {
            session = connectSshSession(profile, vpnService)
        }

        val srv = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = srv
        executor = Executors.newCachedThreadPool()
        running.set(true)

        acceptThread = Thread({
            while (running.get() && !srv.isClosed) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                executor?.execute { handleSocks5Client(client) }
            }
        }, "NekoXray-SSH-Bridge").apply {
            isDaemon = true
            start()
        }

        XrayEngineState.appendLog(
            "INFO",
            "SSH tunnel connected to ${profile.username.ifBlank { "root" }}@${profile.serverAddress}:${profile.serverPort}; " +
                "local SOCKS5 TCP+UDP bridge listening on 127.0.0.1:${srv.localPort}"
        )
        return srv.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        runCatching { executor?.shutdownNow() }
        executor = null
        synchronized(sessionLock) {
            runCatching { session?.disconnect() }
            session = null
        }
    }

    fun reconnectSession(reason: String = "network handoff") {
        if (!running.get() || directTcpConnector != null) return
        synchronized(sessionLock) {
            runCatching { session?.disconnect() }
            session = null
            runCatching {
                session = connectSshSession(profile, vpnService)
                XrayEngineState.appendLog(
                    "INFO",
                    "SSH session re-established after $reason (${profile.serverAddress}:${profile.serverPort}).",
                )
            }.onFailure { err ->
                XrayEngineState.appendLog(
                    "WARN",
                    "SSH session lazy reconnect queued after $reason: ${err.message ?: err.javaClass.simpleName}",
                )
            }
        }
    }

    private fun getOrConnectSession(): Session {
        val current = session
        if (current != null && current.isConnected) return current
        synchronized(sessionLock) {
            val again = session
            if (again != null && again.isConnected) return again
            XrayEngineState.appendLog("INFO", "Reconnecting SSH session to ${profile.serverAddress}:${profile.serverPort}...")
            val reconnected = connectSshSession(profile, vpnService)
            session = reconnected
            return reconnected
        }
    }

    private fun handleSocks5Client(client: Socket) {
        client.use { sock ->
            runCatching {
                sock.tcpNoDelay = true
                sock.soTimeout = 15_000
                val inp = sock.getInputStream()
                val out = sock.getOutputStream()

                // 1. Greeting
                val ver = inp.read()
                val nMethods = inp.read()
                if (ver != 0x05 || nMethods < 1) return
                val methods = ByteArray(nMethods)
                readFully(inp, methods)
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                // 2. Request
                val reqHead = ByteArray(4)
                readFully(inp, reqHead)
                val cmd = reqHead[1].toInt() and 0xFF
                val atyp = reqHead[3].toInt() and 0xFF

                val destHost = when (atyp) {
                    0x01 -> {
                        val b = ByteArray(4)
                        readFully(inp, b)
                        InetAddress.getByAddress(b).hostAddress.orEmpty()
                    }
                    0x03 -> {
                        val len = inp.read() and 0xFF
                        val b = ByteArray(len)
                        readFully(inp, b)
                        String(b, Charsets.UTF_8)
                    }
                    0x04 -> {
                        val b = ByteArray(16)
                        readFully(inp, b)
                        InetAddress.getByAddress(b).hostAddress.orEmpty()
                    }
                    else -> {
                        out.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        out.flush()
                        return
                    }
                }
                val portHi = inp.read()
                val portLo = inp.read()
                if (portHi < 0 || portLo < 0) return
                val destPort = (portHi shl 8) or portLo

                when (cmd) {
                    0x01 -> handleTcpConnect(sock, inp, out, destHost, destPort)
                    0x03 -> handleUdpAssociate(sock, inp, out)
                    else -> {
                        out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        out.flush()
                    }
                }
            }
        }
    }

    private fun handleTcpConnect(
        sock: Socket,
        inp: InputStream,
        out: OutputStream,
        destHost: String,
        destPort: Int,
    ) {
        val connector = directTcpConnector
        if (connector != null) {
            val remote = runCatching { connector.invoke(destHost, destPort) }.getOrElse {
                out.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                return
            }
            remote.use { rem ->
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                sock.soTimeout = 0
                relayStreams(inp, out, rem.getInputStream(), rem.getOutputStream()) { !rem.isClosed }
            }
            return
        }

        val channel = try {
            openDirectTcpChannel(destHost, destPort, sock.port)
        } catch (e: Exception) {
            XrayEngineState.appendLog(
                "WARN",
                "SSH direct-tcpip to $destHost:$destPort failed: ${e.message ?: e.javaClass.simpleName}"
            )
            runCatching {
                out.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
            }
            return
        }

        try {
            val chIn = channel.inputStream
            val chOut = channel.outputStream
            channel.connect(12_000)

            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
            sock.soTimeout = 0

            relayStreams(inp, out, chIn, chOut) { channel.isConnected }
        } catch (e: Exception) {
            XrayEngineState.appendLog(
                "WARN",
                "SSH channel connect to $destHost:$destPort failed: ${e.message ?: e.javaClass.simpleName}"
            )
            runCatching {
                out.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
            }
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    private fun openDirectTcpChannel(destHost: String, destPort: Int, originPort: Int): ChannelDirectTCPIP {
        val sess = getOrConnectSession()
        val ch = sess.openChannel("direct-tcpip") as ChannelDirectTCPIP
        ch.setHost(destHost)
        ch.setPort(destPort)
        ch.setOrgIPAddress("127.0.0.1")
        ch.setOrgPort(originPort.coerceIn(1024, 65535))
        return ch
    }

    private fun relayStreams(
        clientIn: InputStream,
        clientOut: OutputStream,
        remoteIn: InputStream,
        remoteOut: OutputStream,
        isRemoteAlive: () -> Boolean,
    ) {
        val up = Thread {
            runCatching {
                val buf = ByteArray(16384)
                while (running.get() && isRemoteAlive()) {
                    val n = clientIn.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        remoteOut.write(buf, 0, n)
                        remoteOut.flush()
                    }
                }
            }
            runCatching { remoteOut.close() }
        }.apply {
            isDaemon = true
            start()
        }

        runCatching {
            val buf = ByteArray(16384)
            while (running.get()) {
                val n = remoteIn.read(buf)
                if (n < 0) break
                if (n > 0) {
                    clientOut.write(buf, 0, n)
                    clientOut.flush()
                }
            }
        }
        runCatching { up.join(1000) }
    }

    /**
     * Handles SOCKS5 UDP ASSOCIATE (0x03) by binding a local UDP relay socket on 127.0.0.1
     * and translating UDP DNS queries (port 53) into RFC 1035 TCP DNS queries over SSH direct-tcpip.
     */
    private fun handleUdpAssociate(sock: Socket, inp: InputStream, out: OutputStream) {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { udpSocket ->
            udpSocket.soTimeout = 2000
            val bindPort = udpSocket.localPort
            val reply = byteArrayOf(
                0x05, 0x00, 0x00, 0x01,
                127, 0, 0, 1,
                ((bindPort ushr 8) and 0xFF).toByte(),
                (bindPort and 0xFF).toByte(),
            )
            out.write(reply)
            out.flush()
            sock.soTimeout = 0

            val udpActive = AtomicBoolean(true)
            val udpWorker = Thread({
                val buf = ByteArray(4096)
                while (running.get() && udpActive.get() && !udpSocket.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val received = runCatching {
                        udpSocket.receive(pkt)
                        true
                    }.getOrDefault(false)
                    if (!received) continue

                    val clientAddr = pkt.address
                    val clientPort = pkt.port
                    val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                    executor?.execute {
                        handleSocks5UdpDatagram(udpSocket, clientAddr, clientPort, data)
                    }
                }
            }, "NekoXray-SSH-UDP-Relay").apply {
                isDaemon = true
                start()
            }

            // Keep UDP association open until client closes TCP control connection
            runCatching {
                while (running.get() && !sock.isClosed) {
                    if (inp.read() < 0) break
                }
            }
            udpActive.set(false)
            runCatching { udpSocket.close() }
            runCatching { udpWorker.join(500) }
        }
    }

    private fun handleSocks5UdpDatagram(
        udpSocket: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int,
        packet: ByteArray,
    ) {
        val parsed = parseSocks5UdpPacket(packet) ?: return
        val responsePayload = if (parsed.destPort == 53) {
            queryDnsOverSshTcp(parsed.destHost, parsed.destPort, parsed.payload)
                ?: queryDnsOverProtectedUdp(parsed.destHost, parsed.destPort, parsed.payload)
        } else {
            queryDnsOverProtectedUdp(parsed.destHost, parsed.destPort, parsed.payload)
        } ?: return

        val wrapped = buildSocks5UdpPacket(parsed.destHost, parsed.destPort, responsePayload)
        runCatching {
            udpSocket.send(DatagramPacket(wrapped, wrapped.size, clientAddr, clientPort))
        }
    }

    /**
     * Tunnels a raw DNS query over SSH direct-tcpip using RFC 1035 2-byte length-prefixed TCP DNS.
     */
    fun queryDnsOverSshTcp(destHost: String, destPort: Int, dnsPayload: ByteArray): ByteArray? {
        if (dnsPayload.isEmpty()) return null
        val candidates = listOf(destHost, "1.1.1.1", "8.8.8.8").distinct()
        val connector = directTcpConnector
        for (target in candidates) {
            val res = runCatching {
                if (connector != null) {
                    connector.invoke(target, destPort).use { rem ->
                        rem.soTimeout = 6000
                        exchangeTcpDns(rem.getInputStream(), rem.getOutputStream(), dnsPayload)
                    }
                } else {
                    val channel = openDirectTcpChannel(target, destPort, 53000)
                    try {
                        val chIn = channel.inputStream
                        val chOut = channel.outputStream
                        channel.connect(6000)
                        exchangeTcpDns(chIn, chOut, dnsPayload)
                    } finally {
                        runCatching { channel.disconnect() }
                    }
                }
            }.getOrNull()
            if (res != null && res.isNotEmpty()) return res
        }
        return null
    }

    private fun queryDnsOverProtectedUdp(destHost: String, destPort: Int, payload: ByteArray): ByteArray? =
        runCatching {
            DatagramSocket().use { ds ->
                vpnService?.protect(ds)
                ds.soTimeout = 4000
                val addr = InetAddress.getByName(destHost)
                ds.send(DatagramPacket(payload, payload.size, addr, destPort))
                val respBuf = ByteArray(4096)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                ds.receive(respPkt)
                respBuf.copyOfRange(0, respPkt.length)
            }
        }.getOrNull()

    private fun exchangeTcpDns(inp: InputStream, out: OutputStream, dnsQuery: ByteArray): ByteArray {
        val len = dnsQuery.size
        val framed = ByteArray(2 + len)
        framed[0] = ((len ushr 8) and 0xFF).toByte()
        framed[1] = (len and 0xFF).toByte()
        System.arraycopy(dnsQuery, 0, framed, 2, len)
        out.write(framed)
        out.flush()

        val lenBuf = ByteArray(2)
        readFully(inp, lenBuf)
        val respLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
        require(respLen in 1..65535) { "Invalid TCP DNS response length: $respLen" }
        val resp = ByteArray(respLen)
        readFully(inp, resp)
        return resp
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) error("Unexpected EOF")
            off += n
        }
    }

    data class Socks5UdpFrame(
        val destHost: String,
        val destPort: Int,
        val payload: ByteArray,
    )

    companion object {
        fun parseSocks5UdpPacket(packet: ByteArray): Socks5UdpFrame? {
            if (packet.size < 10) return null
            // RSV must be 0x0000, FRAG must be 0x00
            if (packet[0].toInt() != 0 || packet[1].toInt() != 0 || packet[2].toInt() != 0) return null
            val atyp = packet[3].toInt() and 0xFF
            var offset = 4
            val host = when (atyp) {
                0x01 -> {
                    if (packet.size < offset + 4 + 2) return null
                    val addrBytes = packet.copyOfRange(offset, offset + 4)
                    offset += 4
                    InetAddress.getByAddress(addrBytes).hostAddress.orEmpty()
                }
                0x03 -> {
                    val len = packet[offset].toInt() and 0xFF
                    offset += 1
                    if (packet.size < offset + len + 2) return null
                    val hostStr = String(packet, offset, len, Charsets.UTF_8)
                    offset += len
                    hostStr
                }
                0x04 -> {
                    if (packet.size < offset + 16 + 2) return null
                    val addrBytes = packet.copyOfRange(offset, offset + 16)
                    offset += 16
                    InetAddress.getByAddress(addrBytes).hostAddress.orEmpty()
                }
                else -> return null
            }
            val port = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            offset += 2
            val payload = packet.copyOfRange(offset, packet.size)
            return Socks5UdpFrame(destHost = host, destPort = port, payload = payload)
        }

        fun buildSocks5UdpPacket(destHost: String, destPort: Int, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 32)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00) // FRAG = 0
            val parsedAddr = runCatching { InetAddress.getByName(destHost) }.getOrNull()
            when (parsedAddr) {
                is Inet4Address -> {
                    out.write(0x01)
                    out.write(parsedAddr.address)
                }
                is Inet6Address -> {
                    out.write(0x04)
                    out.write(parsedAddr.address)
                }
                else -> {
                    val hostBytes = destHost.toByteArray(Charsets.UTF_8)
                    out.write(0x03)
                    out.write(hostBytes.size and 0xFF)
                    out.write(hostBytes)
                }
            }
            out.write((destPort ushr 8) and 0xFF)
            out.write(destPort and 0xFF)
            out.write(payload)
            return out.toByteArray()
        }

        fun testSshHandshake(profile: ProxyProfile): Int? {
            val start = System.nanoTime()
            return runCatching {
                val sess = connectSshSession(profile, null, timeoutMs = 7000)
                sess.disconnect()
                ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
            }.getOrNull()
        }

        private fun connectSshSession(
            profile: ProxyProfile,
            vpnService: VpnService?,
            timeoutMs: Int = 12_000,
        ): Session {
            val host = profile.serverAddress.trim()
            val port = profile.serverPort.takeIf { it in 1..65535 } ?: 22
            val user = profile.username.trim().ifBlank { "root" }
            require(host.isNotBlank()) { "SSH server address is required." }

            val jsch = JSch()
            if (profile.sshAuthType == SshAuthType.PRIVATE_KEY && profile.sshPrivateKey.isNotBlank()) {
                val passBytes = profile.sshPrivateKeyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
                jsch.addIdentity("nekoxray-ssh", profile.sshPrivateKey.trim().toByteArray(Charsets.UTF_8), null, passBytes)
            }

            val session = jsch.getSession(user, host, port)
            session.setSocketFactory(object : SocketFactory {
                override fun createSocket(h: String, p: Int): Socket {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    if (vpnService is XrayVpnService) {
                        vpnService.bindSocketToUnderlyingNetwork(s)
                    } else {
                        vpnService?.protect(s)
                    }
                    s.connect(InetSocketAddress(h, p), timeoutMs)
                    return s
                }

                override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
            })

            val config = Properties()
            config["StrictHostKeyChecking"] = if (profile.sshPublicKey.isNotBlank()) "yes" else "no"
            config["PreferredAuthentications"] = when (profile.sshAuthType) {
                SshAuthType.PRIVATE_KEY -> "publickey,keyboard-interactive,password"
                SshAuthType.PASSWORD -> "password,keyboard-interactive,publickey"
                SshAuthType.NONE -> "none"
            }

            if (profile.sshPublicKey.isNotBlank()) {
                val allowedLines = profile.sshPublicKey.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val repo = jsch.hostKeyRepository
                for (line in allowedLines) {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val keyBytes = runCatching {
                            android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
                        }.getOrNull()
                        if (keyBytes != null) {
                            runCatching { repo.add(HostKey(host, keyBytes), null) }
                        }
                    } else if (!line.startsWith("SHA256:", ignoreCase = true)) {
                        val keyBytes = runCatching {
                            android.util.Base64.decode(line, android.util.Base64.DEFAULT)
                        }.getOrNull()
                        if (keyBytes != null) {
                            runCatching { repo.add(HostKey(host, keyBytes), null) }
                        }
                    }
                }
            }

            if (profile.sshAuthType == SshAuthType.PASSWORD) {
                session.setPassword(profile.password)
            }
            session.setConfig(config)
            session.setServerAliveInterval(10_000)
            session.setServerAliveCountMax(3)
            session.connect(timeoutMs)

            val hk = session.hostKey
            if (hk != null) {
                XrayEngineState.appendLog("INFO", "SSH host key verified: ${hk.type} ${hk.getFingerPrint(jsch)}")
            }
            return session
        }
    }
}
