package com.example.data.repository

import android.content.Context
import com.example.core.model.IPv6Mode
import com.example.core.model.ProxyGroup
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.RouteAction
import com.example.core.model.RoutingRule
import com.example.core.model.SecurityType
import com.example.core.model.ServiceMode
import com.example.core.model.TransportType
import com.example.core.model.XraySettings
import com.example.core.network.NetworkTester
import com.example.core.parser.UriParser
import com.example.core.vpn.SshTunnelBridge
import com.example.core.vpn.TuicTunnelBridge
import com.example.core.vpn.TunnelStatus
import com.example.core.vpn.XrayEngineState
import com.example.core.vpn.XrayVpnService
import com.example.core.xray.XrayConfigBuilder
import com.example.data.local.GroupEntity
import com.example.data.local.NekoDatabase
import com.example.data.local.ProfileEntity
import com.example.data.local.RoutingRuleEntity
import com.example.data.preferences.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class NekoRepository(
    private val db: NekoDatabase,
    private val settingsStore: SettingsDataStore,
    private val appContext: Context? = null,
) {
    private val profileDao = db.profileDao()
    private val groupDao = db.groupDao()
    private val ruleDao = db.routingRuleDao()
    private val probeHints = java.util.concurrent.ConcurrentHashMap<Long, String>()

    fun lastProbeHint(profileId: Long): String? = probeHints[profileId]

    val profilesFlow: Flow<List<ProxyProfile>> =
        profileDao.observeAll().map { list -> list.map { it.toDomain() } }

    val groupsFlow: Flow<List<ProxyGroup>> =
        groupDao.observeAll().map { list -> list.map { it.toDomain() } }

    val routingRulesFlow: Flow<List<RoutingRule>> =
        ruleDao.observeAll().map { list -> list.map { it.toDomain() } }

    val settingsFlow: Flow<XraySettings> = settingsStore.settingsFlow
    val selectedProfileIdFlow: Flow<Long> = settingsStore.selectedProfileIdFlow
    val selectedGroupIdFlow: Flow<Long> = settingsStore.selectedGroupIdFlow

    suspend fun ensureDefaults() {
        if (groupDao.count() == 0) {
            groupDao.insert(
                GroupEntity(
                    id = 1L,
                    name = "Default",
                    isSubscription = false,
                )
            )
        }
        if (ruleDao.count() == 0) {
            ruleDao.insertAll(
                listOf(
                    RoutingRuleEntity(
                        name = "Block Ads & Trackers",
                        enabled = true,
                        domains = "geosite:category-ads-all",
                        action = RouteAction.BLOCK.name,
                        priority = 1,
                    ),
                    RoutingRuleEntity(
                        name = "Direct Local / RU Domains",
                        enabled = false,
                        domains = "geosite:category-ru,domain:ru,domain:su,domain:xn--p1ai",
                        ips = "geoip:ru",
                        action = RouteAction.DIRECT.name,
                        priority = 2,
                    ),
                    RoutingRuleEntity(
                        name = "Block BitTorrent",
                        enabled = false,
                        protocols = "bittorrent",
                        action = RouteAction.BLOCK.name,
                        priority = 3,
                    ),
                )
            )
        }
    }

    suspend fun getProfile(id: Long): ProxyProfile? = profileDao.getById(id)?.toDomain()

    suspend fun saveProfile(profile: ProxyProfile): Long {
        val id = profileDao.insert(ProfileEntity.fromDomain(profile))
        return if (profile.id > 0L) profile.id else id
    }

    suspend fun duplicateProfile(profile: ProxyProfile): Long {
        val copy = profile.copy(
            id = 0L,
            name = "${profile.displayTitle()} (Copy)",
            createdAt = System.currentTimeMillis(),
        )
        return profileDao.insert(ProfileEntity.fromDomain(copy))
    }

    suspend fun deleteProfile(id: Long) {
        profileDao.clearChainReferences(id)
        profileDao.deleteById(id)
    }

    suspend fun importFromText(raw: String, groupId: Long): Int {
        val targetGroupId = if (groupId <= 0L) 1L else groupId
        val parsed = UriParser.parseBatch(raw, targetGroupId)
        if (parsed.isEmpty()) return 0
        val inserted = profileDao.insertAll(parsed.map { ProfileEntity.fromDomain(it) })
        if (inserted.isNotEmpty()) {
            settingsStore.setSelectedProfileId(inserted.first())
        }
        return inserted.size
    }

    private suspend fun preResolveEntryHop(
        profile: ProxyProfile,
        preferIpv6: Boolean,
        vpnService: XrayVpnService?,
    ): ProxyProfile {
        if (profile.protocol in setOf(ProxyProtocol.SSH, ProxyProtocol.TUIC, ProxyProtocol.CUSTOM_JSON)) {
            return profile
        }
        val rawHost = profile.serverAddress.trim()
        if (rawHost.isEmpty()) return profile
        val resolvedIp = NetworkTester.resolveHostToIp(
            host = rawHost,
            preferIpv6 = preferIpv6,
            vpnService = vpnService,
        ) ?: return profile
        val preservedSni = if (profile.security != SecurityType.NONE && profile.sni.isBlank()) {
            rawHost
        } else {
            profile.sni
        }
        val preservedHost = if (
            profile.transport in setOf(TransportType.WS, TransportType.XHTTP, TransportType.HTTPUPGRADE, TransportType.H2) &&
            profile.host.isBlank()
        ) {
            rawHost
        } else {
            profile.host
        }
        return profile.copy(
            serverAddress = resolvedIp,
            sni = preservedSni,
            host = preservedHost,
        )
    }

    suspend fun testProfileLatency(profile: ProxyProfile): Pair<Int?, Int?> {
        val allProfiles = profileDao.getAllSync().map { it.toDomain() }
        val runtime = XrayEngineState.state.value
        val isActiveConnectedProfile = runtime.status == TunnelStatus.CONNECTED &&
            runtime.activeProfileId == profile.id &&
            profile.id > 0L
        val activeVpn = XrayVpnService.activeServiceOrNull()

        if (profile.chainProxyId > 0L) {
            val chainIssues = XrayConfigBuilder.validateProfile(profile, allProfiles).filter { it.isError }
            if (chainIssues.isNotEmpty()) {
                XrayEngineState.appendLog("WARN", "Chain ping skipped for '${profile.displayTitle()}': ${chainIssues.first().message}")
                profileDao.updateLatency(profile.id, null, null, System.currentTimeMillis())
                return null to null
            }

            // Fast path: if this chained profile is currently connected, probe directly through the live tunnel
            if (isActiveConnectedProfile) {
                val activeMs = XrayVpnService.measureActiveTunnelDelay()
                if (activeMs != null) {
                    profileDao.updateLatency(profile.id, null, activeMs, System.currentTimeMillis())
                    return null to activeMs
                }
            }

            val ctx = appContext
            val chainDelayMs = if (ctx != null) {
                val geo = com.example.core.xray.XrayAssetHelper.ensureInitialized(ctx)
                val settings = runCatching { settingsFlow.first() }.getOrDefault(XraySettings())
                val preferIpv6 = settings.ipv6Mode == IPv6Mode.ONLY || settings.ipv6Mode == IPv6Mode.PREFER
                val chain = XrayConfigBuilder.resolveChain(profile, allProfiles)
                val tempSshBridges = mutableListOf<SshTunnelBridge>()
                val tempTuicBridges = mutableListOf<TuicTunnelBridge>()
                val bridgePorts = mutableMapOf<Long, Int>()
                try {
                    withContext(Dispatchers.IO) {
                        for (hop in chain.hops) {
                            when (hop.protocol) {
                                ProxyProtocol.SSH -> {
                                    val bridge = SshTunnelBridge(profile = hop, vpnService = activeVpn)
                                    val port = bridge.start()
                                    tempSshBridges.add(bridge)
                                    bridgePorts[hop.id] = port
                                }
                                ProxyProtocol.TUIC -> {
                                    val bridge = TuicTunnelBridge(profile = hop, vpnService = activeVpn)
                                    val port = bridge.start()
                                    tempTuicBridges.add(bridge)
                                    bridgePorts[hop.id] = port
                                }
                                else -> Unit
                            }
                        }
                    }

                    val resolvedHops = chain.hops.mapIndexed { idx, hop ->
                        if (idx == chain.hops.lastIndex) {
                            preResolveEntryHop(hop, preferIpv6, activeVpn)
                        } else {
                            hop
                        }
                    }
                    val loopbackPorts = XrayConfigBuilder.allocateChainLoopbackPorts(resolvedHops)
                    val usedPorts = loopbackPorts.values.toMutableSet()
                    val tempSocksPort = NetworkTester.findFreeLoopbackPort(usedPorts).also { if (it > 0) usedPorts.add(it) }
                    val tempHttpPort = NetworkTester.findFreeLoopbackPort(usedPorts)
                    val testSettings = settings.copy(
                        serviceMode = ServiceMode.PROXY_ONLY,
                        socksPort = if (tempSocksPort > 0) tempSocksPort else settings.socksPort + 210,
                        httpPort = if (tempHttpPort > 0) tempHttpPort else settings.httpPort + 210,
                        domainStrategy = "AsIs",
                        bypassLan = false,
                    )
                    val configJson = XrayConfigBuilder.buildConfigJson(
                        profile = resolvedHops.first(),
                        settings = testSettings,
                        rules = emptyList(),
                        includeTunInbound = false,
                        sshLocalBridgePort = bridgePorts[profile.id] ?: 0,
                        tuicLocalBridgePort = bridgePorts[profile.id] ?: 0,
                        hasGeoIp = geo.hasGeoIp,
                        hasGeoSite = geo.hasGeoSite,
                        allProfiles = resolvedHops,
                        chainBridgePorts = bridgePorts,
                        chainLoopbackPorts = loopbackPorts,
                    )
                    NetworkTester.testXrayConfigWithTempCore(
                        context = ctx,
                        configJson = configJson,
                        localSocksPort = testSettings.socksPort,
                    )
                } catch (err: Throwable) {
                    XrayEngineState.appendLog(
                        "WARN",
                        "Chain ping failed for '${profile.displayTitle()}': ${err.message ?: err.javaClass.simpleName}",
                    )
                    null
                } finally {
                    withContext(Dispatchers.IO) {
                        tempSshBridges.forEach { runCatching { it.stop() } }
                        tempTuicBridges.forEach { runCatching { it.stop() } }
                    }
                }
            } else {
                null
            }
            profileDao.updateLatency(profile.id, null, chainDelayMs, System.currentTimeMillis())
            return null to chainDelayMs
        }

        if (profile.protocol == ProxyProtocol.TUIC) {
            val tuicMs = if (isActiveConnectedProfile) {
                XrayVpnService.measureActiveTunnelDelay()
            } else {
                null
            } ?: withContext(Dispatchers.IO) {
                TuicTunnelBridge.testTuicHandshake(profile)
            }
            profileDao.updateLatency(profile.id, null, tuicMs, System.currentTimeMillis())
            return null to tuicMs
        }
        val tcpMs = if (profile.protocol == ProxyProtocol.HYSTERIA2) {
            null
        } else {
            NetworkTester.testTcpLatency(profile.serverAddress, profile.serverPort)
        }
        if (profile.protocol == ProxyProtocol.SSH) {
            val sshMs = if (isActiveConnectedProfile) {
                XrayVpnService.measureActiveTunnelDelay()
            } else {
                null
            } ?: if (tcpMs != null) {
                withContext(Dispatchers.IO) {
                    SshTunnelBridge.testSshHandshake(profile)
                }
            } else {
                null
            }
            profileDao.updateLatency(profile.id, tcpMs, sshMs, System.currentTimeMillis())
            return tcpMs to sshMs
        }
        if (profile.protocol == ProxyProtocol.SOCKS5) {
            val probe = if (tcpMs != null) {
                NetworkTester.probeSocks5Proxy(
                    host = profile.serverAddress,
                    port = profile.serverPort,
                    username = profile.username,
                    password = profile.password,
                )
            } else {
                null
            }
            if (probe != null) {
                val label = probe.summaryLabel()
                probeHints[profile.id] = label
                XrayEngineState.appendLog(
                    if (probe.authOk && (probe.ipv4Exit || probe.ipv6Exit)) "INFO" else "WARN",
                    "SOCKS5 probe '${profile.displayTitle()}' (${profile.serverAddress}:${profile.serverPort}): $label" +
                        (probe.latencyMs?.let { " · ${it}ms" } ?: ""),
                )
                if (!probe.authOk) {
                    profileDao.updateLatency(profile.id, tcpMs, null, System.currentTimeMillis())
                    return tcpMs to null
                }
            } else {
                probeHints.remove(profile.id)
            }
            val ctx = appContext
            val xrayDelayMs = if (isActiveConnectedProfile) {
                XrayVpnService.measureActiveTunnelDelay()
            } else {
                null
            } ?: if (ctx != null) {
                val geo = com.example.core.xray.XrayAssetHelper.ensureInitialized(ctx)
                val settings = runCatching { settingsFlow.first() }.getOrDefault(XraySettings())
                val preferIpv6 = settings.ipv6Mode == IPv6Mode.ONLY || settings.ipv6Mode == IPv6Mode.PREFER
                val resolvedProfile = preResolveEntryHop(profile, preferIpv6, activeVpn)
                val testSettings = settings.copy(
                    serviceMode = ServiceMode.PROXY_ONLY,
                    domainStrategy = "AsIs",
                    bypassLan = false,
                )
                val configJson = XrayConfigBuilder.buildConfigJson(
                    profile = resolvedProfile,
                    settings = testSettings,
                    rules = emptyList(),
                    includeTunInbound = false,
                    hasGeoIp = geo.hasGeoIp,
                    hasGeoSite = geo.hasGeoSite,
                    allProfiles = allProfiles,
                )
                NetworkTester.testXrayOutboundDelay(ctx, configJson)
            } else {
                null
            }
            val finalMs = xrayDelayMs ?: probe?.takeIf { it.authOk && (it.ipv4Exit || it.ipv6Exit) }?.latencyMs
            profileDao.updateLatency(profile.id, tcpMs, finalMs, System.currentTimeMillis())
            return tcpMs to finalMs
        }
        probeHints.remove(profile.id)
        val ctx = appContext
        val xrayDelayMs = if (isActiveConnectedProfile) {
            XrayVpnService.measureActiveTunnelDelay()
        } else {
            null
        } ?: if (ctx != null) {
            val geo = com.example.core.xray.XrayAssetHelper.ensureInitialized(ctx)
            val settings = runCatching { settingsFlow.first() }.getOrDefault(XraySettings())
            val preferIpv6 = settings.ipv6Mode == IPv6Mode.ONLY || settings.ipv6Mode == IPv6Mode.PREFER
            val resolvedProfile = preResolveEntryHop(profile, preferIpv6, activeVpn)
            val testSettings = settings.copy(
                serviceMode = ServiceMode.PROXY_ONLY,
                domainStrategy = "AsIs",
                bypassLan = false,
            )
            val configJson = XrayConfigBuilder.buildConfigJson(
                profile = resolvedProfile,
                settings = testSettings,
                rules = emptyList(),
                includeTunInbound = false,
                hasGeoIp = geo.hasGeoIp,
                hasGeoSite = geo.hasGeoSite,
                allProfiles = allProfiles,
            )
            NetworkTester.testXrayOutboundDelay(ctx, configJson)
        } else {
            null
        }
        val tlsMs = xrayDelayMs ?: if (tcpMs != null &&
            profile.protocol != ProxyProtocol.HYSTERIA2 &&
            profile.security.wireValue != "none"
        ) {
            val sni = profile.sni.ifBlank { profile.serverAddress }
            NetworkTester.probeTlsSni(profile.serverAddress, profile.serverPort, sni).latencyMs
        } else {
            null
        }
        profileDao.updateLatency(profile.id, tcpMs, tlsMs, System.currentTimeMillis())
        return tcpMs to tlsMs
    }

    suspend fun saveGroup(group: ProxyGroup): Long {
        val id = groupDao.insert(GroupEntity.fromDomain(group))
        return if (group.id > 0L) group.id else id
    }

    suspend fun deleteGroup(groupId: Long) {
        if (groupId == 1L) return
        profileDao.clearChainReferencesByGroup(groupId)
        profileDao.deleteByGroup(groupId)
        groupDao.deleteById(groupId)
    }

    suspend fun syncSubscription(group: ProxyGroup): Result<Int> {
        if (!group.isSubscription || group.subscriptionUrl.isBlank()) {
            return Result.failure(IllegalArgumentException("Group is not a subscription."))
        }
        val result = NetworkTester.fetchSubscription(
            url = group.subscriptionUrl,
            groupId = group.id,
            userAgent = group.userAgent,
        )
        return result.map { fetched ->
            profileDao.clearChainReferencesByGroup(group.id)
            profileDao.deleteByGroup(group.id)
            val insertedIds = profileDao.insertAll(fetched.profiles.map { ProfileEntity.fromDomain(it) })
            val currentSelectedId = selectedProfileIdFlow.first()
            val selectedStillExists = currentSelectedId > 0L && profileDao.getById(currentSelectedId) != null
            if (!selectedStillExists && insertedIds.isNotEmpty()) {
                settingsStore.setSelectedProfileId(insertedIds.first())
            }
            val urlHost = runCatching { android.net.Uri.parse(group.subscriptionUrl).host.orEmpty() }.getOrDefault("")
            val resolvedName = if (!fetched.profileTitle.isNullOrBlank() &&
                (group.name.isBlank() || group.name == "Subscription" || group.name.equals(urlHost, ignoreCase = true))
            ) {
                fetched.profileTitle
            } else {
                group.name
            }
            groupDao.update(
                GroupEntity.fromDomain(
                    group.copy(
                        name = resolvedName,
                        lastUpdatedAt = System.currentTimeMillis(),
                        uploadBytes = fetched.uploadBytes,
                        downloadBytes = fetched.downloadBytes,
                        totalBytes = fetched.totalBytes,
                        expireTimestampSec = fetched.expireTimestampSec,
                    )
                )
            )
            fetched.profiles.size
        }
    }

    suspend fun saveRoutingRule(rule: RoutingRule) {
        ruleDao.insert(RoutingRuleEntity.fromDomain(rule))
    }

    suspend fun deleteRoutingRule(id: Long) {
        ruleDao.deleteById(id)
    }

    suspend fun setSelectedProfileId(id: Long) = settingsStore.setSelectedProfileId(id)
    suspend fun setSelectedGroupId(id: Long) = settingsStore.setSelectedGroupId(id)
    suspend fun updateSettings(transform: (XraySettings) -> XraySettings) =
        settingsStore.updateSettings(transform)

    fun buildConfigJson(
        profile: ProxyProfile,
        settings: XraySettings,
        rules: List<RoutingRule>,
        allProfiles: List<ProxyProfile> = emptyList(),
    ): String {
        val geo = appContext?.let { com.example.core.xray.XrayAssetHelper.currentStatus(it) }
        return XrayConfigBuilder.buildConfigJson(
            profile = profile,
            settings = settings,
            rules = rules,
            hasGeoIp = geo?.hasGeoIp ?: true,
            hasGeoSite = geo?.hasGeoSite ?: true,
            allProfiles = allProfiles,
        )
    }
}
