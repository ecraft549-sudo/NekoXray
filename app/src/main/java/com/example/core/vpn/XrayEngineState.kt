package com.example.core.vpn

import com.example.core.model.XRAY_CORE_VERSION
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TunnelStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class LogEntry(
    val timestamp: String,
    val level: String,
    val message: String,
)

data class EngineRuntimeState(
    val status: TunnelStatus = TunnelStatus.IDLE,
    val activeProfileId: Long? = null,
    val activeProfileName: String = "",
    val activeProtocolLabel: String = "",
    val isRealityActive: Boolean = false,
    val connectedSinceMs: Long? = null,
    val upSpeedBps: Long = 0L,
    val downSpeedBps: Long = 0L,
    val totalUpBytes: Long = 0L,
    val totalDownBytes: Long = 0L,
    val activeConfigJson: String = "",
    val nativeCoreVersion: String? = null,
    val isNativeCoreLinked: Boolean = false,
    val hasGeoIp: Boolean = false,
    val hasGeoSite: Boolean = false,
    val lastError: String? = null,
    val logs: List<LogEntry> = emptyList(),
)

object XrayEngineState {
    private val _state = MutableStateFlow(
        EngineRuntimeState(
            nativeCoreVersion = detectNativeLibV2rayVersion(),
            isNativeCoreLinked = detectNativeLibV2ray(),
        )
    )
    val state: StateFlow<EngineRuntimeState> = _state.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        appendLog(
            "INFO",
            "NekoXray initialized (Schema: Xray-core $XRAY_CORE_VERSION, REALITY + XHTTP + ML-KEM-768 ready). " +
                if (_state.value.isNativeCoreLinked) {
                    val ver = _state.value.nativeCoreVersion ?: XRAY_CORE_VERSION
                    "Native libv2ray linked ($ver)."
                } else {
                    "Built-in Kotlin TUN packet engine active."
                },
        )
    }

    fun updateStatus(
        status: TunnelStatus,
        profileId: Long? = _state.value.activeProfileId,
        profileName: String = _state.value.activeProfileName,
        protocolLabel: String = _state.value.activeProtocolLabel,
        isReality: Boolean = _state.value.isRealityActive,
        configJson: String = _state.value.activeConfigJson,
        error: String? = null,
    ) {
        _state.update { current ->
            val switchedProfile = profileId != null && profileId != current.activeProfileId
            val resetMetrics = status == TunnelStatus.IDLE ||
                status == TunnelStatus.ERROR ||
                (status == TunnelStatus.CONNECTING && switchedProfile)
            current.copy(
                status = status,
                activeProfileId = profileId,
                activeProfileName = profileName,
                activeProtocolLabel = protocolLabel,
                isRealityActive = isReality,
                connectedSinceMs = when (status) {
                    TunnelStatus.CONNECTED -> if (switchedProfile) System.currentTimeMillis() else (current.connectedSinceMs ?: System.currentTimeMillis())
                    TunnelStatus.IDLE, TunnelStatus.ERROR -> null
                    TunnelStatus.CONNECTING -> if (switchedProfile) null else current.connectedSinceMs
                },
                upSpeedBps = if (resetMetrics) 0L else current.upSpeedBps,
                downSpeedBps = if (resetMetrics) 0L else current.downSpeedBps,
                totalUpBytes = if (resetMetrics) 0L else current.totalUpBytes,
                totalDownBytes = if (resetMetrics) 0L else current.totalDownBytes,
                activeConfigJson = configJson,
                lastError = error,
            )
        }
    }

    fun updateTraffic(
        upSpeedBps: Long,
        downSpeedBps: Long,
        totalUpBytes: Long,
        totalDownBytes: Long,
    ) {
        _state.update { current ->
            if (current.status != TunnelStatus.CONNECTED) return@update current
            current.copy(
                upSpeedBps = upSpeedBps,
                downSpeedBps = downSpeedBps,
                totalUpBytes = totalUpBytes,
                totalDownBytes = totalDownBytes,
            )
        }
    }

    fun appendLog(level: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            message = message,
        )
        _state.update { current ->
            val updated = (current.logs + entry).takeLast(200)
            current.copy(logs = updated)
        }
    }

    fun updateGeoAssets(hasGeoIp: Boolean, hasGeoSite: Boolean) {
        _state.update { it.copy(hasGeoIp = hasGeoIp, hasGeoSite = hasGeoSite) }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    private fun detectNativeLibV2ray(): Boolean = runCatching {
        Class.forName("libv2ray.Libv2ray", false, XrayEngineState::class.java.classLoader)
        true
    }.getOrDefault(false)

    private fun detectNativeLibV2rayVersion(): String? = runCatching {
        libv2ray.Libv2ray.checkVersionX()
    }.getOrNull()
}
