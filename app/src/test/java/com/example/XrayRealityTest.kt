package com.example

import com.example.core.crypto.RealityKeyGenerator
import com.example.core.model.FlowType
import com.example.core.model.ProxyProtocol
import com.example.core.model.SecurityType
import com.example.core.model.TransportType
import com.example.core.model.XraySettings
import com.example.core.parser.UriParser
import com.example.core.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XrayRealityTest {

    @Test
    fun `reality keypair generator produces valid X25519 keys`() {
        val bundle = RealityKeyGenerator.generateBundle()
        assertTrue(RealityKeyGenerator.isValidRealityPublicKey(bundle.privateKey))
        assertTrue(RealityKeyGenerator.isValidRealityPublicKey(bundle.publicKey))
        assertTrue(RealityKeyGenerator.isValidShortId(bundle.shortId))
        assertEquals(36, bundle.uuid.length)
    }

    @Test
    fun `vless reality uri parses and exports accurately`() {
        val keys = RealityKeyGenerator.generateBundle()
        val uri = "vless://${keys.uuid}@198.51.100.10:443" +
            "?encryption=none&flow=xtls-rprx-vision&security=reality" +
            "&sni=www.microsoft.com&fp=chrome&pbk=${keys.publicKey}" +
            "&sid=${keys.shortId}&spx=%2F&type=tcp#Frankfurt%20REALITY"

        val profile = UriParser.parseSingle(uri)
        assertNotNull(profile)
        profile!!
        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertEquals("198.51.100.10", profile.serverAddress)
        assertEquals(443, profile.serverPort)
        assertEquals(SecurityType.REALITY, profile.security)
        assertEquals(FlowType.VISION, profile.flow)
        assertEquals("www.microsoft.com", profile.sni)
        assertEquals(keys.publicKey, profile.realityPublicKey)
        assertEquals(keys.shortId, profile.realityShortId)
        assertEquals("Frankfurt REALITY", profile.name)

        val reExported = UriParser.toShareUri(profile)
        val reparsed = UriParser.parseSingle(reExported)
        assertNotNull(reparsed)
        assertEquals(profile.realityPublicKey, reparsed!!.realityPublicKey)
        assertEquals(profile.sni, reparsed.sni)
        assertEquals(SecurityType.REALITY, reparsed.security)
    }

    @Test
    fun `xray config builder generates valid reality and xhttp json`() {
        val keys = RealityKeyGenerator.generateBundle()
        val profile = UriParser.parseSingle(
            "vless://${keys.uuid}@203.0.113.5:443?type=xhttp&mode=packet-up&path=%2Fxhttp&security=reality&sni=dl.google.com&fp=firefox&pbk=${keys.publicKey}&sid=${keys.shortId}#XHTTP-Node"
        )!!

        assertEquals(TransportType.XHTTP, profile.transport)
        assertEquals("packet-up", profile.xhttpMode)

        val jsonStr = XrayConfigBuilder.buildConfigJson(profile, XraySettings(), emptyList())
        val root = JSONObject(jsonStr)
        val outbounds = root.getJSONArray("outbounds")
        val proxyOutbound = outbounds.getJSONObject(0)
        assertEquals("vless", proxyOutbound.getString("protocol"))

        val stream = proxyOutbound.getJSONObject("streamSettings")
        assertEquals("xhttp", stream.getString("network"))
        assertEquals("reality", stream.getString("security"))

        val reality = stream.getJSONObject("realitySettings")
        assertEquals("dl.google.com", reality.getString("serverName"))
        assertEquals("firefox", reality.getString("fingerprint"))
        assertEquals(keys.publicKey, reality.getString("publicKey"))
        assertEquals(keys.shortId, reality.getString("shortId"))

        val xhttp = stream.getJSONObject("xhttpSettings")
        assertEquals("/xhttp", xhttp.getString("path"))
        assertEquals("packet-up", xhttp.getString("mode"))
    }

    @Test
    fun `kotlin tun forwarder builds valid ipv4 udp and tcp packets`() {
        val srcIp = (1 shl 24) or (1 shl 16) or (1 shl 8) or 1 // 1.1.1.1
        val dstIp = (172 shl 24) or (19 shl 16) or (0 shl 8) or 1 // 172.19.0.1
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00)

        val udpPkt = com.example.core.vpn.KotlinTunForwarder.buildUdpIpv4Packet(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 53,
            dstPort = 54321,
            payload = payload,
        )
        assertEquals(20 + 8 + payload.size, udpPkt.size)
        assertEquals(0x45, udpPkt[0].toInt() and 0xFF)
        assertEquals(17, udpPkt[9].toInt() and 0xFF)

        val tcpSynAck = com.example.core.vpn.KotlinTunForwarder.buildTcpIpv4Packet(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 443,
            dstPort = 50000,
            seq = 1000L,
            ack = 2001L,
            flags = 0x12,
            payload = ByteArray(0),
            mss = 1360,
        )
        assertEquals(44, tcpSynAck.size)
        assertEquals(6, tcpSynAck[9].toInt() and 0xFF)
        assertEquals(0x12, tcpSynAck[33].toInt() and 0xFF)
        assertEquals(0x60, tcpSynAck[32].toInt() and 0xFF) // 24-byte TCP header with MSS
    }

    @Test
    fun `user vless reality xhttp pqv mlkem768 uri parses validates and probes`() {
        val userUri = "vless://32622b0f-1a68-4e38-a7f7-a557edc26979@78.108.57.38:443" +
            "?encryption=mlkem768x25519plus.native.0rtt.nuUKibP8C39lqkEjC5EmHQyp7BnndLVuCwdeD5mF31g" +
            "&security=reality&sni=www.nvidia.com&fp=chrome" +
            "&pbk=t4Xs-5ksSsv71Pvg-FeYxeAE2ZL7ggkXiuNfhSPMdCk&sid=6e9acac04d&spx=%2F" +
            "&pqv=QV8gw4CUYv3tY2gqXgalRkFv5sSV3usyEoEfnI3zSad-ynVfHvn9x8cF7WaUAndRrB-l1bft__AibNwMJ60Y_CmPtqBLZskaFcr1BB1llGahGhNsTXp50BVQUp8u0zf_rzyRAl7d5W31AoXjvIvyFWkO8TUVRDPgfGpFb86-id86zbL9YMDwToWDPAPVOcAvmU6lMtZavwrJV5HDMXwaWbbPLwJEiZqsDuL9AbxnWUWr90hB1pmkKGck1dorIvm7hu24J89SNvTWFU0duZeugKcTEDmX-Qmm8GWB4EeE7YSzAigcgeAbkQwE9E2TRwSmLKzveyUXxvbotVwjNSNQeC5oKzRpWMBe5upmSFehf4Sc4__qLg5Oi7EldHA0F_ulzHSs8xEpIFeuwZCq1IENJpspEVO8sgI6uXCXImVZBiSoKQ-nHomlovwHZYMx_TviJwvCgxkqrkVdrS25LOxpWFQwKQpGFbUSw7hzCoCs2yP-7xXufOlQLC2F9itM0FN37Nvsqwac9JvYvt6OlcHRlf7GMTad_nrtiD9Gr_Rn7BjdwsLIpv84l7D-8Z6yEXJNXxu1Rl0XQZ9wiEOeesXqeZEydVnCBNPZ87HGzI_fnvF5Vk6hZDmQcdvmO1ZMMYGJ5Sj1CGl_MI_vpN4JJnuao8gsnFsR_t3nC-iftDeqsglqZN-W_oMIXE1GYMJXMm5rksmk37BWPgWz2hejWTt-7mLwyWKhO9rAnN_W2iXcoGXKITudIAGgKWmPL9m3lGoqij4pflQ2AJevWCev5laM1Ni1F0wx0iCLRl9AgkDGVVXWmCSS-DfNf1vIzc50ucdh50IIlKk1a5vANeEx6LjbJIeOZDdeIIx0a_FLyWaOTjVEotP9zNUSoa5rNczyR8-RusnXnrfLBz14SREAqi04WzQTdnMbS6V0wQny7HNUHwWhQifOyEYgo8RJfGkWZ4LKs_0XkLI_FpdLETOzWUovRldj8M2i7KBrJecpTo9TO_XxECCwNQh92rbogqrlmyzOEgOfvjoJ_GqzmKdNv_rTxFnEeDeSxLfKs90mRyLm78I99VEZONLg4B5Fi2aVueGc4zoxJc8dh2zqHeJZ3RuNNG6hWrjCm2opg-TodC4jBX3WCnwUaHvKBIVDS5Jm4N05-PYS7qp2LF0yIed3vKfHmxVWQn_UnAz1AHdEdRfic0BKYluWJSd57AduL1P17UnVd1VgLnczE1f4j513MVA_iqReAUQ77xg6A6IumGVJ0-iVgtUZ2EqXeLBKpUv5yepmquYhl88v-_Bge5nJ7pEBz6UCYb3YpdjAYo674rJo_kAuoibTH3IVFqAh20DoDTu2Vr--nNasSEt76NOK6P5gYM3RVSme-FUdyLHo16qVR1l0LTgtfOh1Y8OyzVZe5fKpogiiFBVuwJjZfGlrpUIVkf4jWPEQEVrJ3SNDBjE1h7q_L7UqEmBi53kE0WTd_ltHVnd-X1-bGADka4UTxL0i3EbpuMjgp4q8u1UoRfuVobJSfCO9Jx9RN_1Ax6JGT0RDuF6mZ2IgbRnXBz1lAbE0d0L7Dr0XJRggu9rP1BQmXANcrV50ezXI7tHcnJPfInTHebQZaF2Wm5mPrPqVVqYHccMszrD_LBrHiYFul56K56LGTWRMnwmSTht8WxMxNqSpUYrOL1-fz94vYFJGIMEsSGc-UTMSu1sH1Q0hV00BFYQGrmQFsT6mVpa1ca96YERyhxdze-jMcmbL_bYGLXQOoyaFuyK3cLgzpOz3RL7Qoqcr4gB-mi820okPp1J-Hnh63wdVRX2w91p_qtKqopKE29IailnXQhN_pqmMVG1DKKt0vpmbunwHdGzDWsK_22M4T-HEb5rYO7CPL-yAk4gI6ZjBOtEN-QNbouofD-o7QKOGPA3E3WgnvoNBzOfX4OPZZkePQsi72e2EbinKI-4hF73vvlX8DbfZU44yb9N064P0Nb5XFKMHl09Q61RPehf3m0vMBSPlrBRUhg5Md-3v8yI03opM7itL0SRX-Yn6jIevgiKENAGaKnx3L5Z1NUsk86PsQ7XFVNXxQYabafBWcDQ2ReuIf5rib1P1W9zjq8X3aERPl7PTToye0XltsMOftgaUZkqoe-PCEUin-svlkn5afpw_PZzhrjCaw0PwX2QCsY1_LsQX__mivgqmyd2zZ7LnjpFAKrgSF0f3jS_KJFaK9ceHt_v-QYE2Zm9HcB3HhJQwBAj09cl5XmEN-4pT3Rb-ScA4TPLQtV-EkkfGfAYKNIkRiILGt-QNVajH3FuIhqUgS_iwaXWNnPJ4bz6ag-IZ7aBjxQIZ0BSNgveQu-VaGHiOmwWdL93NnPhwPa8mBHD9SCuyv4jg1_1CvA_NfuOQdwX6lH2vRY1TVCJDZtxZ3WjPmlLcELTUHF3iXv2J2QPfZbdIStx-MtvH4J7Kc05DhHVn6yAa8GkD4Pcj4_GAPF8fUr7f3_qd-TDmmAUJKhSdNhaa4D-j4gundkIGHcaMIE6SkNNx2Ve4CnndgcMw86voOqyyl-Q_ALS9eZ-qDwerTMOMwN-TEalb1gMp8fMbjMV_DCLPeGrA2tyuIqwkeOHhlS_poL954U9O2Ds" +
            "&type=xhttp&path=%2F&mode=auto" +
            "&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%2C%22mode%22%3A%22auto%22%2C%22scMaxEachPostBytes%22%3A%221000000%22%7D" +
            "#Czech%F0%9F%87%A8%F0%9F%87%BF-%20Volta%20frends"

        val profile = UriParser.parseSingle(userUri)
        assertNotNull(profile)
        profile!!
        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertEquals("78.108.57.38", profile.serverAddress)
        assertEquals(443, profile.serverPort)
        assertEquals("32622b0f-1a68-4e38-a7f7-a557edc26979", profile.uuid)
        assertEquals(SecurityType.REALITY, profile.security)
        assertEquals(TransportType.XHTTP, profile.transport)
        assertEquals("www.nvidia.com", profile.sni)
        assertEquals("t4Xs-5ksSsv71Pvg-FeYxeAE2ZL7ggkXiuNfhSPMdCk", profile.realityPublicKey)
        assertEquals("6e9acac04d", profile.realityShortId)
        assertTrue(profile.encryption.startsWith("mlkem768x25519plus.native.0rtt."))
        assertTrue(profile.realityMldsa65Verify.startsWith("QV8gw4CU"))
        assertEquals("auto", profile.xhttpMode)
        assertTrue(profile.xhttpExtra.contains("scMaxEachPostBytes"))

        val errors = XrayConfigBuilder.validateProfile(profile).filter { it.isError }
        assertTrue("Expected no validation errors, got: $errors", errors.isEmpty())

        val jsonStr = XrayConfigBuilder.buildConfigJson(profile, XraySettings(), emptyList())
        val outbound = JSONObject(jsonStr).getJSONArray("outbounds").getJSONObject(0)
        val user = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertTrue(user.getString("encryption").startsWith("mlkem768x25519plus"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("xhttp", stream.getString("network"))
        assertEquals("reality", stream.getString("security"))
        assertTrue(stream.getJSONObject("realitySettings").getString("mldsa65Verify").startsWith("QV8gw4CU"))
        assertTrue(stream.getJSONObject("xhttpSettings").has("extra"))

        val dialer = com.example.core.vpn.KotlinOutboundDialer(profile)
        assertTrue(dialer.requiresNativeXrayCore())
    }

    @Test
    fun `libv2ray aar is linked and vpn vs proxy-only configs configure tun inbound correctly`() {
        assertTrue(
            "Expected libv2ray.Libv2ray from bundled libv2ray.aar to be linked",
            com.example.core.vpn.XrayEngineState.state.value.isNativeCoreLinked,
        )

        val profile = com.example.core.model.ProxyProfile(
            protocol = ProxyProtocol.VLESS,
            serverAddress = "edge.example.com",
            serverPort = 443,
            uuid = "32622b0f-1a68-4e38-a7f7-a557edc26979",
            security = SecurityType.REALITY,
            sni = "www.nvidia.com",
            realityPublicKey = "t4Xs-5ksSsv71Pvg-FeYxeAE2ZL7ggkXiuNfhSPMdCk",
        )

        val vpnJson = JSONObject(
            XrayConfigBuilder.buildConfigJson(
                profile = profile,
                settings = XraySettings(serviceMode = com.example.core.model.ServiceMode.VPN, mtu = 1480),
                rules = emptyList(),
            )
        )
        val vpnInbounds = vpnJson.getJSONArray("inbounds")
        assertEquals("tun-in", vpnInbounds.getJSONObject(0).getString("tag"))
        assertEquals("tun", vpnInbounds.getJSONObject(0).getString("protocol"))
        assertEquals(1480, vpnInbounds.getJSONObject(0).getJSONObject("settings").getInt("MTU"))

        val dnsRule = vpnJson.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("dns-out", dnsRule.getString("outboundTag"))
        assertEquals("tun-in", dnsRule.getJSONArray("inboundTag").getString(0))

        val dnsServers = vpnJson.getJSONObject("dns").getJSONArray("servers").toString()
        assertTrue(dnsServers.contains("full:edge.example.com"))

        val sockopt = vpnJson.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("sockopt")
        assertTrue(!sockopt.has("mark"))

        val proxyOnlyJson = JSONObject(
            XrayConfigBuilder.buildConfigJson(
                profile = profile,
                settings = XraySettings(serviceMode = com.example.core.model.ServiceMode.PROXY_ONLY),
                rules = emptyList(),
            )
        )
        val proxyOnlyInbounds = proxyOnlyJson.getJSONArray("inbounds")
        assertEquals("socks-in", proxyOnlyInbounds.getJSONObject(0).getString("tag"))
    }

    @Test
    fun `kotlin tun forwarder relays multi-chunk tcp stream in order without rst`() {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val serverPort = server.localPort
        val receivedOnServer = java.io.ByteArrayOutputStream()
        val serverThread = Thread {
            server.use { srv ->
                srv.accept().use { client ->
                    val inp = client.getInputStream()
                    val buf = ByteArray(1024)
                    var total = 0
                    while (total < 10) {
                        val r = inp.read(buf)
                        if (r <= 0) break
                        receivedOnServer.write(buf, 0, r)
                        total += r
                    }
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                    client.getOutputStream().flush()
                }
            }
        }
        serverThread.start()

        val clientToTunOut = java.io.PipedOutputStream()
        val tunIn = java.io.PipedInputStream(clientToTunOut, 65536)
        val tunOut = java.io.PipedOutputStream()
        val clientFromTunIn = java.io.PipedInputStream(tunOut, 65536)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val dialer = com.example.core.vpn.KotlinOutboundDialer(
            com.example.core.model.ProxyProfile(
                protocol = com.example.core.model.ProxyProtocol.VLESS,
                security = com.example.core.model.SecurityType.REALITY,
            )
        )
        val forwarder = com.example.core.vpn.KotlinTunForwarder(
            input = tunIn,
            output = tunOut,
            dialer = dialer,
            scope = scope,
            forTest = true,
        )
        forwarder.start()

        val clientIp = (172 shl 24) or (19 shl 16) or (0 shl 8) or 1
        val srvIp = (127 shl 24) or 1
        val clientPort = 45678
        val isn = 1000L

        // 1. Send SYN
        val synPkt = com.example.core.vpn.KotlinTunForwarder.buildTcpIpv4Packet(
            srcIp = clientIp, dstIp = srvIp, srcPort = clientPort, dstPort = serverPort,
            seq = isn, ack = 0L, flags = 0x02, payload = ByteArray(0),
        )
        clientToTunOut.write(synPkt)
        clientToTunOut.flush()

        val synAckBuf = ByteArray(4096)
        val synAckLen = clientFromTunIn.read(synAckBuf)
        assertTrue(synAckLen >= 40)
        assertEquals(0x12, synAckBuf[33].toInt() and 0xFF) // SYN|ACK

        // 2. Send chunk 2 BEFORE chunk 1 (out-of-order test)
        val part2 = "WORLD".toByteArray()
        val part1 = "HELLO".toByteArray()
        clientToTunOut.write(
            com.example.core.vpn.KotlinTunForwarder.buildTcpIpv4Packet(
                srcIp = clientIp, dstIp = srvIp, srcPort = clientPort, dstPort = serverPort,
                seq = isn + 1 + part1.size, ack = 1L, flags = 0x18, payload = part2,
            )
        )
        clientToTunOut.flush()
        Thread.sleep(50)
        clientToTunOut.write(
            com.example.core.vpn.KotlinTunForwarder.buildTcpIpv4Packet(
                srcIp = clientIp, dstIp = srvIp, srcPort = clientPort, dstPort = serverPort,
                seq = isn + 1, ack = 1L, flags = 0x18, payload = part1,
            )
        )
        clientToTunOut.flush()

        // Read packets back until we see HTTP/1.1 200 OK and make sure no RST (0x04) is sent
        val collected = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + 4000L
        while (System.currentTimeMillis() < deadline && !collected.toString().contains("HTTP/1.1 200 OK")) {
            if (clientFromTunIn.available() > 0) {
                val n = clientFromTunIn.read(synAckBuf)
                if (n > 40) {
                    val ihl = (synAckBuf[0].toInt() and 0x0F) * 4
                    val tcpOff = ((synAckBuf[ihl + 12].toInt() ushr 4) and 0x0F) * 4
                    val flags = synAckBuf[ihl + 13].toInt() and 0x3F
                    assertTrue("Unexpected TCP RST packet", (flags and 0x04) == 0)
                    val hdr = ihl + tcpOff
                    if (n > hdr) collected.write(synAckBuf, hdr, n - hdr)
                }
            } else {
                Thread.sleep(20)
            }
        }

        assertEquals("HELLOWORLD", receivedOnServer.toString())
        assertTrue("Expected HTTP 200 response via TUN, got: ${collected}", collected.toString().contains("HTTP/1.1 200 OK"))

        forwarder.stop()
        serverThread.join(1000)
    }

    @Test
    fun `ssh profile uri roundtrip validation xray socks bridge and jsch linkage`() {
        val uri = "ssh://alice:s3cr3t@ssh.example.org:2222?publicKey=ssh-ed25519%20AAAAtestkey#SSH%20Node"
        val profile = UriParser.parseSingle(uri)
        assertNotNull(profile)
        profile!!
        assertEquals(ProxyProtocol.SSH, profile.protocol)
        assertEquals("ssh.example.org", profile.serverAddress)
        assertEquals(2222, profile.serverPort)
        assertEquals("alice", profile.username)
        assertEquals("s3cr3t", profile.password)
        assertEquals(com.example.core.model.SshAuthType.PASSWORD, profile.sshAuthType)
        assertEquals("ssh-ed25519 AAAAtestkey", profile.sshPublicKey)
        assertEquals("SSH Node", profile.name)

        val shared = UriParser.toShareUri(profile)
        val reparsed = UriParser.parseSingle(shared)
        assertNotNull(reparsed)
        assertEquals(ProxyProtocol.SSH, reparsed!!.protocol)
        assertEquals("alice", reparsed.username)
        assertEquals("s3cr3t", reparsed.password)
        assertEquals("ssh-ed25519 AAAAtestkey", reparsed.sshPublicKey)

        // Validation checks for password vs private key modes
        val passwordErrors = XrayConfigBuilder.validateProfile(profile).filter { it.isError }
        assertTrue("Expected no validation errors for password SSH, got: $passwordErrors", passwordErrors.isEmpty())

        val missingKeyProfile = profile.copy(
            sshAuthType = com.example.core.model.SshAuthType.PRIVATE_KEY,
            sshPrivateKey = "",
        )
        val keyErrors = XrayConfigBuilder.validateProfile(missingKeyProfile).filter { it.isError }
        assertTrue("Expected private key error", keyErrors.isNotEmpty())

        val validKeyProfile = missingKeyProfile.copy(sshPrivateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----")
        assertTrue(XrayConfigBuilder.validateProfile(validKeyProfile).none { it.isError })

        // XrayConfigBuilder chains TUN -> socks outbound on local SshTunnelBridge port
        val json = JSONObject(
            XrayConfigBuilder.buildConfigJson(
                profile = validKeyProfile,
                settings = XraySettings(serviceMode = com.example.core.model.ServiceMode.VPN),
                rules = emptyList(),
                sshLocalBridgePort = 18080,
            )
        )
        val proxyOutbound = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", proxyOutbound.getString("protocol"))
        val serverObj = proxyOutbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("127.0.0.1", serverObj.getString("address"))
        assertEquals(18080, serverObj.getInt("port"))

        // Room entity round-trip preserves all SSH columns
        val entity = com.example.data.local.ProfileEntity.fromDomain(validKeyProfile)
        val roundTripped = entity.toDomain()
        assertEquals(com.example.core.model.SshAuthType.PRIVATE_KEY, roundTripped.sshAuthType)
        assertEquals(validKeyProfile.sshPrivateKey, roundTripped.sshPrivateKey)
        assertEquals(validKeyProfile.sshPublicKey, roundTripped.sshPublicKey)

        // Verify mwiede/jsch is present on classpath
        val jsch = com.jcraft.jsch.JSch()
        assertNotNull(jsch)
    }

    @Test
    fun `routing normalizes domains splits domain and ip into OR rules and routes direct dns`() {
        assertEquals("domain:ru", XrayConfigBuilder.normalizeDomainPattern("domain:.ru"))
        assertEquals("domain:ru", XrayConfigBuilder.normalizeDomainPattern(".ru"))
        assertEquals("domain:ya.ru", XrayConfigBuilder.normalizeDomainPattern("*.ya.ru"))
        assertEquals("domain:ya.ru", XrayConfigBuilder.normalizeDomainPattern("https://ya.ru/internet"))
        assertEquals("domain:ya.ru", XrayConfigBuilder.normalizeDomainPattern("ya.ru"))
        assertEquals("geosite:category-ru", XrayConfigBuilder.normalizeDomainPattern("geosite:category-ru"))
        assertEquals("yandex", XrayConfigBuilder.normalizeDomainPattern("yandex"))

        val profile = com.example.core.model.ProxyProfile(
            protocol = ProxyProtocol.VLESS,
            serverAddress = "vpn.example.com",
            serverPort = 443,
            uuid = "32622b0f-1a68-4e38-a7f7-a557edc26979",
            security = SecurityType.REALITY,
            sni = "www.nvidia.com",
            realityPublicKey = "t4Xs-5ksSsv71Pvg-FeYxeAE2ZL7ggkXiuNfhSPMdCk",
        )
        val ruRule = com.example.core.model.RoutingRule(
            id = 2L,
            name = "Direct Local / RU Domains",
            enabled = true,
            domains = "geosite:category-ru,domain:.ru,ya.ru",
            ips = "geoip:ru",
            action = com.example.core.model.RouteAction.DIRECT,
            priority = 2,
        )

        val json = JSONObject(
            XrayConfigBuilder.buildConfigJson(
                profile = profile,
                settings = XraySettings(),
                rules = listOf(ruRule),
            )
        )

        // Direct DNS must resolve both proxy hostname and enabled DIRECT rule domains via 8.8.8.8 (never Android-broken localhost)
        val dnsStr = json.getJSONObject("dns").toString()
        assertTrue(!dnsStr.contains("\"localhost\""))
        assertTrue(dnsStr.contains("domain:ru"))
        assertTrue(dnsStr.contains("domain:ya.ru"))
        assertTrue(dnsStr.contains("geosite:category-ru"))
        assertTrue(dnsStr.contains("full:vpn.example.com"))

        // Domain + IP in one UI rule must emit two separate Xray rules (OR semantics, not AND)
        val routingRules = json.getJSONObject("routing").getJSONArray("rules")
        val ruleList = (0 until routingRules.length()).map { routingRules.getJSONObject(it) }

        val directDnsPort53Rule = ruleList.find {
            it.optString("outboundTag") == "direct" &&
                it.optString("port") == "53" &&
                it.optJSONArray("ip")?.toString()?.contains("8.8.8.8") == true
        }
        assertNotNull("Expected direct DNS port 53 bypass rule", directDnsPort53Rule)

        val directDomainOnlyRule = ruleList.find {
            it.optString("outboundTag") == "direct" &&
                it.has("domain") && !it.has("ip") &&
                it.getJSONArray("domain").toString().contains("domain:ru")
        }
        assertNotNull("Expected separate domain-only direct rule without IP AND-constraint", directDomainOnlyRule)

        val directIpOnlyRule = ruleList.find {
            it.optString("outboundTag") == "direct" &&
                it.has("ip") && !it.has("domain") &&
                it.getJSONArray("ip").toString().contains("geoip:ru")
        }
        assertNotNull("Expected separate ip-only direct rule without domain AND-constraint", directIpOnlyRule)
    }

    @Test
    fun `vpn notification traffic formatting and stats json parsing work accurately`() {
        assertEquals("512 B", com.example.core.vpn.XrayVpnService.formatBytes(512))
        assertEquals("1.5 KB", com.example.core.vpn.XrayVpnService.formatBytes(1536))
        assertEquals("2.0 MB/s", com.example.core.vpn.XrayVpnService.formatSpeed(2L * 1024L * 1024L))

        val stats = com.example.core.vpn.XrayVpnService.parseOutboundStatsJson(
            """{"proxy":{"uplink":1200,"downlink":4800},"direct":{"uplink":300,"downlink":700}}"""
        )
        assertNotNull(stats)
        assertEquals(1500L, stats!!.first)
        assertEquals(5500L, stats.second)
    }

    @Test
    fun `ssh tunnel bridge relays socks5 tcp connect and socks5 udp associate dns over tcp`() {
        // 1. Verify XrayConfigBuilder configures TCP DNS and nonIPQuery=reject for SSH profiles
        val sshProfile = com.example.core.model.ProxyProfile(
            protocol = ProxyProtocol.SSH,
            serverAddress = "ssh.example.org",
            serverPort = 22,
            username = "root",
            password = "secret",
            sshAuthType = com.example.core.model.SshAuthType.PASSWORD,
        )
        val sshConfig = JSONObject(
            XrayConfigBuilder.buildConfigJson(
                profile = sshProfile,
                settings = XraySettings(),
                rules = emptyList(),
                sshLocalBridgePort = 19080,
            )
        )
        val dnsObj = sshConfig.getJSONObject("dns")
        assertEquals("UseIPv4", dnsObj.getString("queryStrategy"))
        val dnsServers = dnsObj.getJSONArray("servers")
        assertEquals("tcp://1.1.1.1:53", dnsServers.getString(0))
        assertEquals("tcp://8.8.8.8:53", dnsServers.getString(1))

        val outbounds = sshConfig.getJSONArray("outbounds")
        val dnsOut = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .first { it.getString("tag") == "dns-out" }
        assertEquals("reject", dnsOut.getJSONObject("settings").getString("nonIPQuery"))

        // 2. Stand up a mock TCP DNS server (RFC 1035 2-byte length prefix)
        val mockTcpDnsServer = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val dnsResponsePayload = byteArrayOf(
            0x12, 0x34, // Transaction ID
            0x81.toByte(), 0x80.toByte(), // Standard query response, no error
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            // Answer 93.184.216.34
            0x00, 0x04, 93, 184.toByte(), 216.toByte(), 34,
        )
        val dnsServerThread = Thread {
            mockTcpDnsServer.use { srv ->
                runCatching {
                    srv.accept().use { client ->
                        val inp = client.getInputStream()
                        val out = client.getOutputStream()
                        val lenHi = inp.read()
                        val lenLo = inp.read()
                        val qLen = (lenHi shl 8) or lenLo
                        val qBuf = ByteArray(qLen)
                        var off = 0
                        while (off < qLen) {
                            val r = inp.read(qBuf, off, qLen - off)
                            if (r < 0) break
                            off += r
                        }
                        out.write(byteArrayOf(((dnsResponsePayload.size ushr 8) and 0xFF).toByte(), (dnsResponsePayload.size and 0xFF).toByte()))
                        out.write(dnsResponsePayload)
                        out.flush()
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        // 3. Start SshTunnelBridge wired to the mock TCP DNS server via directTcpConnector
        val bridge = com.example.core.vpn.SshTunnelBridge(
            profile = sshProfile,
            vpnService = null,
            directTcpConnector = { _, _ ->
                java.net.Socket("127.0.0.1", mockTcpDnsServer.localPort)
            }
        )
        val bridgePort = bridge.start()
        assertTrue(bridgePort > 0)

        try {
            // Perform SOCKS5 UDP ASSOCIATE (cmd = 0x03)
            java.net.Socket("127.0.0.1", bridgePort).use { ctrl ->
                ctrl.soTimeout = 5000
                val inp = ctrl.getInputStream()
                val out = ctrl.getOutputStream()

                // Greeting
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertEquals(0x05, inp.read())
                assertEquals(0x00, inp.read())

                // UDP ASSOCIATE request (0x03)
                out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()

                val reply = ByteArray(10)
                var readOff = 0
                while (readOff < 10) {
                    val r = inp.read(reply, readOff, 10 - readOff)
                    assertTrue(r > 0)
                    readOff += r
                }
                assertEquals(0x05, reply[0].toInt() and 0xFF)
                assertEquals(0x00, reply[1].toInt() and 0xFF)
                val udpRelayPort = ((reply[8].toInt() and 0xFF) shl 8) or (reply[9].toInt() and 0xFF)
                assertTrue(udpRelayPort > 0)

                // Send SOCKS5 UDP DNS packet to port 53 through the relay
                val dnsQuery = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                val socks5UdpReq = com.example.core.vpn.SshTunnelBridge.buildSocks5UdpPacket("1.1.1.1", 53, dnsQuery)

                java.net.DatagramSocket().use { clientUdp ->
                    clientUdp.soTimeout = 5000
                    val sendPkt = java.net.DatagramPacket(
                        socks5UdpReq,
                        socks5UdpReq.size,
                        java.net.InetAddress.getByName("127.0.0.1"),
                        udpRelayPort,
                    )
                    clientUdp.send(sendPkt)

                    val recvBuf = ByteArray(1024)
                    val recvPkt = java.net.DatagramPacket(recvBuf, recvBuf.size)
                    clientUdp.receive(recvPkt)

                    val parsed = com.example.core.vpn.SshTunnelBridge.parseSocks5UdpPacket(
                        recvBuf.copyOfRange(0, recvPkt.length)
                    )
                    assertNotNull(parsed)
                    assertEquals(53, parsed!!.destPort)
                    assertTrue(parsed.payload.contentEquals(dnsResponsePayload))
                }
            }
        } finally {
            bridge.stop()
            dnsServerThread.join(1000)
        }
    }

    @Test
    fun `per app clipboard codec parses and exports nekobox false and true package pattern`() {
        val rawInput = """
            false
            yio.tro.onliyoy
            com.brave.browser
            com.ijsoft.cpul
            com.android.chrome
            com.discord
            org.telegram.messenger
            com.google.android.youtube
            org.iggymedia.periodtracker
        """.trimIndent()

        val parsedProxySelected = com.example.ui.screens.PerAppClipboardCodec.parse(rawInput)
        assertEquals(com.example.core.model.PerAppProxyMode.PROXY_SELECTED, parsedProxySelected.mode)
        assertEquals(false, parsedProxySelected.explicitBypassFlag)
        assertEquals(8, parsedProxySelected.packages.size)
        assertTrue("yio.tro.onliyoy" in parsedProxySelected.packages)
        assertTrue("org.iggymedia.periodtracker" in parsedProxySelected.packages)

        val exported = com.example.ui.screens.PerAppClipboardCodec.format(
            mode = parsedProxySelected.mode,
            packages = parsedProxySelected.packages,
        )
        val lines = exported.lines()
        assertEquals("false", lines.first())
        assertEquals(9, lines.size)

        // Round-trip with bypass = true
        val parsedBypass = com.example.ui.screens.PerAppClipboardCodec.parse(
            "true\ncom.android.chrome\norg.telegram.messenger"
        )
        assertEquals(com.example.core.model.PerAppProxyMode.BYPASS_SELECTED, parsedBypass.mode)
        assertEquals(true, parsedBypass.explicitBypassFlag)
        assertTrue(
            com.example.ui.screens.PerAppClipboardCodec.format(parsedBypass.mode, parsedBypass.packages)
                .startsWith("true\n")
        )
    }

    @Test
    fun `qr encoder and decoder handle huge 2200+ char mlkem768 pqv vless uri`() {
        val hugePayload = "vless://32622b0f-1a68-4e38-a7f7-a557edc26979@78.108.57.38:443" +
            "?encryption=mlkem768x25519plus.native.0rtt.nuUKibP8C39lqkEjC5EmHQyp7BnndLVuCwdeD5mF31g" +
            "&security=reality&sni=www.nvidia.com&fp=chrome" +
            "&pbk=t4Xs-5ksSsv71Pvg-FeYxeAE2ZL7ggkXiuNfhSPMdCk&sid=6e9acac04d&spx=%2F" +
            "&pqv=" + "A".repeat(1900) + "#Czech-Volta"

        val encoded = com.example.ui.components.QrMatrixEncoder.encode(hugePayload).getOrThrow()
        assertEquals(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, encoded.errorCorrectionLevel)
        assertTrue(encoded.versionNumber >= 30)

        // Render QR matrix to a crisp Android Bitmap with 4-module quiet zone & 4px per module and decode via QrBitmapDecoder
        val scale = 4
        val quiet = 4
        val imgW = (encoded.matrixWidth + quiet * 2) * scale
        val imgH = (encoded.matrixHeight + quiet * 2) * scale
        val bmp = android.graphics.Bitmap.createBitmap(imgW, imgH, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        for (y in 0 until encoded.matrixHeight) {
            for (x in 0 until encoded.matrixWidth) {
                if (encoded.modules[y * encoded.matrixWidth + x]) {
                    val baseX = (x + quiet) * scale
                    val baseY = (y + quiet) * scale
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            bmp.setPixel(baseX + dx, baseY + dy, android.graphics.Color.BLACK)
                        }
                    }
                }
            }
        }

        val decoded = com.example.ui.components.QrBitmapDecoder.decodeFromBitmap(bmp)
        assertEquals(hugePayload, decoded)
    }

    @Test
    fun `app language resolution selects english, russian, or system locale accurately`() {
        val ruExplicit = com.example.ui.i18n.resolveAppStrings(
            language = com.example.core.model.AppLanguage.RU,
            systemLanguageCode = "en",
        )
        assertEquals("Профили", ruExplicit.navProfiles)
        assertEquals("Настройки", ruExplicit.navSettings)
        assertEquals("Роутинг", ruExplicit.navRouting)
        assertEquals(
            "Только выбранные",
            ruExplicit.perAppModeName(com.example.core.model.PerAppProxyMode.PROXY_SELECTED),
        )

        val enExplicit = com.example.ui.i18n.resolveAppStrings(
            language = com.example.core.model.AppLanguage.EN,
            systemLanguageCode = "ru",
        )
        assertEquals("Profiles", enExplicit.navProfiles)
        assertEquals("Settings", enExplicit.navSettings)

        val systemRu = com.example.ui.i18n.resolveAppStrings(
            language = com.example.core.model.AppLanguage.SYSTEM,
            systemLanguageCode = "ru",
        )
        assertEquals("Профили", systemRu.navProfiles)

        val systemEn = com.example.ui.i18n.resolveAppStrings(
            language = com.example.core.model.AppLanguage.SYSTEM,
            systemLanguageCode = "de",
        )
        assertEquals("Profiles", systemEn.navProfiles)

        val zhCn = com.example.ui.i18n.resolveAppStrings(com.example.core.model.AppLanguage.ZH_CN)
        assertEquals("配置", zhCn.navProfiles)
        assertEquals(false, zhCn.isRtl)

        val zhTw = com.example.ui.i18n.resolveAppStrings(com.example.core.model.AppLanguage.ZH_TW)
        assertEquals("設定檔", zhTw.navProfiles)
        assertEquals(false, zhTw.isRtl)

        val fa = com.example.ui.i18n.resolveAppStrings(com.example.core.model.AppLanguage.FA)
        assertEquals("پروفایل‌ها", fa.navProfiles)
        assertEquals(true, fa.isRtl)

        val es = com.example.ui.i18n.resolveAppStrings(com.example.core.model.AppLanguage.ES)
        assertEquals("Perfiles", es.navProfiles)
        assertEquals(false, es.isRtl)

        val systemZhTw = com.example.ui.i18n.resolveAppStrings(
            language = com.example.core.model.AppLanguage.SYSTEM,
            systemLanguageCode = "zh",
            systemScriptOrCountry = "Hant_TW",
        )
        assertEquals("設定檔", systemZhTw.navProfiles)

        val systemZhCn = com.example.ui.i18n.resolveAppStrings(
            language = com.example.core.model.AppLanguage.SYSTEM,
            systemLanguageCode = "zh",
            systemScriptOrCountry = "Hans_CN",
        )
        assertEquals("配置", systemZhCn.navProfiles)

        val systemFa = com.example.ui.i18n.resolveAppStrings(
            language = com.example.core.model.AppLanguage.SYSTEM,
            systemLanguageCode = "fa",
        )
        assertEquals(true, systemFa.isRtl)

        val systemEs = com.example.ui.i18n.resolveAppStrings(
            language = com.example.core.model.AppLanguage.SYSTEM,
            systemLanguageCode = "es",
        )
        assertEquals("Perfiles", systemEs.navProfiles)
    }

    @Test
    fun `engine state updates live traffic and resets metrics when switching profiles`() {
        val stateObj = com.example.core.vpn.XrayEngineState
        stateObj.updateStatus(
            status = com.example.core.vpn.TunnelStatus.CONNECTED,
            profileId = 10L,
            profileName = "Node A",
            protocolLabel = "VLESS · REALITY · TCP",
            isReality = true,
        )
        stateObj.updateTraffic(
            upSpeedBps = 12_000L,
            downSpeedBps = 250_000L,
            totalUpBytes = 50_000L,
            totalDownBytes = 1_200_000L,
        )
        assertEquals(250_000L, stateObj.state.value.downSpeedBps)
        assertEquals(1_200_000L, stateObj.state.value.totalDownBytes)

        // Switching to a different profile resets live speeds and totals
        stateObj.updateStatus(
            status = com.example.core.vpn.TunnelStatus.CONNECTING,
            profileId = 20L,
            profileName = "Node B",
            protocolLabel = "SSH · TCP",
            isReality = false,
        )
        assertEquals(20L, stateObj.state.value.activeProfileId)
        assertEquals(0L, stateObj.state.value.downSpeedBps)
        assertEquals(0L, stateObj.state.value.totalDownBytes)

        // Clean up to IDLE
        stateObj.updateStatus(status = com.example.core.vpn.TunnelStatus.IDLE)
        assertEquals(0L, stateObj.state.value.upSpeedBps)
    }

    @Test
    fun `widget manager builds valid start service intent and tray strings resolve`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val keys = RealityKeyGenerator.generateBundle()
        val profile = com.example.core.model.ProxyProfile(
            id = 42L,
            name = "Berlin REALITY",
            protocol = ProxyProtocol.VLESS,
            serverAddress = "de-ber.example.net",
            serverPort = 443,
            uuid = keys.uuid,
            security = SecurityType.REALITY,
            sni = "www.microsoft.com",
            realityPublicKey = keys.publicKey,
            realityShortId = keys.shortId,
        )

        val label = com.example.core.widget.NekoWidgetManager.formatProtocolLabel(profile)
        assertTrue(label.contains("VLESS"))
        assertTrue(label.contains("REALITY"))

        val intent = com.example.core.widget.NekoWidgetManager.buildStartServiceIntent(
            context = context,
            profile = profile,
            settings = XraySettings(),
            rules = emptyList(),
        )
        assertEquals(com.example.core.vpn.XrayVpnService.ACTION_START, intent.action)
        assertEquals(42L, intent.getLongExtra(com.example.core.vpn.XrayVpnService.EXTRA_PROFILE_ID, -1L))
        assertEquals("Berlin REALITY", intent.getStringExtra(com.example.core.vpn.XrayVpnService.EXTRA_PROFILE_NAME))

        val ruStrings = com.example.ui.i18n.resolveAppStrings(com.example.core.model.AppLanguage.RU)
        assertTrue(ruStrings.trayWidgetTitle.isNotBlank())
        assertTrue(ruStrings.switchedProfilePrefix.isNotBlank())
        assertTrue(ruStrings.pinMiniWidgetBtn.contains("1×1"))
    }

    @Test
    fun `mini 1x1 widget renders active and idle remote views cleanly`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val strings = com.example.ui.i18n.resolveAppStrings(com.example.core.model.AppLanguage.RU)
        val profile = com.example.core.model.ProxyProfile(id = 7L, name = "Finland")

        val connectedViews = com.example.core.widget.NekoWidgetManager.buildMiniRemoteViews(
            context = context,
            runtime = com.example.core.vpn.EngineRuntimeState(
                status = com.example.core.vpn.TunnelStatus.CONNECTED,
                activeProfileId = 7L,
                activeProfileName = "Finland",
            ),
            profile = profile,
            strings = strings,
        )
        assertNotNull(connectedViews)

        val idleViews = com.example.core.widget.NekoWidgetManager.buildMiniRemoteViews(
            context = context,
            runtime = com.example.core.vpn.EngineRuntimeState(
                status = com.example.core.vpn.TunnelStatus.IDLE,
            ),
            profile = profile,
            strings = strings,
        )
        assertNotNull(idleViews)
    }

    @Test
    fun `subscription urls parse as groups instead of http proxy configs`() {
        val httpsSub = UriParser.parseSubscriptions("https://sub.example.com/api/v1/client/subscribe?token=abc#My%20VPN")
        assertEquals(1, httpsSub.size)
        assertEquals("https://sub.example.com/api/v1/client/subscribe?token=abc", httpsSub[0].url)
        assertEquals("My VPN", httpsSub[0].name)
        assertTrue(UriParser.parseBatch("https://sub.example.com/api/v1/client/subscribe?token=abc#My%20VPN").isEmpty())

        val pathSub = "https://panel.example.org/sub/12345"
        assertEquals(1, UriParser.parseSubscriptions(pathSub).size)
        assertTrue(UriParser.parseBatch(pathSub).isEmpty())

        val encodedSubUrl = android.util.Base64.encodeToString(
            "https://cdn.example.net/sub/xyz#FastSub".toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
        )
        val subScheme = UriParser.parseSubscriptions("sub://$encodedSubUrl")
        assertEquals(1, subScheme.size)
        assertEquals("https://cdn.example.net/sub/xyz", subScheme[0].url)
        assertEquals("FastSub", subScheme[0].name)

        val v2rayNgSub = UriParser.parseSubscriptions(
            "v2rayng://install-sub?url=https%3A%2F%2Fexample.com%2Fsub%3Fid%3D9&name=CloudSub"
        )
        assertEquals(1, v2rayNgSub.size)
        assertEquals("https://example.com/sub?id=9", v2rayNgSub[0].url)
        assertEquals("CloudSub", v2rayNgSub[0].name)

        val clashSub = UriParser.parseSubscriptions(
            "clash://install-config?url=https%3A%2F%2Fexample.org%2Fclash.yaml#ClashGroup"
        )
        assertEquals(1, clashSub.size)
        assertEquals("https://example.org/clash.yaml", clashSub[0].url)
        assertEquals("ClashGroup", clashSub[0].name)

        val happSub = UriParser.parseSubscriptions("happ://add/https%3A%2F%2Fexample.io%2Fsub#HappGroup")
        assertEquals(1, happSub.size)
        assertEquals("https://example.io/sub", happSub[0].url)
        assertEquals("HappGroup", happSub[0].name)

        // Explicit bare HTTP proxy URIs must NOT be captured as subscriptions and must remain HTTP profiles
        val explicitHttpProxy = "http://alice:pass@1.2.3.4:8080#WorkHttp"
        assertTrue(UriParser.parseSubscriptions(explicitHttpProxy).isEmpty())
        val parsedHttpProfiles = UriParser.parseBatch(explicitHttpProxy)
        assertEquals(1, parsedHttpProfiles.size)
        assertEquals(ProxyProtocol.HTTP, parsedHttpProfiles[0].protocol)
        assertEquals("1.2.3.4", parsedHttpProfiles[0].serverAddress)
        assertEquals(8080, parsedHttpProfiles[0].serverPort)
        assertEquals("alice", parsedHttpProfiles[0].username)
        assertEquals("pass", parsedHttpProfiles[0].password)
        assertEquals("WorkHttp", parsedHttpProfiles[0].name)

        val barePortHttpProxy = "http://198.51.100.2:3128#Squid"
        assertTrue(UriParser.parseSubscriptions(barePortHttpProxy).isEmpty())
        val parsedSquid = UriParser.parseBatch(barePortHttpProxy)
        assertEquals(1, parsedSquid.size)
        assertEquals(ProxyProtocol.HTTP, parsedSquid[0].protocol)
        assertEquals(3128, parsedSquid[0].serverPort)
    }

    @Test
    fun `libv2ray aar and app assets geo dat files unpack cleanly with explicit first-launch log`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Inspect libv2ray.aar directly: v26.10.31 AAR bundles upstream AndroidLibXrayLite assets/*.dat
        val candidates = listOf(
            java.io.File("libs/libv2ray.aar"),
            java.io.File("app/libs/libv2ray.aar"),
        )
        val aarFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Expected libs/libv2ray.aar to exist", aarFile)
        val datEntries = java.util.zip.ZipFile(aarFile!!).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".dat", ignoreCase = true) }
                .associate { it.name to it.size }
        }
        assertTrue("Expected assets/geoip.dat in libv2ray.aar, got: ${datEntries.keys}", "assets/geoip.dat" in datEntries)
        assertTrue("Expected assets/geosite.dat in libv2ray.aar, got: ${datEntries.keys}", "assets/geosite.dat" in datEntries)
        assertTrue(
            "Expected assets/geoip-only-cn-private.dat in libv2ray.aar, got: ${datEntries.keys}",
            "assets/geoip-only-cn-private.dat" in datEntries,
        )

        // 2. Verify app-level v2fly geoip.dat & geosite.dat were downloaded by Gradle :app:downloadGeoAssets
        val appAssetsDir = listOf(
            java.io.File("src/main/assets"),
            java.io.File("app/src/main/assets"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected app/src/main/assets to exist after downloadGeoAssets", appAssetsDir)
        assertTrue(java.io.File(appAssetsDir, "geoip.dat").length() > 100_000L)
        assertTrue(java.io.File(appAssetsDir, "geosite.dat").length() > 100_000L)

        // 3. Verify XrayAssetHelper extracts bundled geoip.dat & geosite.dat and logs on first launch
        com.example.core.xray.XrayAssetHelper.resetForTest()
        com.example.core.vpn.XrayEngineState.clearLogs()
        val status = com.example.core.xray.XrayAssetHelper.ensureInitialized(context)
        assertTrue("Expected geoip.dat to be bundled and unpacked", status.hasGeoIp)
        assertTrue("Expected geosite.dat to be bundled and unpacked", status.hasGeoSite)
        assertTrue(status.geoIpBytes > 100_000L)
        assertTrue(status.geoSiteBytes > 100_000L)
        assertTrue(java.io.File(context.filesDir, "geoip.dat").length() > 100_000L)
        assertTrue(java.io.File(context.filesDir, "geosite.dat").length() > 100_000L)
        assertTrue(com.example.core.vpn.XrayEngineState.state.value.hasGeoIp)
        assertTrue(com.example.core.vpn.XrayEngineState.state.value.hasGeoSite)
        val okLogs = com.example.core.vpn.XrayEngineState.state.value.logs
        assertTrue(
            "Expected INFO log for Xray geo assets initialization, got: $okLogs",
            okLogs.any { it.level == "INFO" && it.message.contains("geoip.dat") && it.message.contains("geosite.dat") },
        )

        // 4. Verify missing .dat files emit explicit WARN log instead of failing silently
        val emptyDir = java.io.File(context.cacheDir, "empty_geo_test_${System.nanoTime()}").apply { mkdirs() }
        try {
            val emptyAssetsCtx = object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): java.io.File = emptyDir
                override fun getAssets(): android.content.res.AssetManager {
                    return android.content.res.AssetManager::class.java.getDeclaredConstructor().apply {
                        isAccessible = true
                    }.newInstance()
                }
            }
            com.example.core.xray.XrayAssetHelper.resetForTest()
            com.example.core.vpn.XrayEngineState.clearLogs()
            val missingStatus = com.example.core.xray.XrayAssetHelper.ensureInitialized(emptyAssetsCtx)
            assertEquals(false, missingStatus.hasGeoIp)
            assertEquals(false, missingStatus.hasGeoSite)
            val warnLogs = com.example.core.vpn.XrayEngineState.state.value.logs
            assertTrue(
                "Expected explicit WARN log when geo assets are missing, got: $warnLogs",
                warnLogs.any { it.level == "WARN" && it.message.contains("Missing geo assets") },
            )
        } finally {
            com.example.core.xray.XrayAssetHelper.resetForTest()
            emptyDir.deleteRecursively()
        }
    }

    @Test
    fun `config builder removes geosite and geoip matchers cleanly when dat assets are unavailable`() {
        val profile = com.example.core.model.ProxyProfile(
            protocol = ProxyProtocol.VLESS,
            serverAddress = "vpn.example.com",
            serverPort = 443,
            uuid = "32622b0f-1a68-4e38-a7f7-a557edc26979",
            security = SecurityType.REALITY,
            sni = "www.nvidia.com",
            realityPublicKey = "t4Xs-5ksSsv71Pvg-FeYxeAE2ZL7ggkXiuNfhSPMdCk",
        )
        val rules = listOf(
            com.example.core.model.RoutingRule(
                id = 1L,
                name = "Bypass Private / LAN",
                enabled = true,
                ips = "geoip:private,192.168.0.0/16",
                action = com.example.core.model.RouteAction.DIRECT,
                priority = 1,
            ),
            com.example.core.model.RoutingRule(
                id = 2L,
                name = "Direct Local / RU Domains",
                enabled = true,
                domains = "geosite:category-ru,domain:.ru,ya.ru",
                ips = "geoip:ru",
                action = com.example.core.model.RouteAction.DIRECT,
                priority = 2,
            ),
            com.example.core.model.RoutingRule(
                id = 3L,
                name = "Block Ads & Trackers",
                enabled = true,
                domains = "geosite:category-ads-all",
                action = com.example.core.model.RouteAction.BLOCK,
                priority = 3,
            ),
        )

        val fallbackJson = XrayConfigBuilder.buildConfigJson(
            profile = profile,
            settings = XraySettings(bypassLan = true),
            rules = rules,
            hasGeoIp = false,
            hasGeoSite = false,
        )

        assertTrue("Fallback config must not contain geoip: references", !fallbackJson.contains("geoip:"))
        assertTrue("Fallback config must not contain geosite: references", !fallbackJson.contains("geosite:"))

        val root = JSONObject(fallbackJson)
        val dnsStr = root.getJSONObject("dns").toString()
        assertTrue(dnsStr.contains("domain:local"))

        val routingRules = root.getJSONObject("routing").getJSONArray("rules")
        val ruleList = (0 until routingRules.length()).map { routingRules.getJSONObject(it) }
        // Pure geosite:category-ads-all block rule must be omitted cleanly rather than creating a catch-all block rule
        assertTrue(ruleList.none { it.optString("outboundTag") == "block" })
        // Private CIDRs still present in bypassLan direct rule (compare decoded strings, avoiding JSON \/ escaping)
        assertTrue(
            ruleList.any { obj ->
                if (obj.optString("outboundTag") != "direct") return@any false
                val ips = obj.optJSONArray("ip") ?: return@any false
                (0 until ips.length()).any { idx -> ips.getString(idx) == "10.0.0.0/8" }
            }
        )
    }

    @Test
    fun `underlying physical network ranking and always-on lockdown detection work accurately`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Verify AndroidManifest declares SUPPORTS_ALWAYS_ON = true on XrayVpnService
        val serviceComponent = android.content.ComponentName(context, com.example.core.vpn.XrayVpnService::class.java)
        val serviceInfo = context.packageManager.getServiceInfo(
            serviceComponent,
            android.content.pm.PackageManager.GET_META_DATA,
        )
        assertNotNull("Expected meta-data on XrayVpnService", serviceInfo.metaData)
        assertEquals(
            true,
            serviceInfo.metaData.getBoolean("android.net.VpnService.SUPPORTS_ALWAYS_ON", false),
        )

        // 2. Verify Always-On invocation detection (null intent, android.net.VpnService action, and unflagged start vs self-initiated)
        assertTrue(com.example.core.vpn.XrayVpnService.isSystemAlwaysOnInvocation(null))
        val systemAlwaysOnIntent = android.content.Intent(android.net.VpnService.SERVICE_INTERFACE)
        assertTrue(com.example.core.vpn.XrayVpnService.isSystemAlwaysOnInvocation(systemAlwaysOnIntent))
        val unflaggedIntent = android.content.Intent(context, com.example.core.vpn.XrayVpnService::class.java)
        assertTrue(com.example.core.vpn.XrayVpnService.isSystemAlwaysOnInvocation(unflaggedIntent))

        val profile = com.example.core.model.ProxyProfile(
            protocol = ProxyProtocol.VLESS,
            serverAddress = "vpn.example.com",
            serverPort = 443,
            uuid = "32622b0f-1a68-4e38-a7f7-a557edc26979",
        )
        val selfStartIntent = com.example.core.vpn.XrayVpnService.buildStartIntent(
            context = context,
            profile = profile,
            protocolLabel = "VLESS REALITY",
            configJson = "{}",
            mtu = 1500,
            bypassLan = true,
            proxyOnly = false,
            settings = XraySettings(),
            rules = emptyList(),
        )
        assertEquals(false, com.example.core.vpn.XrayVpnService.isSystemAlwaysOnInvocation(selfStartIntent))
        val selfStopIntent = com.example.core.vpn.XrayVpnService.buildStopIntent(context)
        assertEquals(false, com.example.core.vpn.XrayVpnService.isSystemAlwaysOnInvocation(selfStopIntent))

        // 3. Verify rankPhysicalNetworks prioritizes validated Wi-Fi over Cellular and ignores VPN transports
        val netCell = org.robolectric.shadows.ShadowNetwork.newInstance(101)
        val netWifi = org.robolectric.shadows.ShadowNetwork.newInstance(102)
        val netVpn = org.robolectric.shadows.ShadowNetwork.newInstance(103)

        val capsCell = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().apply {
            val shadow = org.robolectric.Shadows.shadowOf(this)
            shadow.addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val capsWifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().apply {
            val shadow = org.robolectric.Shadows.shadowOf(this)
            shadow.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val capsVpn = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().apply {
            val shadow = org.robolectric.Shadows.shadowOf(this)
            shadow.addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
            shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        val ranked = com.example.core.vpn.XrayVpnService.rankPhysicalNetworks(
            mapOf(
                netCell to capsCell,
                netWifi to capsWifi,
                netVpn to capsVpn,
            )
        )
        assertEquals("Expected VPN network to be excluded and Wi-Fi ranked before Cellular", listOf(netWifi, netCell), ranked)
        assertEquals("Wi-Fi", com.example.core.vpn.XrayVpnService.formatNetworkCapabilitiesLabel(capsWifi))
        assertEquals("Cellular (LTE/5G)", com.example.core.vpn.XrayVpnService.formatNetworkCapabilitiesLabel(capsCell))

        // When Wi-Fi drops, Cellular becomes primary
        val rankedAfterWifiDrop = com.example.core.vpn.XrayVpnService.rankPhysicalNetworks(
            mapOf(netCell to capsCell)
        )
        assertEquals(listOf(netCell), rankedAfterWifiDrop)

        // Unvalidated Wi-Fi (even if marked activeDefaultNetwork) must yield to validated Cellular
        val capsWifiUnvalidated = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().apply {
            val shadow = org.robolectric.Shadows.shadowOf(this)
            shadow.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        val rankedValidatedFirst = com.example.core.vpn.XrayVpnService.rankPhysicalNetworks(
            networks = mapOf(netWifi to capsWifiUnvalidated, netCell to capsCell),
            activeDefaultNetwork = netWifi,
        )
        assertEquals("Validated Cellular must rank ahead of unvalidated Wi-Fi", listOf(netCell, netWifi), rankedValidatedFirst)

        // LinkProperties signature must detect when Cellular acquires a routable IP
        val emptyLp = android.net.LinkProperties().apply { interfaceName = "rmnet_data0" }
        val (_, hasEmptyIp) = com.example.core.vpn.XrayVpnService.buildLinkSignature(emptyLp)
        assertEquals(false, hasEmptyIp)

        val readyLp = android.net.LinkProperties().apply {
            interfaceName = "rmnet_data0"
            val addr = java.net.InetAddress.getByName("10.12.34.56")
            val linkAddr = runCatching {
                android.net.LinkAddress::class.java.getDeclaredConstructor(
                    java.net.InetAddress::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }.newInstance(addr, 24, 0, 0)
            }.getOrElse {
                android.net.LinkAddress::class.java.getDeclaredConstructor(String::class.java)
                    .apply { isAccessible = true }
                    .newInstance("10.12.34.56/24")
            }
            setLinkAddresses(listOf(linkAddr))
            setDnsServers(listOf(java.net.InetAddress.getByName("8.8.8.8")))
        }
        val (readySig, hasReadyIp) = com.example.core.vpn.XrayVpnService.buildLinkSignature(readyLp)
        assertTrue(hasReadyIp)
        assertTrue(readySig.contains("10.12.34.56"))

        // Verify XrayVpnService networkCallback handles Wi-Fi -> Offline -> Cellular (LinkProperties + Validated)
        val controller = org.robolectric.Robolectric.buildService(com.example.core.vpn.XrayVpnService::class.java).create()
        val vpnService = controller.get()
        try {
            com.example.core.vpn.XrayEngineState.clearLogs()
            com.example.core.vpn.XrayEngineState.updateStatus(
                status = com.example.core.vpn.TunnelStatus.CONNECTED,
                profileId = 1L,
                profileName = "Test Node",
            )
            vpnService.networkCallbackForTest.onCapabilitiesChanged(netWifi, capsWifi)
            assertEquals("Wi-Fi", com.example.core.vpn.XrayEngineState.state.value.underlyingNetworkLabel)

            vpnService.networkCallbackForTest.onLost(netWifi)
            assertEquals("Offline", com.example.core.vpn.XrayEngineState.state.value.underlyingNetworkLabel)

            // Cellular arrives initially unvalidated & without IP, then receives LinkProperties & VALIDATED
            val capsCellUnvalidated = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().apply {
                val shadow = org.robolectric.Shadows.shadowOf(this)
                shadow.addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            vpnService.networkCallbackForTest.onCapabilitiesChanged(netCell, capsCellUnvalidated)
            assertEquals("Cellular (LTE/5G)", com.example.core.vpn.XrayEngineState.state.value.underlyingNetworkLabel)

            vpnService.networkCallbackForTest.onLinkPropertiesChanged(netCell, readyLp)
            vpnService.networkCallbackForTest.onCapabilitiesChanged(netCell, capsCell)

            val handoffLogs = com.example.core.vpn.XrayEngineState.state.value.logs
            assertTrue(
                "Expected handoff log for onLinkPropertiesChanged with ip=true, got: $handoffLogs",
                handoffLogs.any { it.message.contains("onLinkPropertiesChanged") && it.message.contains("ip=true") },
            )
        } finally {
            com.example.core.vpn.XrayEngineState.updateStatus(status = com.example.core.vpn.TunnelStatus.IDLE)
            controller.destroy()
        }
    }

    @Test
    fun `rapid network switches and immediate stop recover even when engine mutex is stalled`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = org.robolectric.Robolectric.buildService(com.example.core.vpn.XrayVpnService::class.java).create()
        val vpnService = controller.get()
        try {
            val netCell = org.robolectric.shadows.ShadowNetwork.newInstance(201)
            val netWifi = org.robolectric.shadows.ShadowNetwork.newInstance(202)
            val capsWifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().apply {
                val shadow = org.robolectric.Shadows.shadowOf(this)
                shadow.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            val capsCell = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().apply {
                val shadow = org.robolectric.Shadows.shadowOf(this)
                shadow.addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

            // Simulate CONNECTED state and rapid Wi-Fi <-> Cellular flapping
            com.example.core.vpn.XrayEngineState.clearLogs()
            com.example.core.vpn.XrayEngineState.updateStatus(
                status = com.example.core.vpn.TunnelStatus.CONNECTED,
                profileId = 99L,
                profileName = "Flap Node",
            )
            repeat(6) {
                vpnService.networkCallbackForTest.onCapabilitiesChanged(netWifi, capsWifi)
                vpnService.networkCallbackForTest.onLost(netWifi)
                vpnService.networkCallbackForTest.onCapabilitiesChanged(netCell, capsCell)
                vpnService.networkCallbackForTest.onLost(netCell)
            }

            // Simulate a stalled native call holding the current engineMutex AND an open TUN ParcelFileDescriptor
            val mutexRefField = com.example.core.vpn.XrayVpnService::class.java.getDeclaredField("engineMutexRef").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val mutexRef = mutexRefField.get(vpnService) as java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.sync.Mutex>
            val stalledMutex = kotlinx.coroutines.sync.Mutex(locked = true)
            mutexRef.set(stalledMutex)

            val pipe = android.os.ParcelFileDescriptor.createPipe()
            val fakeTunPfd = pipe[0]
            pipe[1].close()
            val orphanPipe = android.os.ParcelFileDescriptor.createPipe()
            val orphanTunPfd = orphanPipe[0]
            orphanPipe[1].close()
            val tunField = com.example.core.vpn.XrayVpnService::class.java.getDeclaredField("tunInterface").apply {
                isAccessible = true
            }
            tunField.set(vpnService, fakeTunPfd)
            val activeSetField = com.example.core.vpn.XrayVpnService::class.java.getDeclaredField("activeTunDescriptors").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val activeTunSet = activeSetField.get(vpnService) as MutableSet<android.os.ParcelFileDescriptor>
            activeTunSet.add(fakeTunPfd)
            activeTunSet.add(orphanTunPfd)

            // Trigger immediate stop + ACTION_STOP while stalledMutex is held
            com.example.core.vpn.XrayVpnService.requestImmediateStop()
            vpnService.onStartCommand(com.example.core.vpn.XrayVpnService.buildStopIntent(context), 0, 1)

            assertEquals(com.example.core.vpn.TunnelStatus.IDLE, com.example.core.vpn.XrayEngineState.state.value.status)

            // Wait up to 2.5s for bounded 800ms mutex recovery to close fakeTunPfd and swap out stalledMutex
            val deadline = System.currentTimeMillis() + 2500L
            while (System.currentTimeMillis() < deadline &&
                (tunField.get(vpnService) != null || activeTunSet.isNotEmpty() || mutexRef.get() === stalledMutex)
            ) {
                Thread.sleep(50L)
            }
            assertEquals("Expected stalled TUN fd to be force-closed and cleared", null, tunField.get(vpnService))
            assertTrue("Expected all tracked activeTunDescriptors to be closed and cleared", activeTunSet.isEmpty())
            assertTrue("Expected stalled mutex to be replaced by fresh unlocked Mutex", mutexRef.get() !== stalledMutex)
            assertEquals(false, mutexRef.get().isLocked)
            assertTrue(
                "Expected force-closing warning log",
                com.example.core.vpn.XrayEngineState.state.value.logs.any {
                    it.message.contains("Force-closing stalled VPN interface")
                },
            )
        } finally {
            com.example.core.vpn.XrayEngineState.updateStatus(status = com.example.core.vpn.TunnelStatus.IDLE)
            controller.destroy()
        }
    }

    @Test
    fun `direct dns is optional but enabled by default with anti-spoofing doh protection`() {
        val defaultSettings = XraySettings()
        assertEquals("Expected directDnsEnabled to be true by default", true, defaultSettings.directDnsEnabled)
        assertEquals("Expected protectDirectDns to be true by default", true, defaultSettings.protectDirectDns)

        val ruRule = com.example.core.model.RoutingRule(
            id = 2L,
            name = "Direct Local / RU Domains",
            enabled = true,
            domains = "domain:.ru,ya.ru,geosite:category-ru",
            ips = "geoip:ru",
            action = com.example.core.model.RouteAction.DIRECT,
            priority = 2,
        )
        val hostnameProfile = com.example.core.model.ProxyProfile(
            protocol = ProxyProtocol.VLESS,
            serverAddress = "vpn.example.com",
            serverPort = 443,
            uuid = "32622b0f-1a68-4e38-a7f7-a557edc26979",
            security = SecurityType.REALITY,
            sni = "www.nvidia.com",
            realityPublicKey = "t4Xs-5ksSsv71Pvg-FeYxeAE2ZL7ggkXiuNfhSPMdCk",
        )

        // 1. Default settings: Direct DNS enabled + DoH protection upgrades 8.8.8.8 -> https+local://8.8.8.8/dns-query
        val defaultJson = JSONObject(
            XrayConfigBuilder.buildConfigJson(
                profile = hostnameProfile,
                settings = defaultSettings,
                rules = listOf(ruRule),
            )
        )
        val defaultServers = defaultJson.getJSONObject("dns").getJSONArray("servers")
        assertEquals(2, defaultServers.length())
        val directServerObj = defaultServers.getJSONObject(1)
        assertEquals("https+local://8.8.8.8/dns-query", directServerObj.getString("address"))
        val directDomainsStr = directServerObj.getJSONArray("domains").toString()
        assertTrue(directDomainsStr.contains("geosite:private"))
        assertTrue(directDomainsStr.contains("full:vpn.example.com"))
        assertTrue(directDomainsStr.contains("domain:ru"))

        val defaultRules = defaultJson.getJSONObject("routing").getJSONArray("rules")
        val defaultRuleList = (0 until defaultRules.length()).map { defaultRules.getJSONObject(it) }
        assertTrue(
            "Expected direct port 443 bypass rule for DoH Direct DNS",
            defaultRuleList.any {
                it.optString("outboundTag") == "direct" &&
                    it.optString("port") == "443" &&
                    it.optJSONArray("ip")?.toString()?.contains("8.8.8.8") == true
            },
        )

        // 2. When protectDirectDns = false: plain 8.8.8.8 is used
        val plainDirectJson = JSONObject(
            XrayConfigBuilder.buildConfigJson(
                profile = hostnameProfile,
                settings = defaultSettings.copy(protectDirectDns = false),
                rules = listOf(ruRule),
            )
        )
        val plainDirectObj = plainDirectJson.getJSONObject("dns").getJSONArray("servers").getJSONObject(1)
        assertEquals("8.8.8.8", plainDirectObj.getString("address"))

        // 3. When directDnsEnabled = false with hostname server: only full:vpn.example.com bootstrap is kept,
        // while all user DIRECT rule domains and geosite:private resolve exclusively via encrypted Remote DNS
        val disabledHostnameJson = JSONObject(
            XrayConfigBuilder.buildConfigJson(
                profile = hostnameProfile,
                settings = defaultSettings.copy(directDnsEnabled = false),
                rules = listOf(ruRule),
            )
        )
        val bootstrapObj = disabledHostnameJson.getJSONObject("dns").getJSONArray("servers").getJSONObject(1)
        val bootstrapDomains = bootstrapObj.getJSONArray("domains").toString()
        assertTrue(bootstrapDomains.contains("full:vpn.example.com"))
        assertTrue(!bootstrapDomains.contains("domain:ru"))
        assertTrue(!bootstrapDomains.contains("geosite:private"))

        // 4. When directDnsEnabled = false with IP-literal server: only Remote DNS server is in dns.servers
        val ipProfile = hostnameProfile.copy(serverAddress = "203.0.113.10")
        val disabledIpJson = JSONObject(
            XrayConfigBuilder.buildConfigJson(
                profile = ipProfile,
                settings = defaultSettings.copy(directDnsEnabled = false),
                rules = listOf(ruRule),
            )
        )
        val ipServers = disabledIpJson.getJSONObject("dns").getJSONArray("servers")
        assertEquals(1, ipServers.length())
        assertEquals("https://1.1.1.1/dns-query", ipServers.getString(0))
    }

    @Test
    fun `per-app proxy automatically groups download manager and play services with google play store so app updates work`() {
        // 1. PROXY_SELECTED with Google Play Store includes DownloadManager and Google Play Services
        val playSelected = com.example.core.vpn.XrayVpnService.expandPerAppCompanionPackages(
            packages = setOf("com.android.vending", "org.telegram.messenger"),
            mode = com.example.core.model.PerAppProxyMode.PROXY_SELECTED,
            selfPackage = "com.aistudio.nekoxray.lwkpmr",
        )
        assertTrue("Expected Play Store", "com.android.vending" in playSelected)
        assertTrue("Expected DownloadManager for Play Store APK updates", "com.android.providers.downloads" in playSelected)
        assertTrue("Expected DownloadManager UI", "com.android.providers.downloads.ui" in playSelected)
        assertTrue("Expected Google Play Services", "com.google.android.gms" in playSelected)
        assertTrue("Expected Google Services Framework", "com.google.android.gsf" in playSelected)

        // 2. BYPASS_SELECTED with Google Play Store also bypasses DownloadManager so CDN tokens match direct IP
        val playBypassed = com.example.core.vpn.XrayVpnService.expandPerAppCompanionPackages(
            packages = setOf("com.android.vending"),
            mode = com.example.core.model.PerAppProxyMode.BYPASS_SELECTED,
            selfPackage = "com.aistudio.nekoxray.lwkpmr",
        )
        assertTrue("Expected Play Store", "com.android.vending" in playBypassed)
        assertTrue("Expected DownloadManager bypassed alongside Play Store", "com.android.providers.downloads" in playBypassed)
        assertTrue("GMS should not be forcibly bypassed when Play Store is bypassed", "com.google.android.gms" !in playBypassed)
    }
}
