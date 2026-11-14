package com.example.core.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Pure-Kotlin local SOCKS5 / HTTP CONNECT listener that forwards traffic through
 * [KotlinOutboundDialer] (VLESS, Trojan, SOCKS5, HTTP, or protected direct).
 */
class LocalProxyServer(
    private val socksPort: Int,
    private val httpPort: Int,
    private val dialer: KotlinOutboundDialer,
) {
    private var socksServer: ServerSocket? = null
    private var httpServer: ServerSocket? = null
    private val jobs = mutableListOf<Job>()

    fun start(scope: CoroutineScope) {
        stop()
        runCatching {
            val ss = ServerSocket(socksPort, 64, InetAddress.getByName("127.0.0.1"))
            socksServer = ss
            jobs += scope.launch(Dispatchers.IO) {
                while (isActive && !ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) { handleSocksClient(client) }
                }
            }
            XrayEngineState.appendLog("INFO", "Local SOCKS5 inbound bound on 127.0.0.1:$socksPort")
        }.onFailure {
            XrayEngineState.appendLog("WARN", "SOCKS5 port $socksPort bind failed: ${it.message}")
        }

        runCatching {
            val hs = ServerSocket(httpPort, 64, InetAddress.getByName("127.0.0.1"))
            httpServer = hs
            jobs += scope.launch(Dispatchers.IO) {
                while (isActive && !hs.isClosed) {
                    val client = runCatching { hs.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) { handleHttpClient(client) }
                }
            }
            XrayEngineState.appendLog("INFO", "Local HTTP inbound bound on 127.0.0.1:$httpPort")
        }.onFailure {
            XrayEngineState.appendLog("WARN", "HTTP port $httpPort bind failed: ${it.message}")
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { socksServer?.close() }
        runCatching { httpServer?.close() }
        socksServer = null
        httpServer = null
    }

    private fun handleSocksClient(client: Socket) {
        client.use { sock ->
            runCatching {
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val ver = input.read()
                if (ver != 0x05) return
                val nMethods = input.read()
                if (nMethods <= 0) return
                repeat(nMethods) { input.read() }
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                val reqVer = input.read()
                val cmd = input.read()
                input.read() // rsv
                val atyp = input.read()
                if (reqVer != 0x05 || cmd != 0x01) {
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    return
                }
                val host = when (atyp) {
                    0x01 -> {
                        val b = ByteArray(4)
                        readFully(input, b)
                        InetAddress.getByAddress(b).hostAddress.orEmpty()
                    }
                    0x03 -> {
                        val len = input.read()
                        val b = ByteArray(len)
                        readFully(input, b)
                        String(b, Charsets.UTF_8)
                    }
                    0x04 -> {
                        val b = ByteArray(16)
                        readFully(input, b)
                        InetAddress.getByAddress(b).hostAddress.orEmpty()
                    }
                    else -> return
                }
                val portHi = input.read()
                val portLo = input.read()
                val port = (portHi shl 8) or portLo

                val targetSocket = dialer.dialStream(host, port)
                targetSocket.use { remote ->
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    pipeBidirectional(input, output, remote.getInputStream(), remote.getOutputStream())
                }
            }
        }
    }

    private fun handleHttpClient(client: Socket) {
        client.use { sock ->
            runCatching {
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val headerBytes = readUntilDoubleCrlf(input) ?: return
                val headerText = String(headerBytes, Charsets.ISO_8859_1)
                val firstLine = headerText.lineSequence().firstOrNull().orEmpty()
                val parts = firstLine.split(" ")
                if (parts.size < 2) return

                if (parts[0].equals("CONNECT", ignoreCase = true)) {
                    val hp = parts[1].split(":", limit = 2)
                    val host = hp[0]
                    val port = hp.getOrNull(1)?.toIntOrNull() ?: 443
                    val remote = dialer.dialStream(host, port)
                    remote.use { r ->
                        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        output.flush()
                        pipeBidirectional(input, output, r.getInputStream(), r.getOutputStream())
                    }
                } else {
                    output.write("HTTP/1.1 501 Not Implemented\r\n\r\n".toByteArray())
                    output.flush()
                }
            }
        }
    }

    private fun pipeBidirectional(
        cIn: InputStream,
        cOut: OutputStream,
        rIn: InputStream,
        rOut: OutputStream,
    ) {
        val t = Thread {
            runCatching { rIn.copyTo(cOut) }
            runCatching { cOut.flush() }
        }
        t.start()
        runCatching { cIn.copyTo(rOut) }
        runCatching { rOut.flush() }
        runCatching { t.join(500) }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) error("Unexpected EOF")
            offset += n
        }
    }

    private fun readUntilDoubleCrlf(input: InputStream): ByteArray? {
        val buf = ArrayList<Byte>(512)
        while (buf.size < 16384) {
            val b = input.read()
            if (b < 0) return null
            buf.add(b.toByte())
            val n = buf.size
            if (n >= 4 &&
                buf[n - 4] == '\r'.code.toByte() &&
                buf[n - 3] == '\n'.code.toByte() &&
                buf[n - 2] == '\r'.code.toByte() &&
                buf[n - 1] == '\n'.code.toByte()
            ) {
                return buf.toByteArray()
            }
        }
        return null
    }
}
