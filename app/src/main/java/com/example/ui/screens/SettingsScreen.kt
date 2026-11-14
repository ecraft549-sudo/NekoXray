package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.AppLanguage
import com.example.core.model.IPv6Mode
import com.example.core.model.ServiceMode
import com.example.core.model.XRAY_CORE_VERSION
import com.example.core.model.XraySettings
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.NekoUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: NekoUiState,
    onUpdateSettings: ((XraySettings) -> XraySettings) -> Unit,
    onOpenAppManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state.settings
    val strings = LocalAppStrings.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        item {
            Text(strings.settingsTitle, style = MaterialTheme.typography.titleLarge)
        }

        // Interface Language Selector
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.languageTitle, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = strings.languageSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = s.appLanguage == lang,
                                onClick = { onUpdateSettings { it.copy(appLanguage = lang) } },
                                label = { Text(strings.languageOptionName(lang), maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("language_chip_${lang.name}"),
                            )
                        }
                    }
                }
            }
        }

        // Service Mode & Appearance
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.serviceModeTitle, style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ServiceMode.entries.forEach { mode ->
                            FilterChip(
                                selected = s.serviceMode == mode,
                                onClick = { onUpdateSettings { it.copy(serviceMode = mode) } },
                                label = { Text(strings.serviceModeName(mode), maxLines = 1, softWrap = false) },
                            )
                        }
                    }

                    SettingToggle(
                        title = strings.darkThemeTitle,
                        subtitle = strings.darkThemeSubtitle,
                        checked = s.darkTheme,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(darkTheme = c) } },
                    )
                }
            }
        }

        // Per-App Proxy
        item {
            Card(
                onClick = onOpenAppManager,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_open_app_manager"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.perAppSettingsTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${strings.perAppModeName(s.perAppMode)} · ${s.perAppPackages.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }

        // Inbound Ports & TUN
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.inboundsTunTitle, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = s.socksPort.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { p -> onUpdateSettings { it.copy(socksPort = p) } } },
                            label = { Text(strings.socks5Port) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = s.httpPort.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { p -> onUpdateSettings { it.copy(httpPort = p) } } },
                            label = { Text(strings.httpPort) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = s.mtu.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { m -> onUpdateSettings { it.copy(mtu = m) } } },
                            label = { Text("MTU") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Text(strings.ipv6StrategyTitle, style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IPv6Mode.entries.forEach { mode ->
                            FilterChip(
                                selected = s.ipv6Mode == mode,
                                onClick = { onUpdateSettings { it.copy(ipv6Mode = mode) } },
                                label = { Text(strings.ipv6ModeName(mode), maxLines = 1, softWrap = false) },
                            )
                        }
                    }
                }
            }
        }

        // DNS & Sniffing
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.dnsSniffingTitle, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = s.remoteDns,
                        onValueChange = { v -> onUpdateSettings { it.copy(remoteDns = v) } },
                        label = { Text(strings.remoteDnsLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.directDns,
                        onValueChange = { v -> onUpdateSettings { it.copy(directDns = v) } },
                        label = { Text(strings.directDnsLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SettingToggle(
                        title = strings.bypassLanTitle,
                        subtitle = strings.bypassLanSubtitle,
                        checked = s.bypassLan,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(bypassLan = c) } },
                    )
                    SettingToggle(
                        title = strings.sniffingTitle,
                        subtitle = strings.sniffingSubtitle,
                        checked = s.sniffingEnabled,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(sniffingEnabled = c) } },
                    )
                    SettingToggle(
                        title = strings.routeOnlyTitle,
                        subtitle = strings.routeOnlySubtitle,
                        checked = s.routeOnly,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(routeOnly = c) } },
                    )
                }
            }
        }

        // Anti-DPI TLS Fragment & Security
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.fragmentSectionTitle, style = MaterialTheme.typography.titleMedium)
                    SettingToggle(
                        title = strings.enableFragmentTitle,
                        subtitle = strings.enableFragmentSubtitle,
                        checked = s.fragmentEnabled,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(fragmentEnabled = c) } },
                    )
                    if (s.fragmentEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = s.fragmentLength,
                                onValueChange = { v -> onUpdateSettings { it.copy(fragmentLength = v) } },
                                label = { Text(strings.lengthLabel) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = s.fragmentInterval,
                                onValueChange = { v -> onUpdateSettings { it.copy(fragmentInterval = v) } },
                                label = { Text(strings.intervalLabel) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    SettingToggle(
                        title = strings.allowInsecureGlobalTitle,
                        subtitle = strings.allowInsecureGlobalSubtitle,
                        checked = s.globalAllowInsecure,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(globalAllowInsecure = c) } },
                    )
                }
            }
        }

        // About Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_lain),
                        contentDescription = "NekoXray icon",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("NekoXray · Xray-core $XRAY_CORE_VERSION", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = strings.aboutDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
