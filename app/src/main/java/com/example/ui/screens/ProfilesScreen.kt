package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.XRAY_CORE_VERSION
import com.example.core.parser.UriParser
import androidx.compose.material.icons.filled.Warning
import com.example.core.vpn.KotlinOutboundDialer
import com.example.core.vpn.TunnelStatus
import com.example.ui.components.ProxyProfileCard
import com.example.ui.components.QrCodeDialog
import com.example.ui.components.QrScannerDialog
import com.example.ui.components.TagPill
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SignalEmerald
import com.example.ui.viewmodel.NekoUiState
import kotlinx.coroutines.delay

@Composable
fun ProfilesScreen(
    state: NekoUiState,
    onSelectProfile: (Long) -> Unit,
    onSelectGroup: (Long) -> Unit,
    onSearchChange: (String) -> Unit,
    onImportText: (String) -> Unit,
    onCreateProfile: (ProxyProtocol) -> Unit,
    onEditProfile: (Long) -> Unit,
    onPreviewConfig: (Long) -> Unit,
    onTestProfile: (ProxyProfile) -> Unit,
    onTestAll: () -> Unit,
    onDuplicateProfile: (ProxyProfile) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onCopiedNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val strings = LocalAppStrings.current
    var showAddMenu by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    var qrProfile by remember { mutableStateOf<ProxyProfile?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        // Top Cyber Hero / Status Banner
        EngineStatusBanner(
            state = state,
            onOpenImport = { showImportDialog = true },
            onOpenQrScanner = { showQrScanner = true },
            onTestAll = onTestAll,
            showAddMenu = showAddMenu,
            onToggleAddMenu = { showAddMenu = it },
            onCreateProfile = onCreateProfile,
        )

        val activeNeedsNative = state.activeProfile?.let {
            !state.engineState.isNativeCoreLinked && KotlinOutboundDialer(it).requiresNativeXrayCore()
        } == true

        if (activeNeedsNative) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = SignalAmber.copy(alpha = 0.12f),
                contentColor = SignalAmber,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SignalAmber.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
                    .testTag("missing_native_core_banner"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = strings.missingNativeCoreBanner,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Search + Group Chips
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(strings.searchNodesPlaceholder) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = strings.close)
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_profiles_input"),
        )

        Spacer(Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = state.selectedGroupId == 0L,
                    onClick = { onSelectGroup(0L) },
                    label = { Text("${strings.allFilter} (${state.profiles.size})") },
                )
            }
            items(state.groups, key = { it.id }) { group ->
                val count = state.profiles.count { it.groupId == group.id }
                FilterChip(
                    selected = state.selectedGroupId == group.id,
                    onClick = { onSelectGroup(group.id) },
                    label = { Text("${group.name} ($count)") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.visibleProfiles.isEmpty()) {
            EmptyProfilesState(
                onPasteClipboard = {
                    val clipText = clipboard.getText()?.text.orEmpty()
                    if (clipText.isNotBlank()) {
                        onImportText(clipText)
                    } else {
                        showImportDialog = true
                    }
                },
                onScanQr = { showQrScanner = true },
                onCreateReality = { onCreateProfile(ProxyProtocol.VLESS) },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(state.visibleProfiles, key = { it.id }) { profile ->
                    ProxyProfileCard(
                        profile = profile,
                        allProfiles = state.profiles,
                        isSelected = profile.id == state.activeProfile?.id,
                        isTesting = profile.id in state.testingProfileIds,
                        onSelect = { onSelectProfile(profile.id) },
                        onEdit = { onEditProfile(profile.id) },
                        onPreviewConfig = { onPreviewConfig(profile.id) },
                        onShareQr = { qrProfile = profile },
                        onTest = { onTestProfile(profile) },
                        onDuplicate = { onDuplicateProfile(profile) },
                        onDelete = { onDeleteProfile(profile.id) },
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(strings.importDialogTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.importDialogDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = { importInput = it },
                        placeholder = { Text("vless://uuid@host:443?security=reality&...") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_uri_textfield"),
                    )
                    TextButton(
                        onClick = {
                            val clip = clipboard.getText()?.text.orEmpty()
                            if (clip.isNotBlank()) importInput = clip
                        },
                    ) {
                        Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.pasteFromClipboard)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportText(importInput)
                        importInput = ""
                        showImportDialog = false
                    },
                    enabled = importInput.isNotBlank(),
                    modifier = Modifier.testTag("confirm_import_button"),
                ) {
                    Text(strings.importAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(strings.cancel)
                }
            },
        )
    }

    qrProfile?.let { profile ->
        QrCodeDialog(
            title = profile.displayTitle(),
            uri = UriParser.toShareUri(profile),
            onDismiss = { qrProfile = null },
            onCopied = { onCopiedNotice(strings.copiedUriNotice) },
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onQrScanned = { decoded -> onImportText(decoded) },
            onError = { msg -> onCopiedNotice(msg) },
            onDismiss = { showQrScanner = false },
        )
    }
}

@Composable
private fun EngineStatusBanner(
    state: NekoUiState,
    onOpenImport: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onTestAll: () -> Unit,
    showAddMenu: Boolean,
    onToggleAddMenu: (Boolean) -> Unit,
    onCreateProfile: (ProxyProtocol) -> Unit,
) {
    val engine = state.engineState
    val strings = LocalAppStrings.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(engine.status, engine.connectedSinceMs) {
        while (engine.status == TunnelStatus.CONNECTED && engine.connectedSinceMs != null) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val elapsedText = remember(nowMs, engine.connectedSinceMs, engine.status) {
        val since = engine.connectedSinceMs
        if (engine.status == TunnelStatus.CONNECTED && since != null) {
            val sec = ((nowMs - since) / 1000L).coerceAtLeast(0L)
            "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
        } else {
            "00:00:00"
        }
    }

    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        )
    )

    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), MaterialTheme.shapes.large),
    ) {
        Box(
            modifier = Modifier
                .background(gradient)
                .padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_lain),
                    contentDescription = "NekoXray emblem",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "NekoXray",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                        TagPill(
                            text = XRAY_CORE_VERSION,
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    val isFallbackDirect = !engine.isNativeCoreLinked &&
                        state.activeProfile?.let { KotlinOutboundDialer(it).requiresNativeXrayCore() } == true
                    val statusText = when (engine.status) {
                        TunnelStatus.CONNECTED -> if (isFallbackDirect) {
                            "${strings.statusDirectBypass} · $elapsedText"
                        } else {
                            "${strings.statusConnected} · $elapsedText · ${engine.activeProfileName}"
                        }
                        TunnelStatus.CONNECTING -> "${strings.statusConnecting} · ${engine.activeProfileName}"
                        TunnelStatus.ERROR -> "${strings.statusError} · ${engine.lastError ?: "Tunnel error"}"
                        TunnelStatus.IDLE -> state.activeProfile?.let {
                            "${strings.statusReady} · ${it.displayTitle()}"
                        } ?: strings.statusIdleSelect
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            engine.status == TunnelStatus.CONNECTED && isFallbackDirect -> SignalAmber
                            engine.status == TunnelStatus.CONNECTED -> SignalEmerald
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }

                IconButton(
                    onClick = onOpenQrScanner,
                    modifier = Modifier.testTag("scan_qr_button"),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = strings.scanQrCode)
                }

                IconButton(
                    onClick = onTestAll,
                    enabled = !state.isTestingAll && state.visibleProfiles.isNotEmpty(),
                    modifier = Modifier.testTag("test_all_button"),
                ) {
                    if (state.isTestingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Speed, contentDescription = strings.testAllNodes)
                    }
                }

                Box {
                    IconButton(
                        onClick = { onToggleAddMenu(true) },
                        modifier = Modifier.testTag("add_profile_menu_button"),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = strings.add)
                    }
                    DropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = { onToggleAddMenu(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.scanQrCode) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {
                                onToggleAddMenu(false)
                                onOpenQrScanner()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.importUriMenu) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {
                                onToggleAddMenu(false)
                                onOpenImport()
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        val protocolCreateItems = listOf(
                            Triple(strings.newVlessReality, ProxyProtocol.VLESS, Icons.Default.Dns),
                            Triple(strings.newVmess, ProxyProtocol.VMESS, Icons.Default.Dns),
                            Triple(strings.newTrojan, ProxyProtocol.TROJAN, Icons.Default.Dns),
                            Triple(strings.newShadowsocks, ProxyProtocol.SHADOWSOCKS, Icons.Default.Dns),
                            Triple(strings.newHysteria2, ProxyProtocol.HYSTERIA2, Icons.Default.Dns),
                            Triple(strings.newTuic, ProxyProtocol.TUIC, Icons.Default.Dns),
                            Triple(strings.newWireGuard, ProxyProtocol.WIREGUARD, Icons.Default.Dns),
                            Triple(strings.newSocks5, ProxyProtocol.SOCKS5, Icons.Default.Dns),
                            Triple(strings.newHttpProxy, ProxyProtocol.HTTP, Icons.Default.Dns),
                            Triple(strings.newSshTunnel, ProxyProtocol.SSH, Icons.Default.Terminal),
                            Triple(strings.customXrayJson, ProxyProtocol.CUSTOM_JSON, Icons.Default.Code),
                        )
                        protocolCreateItems.forEach { (label, protocol, icon) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    onToggleAddMenu(false)
                                    onCreateProfile(protocol)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyProfilesState(
    onPasteClipboard: () -> Unit,
    onScanQr: () -> Unit,
    onCreateReality: () -> Unit,
) {
    val strings = LocalAppStrings.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            Text(
                text = strings.emptyProfilesTitle,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = strings.emptyProfilesSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onPasteClipboard,
                    modifier = Modifier.testTag("empty_import_button"),
                ) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.importUri)
                }
                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier.testTag("empty_scan_qr_button"),
                ) {
                    Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.scanQrShort)
                }
            }
            Button(
                onClick = onCreateReality,
                modifier = Modifier.testTag("empty_create_reality_button"),
            ) {
                Icon(Icons.Default.Shield, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("VLESS REALITY")
            }
        }
    }
}
