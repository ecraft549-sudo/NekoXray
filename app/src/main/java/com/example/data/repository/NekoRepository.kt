package com.example.data.repository

import android.content.Context
import com.example.core.model.ProxyGroup
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.RouteAction
import com.example.core.model.RoutingRule
import com.example.core.model.XraySettings
import com.example.core.network.NetworkTester
import com.example.core.parser.UriParser
import com.example.core.vpn.SshTunnelBridge
import com.example.core.xray.XrayConfigBuilder
import com.example.data.local.GroupEntity
import com.example.data.local.NekoDatabase
import com.example.data.local.ProfileEntity
import com.example.data.local.RoutingRuleEntity
import com.example.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class NekoRepository(
    private val db: NekoDatabase,
    private val settingsStore: SettingsDataStore,
    private val appContext: Context? = null,
) {
    private val profileDao = db.profileDao()
    private val groupDao = db.groupDao()
    private val ruleDao = db.routingRuleDao()

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

    suspend fun testProfileLatency(profile: ProxyProfile): Pair<Int?, Int?> {
        val allProfiles = profileDao.getAllSync().map { it.toDomain() }
        if (profile.chainProxyId > 0L) {
            val chainIssues = XrayConfigBuilder.validateProfile(profile, allProfiles).filter { it.isError }
            if (chainIssues.isNotEmpty()) {
                profileDao.updateLatency(profile.id, null, null, System.currentTimeMillis())
                return null to null
            }
            val ctx = appContext
            val chainDelayMs = if (ctx != null) {
                val geo = com.example.core.xray.XrayAssetHelper.ensureInitialized(ctx)
                val settings = runCatching { settingsFlow.first() }.getOrDefault(XraySettings())
                val rules = runCatching { routingRulesFlow.first() }.getOrDefault(emptyList())
                val configJson = XrayConfigBuilder.buildConfigJson(
                    profile = profile,
                    settings = settings,
                    rules = rules,
                    includeTunInbound = false,
                    hasGeoIp = geo.hasGeoIp,
                    hasGeoSite = geo.hasGeoSite,
                    allProfiles = allProfiles,
                )
                NetworkTester.testXrayOutboundDelay(ctx, configJson)
            } else {
                null
            }
            profileDao.updateLatency(profile.id, null, chainDelayMs, System.currentTimeMillis())
            return null to chainDelayMs
        }

        if (profile.protocol == ProxyProtocol.TUIC) {
            val tuicMs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.example.core.vpn.TuicTunnelBridge.testTuicHandshake(profile)
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
            val sshMs = if (tcpMs != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    SshTunnelBridge.testSshHandshake(profile)
                }
            } else {
                null
            }
            profileDao.updateLatency(profile.id, tcpMs, sshMs, System.currentTimeMillis())
            return tcpMs to sshMs
        }
        val ctx = appContext
        val xrayDelayMs = if (ctx != null) {
            val geo = com.example.core.xray.XrayAssetHelper.ensureInitialized(ctx)
            val settings = runCatching { settingsFlow.first() }.getOrDefault(XraySettings())
            val rules = runCatching { routingRulesFlow.first() }.getOrDefault(emptyList())
            val configJson = XrayConfigBuilder.buildConfigJson(
                profile = profile,
                settings = settings,
                rules = rules,
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
