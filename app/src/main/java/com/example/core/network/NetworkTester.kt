package com.example.core.network

import android.content.Context
import com.example.core.model.ProxyProfile
import com.example.core.parser.UriParser
import com.example.core.xray.XrayAssetHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
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

data class SubscriptionFetchResult(
    val profiles: List<ProxyProfile>,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestampSec: Long = 0L,
)

object NetworkTester {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
        }.getOrNull()
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

                SubscriptionFetchResult(
                    profiles = profiles,
                    uploadBytes = infoMap["upload"] ?: 0L,
                    downloadBytes = infoMap["download"] ?: 0L,
                    totalBytes = infoMap["total"] ?: 0L,
                    expireTimestampSec = infoMap["expire"] ?: 0L,
                )
            }
        }
    }
}
