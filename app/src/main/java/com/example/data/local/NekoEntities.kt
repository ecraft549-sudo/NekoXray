package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.core.model.FlowType
import com.example.core.model.ProxyGroup
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.RouteAction
import com.example.core.model.RoutingRule
import com.example.core.model.SecurityType
import com.example.core.model.SshAuthType
import com.example.core.model.TransportType

@Entity(tableName = "proxy_profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val groupId: Long = 1L,
    val name: String = "",
    val protocol: String = ProxyProtocol.VLESS.name,
    val serverAddress: String = "",
    val serverPort: Int = 443,
    val uuid: String = "",
    val password: String = "",
    val username: String = "",
    val alterId: Int = 0,
    val encryption: String = "none",
    val flow: String = FlowType.VISION.name,
    val transport: String = TransportType.TCP.name,
    val host: String = "",
    val path: String = "",
    val grpcAuthority: String = "",
    val xhttpMode: String = "auto",
    val xhttpExtra: String = "",
    val headerType: String = "none",
    val security: String = SecurityType.REALITY.name,
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "chrome",
    val allowInsecure: Boolean = false,
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "/",
    val realityMldsa65Verify: String = "",
    val wgPrivateKey: String = "",
    val wgPeerPublicKey: String = "",
    val wgPreSharedKey: String = "",
    val wgLocalAddress: String = "10.0.0.2/32",
    val wgMtu: Int = 1420,
    val wgReserved: String = "",
    val sshAuthType: String = SshAuthType.PASSWORD.name,
    val sshPrivateKey: String = "",
    val sshPrivateKeyPassphrase: String = "",
    val sshPublicKey: String = "",
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 8,
    val packetEncoding: String = "xudp",
    val customConfigJson: String = "",
    val latencyMs: Int? = null,
    val tlsLatencyMs: Int? = null,
    val lastTestedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toDomain(): ProxyProfile = ProxyProfile(
        id = id,
        groupId = groupId,
        name = name,
        protocol = runCatching { ProxyProtocol.valueOf(protocol) }.getOrDefault(ProxyProtocol.VLESS),
        serverAddress = serverAddress,
        serverPort = serverPort,
        uuid = uuid,
        password = password,
        username = username,
        alterId = alterId,
        encryption = encryption,
        flow = runCatching { FlowType.valueOf(flow) }.getOrDefault(FlowType.NONE),
        transport = runCatching { TransportType.valueOf(transport) }.getOrDefault(TransportType.TCP),
        host = host,
        path = path,
        grpcAuthority = grpcAuthority,
        xhttpMode = xhttpMode,
        xhttpExtra = xhttpExtra,
        headerType = headerType,
        security = runCatching { SecurityType.valueOf(security) }.getOrDefault(SecurityType.NONE),
        sni = sni,
        alpn = alpn,
        fingerprint = fingerprint,
        allowInsecure = allowInsecure,
        realityPublicKey = realityPublicKey,
        realityShortId = realityShortId,
        realitySpiderX = realitySpiderX,
        realityMldsa65Verify = realityMldsa65Verify,
        wgPrivateKey = wgPrivateKey,
        wgPeerPublicKey = wgPeerPublicKey,
        wgPreSharedKey = wgPreSharedKey,
        wgLocalAddress = wgLocalAddress,
        wgMtu = wgMtu,
        wgReserved = wgReserved,
        sshAuthType = runCatching { SshAuthType.valueOf(sshAuthType) }.getOrDefault(SshAuthType.PASSWORD),
        sshPrivateKey = sshPrivateKey,
        sshPrivateKeyPassphrase = sshPrivateKeyPassphrase,
        sshPublicKey = sshPublicKey,
        muxEnabled = muxEnabled,
        muxConcurrency = muxConcurrency,
        packetEncoding = packetEncoding,
        customConfigJson = customConfigJson,
        latencyMs = latencyMs,
        tlsLatencyMs = tlsLatencyMs,
        lastTestedAt = lastTestedAt,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(p: ProxyProfile): ProfileEntity = ProfileEntity(
            id = p.id,
            groupId = p.groupId,
            name = p.name,
            protocol = p.protocol.name,
            serverAddress = p.serverAddress,
            serverPort = p.serverPort,
            uuid = p.uuid,
            password = p.password,
            username = p.username,
            alterId = p.alterId,
            encryption = p.encryption,
            flow = p.flow.name,
            transport = p.transport.name,
            host = p.host,
            path = p.path,
            grpcAuthority = p.grpcAuthority,
            xhttpMode = p.xhttpMode,
            xhttpExtra = p.xhttpExtra,
            headerType = p.headerType,
            security = p.security.name,
            sni = p.sni,
            alpn = p.alpn,
            fingerprint = p.fingerprint,
            allowInsecure = p.allowInsecure,
            realityPublicKey = p.realityPublicKey,
            realityShortId = p.realityShortId,
            realitySpiderX = p.realitySpiderX,
            realityMldsa65Verify = p.realityMldsa65Verify,
            wgPrivateKey = p.wgPrivateKey,
            wgPeerPublicKey = p.wgPeerPublicKey,
            wgPreSharedKey = p.wgPreSharedKey,
            wgLocalAddress = p.wgLocalAddress,
            wgMtu = p.wgMtu,
            wgReserved = p.wgReserved,
            sshAuthType = p.sshAuthType.name,
            sshPrivateKey = p.sshPrivateKey,
            sshPrivateKeyPassphrase = p.sshPrivateKeyPassphrase,
            sshPublicKey = p.sshPublicKey,
            muxEnabled = p.muxEnabled,
            muxConcurrency = p.muxConcurrency,
            packetEncoding = p.packetEncoding,
            customConfigJson = p.customConfigJson,
            latencyMs = p.latencyMs,
            tlsLatencyMs = p.tlsLatencyMs,
            lastTestedAt = p.lastTestedAt,
            createdAt = p.createdAt,
        )
    }
}

