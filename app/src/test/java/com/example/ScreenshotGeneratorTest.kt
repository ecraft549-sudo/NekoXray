package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.example.core.crypto.RealityKeyGenerator
import com.example.core.model.AppLanguage
import com.example.core.model.FlowType
import com.example.core.model.PerAppProxyMode
import com.example.core.model.ProxyGroup
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.RouteAction
import com.example.core.model.RoutingRule
import com.example.core.model.SecurityType
import com.example.core.model.SshAuthType
import com.example.core.model.TransportType
import com.example.core.model.XraySettings
import com.example.core.vpn.EngineRuntimeState
import com.example.core.vpn.LogEntry
import com.example.core.vpn.TunnelStatus
import com.example.ui.components.QrCodeDialog
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.resolveAppStrings
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.RoutingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.NekoXrayTheme
import com.example.ui.theme.SignalEmerald
import com.example.ui.viewmodel.NekoUiState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotGeneratorTest {

    private fun sampleState(language: AppLanguage = AppLanguage.EN): NekoUiState {
        val keys = RealityKeyGenerator.generateBundle()
        val profiles = listOf(
            ProxyProfile(
                id = 1L,
                groupId = 1L,
                name = "Frankfurt REALITY · Vision",
                protocol = ProxyProtocol.VLESS,
                serverAddress = "de-fra.example.net",
                serverPort = 443,
                uuid = keys.uuid,
                security = SecurityType.REALITY,
                transport = TransportType.TCP,
                flow = FlowType.VISION,
                sni = "www.microsoft.com",
                fingerprint = "chrome",
                realityPublicKey = keys.publicKey,
                realityShortId = keys.shortId,
                latencyMs = 42,
                tlsLatencyMs = 89,
            ),
            ProxyProfile(
                id = 2L,
                groupId = 1L,
                name = "Czech XHTTP · ML-KEM-768",
                protocol = ProxyProtocol.VLESS,
                serverAddress = "cz-prg.example.net",
                serverPort = 443,
                uuid = keys.uuid,
                encryption = "mlkem768x25519plus.native.0rtt",
                security = SecurityType.REALITY,
                transport = TransportType.XHTTP,
                xhttpMode = "auto",
                sni = "www.nvidia.com",
                fingerprint = "chrome",
                realityPublicKey = keys.publicKey,
                realityShortId = keys.shortId,
                latencyMs = 68,
                tlsLatencyMs = 131,
            ),
            ProxyProfile(
                id = 3L,
                groupId = 2L,
                name = "Helsinki SSH Tunnel",
                protocol = ProxyProtocol.SSH,
                serverAddress = "fi-hel.example.org",
                serverPort = 2222,
                username = "tunnel",
                sshAuthType = SshAuthType.PRIVATE_KEY,
                sshPublicKey = "ssh-ed25519 AAAA...",
                security = SecurityType.NONE,
                flow = FlowType.NONE,
                latencyMs = 54,
            ),
            ProxyProfile(
                id = 4L,
                groupId = 2L,
                name = "Amsterdam Trojan gRPC",
                protocol = ProxyProtocol.TROJAN,
                serverAddress = "nl-ams.example.org",
                serverPort = 443,
                security = SecurityType.TLS,
                transport = TransportType.GRPC,
                sni = "cdn.example.org",
                fingerprint = "firefox",
                flow = FlowType.NONE,
                latencyMs = 112,
                tlsLatencyMs = 210,
            ),
        )
        val groups = listOf(
            ProxyGroup(id = 1L, name = "REALITY Nodes", isSubscription = true, subscriptionUrl = "https://sub.example.net/xray"),
            ProxyGroup(id = 2L, name = "SSH & Backup", isSubscription = false),
        )
        val rules = listOf(
            RoutingRule(
                id = 1L,
                name = "Bypass Private / LAN",
                enabled = true,
                ips = "geoip:private,192.168.0.0/16",
                action = RouteAction.DIRECT,
                priority = 1,
            ),
            RoutingRule(
                id = 2L,
                name = "Direct Local / RU Domains",
                enabled = true,
                domains = "geosite:category-ru,domain:ru,ya.ru",
                ips = "geoip:ru",
                action = RouteAction.DIRECT,
                priority = 2,
            ),
            RoutingRule(
                id = 3L,
                name = "Block Ads & Trackers",
                enabled = true,
                domains = "geosite:category-ads-all",
                action = RouteAction.BLOCK,
                priority = 3,
            ),
        )
        return NekoUiState(
            profiles = profiles,
            groups = groups,
            routingRules = rules,
            settings = XraySettings(
                darkTheme = true,
                appLanguage = language,
                perAppMode = PerAppProxyMode.PROXY_SELECTED,
                perAppPackages = setOf(
                    "org.telegram.messenger",
                    "com.google.android.youtube",
                    "com.discord",
                    "com.android.chrome",
                ),
                fragmentEnabled = true,
            ),
            selectedProfileId = 1L,
            selectedGroupId = 0L,
            engineState = EngineRuntimeState(
                status = TunnelStatus.CONNECTED,
                isNativeCoreLinked = true,
                nativeCoreVersion = "v26.10.31",
                hasGeoIp = true,
                hasGeoSite = true,
                activeProfileId = 1L,
                activeProfileName = "Frankfurt REALITY · Vision",
                activeProtocolLabel = "VLESS · REALITY · TCP",
                isRealityActive = true,
                connectedSinceMs = System.currentTimeMillis() - 125_000L,
                logs = listOf(
                    LogEntry("12:04:01", "INFO", "Xray-core v26.10.31 initialized (libv2ray linked)"),
                    LogEntry("12:04:02", "INFO", "TUN interface 172.19.0.1/30 MTU 1500 established"),
                    LogEntry("12:04:02", "INFO", "REALITY handshake complete -> de-fra.example.net:443 (chrome uTLS)"),
                    LogEntry("12:04:09", "INFO", "Direct route matched: ya.ru -> direct"),
                ),
            ),
            generatedRealityKeys = keys,
        )
    }

    private fun saveScreen(
        fileName: String,
        content: @Composable () -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val dm = activity.resources.displayMetrics
        val widthPx = dm.widthPixels
        val heightPx = dm.heightPixels

        val composeView = ComposeView(activity).apply {
            setContent(content)
        }
        activity.setContentView(composeView)

        val decor = activity.window.decorView
        val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        decor.measure(wSpec, hSpec)
        decor.layout(0, 0, widthPx, heightPx)

        // Pump a couple of frames so LazyColumn / layouts settle without spinning on infinite timers
        ShadowLooper.runUiThreadTasks()
        decor.measure(wSpec, hSpec)
        decor.layout(0, 0, widthPx, heightPx)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val globalViews = runCatching { WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
        if (globalViews.isNotEmpty()) {
            globalViews.forEachIndexed { idx, rootView ->
                if (idx > 0) {
                    // Dim backdrop behind dialogs, then center the dialog window
                    canvas.drawColor(android.graphics.Color.argb(165, 0, 0, 0))
                    rootView.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST),
                    )
                    val dw = rootView.measuredWidth.coerceAtLeast(1)
                    val dh = rootView.measuredHeight.coerceAtLeast(1)
                    rootView.layout(0, 0, dw, dh)
                    canvas.save()
                    canvas.translate((widthPx - dw) / 2f, (heightPx - dh) / 2f)
                    rootView.draw(canvas)
                    canvas.restore()
                } else {
                    rootView.draw(canvas)
                }
            }
        } else {
            decor.draw(canvas)
        }

        val rootDir = File("..").takeIf { File(it, "settings.gradle.kts").exists() } ?: File(".")
        val outDir = File(rootDir, "docs/screenshots").apply { mkdirs() }
        val outFile = File(outDir, fileName)
        outFile.outputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue(outFile.exists() && outFile.length() > 5_000L)
        controller.pause().stop().destroy()
    }

    @Composable
    private fun ScreenShell(
        selectedIndex: Int,
        state: NekoUiState,
        content: @Composable (Modifier) -> Unit,
    ) {
        val strings = resolveAppStrings(state.settings.appLanguage)
        val navItems = listOf(
            Triple(strings.navProfiles, Icons.Filled.Dns, Icons.Outlined.Dns),
            Triple(strings.navGroups, Icons.Filled.FolderCopy, Icons.Outlined.FolderCopy),
            Triple(strings.navRouting, Icons.AutoMirrored.Filled.AltRoute, Icons.AutoMirrored.Outlined.AltRoute),
            Triple(strings.navTools, Icons.Filled.Build, Icons.Outlined.Build),
            Triple(strings.navSettings, Icons.Filled.Settings, Icons.Outlined.Settings),
        )
        CompositionLocalProvider(LocalAppStrings provides strings) {
            NekoXrayTheme(darkTheme = state.settings.darkTheme) {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            navItems.forEachIndexed { idx, (label, selIcon, unselIcon) ->
                                NavigationBarItem(
                                    selected = idx == selectedIndex,
                                    onClick = {},
                                    icon = { Icon(if (idx == selectedIndex) selIcon else unselIcon, label) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                    floatingActionButton = {
                        val active = state.engineState.status == TunnelStatus.CONNECTED
                        ExtendedFloatingActionButton(
                            onClick = {},
                            icon = { Icon(if (active) Icons.Filled.Stop else Icons.Filled.PlayArrow, null) },
                            text = { Text(if (active) strings.disconnect else strings.connect) },
                            containerColor = if (active) SignalEmerald else MaterialTheme.colorScheme.primary,
                            contentColor = if (active) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
                        )
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(innerPadding),
                    ) {
                        content(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    @Test
    fun renderProfilesScreenshot() {
        val enState = sampleState(AppLanguage.EN)
        saveScreen("01_profiles_dark.png") {
            ScreenShell(0, enState) { mod ->
                ProfilesScreen(
                    state = enState,
                    onSelectProfile = {},
                    onSelectGroup = {},
                    onSearchChange = {},
                    onImportText = {},
                    onCreateProfile = {},
                    onEditProfile = {},
                    onPreviewConfig = {},
                    onTestProfile = {},
                    onTestAll = {},
                    onDuplicateProfile = {},
                    onDeleteProfile = {},
                    onCopiedNotice = {},
                    modifier = mod,
                )
            }
        }
    }

    @Test
    fun renderRoutingScreenshot() {
        val enState = sampleState(AppLanguage.EN)
        saveScreen("02_routing_rules.png") {
            ScreenShell(2, enState) { mod ->
                RoutingScreen(
                    state = enState,
                    onToggleRule = {},
                    onSaveRule = {},
                    onDeleteRule = {},
                    onOpenAppManager = {},
                    modifier = mod,
                )
            }
        }
    }

    @Test
    fun renderToolsScreenshot() {
        val enState = sampleState(AppLanguage.EN)
        saveScreen("03_tools_reality_keygen.png") {
            ScreenShell(3, enState) { mod ->
                ToolsScreen(
                    state = enState,
                    onGenerateKeys = {},
                    onProbeSni = { _, _, _ -> },
                    onClearLogs = {},
                    onCopiedNotice = {},
                    modifier = mod,
                )
            }
        }
    }

    @Test
    fun renderSettingsRuScreenshot() {
        val ruState = sampleState(AppLanguage.RU)
        saveScreen("04_settings_ru.png") {
            ScreenShell(4, ruState) { mod ->
                SettingsScreen(
                    state = ruState,
                    onUpdateSettings = {},
                    onOpenAppManager = {},
                    modifier = mod,
                )
            }
        }
    }

    @Test
    fun renderQrShareScreenshot() {
        val enState = sampleState(AppLanguage.EN)
        saveScreen("05_qr_share.png") {
            ScreenShell(0, enState) { mod ->
                ProfilesScreen(
                    state = enState,
                    onSelectProfile = {},
                    onSelectGroup = {},
                    onSearchChange = {},
                    onImportText = {},
                    onCreateProfile = {},
                    onEditProfile = {},
                    onPreviewConfig = {},
                    onTestProfile = {},
                    onTestAll = {},
                    onDuplicateProfile = {},
                    onDeleteProfile = {},
                    onCopiedNotice = {},
                    modifier = mod,
                )
                QrCodeDialog(
                    title = "Czech XHTTP · ML-KEM-768",
                    uri = "vless://32622b0f-1a68-4e38-a7f7-a557edc26979@cz-prg.example.net:443?encryption=mlkem768x25519plus.native.0rtt&security=reality&sni=www.nvidia.com&fp=chrome&pbk=${enState.generatedRealityKeys.publicKey}&sid=${enState.generatedRealityKeys.shortId}&type=xhttp&mode=auto#Czech%20XHTTP",
                    onDismiss = {},
                    onCopied = {},
                )
            }
        }
    }
}
