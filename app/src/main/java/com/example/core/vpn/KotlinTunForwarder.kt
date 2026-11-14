package com.example.core.vpn

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-Kotlin user-space IPv4 TUN packet forwarder (ICMP Echo + stateful UDP/DNS NAT + ordered TCP)
 * used when native libv2ray.aar is not bundled in app/libs/.
 *
 * Uses strict TCP sequence ordering + single-writer Channel per flow + MSS clamping (1360 bytes)
 * so multi-segment TLS ClientHello handshakes never reorder or corrupt (preventing ERR_SSL_PROTOCOL_ERROR).
 */
class KotlinTunForwarder private constructor(
    private val tunInput: InputStream,
    private var tunOut: OutputStream?,
    private val dialer: KotlinOutboundDialer,
    private val scope: CoroutineScope,
) {
    constructor(
        tunPfd: ParcelFileDescriptor,
        dialer: KotlinOutboundDialer,
        scope: CoroutineScope,
    ) : this(
        tunInput = FileInputStream(tunPfd.fileDescriptor),
        tunOut = FileOutputStream(tunPfd.fileDescriptor),
        dialer = dialer,
        scope = scope,
    )

    internal constructor(
        input: InputStream,
        output: OutputStream,
        dialer: KotlinOutboundDialer,
        scope: CoroutineScope,
        @Suppress("UNUSED_PARAMETER") forTest: Boolean = true,
    ) : this(
        tunInput = input,
        tunOut = output,
        dialer = dialer,
        scope = scope,
    )

    private val writeLock = Any()
    private var readerJob: Job? = null
    private val tcpSessions = ConcurrentHashMap<FlowKey, TcpSession>()
    private val udpSessions = ConcurrentHashMap<FlowKey, UdpSession>()

    data class FlowKey(
        val srcIp: Int,
        val srcPort: Int,
        val dstIp: Int,
        val dstPort: Int,
    )

    fun start() {
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(32768)
            while (isActive) {
                val n = runCatching { tunInput.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                if (n == 0) continue
                val pkt = buf.copyOfRange(0, n)
                handlePacket(pkt)
            }
        }
        XrayEngineState.appendLog("INFO", "Pure-Kotlin TUN packet engine active (ordered TCP + stateful UDP/DNS + VpnService.protect).")
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        tcpSessions.values.forEach { it.closeQuietly() }
        tcpSessions.clear()
        udpSessions.values.forEach { it.closeQuietly() }
        udpSessions.clear()
        runCatching { tunOut?.close() }
        tunOut = null
    }

    private fun writeTun(packet: ByteArray) {
        synchronized(writeLock) {
            runCatching {
                tunOut?.write(packet)
                tunOut?.flush()
            }
        }
    }

    private fun handlePacket(pkt: ByteArray) {
        if (pkt.size < 20) return
        val version = (pkt[0].toInt() ushr 4) and 0x0F
        if (version != 4) return
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || pkt.size < ihl) return
        val totalLen = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        val actualLen = minOf(totalLen, pkt.size)
        if (actualLen < ihl) return

        val proto = pkt[9].toInt() and 0xFF
        val srcIp = readIntBE(pkt, 12)
        val dstIp = readIntBE(pkt, 16)

        when (proto) {
            1 -> handleIcmp(pkt, ihl, actualLen, srcIp, dstIp)
            17 -> handleUdp(pkt, ihl, actualLen, srcIp, dstIp)
            6 -> handleTcp(pkt, ihl, actualLen, srcIp, dstIp)
        }
    }

    private fun handleIcmp(pkt: ByteArray, ihl: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        if (totalLen < ihl + 8) return
        val icmpType = pkt[ihl].toInt() and 0xFF
        if (icmpType != 8) return
        val reply = pkt.copyOfRange(0, totalLen)
        writeIntBE(reply, 12, dstIp)
        writeIntBE(reply, 16, srcIp)
        reply[ihl] = 0
        reply[ihl + 2] = 0
        reply[ihl + 3] = 0
        val icmpCsum = computeChecksum(reply, ihl, totalLen - ihl)
        reply[ihl + 2] = ((icmpCsum shr 8) and 0xFF).toByte()
        reply[ihl + 3] = (icmpCsum and 0xFF).toByte()
        updateIpv4HeaderChecksum(reply, ihl)
        writeTun(reply)
    }

    private fun handleUdp(pkt: ByteArray, ihl: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        if (totalLen < ihl + 8) return
        val srcPort = readUInt16BE(pkt, ihl)
        val dstPort = readUInt16BE(pkt, ihl + 2)
        val udpLen = readUInt16BE(pkt, ihl + 4)
        val payloadOffset = ihl + 8
        val payloadLen = minOf(totalLen - payloadOffset, udpLen - 8)
        if (payloadLen <= 0) return
        val payload = pkt.copyOfRange(payloadOffset, payloadOffset + payloadLen)

        if (dstPort == 53) {
            scope.launch(Dispatchers.IO) {
                val dnsResp = dialer.queryDnsProtected(payload, intToIpString(dstIp))
                if (dnsResp != null && dnsResp.isNotEmpty()) {
                    val respPkt = buildUdpIpv4Packet(
                        srcIp = dstIp,
                        dstIp = srcIp,
                        srcPort = dstPort,
                        dstPort = srcPort,
                        payload = dnsResp,
                    )
                    writeTun(respPkt)
                }
            }
            return
        }

        val key = FlowKey(srcIp, srcPort, dstIp, dstPort)
        val session = udpSessions.computeIfAbsent(key) {
            UdpSession(key).also { it.start() }
        }
        session.send(payload)
    }

    private inner class UdpSession(private val key: FlowKey) {
        private val sock: DatagramSocket = dialer.openProtectedDatagramSocket().apply {
            soTimeout = 30000
        }
        private val targetAddr: InetAddress = InetAddress.getByName(intToIpString(key.dstIp))
        private var recvJob: Job? = null

        @Volatile
        private var closed = false

        fun start() {
            recvJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(4096)
                val pkt = DatagramPacket(buf, buf.size)
                while (isActive && !closed) {
                    val ok = try {
                        sock.receive(pkt)
                        true
                    } catch (_: SocketTimeoutException) {
                        false
                    } catch (_: Exception) {
                        false
                    }
                    if (!ok) break
                    val respPayload = buf.copyOfRange(0, pkt.length)
                    val respPkt = buildUdpIpv4Packet(
                        srcIp = key.dstIp,
                        dstIp = key.srcIp,
                        srcPort = key.dstPort,
                        dstPort = key.srcPort,
                        payload = respPayload,
                    )
                    writeTun(respPkt)
                }
                closeQuietly()
            }
        }

        fun send(payload: ByteArray) {
            if (closed) return
            scope.launch(Dispatchers.IO) {
                runCatching {
                    sock.send(DatagramPacket(payload, payload.size, targetAddr, key.dstPort))
                }.onFailure {
                    closeQuietly()
                }
            }
        }

        fun closeQuietly() {
            if (closed) return
            closed = true
            udpSessions.remove(key)
            recvJob?.cancel()
            runCatching { sock.close() }
        }
    }

    private fun handleTcp(pkt: ByteArray, ihl: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        if (totalLen < ihl + 20) return
        val srcPort = readUInt16BE(pkt, ihl)
        val dstPort = readUInt16BE(pkt, ihl + 2)
        val seqNum = readUInt32BE(pkt, ihl + 4)
        val ackNum = readUInt32BE(pkt, ihl + 8)
        val dataOffset = ((pkt[ihl + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffset < 20 || ihl + dataOffset > totalLen) return
        val flags = pkt[ihl + 13].toInt() and 0x3F
        val payloadStart = ihl + dataOffset
        val payload = if (totalLen > payloadStart) {
            pkt.copyOfRange(payloadStart, totalLen)
        } else {
            ByteArray(0)
        }

        val flagSyn = (flags and 0x02) != 0
        val flagAck = (flags and 0x10) != 0
        val flagFin = (flags and 0x01) != 0
        val flagRst = (flags and 0x04) != 0

        val key = FlowKey(srcIp, srcPort, dstIp, dstPort)

        if (flagRst) {
            tcpSessions.remove(key)?.closeQuietly()
            return
        }

        if (flagSyn && !flagAck) {
            val existing = tcpSessions[key]
            if (existing != null && existing.clientIsn == seqNum && !existing.isClosed) {
                existing.resendSynAck()
                return
            }
            existing?.closeQuietly()
            val session = TcpSession(key, seqNum)
            tcpSessions[key] = session
            session.onSynReceived()
            return
        }

        val session = tcpSessions[key] ?: return
        session.onPacket(seqNum, ackNum, flagFin, payload)
    }

    private inner class TcpSession(
        val key: FlowKey,
        val clientIsn: Long,
    ) {
        private val sessionLock = Any()
        private val outboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
        private val outOfOrder = HashMap<Long, ByteArray>()

        @Volatile
        private var clientNextSeq: Long = (clientIsn + 1) and 0xFFFFFFFFL

        @Volatile
        private var serverSeq: Long = (System.nanoTime() and 0x7FFFFFFFL)

        private val synAckServerSeq: Long = serverSeq

        @Volatile
        private var remoteSocket: Socket? = null

        @Volatile
        var isClosed: Boolean = false
            private set

        private var finPending: Boolean = false
        private var finSeq: Long = 0L
        private var sentServerFin: Boolean = false
        private var connectJob: Job? = null
        private var writerJob: Job? = null

        fun onSynReceived() {
            val synAckPkt = buildTcpIpv4Packet(
                srcIp = key.dstIp,
                dstIp = key.srcIp,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seq = synAckServerSeq,
                ack = clientNextSeq,
                flags = 0x12, // SYN | ACK
                payload = ByteArray(0),
                mss = 1360,
            )
            serverSeq = (synAckServerSeq + 1) and 0xFFFFFFFFL
            writeTun(synAckPkt)

            connectJob = scope.launch(Dispatchers.IO) {
                val dstHost = intToIpString(key.dstIp)
                runCatching {
                    val sock = dialer.dialStream(dstHost, key.dstPort)
                    remoteSocket = sock

                    writerJob = scope.launch(Dispatchers.IO) {
                        runCatching {
                            val out = sock.getOutputStream()
                            for (chunk in outboundChannel) {
                                out.write(chunk)
                                out.flush()
                            }
                            runCatching { sock.shutdownOutput() }
                        }.onFailure { err ->
                            if (!isClosed && !sentServerFin) {
                                XrayEngineState.appendLog("WARN", "TCP write failed ($dstHost:${key.dstPort}): ${err.message}")
                                sendRst()
                                closeQuietly()
                            }
                        }
                    }

                    val inp = sock.getInputStream()
                    val buf = ByteArray(8192)
                    while (isActive && !isClosed) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        if (r > 0) {
                            var offset = 0
                            while (offset < r) {
                                val segLen = minOf(1360, r - offset)
                                val seg = buf.copyOfRange(offset, offset + segLen)
                                val seqToSend = synchronized(sessionLock) {
                                    val s = serverSeq
                                    serverSeq = (serverSeq + segLen) and 0xFFFFFFFFL
                                    s
                                }
                                val dataPkt = buildTcpIpv4Packet(
                                    srcIp = key.dstIp,
                                    dstIp = key.srcIp,
                                    srcPort = key.dstPort,
                                    dstPort = key.srcPort,
                                    seq = seqToSend,
                                    ack = clientNextSeq,
                                    flags = 0x18, // PSH | ACK
                                    payload = seg,
                                )
                                writeTun(dataPkt)
                                offset += segLen
                            }
                        }
                    }
                    if (!isClosed) {
                        sendFinAck()
                    }
                }.onFailure { err ->
                    if (!isClosed && !sentServerFin) {
                        XrayEngineState.appendLog("WARN", "TCP stream aborted ($dstHost:${key.dstPort}): ${err.message}")
                        sendRst()
                    }
                }
                closeQuietly()
            }
        }

        fun resendSynAck() {
            if (isClosed) return
            val synAckPkt = buildTcpIpv4Packet(
                srcIp = key.dstIp,
                dstIp = key.srcIp,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seq = synAckServerSeq,
                ack = clientNextSeq,
                flags = 0x12,
                payload = ByteArray(0),
                mss = 1360,
            )
            writeTun(synAckPkt)
        }

        fun onPacket(seq: Long, @Suppress("UNUSED_PARAMETER") ack: Long, fin: Boolean, payload: ByteArray) {
            var ackToSend: Long? = null
            var shouldHalfClose = false

            synchronized(sessionLock) {
                if (isClosed) return

                if (payload.isNotEmpty()) {
                    val rel = (seq - clientNextSeq) and 0xFFFFFFFFL
                    when {
                        rel == 0L -> {
                            outboundChannel.trySend(payload)
                            clientNextSeq = (clientNextSeq + payload.size) and 0xFFFFFFFFL
                            drainOutOfOrderLocked()
                            ackToSend = clientNextSeq
                        }
                        rel > 0x80000000L -> {
                            val overlap = (clientNextSeq - seq) and 0xFFFFFFFFL
                            if (payload.size > overlap) {
                                val fresh = payload.copyOfRange(overlap.toInt(), payload.size)
                                outboundChannel.trySend(fresh)
                                clientNextSeq = (clientNextSeq + fresh.size) and 0xFFFFFFFFL
                                drainOutOfOrderLocked()
                            }
                            ackToSend = clientNextSeq
                        }
                        rel in 1L..65536L -> {
                            if (outOfOrder.size < 32 && !outOfOrder.containsKey(seq)) {
                                outOfOrder[seq] = payload
                            }
                            ackToSend = clientNextSeq
                        }
                    }
                }

                if (fin) {
                    finPending = true
                    finSeq = (seq + payload.size) and 0xFFFFFFFFL
                }

                if (finPending && clientNextSeq == finSeq) {
                    clientNextSeq = (clientNextSeq + 1) and 0xFFFFFFFFL
                    finPending = false
                    ackToSend = clientNextSeq
                    shouldHalfClose = true
                }
            }

            if (ackToSend != null) {
                val ackPkt = buildTcpIpv4Packet(
                    srcIp = key.dstIp,
                    dstIp = key.srcIp,
                    srcPort = key.dstPort,
                    dstPort = key.srcPort,
                    seq = synchronized(sessionLock) { serverSeq },
                    ack = ackToSend!!,
                    flags = 0x10,
                    payload = ByteArray(0),
                )
                writeTun(ackPkt)
            }

            if (shouldHalfClose) {
                outboundChannel.close()
            }
        }

        private fun drainOutOfOrderLocked() {
            while (true) {
                val nextChunk = outOfOrder.remove(clientNextSeq) ?: break
                outboundChannel.trySend(nextChunk)
                clientNextSeq = (clientNextSeq + nextChunk.size) and 0xFFFFFFFFL
            }
        }

        private fun sendFinAck() {
            val seqToSend = synchronized(sessionLock) {
                if (isClosed || sentServerFin) return
                sentServerFin = true
                val s = serverSeq
                serverSeq = (serverSeq + 1) and 0xFFFFFFFFL
                s
            }
            val finAck = buildTcpIpv4Packet(
                srcIp = key.dstIp,
                dstIp = key.srcIp,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seq = seqToSend,
                ack = clientNextSeq,
                flags = 0x11,
                payload = ByteArray(0),
            )
            writeTun(finAck)
        }

        private fun sendRst() {
            val seqToSend = synchronized(sessionLock) {
                if (isClosed) return
                serverSeq
            }
            val rst = buildTcpIpv4Packet(
                srcIp = key.dstIp,
                dstIp = key.srcIp,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seq = seqToSend,
                ack = clientNextSeq,
                flags = 0x14,
                payload = ByteArray(0),
            )
            writeTun(rst)
        }

        fun closeQuietly() {
            synchronized(sessionLock) {
                if (isClosed) return
                isClosed = true
                outOfOrder.clear()
            }
            outboundChannel.close()
            writerJob?.cancel()
            connectJob?.cancel()
            tcpSessions.remove(key)
            runCatching { remoteSocket?.close() }
            remoteSocket = null
        }
    }

    companion object {
        fun buildUdpIpv4Packet(
            srcIp: Int,
            dstIp: Int,
            srcPort: Int,
            dstPort: Int,
            payload: ByteArray,
        ): ByteArray {
            val totalLen = 20 + 8 + payload.size
            val out = ByteArray(totalLen)
            out[0] = 0x45.toByte()
            out[1] = 0x00
            out[2] = ((totalLen shr 8) and 0xFF).toByte()
            out[3] = (totalLen and 0xFF).toByte()
            out[4] = 0
            out[5] = 0
            out[6] = 0x40.toByte()
            out[7] = 0
            out[8] = 64
            out[9] = 17
            writeIntBE(out, 12, srcIp)
            writeIntBE(out, 16, dstIp)
            updateIpv4HeaderChecksum(out, 20)

            val udpLen = 8 + payload.size
            writeUInt16BE(out, 20, srcPort)
            writeUInt16BE(out, 22, dstPort)
            writeUInt16BE(out, 24, udpLen)
            writeUInt16BE(out, 26, 0)
            System.arraycopy(payload, 0, out, 28, payload.size)
            return out
        }

        fun buildTcpIpv4Packet(
            srcIp: Int,
            dstIp: Int,
            srcPort: Int,
            dstPort: Int,
            seq: Long,
            ack: Long,
            flags: Int,
            payload: ByteArray,
            mss: Int? = null,
        ): ByteArray {
            val tcpHeaderLen = if (mss != null) 24 else 20
            val totalLen = 20 + tcpHeaderLen + payload.size
            val out = ByteArray(totalLen)
            out[0] = 0x45.toByte()
            out[1] = 0x00
            out[2] = ((totalLen shr 8) and 0xFF).toByte()
            out[3] = (totalLen and 0xFF).toByte()
            out[4] = 0
            out[5] = 0
            out[6] = 0x40.toByte()
            out[7] = 0
            out[8] = 64
            out[9] = 6
            writeIntBE(out, 12, srcIp)
            writeIntBE(out, 16, dstIp)
            updateIpv4HeaderChecksum(out, 20)

            writeUInt16BE(out, 20, srcPort)
            writeUInt16BE(out, 22, dstPort)
            writeUInt32BE(out, 24, seq)
            writeUInt32BE(out, 28, ack)
            out[32] = ((tcpHeaderLen / 4) shl 4).toByte()
            out[33] = (flags and 0xFF).toByte()
            writeUInt16BE(out, 34, 65535)
            writeUInt16BE(out, 36, 0)
            writeUInt16BE(out, 38, 0)

            if (mss != null) {
                out[40] = 0x02
                out[41] = 0x04
                out[42] = ((mss shr 8) and 0xFF).toByte()
                out[43] = (mss and 0xFF).toByte()
            }

            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, out, 20 + tcpHeaderLen, payload.size)
            }

            val tcpLen = tcpHeaderLen + payload.size
            val tcpCsum = computeTransportChecksum(out, 20, tcpLen, srcIp, dstIp, 6)
            writeUInt16BE(out, 36, tcpCsum)
            return out
        }

        private fun updateIpv4HeaderChecksum(pkt: ByteArray, ihl: Int) {
            pkt[10] = 0
            pkt[11] = 0
            val csum = computeChecksum(pkt, 0, ihl)
            pkt[10] = ((csum shr 8) and 0xFF).toByte()
            pkt[11] = (csum and 0xFF).toByte()
        }

        private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
            var sum = 0L
            var i = offset
            val end = offset + length
            while (i + 1 < end) {
                val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
                sum += word
                i += 2
            }
            if (i < end) {
                sum += (buf[i].toInt() and 0xFF) shl 8
            }
            while ((sum shr 16) != 0L) {
                sum = (sum and 0xFFFFL) + (sum shr 16)
            }
            return (sum.inv() and 0xFFFFL).toInt()
        }

        private fun computeTransportChecksum(
            pkt: ByteArray,
            offset: Int,
            length: Int,
            srcIp: Int,
            dstIp: Int,
            proto: Int,
        ): Int {
            var sum = 0L
            sum += ((srcIp ushr 16) and 0xFFFF) + (srcIp and 0xFFFF)
            sum += ((dstIp ushr 16) and 0xFFFF) + (dstIp and 0xFFFF)
            sum += proto
            sum += length

            var i = offset
            val end = offset + length
            while (i + 1 < end) {
                val word = ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
                sum += word
                i += 2
            }
            if (i < end) {
                sum += (pkt[i].toInt() and 0xFF) shl 8
            }
            while ((sum shr 16) != 0L) {
                sum = (sum and 0xFFFFL) + (sum shr 16)
            }
            return (sum.inv() and 0xFFFFL).toInt()
        }

        private fun readIntBE(buf: ByteArray, off: Int): Int =
            ByteBuffer.wrap(buf, off, 4).int

        private fun writeIntBE(buf: ByteArray, off: Int, value: Int) {
            buf[off] = ((value ushr 24) and 0xFF).toByte()
            buf[off + 1] = ((value ushr 16) and 0xFF).toByte()
            buf[off + 2] = ((value ushr 8) and 0xFF).toByte()
            buf[off + 3] = (value and 0xFF).toByte()
        }

        private fun readUInt16BE(buf: ByteArray, off: Int): Int =
            ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

        private fun writeUInt16BE(buf: ByteArray, off: Int, value: Int) {
            buf[off] = ((value ushr 8) and 0xFF).toByte()
            buf[off + 1] = (value and 0xFF).toByte()
        }

        private fun readUInt32BE(buf: ByteArray, off: Int): Long =
            ((buf[off].toLong() and 0xFFL) shl 24) or
                ((buf[off + 1].toLong() and 0xFFL) shl 16) or
                ((buf[off + 2].toLong() and 0xFFL) shl 8) or
                (buf[off + 3].toLong() and 0xFFL)

        private fun writeUInt32BE(buf: ByteArray, off: Int, value: Long) {
            buf[off] = ((value ushr 24) and 0xFFL).toByte()
            buf[off + 1] = ((value ushr 16) and 0xFFL).toByte()
            buf[off + 2] = ((value ushr 8) and 0xFFL).toByte()
            buf[off + 3] = (value and 0xFFL).toByte()
        }

        private fun intToIpString(ip: Int): String =
            "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }
}