@Entity(tableName = "proxy_groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String = "Default",
    val isSubscription: Boolean = false,
    val subscriptionUrl: String = "",
    val userAgent: String = "NekoXray/v26.10.31",
    val autoUpdate: Boolean = false,
    val lastUpdatedAt: Long? = null,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestampSec: Long = 0L,
) {
    fun toDomain(): ProxyGroup = ProxyGroup(
        id = id,
        name = name,
        isSubscription = isSubscription,
        subscriptionUrl = subscriptionUrl,
        userAgent = userAgent,
        autoUpdate = autoUpdate,
        lastUpdatedAt = lastUpdatedAt,
        uploadBytes = uploadBytes,
        downloadBytes = downloadBytes,
        totalBytes = totalBytes,
        expireTimestampSec = expireTimestampSec,
    )

    companion object {
        fun fromDomain(g: ProxyGroup): GroupEntity = GroupEntity(
            id = g.id,
            name = g.name,
            isSubscription = g.isSubscription,
            subscriptionUrl = g.subscriptionUrl,
            userAgent = g.userAgent,
            autoUpdate = g.autoUpdate,
            lastUpdatedAt = g.lastUpdatedAt,
            uploadBytes = g.uploadBytes,
            downloadBytes = g.downloadBytes,
            totalBytes = g.totalBytes,
            expireTimestampSec = g.expireTimestampSec,
        )
    }
}

@Entity(tableName = "routing_rules")
data class RoutingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String = "",
    val enabled: Boolean = true,
    val domains: String = "",
    val ips: String = "",
    val ports: String = "",
    val protocols: String = "",
    val action: String = RouteAction.DIRECT.name,
    val priority: Int = 0,
) {
    fun toDomain(): RoutingRule = RoutingRule(
        id = id,
        name = name,
        enabled = enabled,
        domains = domains,
        ips = ips,
        ports = ports,
        protocols = protocols,
        action = runCatching { RouteAction.valueOf(action) }.getOrDefault(RouteAction.DIRECT),
        priority = priority,
    )

    companion object {
        fun fromDomain(r: RoutingRule): RoutingRuleEntity = RoutingRuleEntity(
            id = r.id,
            name = r.name,
            enabled = r.enabled,
            domains = r.domains,
            ips = r.ips,
            ports = r.ports,
            protocols = r.protocols,
            action = r.action.name,
            priority = r.priority,
        )
    }
}
