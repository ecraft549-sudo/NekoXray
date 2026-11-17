package com.example.core.vpn

import android.net.VpnService
import com.example.core.model.ProxyProfile
import tech.kwik.core.DatagramSocketFactory
import tech.kwik.core.QuicClientConnection
import tech.kwik.core.QuicSessionTicket
import tech.kwik.core.QuicStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Field
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Local SOCKS5 (TCP CONNECT 0x01 + UDP ASSOCIATE 0x03) -> TUIC v5 (QUIC + TLS 1.3 ExportKeyingMaterial)
 * bridge powered by tech.kwik:kwik.
 *
 * Allows Xray-core (tun-in / socks-in / http-in) to proxy TCP and UDP traffic over a TUIC v5 endpoint
 * with support for native QUIC datagrams & QUIC unidirectional streams, fragmentation/reassembly,
 * 0-RTT TLS resumption tickets, heartbeat keepalive, and congestion control / SNI options.
 */
class TuicTunnelBridge(
    private val profile: ProxyProfile,
    private val vpnService: VpnService? = null,
    private val directTcpConnector: ((String, Int) -> Socket)? = null,
) {
    private val running = AtomicBoolean(false)
    private val sessionLock = Any()

    @Volatile
    private var quicConnection: QuicClientConnection? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var executor: ExecutorService? = null

    private val assocIdGen = AtomicInteger(1)
    private val packetIdGen = AtomicInteger(0)
    private val udpAssocs = ConcurrentHashMap<Int, UdpAssociation>()
    private val fragBuffers = ConcurrentHashMap<Long, FragmentReassembler>()

    val localPort: Int
        get() = serverSocket?.localPort ?: 0

    private class UdpAssociation(
        val assocId: Int,
        val udpSocket: DatagramSocket,
        @Volatile var clientAddr: InetAddress? = null,
        @Volatile var clientPort: Int = 0,
    )

    private class FragmentReassembler(
        val assocId: Int,
        val pktId: Int,
        val fragTotal: Int,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        @Volatile
        var addr: TuicAddress? = null
        val fragments: Array<ByteArray?> = arrayOfNulls(fragTotal)
        var receivedCount = 0

        @Synchronized
        fun accept(fragId: Int, decodedAddr: TuicAddress?, data: ByteArray): TuicUdpPacket? {
            if (fragId !in 0 until fragTotal) return null
            if (fragId == 0 && decodedAddr != null && decodedAddr !is TuicAddress.None) {
                addr = decodedAddr
            }
            if (fragments[fragId] == null) {
                fragments[fragId] = data
                receivedCount++
            }
            if (receivedCount < fragTotal) return null
            val targetAddr = addr ?: return null
            val totalLen = fragments.sumOf { it?.size ?: 0 }
            val merged = ByteArray(totalLen)
            var pos = 0
            for (part in fragments) {
                if (part == null) return null
                System.arraycopy(part, 0, merged, pos, part.size)
                pos += part.size
            }
            return TuicUdpPacket(assocId = assocId, pktId = pktId, address = targetAddr, payload = merged)
        }
    }

    fun start(): Int {
        if (running.get() && (directTcpConnector != null || quicConnection?.isConnected == true) && localPort > 0) {
            return localPort
        }

        if (directTcpConnector == null) {
            quicConnection = connectAndAuthenticate(profile, vpnService)
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
        }, "NekoXray-TUIC-Bridge").apply {
            isDaemon = true
            start()
        }

        val hbMs = profile.tuicHeartbeatIntervalMs
        if (directTcpConnector == null && hbMs > 0) {
            heartbeatThread = Thread({
                val interval = hbMs.coerceIn(1000, 120_000).toLong()
                while (running.get()) {
                    try {
                        Thread.sleep(interval)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (!running.get()) break
                    val conn = quicConnection ?: continue
                    if (conn.isConnected && conn.canSendDatagram()) {
                        runCatching { conn.sendDatagram(buildHeartbeatFrame()) }
                    }
                }
            }, "NekoXray-TUIC-Heartbeat").apply {
                isDaemon = true
                start()
            }
        }

        XrayEngineState.appendLog(
            "INFO",
            "TUIC v5 connected to ${profile.serverAddress}:${profile.serverPort} " +
                "(udp=${profile.tuicUdpRelayMode}, cc=${profile.tuicCongestionControl}); " +
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
        runCatching { heartbeatThread?.interrupt() }
        heartbeatThread = null
        udpAssocs.values.forEach { assoc ->
            runCatching { assoc.udpSocket.close() }
        }
        udpAssocs.clear()
        fragBuffers.clear()
        runCatching { executor?.shutdownNow() }
        executor = null
        synchronized(sessionLock) {
            runCatching { quicConnection?.close() }
            quicConnection = null
        }
    }

    fun reconnectSession(reason: String = "network handoff") {
        if (!running.get() || directTcpConnector != null) return
        synchronized(sessionLock) {
            runCatching { quicConnection?.close() }
            quicConnection = null
            runCatching {
                quicConnection = connectAndAuthenticate(profile, vpnService)
                XrayEngineState.appendLog(
                    "INFO",
                    "TUIC v5 session re-established after $reason (${profile.serverAddress}:${profile.serverPort}).",
                )
            }.onFailure { err ->
                XrayEngineState.appendLog(
                    "WARN",
                    "TUIC v5 session lazy reconnect queued after $reason: ${err.message ?: err.javaClass.simpleName}",
                )
            }
        }
    }

    private fun getOrConnectQuic(): QuicClientConnection {
        val current = quicConnection
        if (current != null && current.isConnected) return current
        synchronized(sessionLock) {
            val again = quicConnection
            if (again != null && again.isConnected) return again
            XrayEngineState.appendLog("INFO", "Reconnecting TUIC v5 session to ${profile.serverAddress}:${profile.serverPort}...")
            val reconnected = connectAndAuthenticate(profile, vpnService)
            quicConnection = reconnected
            return reconnected
        }
    }

    private fun connectAndAuthenticate(
        profile: ProxyProfile,
        vpnService: VpnService?,
        timeoutMs: Int = 12_000,
    ): QuicClientConnection {
        val conn = connectTuicQuicSession(profile, vpnService, timeoutMs)
        conn.setDatagramHandler { bytes ->
            handleIncomingPacketBytes(bytes)
        }
        conn.setPeerInitiatedStreamCallback { stream ->
            executor?.execute { handleIncomingPeerStream(stream) }
        }
        sendAuthenticateCommand(conn, profile)
        return conn
    }

    private fun handleSocks5Client(client: Socket) {
        client.use { sock ->
            runCatching {
                sock.tcpNoDelay = true
                sock.soTimeout = 15_000
                val inp = sock.getInputStream()
                val out = sock.getOutputStream()

                // 1. SOCKS5 Greeting
                val ver = inp.read()
                val nMethods = inp.read()
                if (ver != 0x05 || nMethods < 1) return
                val methods = ByteArray(nMethods)
                readFully(inp, methods)
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                // 2. SOCKS5 Request
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

        val stream: QuicStream
        try {
            val conn = getOrConnectQuic()
            stream = conn.createStream(true)
            val streamOut = stream.outputStream
            streamOut.write(buildConnectHeader(destHost, destPort))
            streamOut.flush()
        } catch (e: Exception) {
            XrayEngineState.appendLog(
                "WARN",
                "TUIC CONNECT to $destHost:$destPort failed: ${e.message ?: e.javaClass.simpleName}"
            )
            runCatching {
                out.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
            }
            return
        }

        try {
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
            sock.soTimeout = 0
            relayStreams(
                clientIn = inp,
                clientOut = out,
                remoteIn = stream.inputStream,
                remoteOut = stream.outputStream,
                isRemoteAlive = { quicConnection?.isConnected == true },
            )
        } catch (e: Exception) {
            XrayEngineState.appendLog(
                "WARN",
                "TUIC relay to $destHost:$destPort interrupted: ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            runCatching { stream.outputStream.close() }
            runCatching { stream.abortReading(0L) }
        }
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

    private fun handleUdpAssociate(sock: Socket, inp: InputStream, out: OutputStream) {
        val assocId = assocIdGen.getAndIncrement() and 0xFFFF
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { udpSocket ->
            udpSocket.soTimeout = 2000
            val bindPort = udpSocket.localPort
            val assoc = UdpAssociation(assocId = assocId, udpSocket = udpSocket)
            udpAssocs[assocId] = assoc

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
                val buf = ByteArray(65535)
                while (running.get() && udpActive.get() && !udpSocket.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val received = runCatching {
                        udpSocket.receive(pkt)
                        true
                    }.getOrDefault(false)
                    if (!received) continue

                    assoc.clientAddr = pkt.address
                    assoc.clientPort = pkt.port
                    val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                    executor?.execute {
                        forwardClientUdpDatagram(assoc, data)
                    }
                }
            }, "NekoXray-TUIC-UDP-Relay").apply {
                isDaemon = true
                start()
            }

            runCatching {
                while (running.get() && !sock.isClosed) {
                    if (inp.read() < 0) break
                }
            }
            udpActive.set(false)
            udpAssocs.remove(assocId)
            sendDissociateCommand(assocId)
            runCatching { udpSocket.close() }
            runCatching { udpWorker.join(500) }
        }
    }

    private fun forwardClientUdpDatagram(assoc: UdpAssociation, socks5Packet: ByteArray) {
        val parsed = SshTunnelBridge.parseSocks5UdpPacket(socks5Packet) ?: return
        val conn = runCatching { getOrConnectQuic() }.getOrNull() ?: return
        val pktId = packetIdGen.getAndIncrement() and 0xFFFF
        val addrBytes = encodeTuicAddress(parsed.destHost, parsed.destPort)
        val useDatagram = !profile.tuicUdpRelayMode.equals("quic", ignoreCase = true) && conn.canSendDatagram()

        if (useDatagram) {
            val maxDgram = conn.maxDatagramDataSize().coerceAtLeast(300)
            // Packet header: VER(1) + CMD(1) + ASSOC_ID(2) + PKT_ID(2) + FRAG_TOTAL(1) + FRAG_ID(1) + SIZE(2) = 10 + addrBytes
            val maxChunkFirst = (maxDgram - 10 - addrBytes.size).coerceAtLeast(64)
            if (parsed.payload.size <= maxChunkFirst) {
                val frame = buildPacketFrame(
                    assocId = assoc.assocId,
                    pktId = pktId,
                    fragTotal = 1,
                    fragId = 0,
                    encodedAddr = addrBytes,
                    payload = parsed.payload,
                )
                runCatching { conn.sendDatagram(frame) }
            } else {
                val maxChunkRest = (maxDgram - 11).coerceAtLeast(64)
                val chunks = ArrayList<ByteArray>()
                var offset = 0
                val firstLen = minOf(maxChunkFirst, parsed.payload.size)
                chunks.add(parsed.payload.copyOfRange(0, firstLen))
                offset = firstLen
                while (offset < parsed.payload.size) {
                    val end = minOf(offset + maxChunkRest, parsed.payload.size)
                    chunks.add(parsed.payload.copyOfRange(offset, end))
                    offset = end
                }
                val total = chunks.size.coerceAtMost(255)
                for (i in 0 until total) {
                    val fAddr = if (i == 0) addrBytes else byteArrayOf(ADDR_TYPE_NONE.toByte())
                    val frame = buildPacketFrame(
                        assocId = assoc.assocId,
                        pktId = pktId,
                        fragTotal = total,
                        fragId = i,
                        encodedAddr = fAddr,
                        payload = chunks[i],
                    )
                    runCatching { conn.sendDatagram(frame) }
                }
            }
        } else {
            val frame = buildPacketFrame(
                assocId = assoc.assocId,
                pktId = pktId,
                fragTotal = 1,
                fragId = 0,
                encodedAddr = addrBytes,
                payload = parsed.payload,
            )
            runCatching {
                val uni = conn.createStream(false)
                uni.outputStream.use { out ->
                    out.write(frame)
                    out.flush()
                }
            }
        }
    }

    private fun handleIncomingPacketBytes(bytes: ByteArray) {
        val packet = parsePacketFrame(bytes) ?: return
        deliverIncomingUdpPacket(packet)
    }

    private fun handleIncomingPeerStream(stream: QuicStream) {
        runCatching {
            val inp = stream.inputStream
            val header = ByteArray(2)
            readFully(inp, header)
            val ver = header[0].toInt() and 0xFF
            val cmd = header[1].toInt() and 0xFF
            if (ver != TUIC_VERSION || cmd != CMD_PACKET) return

            val meta = ByteArray(8)
            readFully(inp, meta)
            val assocId = ((meta[0].toInt() and 0xFF) shl 8) or (meta[1].toInt() and 0xFF)
            val pktId = ((meta[2].toInt() and 0xFF) shl 8) or (meta[3].toInt() and 0xFF)
            val fragTotal = meta[4].toInt() and 0xFF
            val fragId = meta[5].toInt() and 0xFF
            val size = ((meta[6].toInt() and 0xFF) shl 8) or (meta[7].toInt() and 0xFF)
            val addr = readTuicAddress(inp) ?: return
            val payload = ByteArray(size)
            readFully(inp, payload)

            deliverIncomingUdpFragment(assocId, pktId, fragTotal, fragId, addr, payload)
        }
    }

    private fun deliverIncomingUdpPacket(frame: TuicRawPacketFragment) {
        deliverIncomingUdpFragment(
            assocId = frame.assocId,
            pktId = frame.pktId,
            fragTotal = frame.fragTotal,
            fragId = frame.fragId,
            addr = frame.address,
            payload = frame.payload,
        )
    }

    private fun deliverIncomingUdpFragment(
        assocId: Int,
        pktId: Int,
        fragTotal: Int,
        fragId: Int,
        addr: TuicAddress,
        payload: ByteArray,
    ) {
        if (fragTotal <= 0 || fragId !in 0 until fragTotal) return
        val assoc = udpAssocs[assocId] ?: return
        val clientAddr = assoc.clientAddr ?: return
        val clientPort = assoc.clientPort
        if (clientPort <= 0) return

        val ready: TuicUdpPacket? = if (fragTotal == 1 && fragId == 0) {
            if (addr is TuicAddress.None) null else TuicUdpPacket(assocId, pktId, addr, payload)
        } else {
            val key = (assocId.toLong() shl 16) or (pktId.toLong() and 0xFFFFL)
            if (fragBuffers.size > 256) {
                val cutoff = System.currentTimeMillis() - 10_000L
                fragBuffers.entries.removeIf { it.value.createdAtMs < cutoff }
            }
            val reassembler = fragBuffers.computeIfAbsent(key) {
                FragmentReassembler(assocId, pktId, fragTotal)
            }
            val completed = reassembler.accept(fragId, addr, payload)
            if (completed != null) fragBuffers.remove(key)
            completed
        }

        if (ready != null) {
            val (host, port) = when (val a = ready.address) {
                is TuicAddress.Domain -> a.host to a.port
                is TuicAddress.Ip -> a.address.hostAddress.orEmpty() to a.port
                TuicAddress.None -> return
            }
            if (host.isBlank() || port !in 1..65535) return
            val socks5Resp = SshTunnelBridge.buildSocks5UdpPacket(host, port, ready.payload)
            runCatching {
                assoc.udpSocket.send(DatagramPacket(socks5Resp, socks5Resp.size, clientAddr, clientPort))
            }
        }
    }

    private fun sendDissociateCommand(assocId: Int) {
        val conn = quicConnection ?: return
        if (!conn.isConnected) return
        runCatching {
            val uni = conn.createStream(false)
            uni.outputStream.use { out ->
                out.write(buildDissociateFrame(assocId))
                out.flush()
            }
        }
    }

    private fun sendAuthenticateCommand(conn: QuicClientConnection, profile: ProxyProfile) {
        val uuidBytes = parseUuid16Bytes(profile.uuid)
        val token = exportTuicToken(conn, uuidBytes, profile.password)
        val authFrame = buildAuthenticateFrame(uuidBytes, token)
        val uni = conn.createStream(false)
        uni.outputStream.use { out ->
            out.write(authFrame)
            out.flush()
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("Unexpected EOF")
            off += n
        }
    }

    sealed class TuicAddress {
        data object None : TuicAddress()
        data class Domain(val host: String, val port: Int) : TuicAddress()
        data class Ip(val address: InetAddress, val port: Int) : TuicAddress()
    }

    data class TuicRawPacketFragment(
        val assocId: Int,
        val pktId: Int,
        val fragTotal: Int,
        val fragId: Int,
        val address: TuicAddress,
        val payload: ByteArray,
    )

    data class TuicUdpPacket(
        val assocId: Int,
        val pktId: Int,
        val address: TuicAddress,
        val payload: ByteArray,
    )

    companion object {
        const val TUIC_VERSION = 0x05
        const val CMD_AUTHENTICATE = 0x00
        const val CMD_CONNECT = 0x01
        const val CMD_PACKET = 0x02
        const val CMD_DISSOCIATE = 0x03
        const val CMD_HEARTBEAT = 0x04

        const val ADDR_TYPE_DOMAIN = 0x00
        const val ADDR_TYPE_IPV4 = 0x01
        const val ADDR_TYPE_IPV6 = 0x02
        const val ADDR_TYPE_NONE = 0xFF

        private val sessionTicketCache = ConcurrentHashMap<String, QuicSessionTicket>()

        fun testTuicHandshake(profile: ProxyProfile): Int? {
            val start = System.nanoTime()
            return runCatching {
                val conn = connectTuicQuicSession(profile, null, timeoutMs = 7000)
                try {
                    val uuidBytes = parseUuid16Bytes(profile.uuid)
                    val token = exportTuicToken(conn, uuidBytes, profile.password)
                    val uni = conn.createStream(false)
                    uni.outputStream.use { out ->
                        out.write(buildAuthenticateFrame(uuidBytes, token))
                        out.flush()
                    }
                    ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
                } finally {
                    runCatching { conn.close() }
                }
            }.getOrNull()
        }

        private fun connectTuicQuicSession(
            profile: ProxyProfile,
            vpnService: VpnService?,
            timeoutMs: Int = 12_000,
        ): QuicClientConnection {
            val host = profile.serverAddress.trim()
            val port = profile.serverPort.takeIf { it in 1..65535 } ?: 443
            require(host.isNotBlank()) { "TUIC server address is required." }

            val alpn = profile.alpn
                .split(',', ' ')
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: "h3"
            val effectiveSni = profile.sni.trim()
            val ticketKey = "$host:$port:${effectiveSni.ifBlank { host }}:$alpn"

            val builder = QuicClientConnection.newBuilder()
                .host(host)
                .port(port)
                .applicationProtocol(alpn)
                .connectTimeout(Duration.ofMillis(timeoutMs.toLong()))
                .maxIdleTimeout(Duration.ofSeconds(60))
                .maxOpenPeerInitiatedUnidirectionalStreams(1024)
                .enableDatagramExtension()
                .socketFactory(DatagramSocketFactory {
                    DatagramSocket().also { ds ->
                        if (vpnService is XrayVpnService) {
                            vpnService.bindDatagramSocketToUnderlyingNetwork(ds)
                        } else {
                            vpnService?.protect(ds)
                        }
                    }
                })

            if (profile.allowInsecure) {
                builder.noServerCertificateCheck()
            }
            if (profile.tuicDisableSni) {
                builder.noServerHostnameVerification()
            }
            if (profile.tuicZeroRttHandshake) {
                sessionTicketCache[ticketKey]?.let { ticket ->
                    builder.sessionTicket(ticket)
                }
            }

            val conn = builder.build()

            // Kwik uses Builder.host(...) both for remote InetAddress resolution (done inside build())
            // and for TLS SNI (read from field 'host' inside startHandshake()).
            // When custom SNI is configured and disable_sni is false, override the 'host' field before connect().
            if (!profile.tuicDisableSni && effectiveSni.isNotEmpty() && !effectiveSni.equals(host, ignoreCase = true)) {
                runCatching {
                    val hostField = findField(conn.javaClass, "host")
                    hostField.set(conn, effectiveSni)
                }
            }

            conn.connect()

            if (profile.tuicZeroRttHandshake) {
                runCatching {
                    conn.newSessionTickets.lastOrNull()?.let { ticket ->
                        sessionTicketCache[ticketKey] = ticket
                    }
                }
            }

            return conn
        }

        fun parseUuid16Bytes(uuidText: String): ByteArray {
            val parsed = UUID.fromString(uuidText.trim())
            return ByteBuffer.allocate(16)
                .putLong(parsed.mostSignificantBits)
                .putLong(parsed.leastSignificantBits)
                .array()
        }

        /**
         * Extracts TLS 1.3 handshake secrets from Kwik's Agent15 TlsState and computes the
         * TUIC v5 ExportKeyingMaterial(label = uuid16, context = passwordUtf8, length = 32) token.
         */
        fun exportTuicToken(conn: QuicClientConnection, uuid16Bytes: ByteArray, password: String): ByteArray {
            val tlsEngine = findField(conn.javaClass, "tlsEngine").get(conn)
                ?: error("QUIC TLS engine not initialized")
            val tlsState = findField(tlsEngine.javaClass, "state").get(tlsEngine)
                ?: error("QUIC TLS state not initialized")

            val masterSecret = findField(tlsState.javaClass, "masterSecret").get(tlsState) as? ByteArray
                ?: error("TLS masterSecret unavailable")
            val hashFunction = findField(tlsState.javaClass, "hashFunction").get(tlsState) as? MessageDigest
                ?: error("TLS hashFunction unavailable")
            val emptyHash = findField(tlsState.javaClass, "emptyHash").get(tlsState) as? ByteArray
                ?: error("TLS emptyHash unavailable")
            val hashLength = (findField(tlsState.javaClass, "hashLength").get(tlsState) as? Number)?.toInt()
                ?: hashFunction.digestLength
            val transcriptHash = findField(tlsState.javaClass, "transcriptHash").get(tlsState)
                ?: error("TLS transcriptHash unavailable")
            val handshakeTypeClass = Class.forName(
                "tech.kwik.agent15.TlsConstants\$HandshakeType",
                true,
                transcriptHash.javaClass.classLoader,
            )
            val finishedConst = handshakeTypeClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "finished" }
                ?: error("HandshakeType.finished enum not found")
            val getServerHashMethod = transcriptHash.javaClass.getMethod("getServerHash", handshakeTypeClass)
            val serverFinishedHash = getServerHashMethod.invoke(transcriptHash, finishedConst) as ByteArray
            return computeTlsExporterToken(
                masterSecret = masterSecret,
                serverFinishedHash = serverFinishedHash,
                emptyHash = emptyHash,
                hashLength = hashLength,
                digestAlgorithm = hashFunction.algorithm,
                uuid16Bytes = uuid16Bytes,
                passwordBytes = password.toByteArray(Charsets.UTF_8),
            )
        }

        /**
         * Pure RFC 8446 §7.1 & §7.5 TLS-Exporter implementation:
         * 1. exporter_master_secret = Derive-Secret(master_secret, "exp master", Transcript(CH..SF))
         * 2. derived_secret = Derive-Secret(exporter_master_secret, label, "")
         * 3. exported = HKDF-Expand-Label(derived_secret, "exporter", Hash(context), length)
         */
        fun computeTlsExporterToken(
            masterSecret: ByteArray,
            serverFinishedHash: ByteArray,
            emptyHash: ByteArray,
            hashLength: Int,
            digestAlgorithm: String,
            uuid16Bytes: ByteArray,
            passwordBytes: ByteArray,
            outLength: Int = 32,
        ): ByteArray {
            val macAlg = when {
                digestAlgorithm.contains("384") -> "HmacSHA384"
                else -> "HmacSHA256"
            }
            val exporterMasterSecret = hkdfExpandLabel(
                macAlgorithm = macAlg,
                secret = masterSecret,
                labelBytes = "exp master".toByteArray(Charsets.US_ASCII),
                contextHash = serverFinishedHash,
                length = hashLength,
            )
            val derivedSecret = hkdfExpandLabel(
                macAlgorithm = macAlg,
                secret = exporterMasterSecret,
                labelBytes = uuid16Bytes,
                contextHash = emptyHash,
                length = hashLength,
            )
            val contextHash = MessageDigest.getInstance(digestAlgorithm).digest(passwordBytes)
            return hkdfExpandLabel(
                macAlgorithm = macAlg,
                secret = derivedSecret,
                labelBytes = "exporter".toByteArray(Charsets.US_ASCII),
                contextHash = contextHash,
                length = outLength,
            )
        }

        /**
         * RFC 8446 §7.1 HKDF-Expand-Label(Secret, Label, Context, Length)
         */
        fun hkdfExpandLabel(
            macAlgorithm: String,
            secret: ByteArray,
            labelBytes: ByteArray,
            contextHash: ByteArray,
            length: Int,
        ): ByteArray {
            val prefix = "tls13 ".toByteArray(Charsets.US_ASCII)
            val fullLabel = ByteArray(prefix.size + labelBytes.size)
            System.arraycopy(prefix, 0, fullLabel, 0, prefix.size)
            System.arraycopy(labelBytes, 0, fullLabel, prefix.size, labelBytes.size)

            val hkdfLabel = ByteArray(2 + 1 + fullLabel.size + 1 + contextHash.size)
            var p = 0
            hkdfLabel[p++] = ((length ushr 8) and 0xFF).toByte()
            hkdfLabel[p++] = (length and 0xFF).toByte()
            hkdfLabel[p++] = (fullLabel.size and 0xFF).toByte()
            System.arraycopy(fullLabel, 0, hkdfLabel, p, fullLabel.size)
            p += fullLabel.size
            hkdfLabel[p++] = (contextHash.size and 0xFF).toByte()
            System.arraycopy(contextHash, 0, hkdfLabel, p, contextHash.size)

            val mac = Mac.getInstance(macAlgorithm)
            mac.init(SecretKeySpec(secret, macAlgorithm))
            val hashLen = mac.macLength
            val result = ByteArray(length)
            var previousT = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.reset()
                mac.update(previousT)
                mac.update(hkdfLabel)
                mac.update(counter.toByte())
                val t = mac.doFinal()
                val copyLen = minOf(hashLen, length - offset)
                System.arraycopy(t, 0, result, offset, copyLen)
                offset += copyLen
                previousT = t
                counter++
            }
            return result
        }

        fun buildAuthenticateFrame(uuid16Bytes: ByteArray, token32Bytes: ByteArray): ByteArray {
            require(uuid16Bytes.size == 16) { "UUID must be 16 bytes" }
            require(token32Bytes.size == 32) { "Token must be 32 bytes" }
            return ByteArray(2 + 16 + 32).apply {
                this[0] = TUIC_VERSION.toByte()
                this[1] = CMD_AUTHENTICATE.toByte()
                System.arraycopy(uuid16Bytes, 0, this, 2, 16)
                System.arraycopy(token32Bytes, 0, this, 18, 32)
            }
        }

        fun buildConnectHeader(destHost: String, destPort: Int): ByteArray {
            val addr = encodeTuicAddress(destHost, destPort)
            return ByteArray(2 + addr.size).apply {
                this[0] = TUIC_VERSION.toByte()
                this[1] = CMD_CONNECT.toByte()
                System.arraycopy(addr, 0, this, 2, addr.size)
            }
        }

        fun buildPacketFrame(
            assocId: Int,
            pktId: Int,
            fragTotal: Int,
            fragId: Int,
            encodedAddr: ByteArray,
            payload: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream(10 + encodedAddr.size + payload.size)
            out.write(TUIC_VERSION)
            out.write(CMD_PACKET)
            out.write((assocId ushr 8) and 0xFF)
            out.write(assocId and 0xFF)
            out.write((pktId ushr 8) and 0xFF)
            out.write(pktId and 0xFF)
            out.write(fragTotal and 0xFF)
            out.write(fragId and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(encodedAddr)
            out.write(payload)
            return out.toByteArray()
        }

        fun parsePacketFrame(bytes: ByteArray): TuicRawPacketFragment? {
            if (bytes.size < 11) return null
            val ver = bytes[0].toInt() and 0xFF
            val cmd = bytes[1].toInt() and 0xFF
            if (ver != TUIC_VERSION || cmd != CMD_PACKET) return null
            val assocId = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            val pktId = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            val fragTotal = bytes[6].toInt() and 0xFF
            val fragId = bytes[7].toInt() and 0xFF
            val size = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)

            val inp = ByteArrayInputStream(bytes, 10, bytes.size - 10)
            val addr = readTuicAddress(inp) ?: return null
            if (inp.available() < size) return null
            val payload = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = inp.read(payload, read, size - read)
                if (n < 0) return null
                read += n
            }
            return TuicRawPacketFragment(
                assocId = assocId,
                pktId = pktId,
                fragTotal = fragTotal,
                fragId = fragId,
                address = addr,
                payload = payload,
            )
        }

        fun buildDissociateFrame(assocId: Int): ByteArray = byteArrayOf(
            TUIC_VERSION.toByte(),
            CMD_DISSOCIATE.toByte(),
            ((assocId ushr 8) and 0xFF).toByte(),
            (assocId and 0xFF).toByte(),
        )

        fun buildHeartbeatFrame(): ByteArray = byteArrayOf(
            TUIC_VERSION.toByte(),
            CMD_HEARTBEAT.toByte(),
        )

        fun encodeTuicAddress(host: String, port: Int): ByteArray {
            val clean = host.trim().trimStart('[').trimEnd(']')
            if (clean.isEmpty()) return byteArrayOf(ADDR_TYPE_NONE.toByte())
            val out = ByteArrayOutputStream(clean.length + 4)
            val parsedIp = if (isNumericAddressLiteral(clean)) {
                runCatching { InetAddress.getByName(clean) }.getOrNull()
            } else {
                null
            }
            when (parsedIp) {
                is Inet4Address -> {
                    out.write(ADDR_TYPE_IPV4)
                    out.write(parsedIp.address)
                }
                is Inet6Address -> {
                    out.write(ADDR_TYPE_IPV6)
                    out.write(parsedIp.address)
                }
                else -> {
                    val domainBytes = clean.toByteArray(Charsets.UTF_8)
                    val len = domainBytes.size.coerceAtMost(255)
                    out.write(ADDR_TYPE_DOMAIN)
                    out.write(len)
                    out.write(domainBytes, 0, len)
                }
            }
            out.write((port ushr 8) and 0xFF)
            out.write(port and 0xFF)
            return out.toByteArray()
        }

        fun readTuicAddress(inp: InputStream): TuicAddress? {
            val type = inp.read()
            if (type < 0) return null
            return when (type) {
                ADDR_TYPE_NONE -> TuicAddress.None
                ADDR_TYPE_DOMAIN -> {
                    val len = inp.read()
                    if (len < 0) return null
                    val domainBytes = ByteArray(len)
                    if (!readExact(inp, domainBytes)) return null
                    val port = readU16(inp) ?: return null
                    TuicAddress.Domain(String(domainBytes, Charsets.UTF_8), port)
                }
                ADDR_TYPE_IPV4 -> {
                    val addrBytes = ByteArray(4)
                    if (!readExact(inp, addrBytes)) return null
                    val port = readU16(inp) ?: return null
                    TuicAddress.Ip(InetAddress.getByAddress(addrBytes), port)
                }
                ADDR_TYPE_IPV6 -> {
                    val addrBytes = ByteArray(16)
                    if (!readExact(inp, addrBytes)) return null
                    val port = readU16(inp) ?: return null
                    TuicAddress.Ip(InetAddress.getByAddress(addrBytes), port)
                }
                else -> null
            }
        }

        private fun isNumericAddressLiteral(host: String): Boolean {
            if (host.contains(':')) return true
            val parts = host.split('.')
            if (parts.size != 4) return false
            return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }

        private fun readExact(inp: InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = inp.read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
            return true
        }

        private fun readU16(inp: InputStream): Int? {
            val hi = inp.read()
            val lo = inp.read()
            if (hi < 0 || lo < 0) return null
            return (hi shl 8) or lo
        }

        private fun findField(clazz: Class<*>, name: String): Field {
            var c: Class<*>? = clazz
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    return f
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            throw NoSuchFieldException("Field '$name' not found on ${clazz.name}")
        }
    }
}
