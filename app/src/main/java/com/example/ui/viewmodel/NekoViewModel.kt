package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NekoXrayApp
import com.example.core.crypto.RealityKeyGenerator
import com.example.core.crypto.RealityKeyPair
import com.example.core.model.ConfigValidationIssue
import com.example.core.model.ProxyGroup
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.RoutingRule
import com.example.core.model.ServiceMode
import com.example.core.model.XraySettings
import com.example.core.network.NetworkTester
import com.example.core.network.TlsProbeResult
import com.example.core.parser.UriParser
import com.example.core.vpn.EngineRuntimeState
import com.example.core.vpn.TunnelStatus
import com.example.core.vpn.XrayEngineState
import com.example.core.vpn.XrayVpnService
import com.example.core.widget.NekoWidgetManager
import com.example.core.xray.XrayConfigBuilder
import com.example.ui.i18n.resolveAppStrings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NekoUiState(
    val profiles: List<ProxyProfile> = emptyList(),
    val groups: List<ProxyGroup> = emptyList(),
    val routingRules: List<RoutingRule> = emptyList(),
    val settings: XraySettings = XraySettings(),
    val selectedProfileId: Long = 0L,
    val selectedGroupId: Long = 0L,
    val searchQuery: String = "",
    val engineState: EngineRuntimeState = EngineRuntimeState(),
    val testingProfileIds: Set<Long> = emptySet(),
    val isTestingAll: Boolean = false,
    val syncingGroupIds: Set<Long> = emptySet(),
    val generatedRealityKeys: RealityKeyPair = RealityKeyGenerator.generateBundle(),
    val sniProbeResult: TlsProbeResult? = null,
    val isProbingSni: Boolean = false,
) {
    val visibleProfiles: List<ProxyProfile>
        get() = profiles.filter { p ->
            val groupOk = selectedGroupId <= 0L || p.groupId == selectedGroupId
            val queryOk = searchQuery.isBlank() ||
                p.name.contains(searchQuery, ignoreCase = true) ||
                p.serverAddress.contains(searchQuery, ignoreCase = true) ||
                p.sni.contains(searchQuery, ignoreCase = true) ||
                p.protocol.displayName.contains(searchQuery, ignoreCase = true)
            groupOk && queryOk
        }

    val activeProfile: ProxyProfile?
        get() = profiles.find { it.id == selectedProfileId } ?: profiles.firstOrNull()
}

private data class TransientState(
    val searchQuery: String = "",
    val testingProfileIds: Set<Long> = emptySet(),
    val isTestingAll: Boolean = false,
    val syncingGroupIds: Set<Long> = emptySet(),
    val generatedRealityKeys: RealityKeyPair = RealityKeyGenerator.generateBundle(),
    val sniProbeResult: TlsProbeResult? = null,
    val isProbingSni: Boolean = false,
)

class NekoViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as NekoXrayApp).repository
    private val transient = MutableStateFlow(TransientState())

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    val uiState: StateFlow<NekoUiState> = combine(
        repo.profilesFlow,
        repo.groupsFlow,
        repo.routingRulesFlow,
        repo.settingsFlow,
        repo.selectedProfileIdFlow,
        repo.selectedGroupIdFlow,
        XrayEngineState.state,
        transient,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val profiles = values[0] as List<ProxyProfile>
        @Suppress("UNCHECKED_CAST")
        val groups = values[1] as List<ProxyGroup>
        @Suppress("UNCHECKED_CAST")
        val rules = values[2] as List<RoutingRule>
        val settings = values[3] as XraySettings
        val selectedProfileId = values[4] as Long
        val selectedGroupId = values[5] as Long
        val engine = values[6] as EngineRuntimeState
        val tr = values[7] as TransientState

        NekoUiState(
            profiles = profiles,
            groups = groups,
            routingRules = rules,
            settings = settings,
            selectedProfileId = selectedProfileId,
            selectedGroupId = selectedGroupId,
            searchQuery = tr.searchQuery,
            engineState = engine,
            testingProfileIds = tr.testingProfileIds,
            isTestingAll = tr.isTestingAll,
            syncingGroupIds = tr.syncingGroupIds,
            generatedRealityKeys = tr.generatedRealityKeys,
            sniProbeResult = tr.sniProbeResult,
            isProbingSni = tr.isProbingSni,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = NekoUiState(),
    )

    fun selectProfile(id: Long) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val prevSelectedId = uiState.value.selectedProfileId
            repo.setSelectedProfileId(id)

            val runtime = XrayEngineState.state.value
            val isRunning = runtime.status == TunnelStatus.CONNECTED ||
                runtime.status == TunnelStatus.CONNECTING

            if (isRunning && runtime.activeProfileId != id) {
                val allProfiles = repo.profilesFlow.first()
                val target = repo.getProfile(id) ?: allProfiles.find { it.id == id }
                if (target != null) {
                    val issues = XrayConfigBuilder.validateProfile(target, allProfiles).filter { it.isError }
                    if (issues.isNotEmpty()) {
                        repo.setSelectedProfileId(prevSelectedId)
                        _events.emit(issues.first().message)
                        NekoWidgetManager.refreshAll(app)
                        return@launch
                    }
                    val latestSettings = repo.settingsFlow.first()
                    val latestRules = repo.routingRulesFlow.first()
                    startTunnelWith(
                        profile = target,
                        settings = latestSettings,
                        rules = latestRules,
                        allProfiles = allProfiles,
                    )
                    val strings = resolveAppStrings(latestSettings.appLanguage)
                    _events.emit("${strings.switchedProfilePrefix} '${target.displayTitle()}'.")
                }
            }
            NekoWidgetManager.refreshAll(app)
        }
    }

    fun selectGroup(id: Long) {
        viewModelScope.launch { repo.setSelectedGroupId(id) }
    }

    fun updateSearchQuery(query: String) {
        transient.update { it.copy(searchQuery = query) }
    }

    fun importText(raw: String) {
        viewModelScope.launch {
            val subscriptions = UriParser.parseSubscriptions(raw)
            var createdOrSyncedGroups = 0
            var syncedNodes = 0
            var lastSyncError: String? = null

            if (subscriptions.isNotEmpty()) {
                val existingGroups = uiState.value.groups
                for (sub in subscriptions) {
                    val existing = existingGroups.find {
                        it.isSubscription && it.subscriptionUrl.equals(sub.url, ignoreCase = true)
                    }
                    val targetSubGroup = if (existing != null) {
                        existing
                    } else {
                        val newGroup = ProxyGroup(
                            name = sub.name,
                            isSubscription = true,
                            subscriptionUrl = sub.url,
                        )
                        val newId = repo.saveGroup(newGroup)
                        newGroup.copy(id = newId)
                    }

                    repo.setSelectedGroupId(targetSubGroup.id)
                    transient.update { it.copy(syncingGroupIds = it.syncingGroupIds + targetSubGroup.id) }
                    val res = repo.syncSubscription(targetSubGroup)
                    transient.update { it.copy(syncingGroupIds = it.syncingGroupIds - targetSubGroup.id) }

                    createdOrSyncedGroups++
                    res.onSuccess { n ->
                        syncedNodes += n
                        XrayEngineState.appendLog("INFO", "Subscription '${targetSubGroup.name}' imported ($n nodes).")
                    }.onFailure { err ->
                        lastSyncError = err.message
                        XrayEngineState.appendLog("ERROR", "Subscription '${targetSubGroup.name}' sync failed: ${err.message}")
                    }
                }
            }

            val targetGroup = uiState.value.selectedGroupId.takeIf { it > 0L } ?: 1L
            val profileCount = repo.importFromText(raw, targetGroup)
            if (profileCount > 0) {
                XrayEngineState.appendLog("INFO", "Imported $profileCount profile(s) from URI/JSON.")
            }

            if (createdOrSyncedGroups > 0 || profileCount > 0) {
                NekoWidgetManager.refreshAll(getApplication())
            }

            when {
                createdOrSyncedGroups > 0 && lastSyncError == null -> {
                    _events.emit("Subscription group ready: $syncedNodes profile(s) synced.")
                }
                createdOrSyncedGroups > 0 && lastSyncError != null -> {
                    _events.emit("Saved subscription group, but sync failed: $lastSyncError")
                }
                profileCount > 0 -> {
                    _events.emit("Imported $profileCount profile(s) into group.")
                }
                else -> {
                    _events.emit("No valid subscription URL or vless://, vmess://, trojan://, hysteria2://, tuic://, ss://, socks://, ssh://, wg:// or Xray JSON found.")
                }
            }
        }
    }

    fun saveProfile(profile: ProxyProfile, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repo.saveProfile(profile)
            if (uiState.value.selectedProfileId == 0L || profile.id == 0L) {
                repo.setSelectedProfileId(id)
            } else {
                val runtime = XrayEngineState.state.value
                val isRunning = runtime.status == TunnelStatus.CONNECTED ||
                    runtime.status == TunnelStatus.CONNECTING
                if (isRunning) {
                    val latestProfiles = repo.profilesFlow.first()
                    val activeProfile = latestProfiles.find { it.id == runtime.activeProfileId }
                    val inActiveChain = activeProfile != null &&
                        XrayConfigBuilder.resolveChain(activeProfile, latestProfiles).hops.any { it.id == id }
                    if (runtime.activeProfileId == id || inActiveChain) {
                        reloadTunnelIfActive()
                    }
                }
            }
            NekoWidgetManager.refreshAll(getApplication())
            _events.emit("Saved '${profile.displayTitle()}'.")
            onSaved(id)
        }
    }

    fun duplicateProfile(profile: ProxyProfile) {
        viewModelScope.launch {
            repo.duplicateProfile(profile)
            NekoWidgetManager.refreshAll(getApplication())
            _events.emit("Duplicated '${profile.displayTitle()}'.")
        }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            repo.deleteProfile(id)
            NekoWidgetManager.refreshAll(getApplication())
            _events.emit("Profile deleted.")
        }
    }

    fun testProfile(profile: ProxyProfile) {
        viewModelScope.launch {
            transient.update { it.copy(testingProfileIds = it.testingProfileIds + profile.id) }
            val (tcp, tls) = repo.testProfileLatency(profile)
            transient.update { it.copy(testingProfileIds = it.testingProfileIds - profile.id) }
            val msg = if (tcp != null || tls != null) {
                if (profile.chainProxyId > 0L && tcp == null) {
                    "${profile.displayTitle()}: Chain ${tls}ms"
                } else {
                    val tcpInfo = if (tcp != null) "TCP ${tcp}ms" else "QUIC"
                    val tlsInfo = if (tls != null) " · Xray ${tls}ms" else ""
                    "${profile.displayTitle()}: $tcpInfo$tlsInfo"
                }
            } else {
                "${profile.displayTitle()}: Unreachable / Timeout"
            }
            _events.emit(msg)
        }
    }

    fun testAllVisibleProfiles() {
        val targets = uiState.value.visibleProfiles
        if (targets.isEmpty()) return
        viewModelScope.launch {
            transient.update {
                it.copy(
                    isTestingAll = true,
                    testingProfileIds = it.testingProfileIds + targets.map { p -> p.id },
                )
            }
            targets.map { p ->
                async { repo.testProfileLatency(p) }
            }.awaitAll()
            transient.update {
                it.copy(
                    isTestingAll = false,
                    testingProfileIds = emptySet(),
                )
            }
            _events.emit("Finished latency check for ${targets.size} profile(s).")
        }
    }

    fun saveGroup(group: ProxyGroup) {
        viewModelScope.launch {
            val id = repo.saveGroup(group)
            if (group.isSubscription && group.subscriptionUrl.isNotBlank() && group.id == 0L) {
                syncSubscription(group.copy(id = id))
            } else {
                _events.emit("Saved group '${group.name}'.")
            }
        }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launch {
            repo.deleteGroup(id)
            if (uiState.value.selectedGroupId == id) {
                repo.setSelectedGroupId(0L)
            }
            _events.emit("Group deleted.")
        }
    }

    fun syncSubscription(group: ProxyGroup) {
        viewModelScope.launch {
            transient.update { it.copy(syncingGroupIds = it.syncingGroupIds + group.id) }
            val res = repo.syncSubscription(group)
            transient.update { it.copy(syncingGroupIds = it.syncingGroupIds - group.id) }
            res.onSuccess { count ->
                _events.emit("Updated '${group.name}': $count profile(s).")
                XrayEngineState.appendLog("INFO", "Subscription '${group.name}' synced ($count nodes).")
            }.onFailure { err ->
                _events.emit("Subscription error: ${err.message}")
                XrayEngineState.appendLog("ERROR", "Subscription '${group.name}' failed: ${err.message}")
            }
        }
    }

    fun saveRoutingRule(rule: RoutingRule) {
        viewModelScope.launch {
            repo.saveRoutingRule(rule)
            val reloaded = reloadTunnelIfActive()
            _events.emit(
                if (reloaded) "Saved '${rule.name}' — routing reapplied."
                else "Saved routing rule '${rule.name}'."
            )
        }
    }

    fun toggleRoutingRule(rule: RoutingRule) {
        viewModelScope.launch {
            val updated = rule.copy(enabled = !rule.enabled)
            repo.saveRoutingRule(updated)
            val reloaded = reloadTunnelIfActive()
            if (reloaded) {
                _events.emit(
                    "Routing '${updated.name}' ${if (updated.enabled) "ON" else "OFF"} — tunnel reloaded."
                )
            }
        }
    }

    fun deleteRoutingRule(id: Long) {
        viewModelScope.launch {
            repo.deleteRoutingRule(id)
            val reloaded = reloadTunnelIfActive()
            if (reloaded) {
                _events.emit("Rule deleted — routing reapplied.")
            }
        }
    }

    fun updateSettings(transform: (XraySettings) -> XraySettings) {
        viewModelScope.launch {
            val prev = repo.settingsFlow.first()
            repo.updateSettings(transform)
            val next = repo.settingsFlow.first()
            val prevTunnelRelevant = prev.copy(
                darkTheme = next.darkTheme,
                appLanguage = next.appLanguage,
                trayWidgetEnabled = next.trayWidgetEnabled,
            )
            if (prevTunnelRelevant != next) {
                reloadTunnelIfActive()
            }
            NekoWidgetManager.refreshAll(getApplication())
        }
    }

    private suspend fun reloadTunnelIfActive(): Boolean {
        val status = XrayEngineState.state.value.status
        if (status != TunnelStatus.CONNECTED && status != TunnelStatus.CONNECTING) {
            return false
        }
        val latestProfiles = repo.profilesFlow.first()
        val activeId = XrayEngineState.state.value.activeProfileId
        val profile = latestProfiles.find { it.id == activeId }
            ?: uiState.value.activeProfile
            ?: return false
        val latestSettings = repo.settingsFlow.first()
        val latestRules = repo.routingRulesFlow.first()
        startTunnelWith(
            profile = profile,
            settings = latestSettings,
            rules = latestRules,
            allProfiles = latestProfiles,
        )
        return true
    }

    fun generateRealityKeys() {
        val bundle = RealityKeyGenerator.generateBundle()
        transient.update { it.copy(generatedRealityKeys = bundle) }
    }

    fun probeSniTarget(host: String, port: Int = 443, sni: String = host) {
        viewModelScope.launch {
            transient.update { it.copy(isProbingSni = true, sniProbeResult = null) }
            val result = NetworkTester.probeTlsSni(host, port, sni)
            transient.update { it.copy(isProbingSni = false, sniProbeResult = result) }
        }
    }

    suspend fun loadProfile(id: Long): ProxyProfile? = repo.getProfile(id)

    fun buildConfigJson(
        profile: ProxyProfile,
        includeTunInbound: Boolean = uiState.value.settings.serviceMode == ServiceMode.VPN,
    ): String {
        val geo = com.example.core.xray.XrayAssetHelper.currentStatus(getApplication())
        return XrayConfigBuilder.buildConfigJson(
            profile = profile,
            settings = uiState.value.settings,
            rules = uiState.value.routingRules,
            includeTunInbound = includeTunInbound,
            hasGeoIp = geo.hasGeoIp,
            hasGeoSite = geo.hasGeoSite,
            allProfiles = uiState.value.profiles,
        )
    }

    fun validateProfile(profile: ProxyProfile): List<ConfigValidationIssue> =
        XrayConfigBuilder.validateProfile(profile, uiState.value.profiles)

    fun toggleConnection(
        onRequestVpnPermission: (Intent) -> Unit,
    ) {
        val current = uiState.value
        val app = getApplication<Application>()
        if (current.engineState.status == TunnelStatus.CONNECTED ||
            current.engineState.status == TunnelStatus.CONNECTING
        ) {
            XrayEngineState.updateStatus(status = TunnelStatus.IDLE)
            XrayVpnService.requestImmediateStop()
            val sent = runCatching { app.startService(XrayVpnService.buildStopIntent(app)) }.isSuccess
            if (!sent) {
                runCatching { app.stopService(Intent(app, XrayVpnService::class.java)) }
            }
            NekoWidgetManager.refreshAll(app)
            return
        }

        val profile = current.activeProfile
        if (profile == null) {
            viewModelScope.launch { _events.emit("Create or import an Xray profile first.") }
            return
        }

        val issues = XrayConfigBuilder.validateProfile(profile, current.profiles).filter { it.isError }
        if (issues.isNotEmpty()) {
            viewModelScope.launch { _events.emit(issues.first().message) }
            return
        }

        if (current.settings.serviceMode == ServiceMode.VPN) {
            val prepareIntent = VpnService.prepare(app)
            if (prepareIntent != null) {
                onRequestVpnPermission(prepareIntent)
                return
            }
        }
        startTunnelConfirmed()
    }

    fun startTunnelConfirmed(forceProxyOnly: Boolean = false) {
        val current = uiState.value
        val profile = current.activeProfile ?: return
        startTunnelWith(
            profile = profile,
            settings = current.settings,
            rules = current.routingRules,
            forceProxyOnly = forceProxyOnly,
            allProfiles = current.profiles,
        )
    }

    private fun startTunnelWith(
        profile: ProxyProfile,
        settings: XraySettings,
        rules: List<RoutingRule>,
        forceProxyOnly: Boolean = false,
        allProfiles: List<ProxyProfile> = uiState.value.profiles,
    ) {
        val app = getApplication<Application>()
        val intent = NekoWidgetManager.buildStartServiceIntent(
            context = app,
            profile = profile,
            settings = settings,
            rules = rules,
            forceProxyOnly = forceProxyOnly,
            allProfiles = allProfiles,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun togglePerAppPackage(packageName: String) {
        updateSettings { s ->
            val next = if (packageName in s.perAppPackages) {
                s.perAppPackages - packageName
            } else {
                s.perAppPackages + packageName
            }
            s.copy(perAppPackages = next)
        }
    }

    fun clearLogs() = XrayEngineState.clearLogs()
}
