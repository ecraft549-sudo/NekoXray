package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.core.model.XRAY_CORE_VERSION
import com.example.core.xray.XrayConfigBuilder
import com.example.ui.components.TagPill
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalEmerald
import com.example.ui.viewmodel.NekoUiState

@Composable
fun ToolsScreen(
    state: NekoUiState,
    onGenerateKeys: () -> Unit,
    onProbeSni: (String, Int, String) -> Unit,
    onClearLogs: () -> Unit,
    onCopiedNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val clipboard = LocalClipboardManager.current
    var sniHost by remember { mutableStateOf("www.microsoft.com") }
    val keys = state.generatedRealityKeys
    val serverSnippet = remember(keys, sniHost) {
        XrayConfigBuilder.buildRealityServerSnippet(
            uuid = keys.uuid,
            privateKey = keys.privateKey,
            shortId = keys.shortId,
            dest = "${sniHost.ifBlank { "www.microsoft.com" }}:443",
            serverName = sniHost.ifBlank { "www.microsoft.com" },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        // Native Core Bridge Status
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(strings.engineRuntimeTitle, style = MaterialTheme.typography.titleMedium)
                        TagPill(
                            text = if (state.engineState.isNativeCoreLinked) strings.libv2rayLinked else strings.configTunReady,
                            containerColor = if (state.engineState.isNativeCoreLinked) {
                                SignalEmerald.copy(alpha = 0.16f)
                            } else {
                                SignalAmber.copy(alpha = 0.16f)
                            },
                            contentColor = if (state.engineState.isNativeCoreLinked) SignalEmerald else SignalAmber,
                        )
                    }
                    Text(
                        text = "Xray-core $XRAY_CORE_VERSION (VLESS, XTLS-RPRX-Vision, REALITY, XHTTP, VMess, Trojan, SS-2022, WireGuard, SSH)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.engineState.isNativeCoreLinked) {
                        val coreVer = state.engineState.nativeCoreVersion ?: "AndroidLibXrayLite $XRAY_CORE_VERSION"
                        val geoBadge = when {
                            state.engineState.hasGeoIp && state.engineState.hasGeoSite -> "geoip.dat & geosite.dat ready"
                            !state.engineState.hasGeoIp && !state.engineState.hasGeoSite -> "CIDR fallback (.dat missing)"
                            state.engineState.hasGeoIp -> "geoip.dat ready (geosite fallback)"
                            else -> "geosite.dat ready (geoip CIDR fallback)"
                        }
                        Text(
                            text = "$coreVer ($geoBadge)",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.engineState.hasGeoIp && state.engineState.hasGeoSite) SignalEmerald else SignalAmber,
                        )
                    }
                    val netLabel = state.engineState.underlyingNetworkLabel.ifBlank { "Auto (Wi-Fi / Cellular)" }
                    val handoffs = state.engineState.networkHandoffCount
                    val alwaysOnBadge = when {
                        state.engineState.isLockdown -> " • Always-On (Lockdown)"
                        state.engineState.isAlwaysOn -> " • Always-On VPN"
                        else -> ""
                    }
                    Text(
                        text = "Network: $netLabel • Handoffs: $handoffs$alwaysOnBadge",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // REALITY X25519 Keypair Generator
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.realityKeygenTitle, style = MaterialTheme.typography.titleMedium)
                        }
                        Button(
                            onClick = onGenerateKeys,
                            modifier = Modifier.testTag("generate_reality_keys_button"),
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.regenerate)
                        }
                    }

                    KeyValueCopyRow("UUID", keys.uuid) {
                        clipboard.setText(AnnotatedString(keys.uuid))
                        onCopiedNotice("UUID")
                    }
                    KeyValueCopyRow(strings.privateKeyServer, keys.privateKey) {
                        clipboard.setText(AnnotatedString(keys.privateKey))
                        onCopiedNotice("Private key")
                    }
                    KeyValueCopyRow(strings.publicKeyPbk, keys.publicKey) {
                        clipboard.setText(AnnotatedString(keys.publicKey))
                        onCopiedNotice("Public key")
                    }
                    KeyValueCopyRow(strings.shortIdSid, keys.shortId) {
                        clipboard.setText(AnnotatedString(keys.shortId))
                        onCopiedNotice("Short ID")
                    }

                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(serverSnippet))
                            onCopiedNotice("REALITY config.json")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.copyServerRealityJson)
                    }
                }
            }
        }

        // REALITY SNI Probe
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.sniProbeTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = strings.sniProbeDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = sniHost,
                            onValueChange = { sniHost = it },
                            label = { Text(strings.domainPort443) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("probe_sni_input"),
                        )
                        Button(
                            onClick = { onProbeSni(sniHost.trim(), 443, sniHost.trim()) },
                            enabled = !state.isProbingSni && sniHost.isNotBlank(),
                            modifier = Modifier.testTag("probe_sni_button"),
                        ) {
                            if (state.isProbingSni) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(strings.probeBtn)
                        }
                    }

                    state.sniProbeResult?.let { res ->
                        if (res.success) {
                            Text(
                                text = "${res.tlsProtocol} · RTT ${res.latencyMs}ms\n${res.cipherSuite}\n${
                                    if (res.isRealityCompatible) strings.probeGood else strings.probeUnsuitable
                                }",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (res.isRealityCompatible) SignalEmerald else SignalAmber,
                            )
                        } else {
                            Text(
                                text = "${strings.statusError}: ${res.error}",
                                style = MaterialTheme.typography.labelMedium,
                                color = SignalCrimson,
                            )
                        }
                    }
                }
            }
        }

        // Live Engine Logs
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.logsTitle, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onClearLogs) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = strings.clearLogs)
                }
            }
        }

        items(state.engineState.logs.asReversed()) { entry ->
            val color = when (entry.level) {
                "ERROR" -> SignalCrimson
                "WARN" -> SignalAmber
                "CORE" -> SignalEmerald
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = "[${entry.timestamp}] [${entry.level}] ${entry.message}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun KeyValueCopyRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label", modifier = Modifier.size(18.dp))
        }
    }
}
