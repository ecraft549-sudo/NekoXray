package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.crypto.RealityKeyGenerator
import com.example.core.model.FlowType
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.SecurityType
import com.example.core.model.SshAuthType
import com.example.core.model.TransportType
import com.example.core.model.UtlsFingerprint
import com.example.core.network.NetworkTester
import com.example.core.network.TlsProbeResult
import com.example.core.xray.XrayConfigBuilder
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalEmerald
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    profileId: Long,
    initialProtocol: String,
    defaultGroupId: Long,
    allProfiles: List<ProxyProfile> = emptyList(),
    loadProfile: suspend (Long) -> ProxyProfile?,
    onSave: (ProxyProfile) -> Unit,
    onPreviewConfig: (ProxyProfile) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val strings = LocalAppStrings.current
    val startProto = runCatching { ProxyProtocol.valueOf(initialProtocol) }.getOrDefault(ProxyProtocol.VLESS)

    var profile by remember {
        mutableStateOf(
            ProxyProfile(
                groupId = if (defaultGroupId > 0L) defaultGroupId else 1L,
                protocol = startProto,
                serverPort = when (startProto) {
                    ProxyProtocol.SSH -> 22
                    ProxyProtocol.SOCKS5 -> 1080
                    ProxyProtocol.HTTP -> 8080
                    else -> 443
                },
                username = if (startProto == ProxyProtocol.SSH) "root" else "",
                uuid = if (startProto == ProxyProtocol.TUIC && profileId == 0L) RealityKeyGenerator.generateUuid() else "",
                security = when (startProto) {
                    ProxyProtocol.VLESS -> SecurityType.REALITY
                    ProxyProtocol.TROJAN, ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC -> SecurityType.TLS
                    else -> SecurityType.NONE
                },
                flow = if (startProto == ProxyProtocol.VLESS) FlowType.VISION else FlowType.NONE,
                sni = if (startProto == ProxyProtocol.VLESS) "www.microsoft.com" else "",
                alpn = if (startProto == ProxyProtocol.HYSTERIA2 || startProto == ProxyProtocol.TUIC) "h3" else "",
            )
        )
    }
    var loaded by remember { mutableStateOf(profileId == 0L) }
    var sniCheckResult by remember { mutableStateOf<TlsProbeResult?>(null) }
    var isCheckingSni by remember { mutableStateOf(false) }
    var isLeavingScreen by remember { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        if (profileId > 0L) {
            loadProfile(profileId)?.let { profile = it }
            loaded = true
        }
    }

    val issues = remember(profile, allProfiles) { XrayConfigBuilder.validateProfile(profile, allProfiles) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profileId == 0L) strings.newProfileTitle else strings.editProfileTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isLeavingScreen) {
                                isLeavingScreen = true
                                onBack()
                            }
                        },
                        enabled = !isLeavingScreen,
                        modifier = Modifier.testTag("editor_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!isLeavingScreen) {
                                onPreviewConfig(profile)
                            }
                        },
                        enabled = !isLeavingScreen,
                        modifier = Modifier.testTag("editor_preview_json_button"),
                    ) {
                        Icon(Icons.Default.Code, contentDescription = strings.previewXrayJson)
                    }
                    IconButton(
                        onClick = {
                            if (!isLeavingScreen) {
                                isLeavingScreen = true
                                onSave(profile)
                            }
                        },
                        enabled = !isLeavingScreen,
                        modifier = Modifier.testTag("editor_save_button"),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = strings.save)
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!loaded) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Protocol selector
            EnumDropdown(
                label = strings.protocolLabel,
                selected = profile.protocol.displayName,
                options = ProxyProtocol.entries.map { it to it.displayName },
                onSelect = { proto ->
                    val defaultPorts = setOf(22, 443, 1080, 8080)
                    val nextPort = if (profile.serverPort in defaultPorts) {
                        when (proto) {
                            ProxyProtocol.SSH -> 22
                            ProxyProtocol.SOCKS5 -> 1080
                            ProxyProtocol.HTTP -> 8080
                            else -> 443
                        }
                    } else {
                        profile.serverPort
                    }
                    profile = profile.copy(
                        protocol = proto,
                        serverPort = nextPort,
                        username = if (proto == ProxyProtocol.SSH && profile.username.isBlank()) "root" else profile.username,
                        uuid = if ((proto == ProxyProtocol.TUIC || proto == ProxyProtocol.VLESS || proto == ProxyProtocol.VMESS) && profile.uuid.isBlank()) {
                            RealityKeyGenerator.generateUuid()
                        } else {
                            profile.uuid
                        },
                        security = when (proto) {
                            ProxyProtocol.VLESS -> if (profile.security == SecurityType.NONE) SecurityType.REALITY else profile.security
                            ProxyProtocol.TROJAN -> if (profile.security == SecurityType.NONE) SecurityType.TLS else profile.security
                            ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC -> SecurityType.TLS
                            ProxyProtocol.SHADOWSOCKS, ProxyProtocol.SOCKS5, ProxyProtocol.SSH, ProxyProtocol.WIREGUARD, ProxyProtocol.CUSTOM_JSON -> SecurityType.NONE
                            else -> profile.security
                        },
                        alpn = if ((proto == ProxyProtocol.HYSTERIA2 || proto == ProxyProtocol.TUIC) && profile.alpn.isBlank()) {
                            "h3"
                        } else {
                            profile.alpn
                        },
                        flow = if (proto == ProxyProtocol.VLESS) profile.flow else FlowType.NONE,
                        chainProxyId = if (proto in listOf(ProxyProtocol.SSH, ProxyProtocol.TUIC, ProxyProtocol.CUSTOM_JSON)) 0L else profile.chainProxyId,
                        ipv6OnlyProxy = if (proto == ProxyProtocol.CUSTOM_JSON) false else profile.ipv6OnlyProxy,
                    )
                },
            )

            OutlinedTextField(
                value = profile.name,
                onValueChange = { profile = profile.copy(name = it) },
                label = { Text(strings.profileNameLabel) },
                placeholder = { Text("e.g. Frankfurt REALITY") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_name_input"),
            )

            if (profile.protocol == ProxyProtocol.CUSTOM_JSON) {
                OutlinedTextField(
                    value = profile.customConfigJson,
                    onValueChange = { profile = profile.copy(customConfigJson = it) },
                    label = { Text(strings.customXrayJson) },
                    minLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = profile.serverAddress,
                        onValueChange = { profile = profile.copy(serverAddress = it) },
                        label = { Text(strings.serverAddressLabel) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(2f)
                            .testTag("editor_address_input"),
                    )
                    OutlinedTextField(
                        value = profile.serverPort.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { profile = profile.copy(serverPort = it) }
                        },
                        label = { Text(strings.portLabel) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("editor_port_input"),
                    )
                }

                // Protocol-specific authentication
                when (profile.protocol) {
                    ProxyProtocol.VLESS, ProxyProtocol.VMESS -> {
                        OutlinedTextField(
                            value = profile.uuid,
                            onValueChange = { profile = profile.copy(uuid = it.trim()) },
                            label = { Text("UUID") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        profile = profile.copy(uuid = RealityKeyGenerator.generateUuid())
                                    },
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Generate UUID")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_uuid_input"),
                        )
                        if (profile.protocol == ProxyProtocol.VLESS) {
                            EnumDropdown(
                                label = "XTLS Flow",
                                selected = profile.flow.displayName,
                                options = FlowType.entries.map { it to it.displayName },
                                onSelect = { profile = profile.copy(flow = it) },
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = profile.alterId.toString(),
                                    onValueChange = { v ->
                                        v.toIntOrNull()?.let { profile = profile.copy(alterId = it) }
                                    },
                                    label = { Text("AlterID") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = profile.encryption,
                                    onValueChange = { profile = profile.copy(encryption = it) },
                                    label = { Text("Security Cipher") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    ProxyProtocol.TROJAN -> {
                        OutlinedTextField(
                            value = profile.password,
                            onValueChange = { profile = profile.copy(password = it) },
                            label = { Text("Trojan Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    ProxyProtocol.SHADOWSOCKS -> {
                        OutlinedTextField(
                            value = profile.encryption,
                            onValueChange = { profile = profile.copy(encryption = it) },
                            label = { Text("Method (e.g. 2022-blake3-aes-128-gcm)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = profile.password,
                            onValueChange = { profile = profile.copy(password = it) },
                            label = { Text("Password / Base64 PSK") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    ProxyProtocol.SOCKS5, ProxyProtocol.HTTP -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = profile.username,
                                onValueChange = { profile = profile.copy(username = it) },
                                label = { Text(strings.usernameLabel) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = profile.password,
                                onValueChange = { profile = profile.copy(password = it) },
                                label = { Text(strings.passwordLabel) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    ProxyProtocol.WIREGUARD -> {
                        OutlinedTextField(
                            value = profile.wgPrivateKey,
                            onValueChange = { profile = profile.copy(wgPrivateKey = it.trim()) },
                            label = { Text(strings.privateKeyLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = profile.wgPeerPublicKey,
                            onValueChange = { profile = profile.copy(wgPeerPublicKey = it.trim()) },
                            label = { Text("Peer Public Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = profile.wgLocalAddress,
                                onValueChange = { profile = profile.copy(wgLocalAddress = it) },
                                label = { Text("Local Address CIDR") },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                            )
                            OutlinedTextField(
                                value = profile.wgMtu.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { profile = profile.copy(wgMtu = it) } },
                                label = { Text("MTU") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    ProxyProtocol.SSH -> {
                        OutlinedTextField(
                            value = profile.username,
                            onValueChange = { profile = profile.copy(username = it.trim()) },
                            label = { Text(strings.usernameLabel) },
                            placeholder = { Text("root") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_ssh_username_input"),
                        )

                        EnumDropdown(
                            label = strings.authTypeLabel,
                            selected = profile.sshAuthType.displayName,
                            options = SshAuthType.entries.map { it to it.displayName },
                            onSelect = { profile = profile.copy(sshAuthType = it) },
                        )

                        if (profile.sshAuthType == SshAuthType.PASSWORD) {
                            OutlinedTextField(
                                value = profile.password,
                                onValueChange = { profile = profile.copy(password = it) },
                                label = { Text(strings.passwordLabel) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("editor_ssh_password_input"),
                            )
                        }

                        if (profile.sshAuthType == SshAuthType.PRIVATE_KEY) {
                            OutlinedTextField(
                                value = profile.sshPrivateKey,
                                onValueChange = { profile = profile.copy(sshPrivateKey = it) },
                                label = { Text(strings.privateKeyLabel) },
                                placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("editor_ssh_private_key_input"),
                            )
                            OutlinedTextField(
                                value = profile.sshPrivateKeyPassphrase,
                                onValueChange = { profile = profile.copy(sshPrivateKeyPassphrase = it) },
                                label = { Text(strings.passphraseOptionalLabel) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("editor_ssh_passphrase_input"),
                            )
                        }

                        OutlinedTextField(
                            value = profile.sshPublicKey,
                            onValueChange = { profile = profile.copy(sshPublicKey = it) },
                            label = { Text(strings.publicKeyOptionalLabel) },
                            placeholder = { Text("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA...") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_ssh_public_key_input"),
                        )
                    }

                    ProxyProtocol.HYSTERIA2 -> {
                        OutlinedTextField(
                            value = profile.password,
                            onValueChange = { profile = profile.copy(password = it) },
                            label = { Text(strings.hy2AuthLabel) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_hy2_password_input"),
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(strings.hy2SettingsTitle, style = MaterialTheme.typography.titleMedium)

                                OutlinedTextField(
                                    value = profile.sni,
                                    onValueChange = { profile = profile.copy(sni = it.trim()) },
                                    label = { Text("Server Name (SNI)") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("editor_hy2_sni_input"),
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = profile.alpn,
                                        onValueChange = { profile = profile.copy(alpn = it) },
                                        label = { Text("ALPN (h3)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = profile.fingerprint.ifBlank { "chrome" },
                                        onValueChange = { profile = profile.copy(fingerprint = it.trim()) },
                                        label = { Text("uTLS (fp)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                EnumDropdown(
                                    label = strings.hy2ObfsLabel,
                                    selected = profile.hy2Obfs.ifBlank { "none" },
                                    options = listOf(
                                        "" to "none",
                                        "salamander" to "salamander",
                                    ),
                                    onSelect = { profile = profile.copy(hy2Obfs = it) },
                                )

                                if (profile.hy2Obfs.equals("salamander", ignoreCase = true)) {
                                    OutlinedTextField(
                                        value = profile.hy2ObfsPassword,
                                        onValueChange = { profile = profile.copy(hy2ObfsPassword = it) },
                                        label = { Text(strings.hy2ObfsPasswordLabel) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = profile.hy2UpMbps.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { profile = profile.copy(hy2UpMbps = it.coerceAtLeast(0)) }
                                        },
                                        label = { Text(strings.hy2UpMbpsLabel) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = profile.hy2DownMbps.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { profile = profile.copy(hy2DownMbps = it.coerceAtLeast(0)) }
                                        },
                                        label = { Text(strings.hy2DownMbpsLabel) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = profile.hy2Ports,
                                        onValueChange = { profile = profile.copy(hy2Ports = it.trim()) },
                                        label = { Text(strings.hy2PortsLabel) },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(2f)
                                            .testTag("editor_hy2_ports_input"),
                                    )
                                    OutlinedTextField(
                                        value = profile.hy2HopInterval.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { profile = profile.copy(hy2HopInterval = it.coerceAtLeast(1)) }
                                        },
                                        label = { Text(strings.hy2HopIntervalLabel) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                OutlinedTextField(
                                    value = profile.hy2PinSha256,
                                    onValueChange = { profile = profile.copy(hy2PinSha256 = it.trim()) },
                                    label = { Text(strings.hy2PinSha256Label) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(strings.allowInsecureGlobalTitle, style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = profile.allowInsecure,
                                        onCheckedChange = { profile = profile.copy(allowInsecure = it) },
                                    )
                                }
                            }
                        }
                    }

                    ProxyProtocol.TUIC -> {
                        OutlinedTextField(
                            value = profile.uuid,
                            onValueChange = { profile = profile.copy(uuid = it.trim()) },
                            label = { Text("UUID") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        profile = profile.copy(uuid = RealityKeyGenerator.generateUuid())
                                    },
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Generate UUID")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_tuic_uuid_input"),
                        )

                        OutlinedTextField(
                            value = profile.password,
                            onValueChange = { profile = profile.copy(password = it) },
                            label = { Text(strings.passwordLabel) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_tuic_password_input"),
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(strings.tuicSettingsTitle, style = MaterialTheme.typography.titleMedium)

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = profile.sni,
                                        onValueChange = { profile = profile.copy(sni = it.trim()) },
                                        label = { Text("Server Name (SNI)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.5f),
                                    )
                                    OutlinedTextField(
                                        value = profile.alpn,
                                        onValueChange = { profile = profile.copy(alpn = it) },
                                        label = { Text("ALPN (h3)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        EnumDropdown(
                                            label = strings.tuicCongestionLabel,
                                            selected = profile.tuicCongestionControl.ifBlank { "bbr" },
                                            options = listOf(
                                                "bbr" to "bbr",
                                                "cubic" to "cubic",
                                                "new_reno" to "new_reno",
                                            ),
                                            onSelect = { profile = profile.copy(tuicCongestionControl = it) },
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        EnumDropdown(
                                            label = strings.tuicUdpModeLabel,
                                            selected = profile.tuicUdpRelayMode.ifBlank { "native" },
                                            options = listOf(
                                                "native" to "native",
                                                "quic" to "quic",
                                            ),
                                            onSelect = { profile = profile.copy(tuicUdpRelayMode = it) },
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = profile.tuicHeartbeatIntervalMs.toString(),
                                    onValueChange = { v ->
                                        v.toIntOrNull()?.let { profile = profile.copy(tuicHeartbeatIntervalMs = it.coerceAtLeast(0)) }
                                    },
                                    label = { Text(strings.tuicHeartbeatLabel) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(strings.tuicZeroRttLabel, style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = profile.tuicZeroRttHandshake,
                                        onCheckedChange = { profile = profile.copy(tuicZeroRttHandshake = it) },
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(strings.tuicDisableSniLabel, style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = profile.tuicDisableSni,
                                        onCheckedChange = { profile = profile.copy(tuicDisableSni = it) },
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(strings.allowInsecureGlobalTitle, style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = profile.allowInsecure,
                                        onCheckedChange = { profile = profile.copy(allowInsecure = it) },
                                    )
                                }
                            }
                        }
                    }

                    ProxyProtocol.CUSTOM_JSON -> Unit
                }

                // Transport & Security for V2Ray-family protocols
                if (profile.protocol in listOf(
                        ProxyProtocol.VLESS,
                        ProxyProtocol.VMESS,
                        ProxyProtocol.TROJAN,
                        ProxyProtocol.HTTP,
                    )
                ) {
                    if (profile.protocol != ProxyProtocol.HTTP) {
                        EnumDropdown(
                            label = strings.transportNetworkLabel,
                            selected = profile.transport.displayName,
                            options = TransportType.entries.map { it to it.displayName },
                            onSelect = { profile = profile.copy(transport = it) },
                        )

                        if (profile.transport != TransportType.TCP) {
                            OutlinedTextField(
                                value = profile.host,
                                onValueChange = { profile = profile.copy(host = it) },
                                label = { Text("Host / Authority") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = profile.path,
                                onValueChange = { profile = profile.copy(path = it) },
                                label = {
                                    Text(
                                        if (profile.transport == TransportType.GRPC) "gRPC ServiceName"
                                        else "Path (e.g. /)"
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (profile.transport == TransportType.XHTTP) {
                                EnumDropdown(
                                    label = "XHTTP Mode",
                                    selected = profile.xhttpMode,
                                    options = listOf(
                                        "auto" to "auto",
                                        "packet-up" to "packet-up",
                                        "stream-up" to "stream-up",
                                        "stream-one" to "stream-one",
                                    ),
                                    onSelect = { profile = profile.copy(xhttpMode = it) },
                                )
                            }
                        }
                    }

                    EnumDropdown(
                        label = strings.transportSecurityLabel,
                        selected = profile.security.displayName,
                        options = SecurityType.entries.map { it to it.displayName },
                        onSelect = { sec ->
                            profile = profile.copy(
                                security = sec,
                                fingerprint = if (sec == SecurityType.REALITY && profile.fingerprint.isBlank()) "chrome" else profile.fingerprint,
                            )
                        },
                    )

                    AnimatedVisibility(visible = profile.security == SecurityType.REALITY || profile.security == SecurityType.TLS) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (profile.security == SecurityType.REALITY) {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (profile.security == SecurityType.REALITY) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (profile.security == SecurityType.REALITY) strings.realitySettingsTitle else strings.tlsSettingsTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }

                                OutlinedTextField(
                                    value = profile.sni,
                                    onValueChange = { profile = profile.copy(sni = it.trim()) },
                                    label = { Text("Server Name (SNI)") },
                                    placeholder = { Text("www.microsoft.com") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("editor_sni_input"),
                                )

                                EnumDropdown(
                                    label = "uTLS Fingerprint (fp)",
                                    selected = profile.fingerprint.ifBlank { "chrome" },
                                    options = UtlsFingerprint.entries.map { it.wireValue to it.wireValue },
                                    onSelect = { profile = profile.copy(fingerprint = it) },
                                )

                                if (profile.security == SecurityType.REALITY) {
                                    OutlinedTextField(
                                        value = profile.realityPublicKey,
                                        onValueChange = { profile = profile.copy(realityPublicKey = it.trim()) },
                                        label = { Text("REALITY Public Key (pbk)") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("editor_reality_pbk_input"),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedTextField(
                                            value = profile.realityShortId,
                                            onValueChange = { profile = profile.copy(realityShortId = it.trim()) },
                                            label = { Text("Short ID (sid)") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("editor_reality_sid_input"),
                                        )
                                        OutlinedTextField(
                                            value = profile.realitySpiderX,
                                            onValueChange = { profile = profile.copy(realitySpiderX = it) },
                                            label = { Text("SpiderX (spx)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    OutlinedTextField(
                                        value = profile.realityMldsa65Verify,
                                        onValueChange = { profile = profile.copy(realityMldsa65Verify = it.trim()) },
                                        label = { Text("ML-DSA-65 Verify (pqv, optional)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    OutlinedButton(
                                        onClick = {
                                            val targetSni = profile.sni.ifBlank { profile.serverAddress }
                                            scope.launch {
                                                isCheckingSni = true
                                                sniCheckResult = NetworkTester.probeTlsSni(targetSni, 443, targetSni)
                                                isCheckingSni = false
                                            }
                                        },
                                        enabled = !isCheckingSni && (profile.sni.isNotBlank() || profile.serverAddress.isNotBlank()),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        if (isCheckingSni) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(8.dp))
                                        } else {
                                            Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(strings.probeRealitySniBtn)
                                    }

                                    sniCheckResult?.let { res ->
                                        Text(
                                            text = if (res.success) {
                                                "${res.tlsProtocol} · ${res.latencyMs}ms · ${
                                                    if (res.isRealityCompatible) strings.probeGood else strings.probeUnsuitable
                                                }"
                                            } else {
                                                "Probe failed: ${res.error}"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (res.success && res.isRealityCompatible) SignalEmerald else SignalCrimson,
                                        )
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = profile.alpn,
                                        onValueChange = { profile = profile.copy(alpn = it) },
                                        label = { Text("ALPN (h2,http/1.1)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(strings.allowInsecureGlobalTitle, style = MaterialTheme.typography.bodyMedium)
                                        Switch(
                                            checked = profile.allowInsecure,
                                            onCheckedChange = { profile = profile.copy(allowInsecure = it) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Mux toggle (disabled automatically when XTLS Vision is active)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.enableMuxTitle, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = strings.enableMuxSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = profile.muxEnabled,
                            onCheckedChange = { profile = profile.copy(muxEnabled = it) },
                        )
                    }
                }

                // Proxy Chain (Dialer Proxy) Section
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (profile.chainProxyId > 0L) {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.32f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = strings.chainSectionTitle,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        if (profile.protocol in listOf(ProxyProtocol.SSH, ProxyProtocol.TUIC)) {
                            Text(
                                text = strings.chainUnsupportedExitHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (profile.chainProxyId > 0L) {
                                OutlinedButton(
                                    onClick = { profile = profile.copy(chainProxyId = 0L) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(strings.chainNoneOption)
                                }
                            }
                        } else {
                            val eligibleEntries = remember(allProfiles, profile.id) {
                                allProfiles.filter { candidate ->
                                    candidate.id > 0L &&
                                        candidate.id != profile.id &&
                                        candidate.protocol != ProxyProtocol.CUSTOM_JSON &&
                                        !XrayConfigBuilder.wouldCreateCycle(profile.id, candidate.id, allProfiles)
                                }
                            }
                            val selectedEntry = allProfiles.find { it.id == profile.chainProxyId }
                            val selectedChainLabel = when {
                                profile.chainProxyId <= 0L -> strings.chainNoneOption
                                selectedEntry != null -> "${selectedEntry.displayTitle()} (${selectedEntry.protocol.displayName})"
                                else -> "#${profile.chainProxyId}"
                            }
                            val chainOptions = buildList {
                                add(0L to strings.chainNoneOption)
                                eligibleEntries.forEach { p ->
                                    add(p.id to "${p.displayTitle()} (${p.protocol.displayName})")
                                }
                            }

                            EnumDropdown(
                                label = strings.chainProxyLabel,
                                selected = selectedChainLabel,
                                options = chainOptions,
                                onSelect = { chosenId -> profile = profile.copy(chainProxyId = chosenId) },
                                fieldTestTag = "editor_chain_proxy_dropdown",
                            )

                            Text(
                                text = strings.chainHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (profile.chainProxyId > 0L) {
                                val resolved = remember(profile, allProfiles) {
                                    XrayConfigBuilder.resolveChain(profile, allProfiles)
                                }
                                if (resolved.hops.size > 1 && !resolved.hasCycle) {
                                    val pathStr = buildString {
                                        append(strings.chainDeviceLabel)
                                        resolved.hops.reversed().forEach { hop ->
                                            append(" → ")
                                            append(hop.displayTitle())
                                            append(" (")
                                            append(hop.protocol.displayName)
                                            append(")")
                                        }
                                        append(" → ")
                                        append(strings.chainInternetLabel)
                                    }
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("editor_chain_path_preview"),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = strings.chainTrafficPathLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary,
                                            )
                                            Text(
                                                text = pathStr,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // IPv6-Only Proxy Split Routing Card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (profile.ipv6OnlyProxy) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.AltRoute,
                                    contentDescription = null,
                                    tint = if (profile.ipv6OnlyProxy) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = strings.ipv6OnlyProxyTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = strings.ipv6OnlyProxySubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = profile.ipv6OnlyProxy,
                                onCheckedChange = { profile = profile.copy(ipv6OnlyProxy = it) },
                                modifier = Modifier.testTag("editor_ipv6_only_proxy_switch"),
                            )
                        }

                        AnimatedVisibility(visible = profile.ipv6OnlyProxy) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("editor_ipv6_only_proxy_summary"),
                            ) {
                                Text(
                                    text = strings.ipv6OnlyProxyActiveSummary,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (issues.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(strings.validationChecksTitle, style = MaterialTheme.typography.titleSmall)
                        issues.forEach { issue ->
                            Text(
                                text = "• ${issue.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (issue.isError) SignalCrimson else SignalAmber,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (!isLeavingScreen) {
                        isLeavingScreen = true
                        onSave(profile)
                    }
                },
                enabled = !isLeavingScreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("editor_primary_save_button"),
            ) {
                Text(strings.saveProfileBtn)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    selected: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    fieldTestTag: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .then(if (fieldTestTag != null) Modifier.testTag(fieldTestTag) else Modifier),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}
