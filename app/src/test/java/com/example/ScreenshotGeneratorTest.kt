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
                groupId = 1L,
                name = "Tokyo Hysteria 2 · Brutal",
                protocol = ProxyProtocol.HYSTERIA2,
                serverAddress = "jp-tyo.example.net",
                serverPort = 443,
                password = "hy2-demo-password",
                sni = "cdn.example.net",
                hy2Obfs = "salamander",
                hy2ObfsPassword = "obfs",
                hy2UpMbps = 100,
                hy2DownMbps = 300,
                hy2Ports = "20000-40000",
                hy2HopInterval = 30,
                security = SecurityType.TLS,
                flow = FlowType.NONE,
                latencyMs = null,
                tlsLatencyMs = 74,
            ),
            ProxyProfile(
                id = 4L,
                groupId = 1L,
                name = "Singapore TUIC v5 · 0-RTT",
                protocol = ProxyProtocol.TUIC,
                serverAddress = "sg-sin.example.net",
                serverPort = 443,
                uuid = keys.uuid,
                password = "tuic-demo-password",
                sni = "edge.example.net",
                tuicCongestionControl = "bbr",
                tuicUdpRelayMode = "native",
                tuicZeroRttHandshake = true,
                security = SecurityType.TLS,
                flow = FlowType.NONE,
                latencyMs = null,
                tlsLatencyMs = 81,
            ),
            ProxyProfile(
                id = 5L,
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
                    "com.android.vending",
                    "com.android.chrome",
                ),
                fragmentEnabled = true,
                directDnsEnabled = true,
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
                    title = "Frankfurt XHTTP · ML-KEM-768",
                    uri = "vless://00000000-0000-4000-8000-000000000001@de-fra.example.net:443?encryption=mlkem768x25519plus.native.0rtt&security=reality&sni=www.microsoft.com&fp=chrome&pbk=${enState.generatedRealityKeys.publicKey}&sid=${enState.generatedRealityKeys.shortId}&type=xhttp&mode=auto#Frankfurt%20XHTTP",
                    onDismiss = {},
                    onCopied = {},
                )
            }
        }
    }

    @Test
    fun renderHeroBanner() {
        val rootDir = File("..").takeIf { File(it, "settings.gradle.kts").exists() } ?: File(".")
        val screenshotsDir = File(rootDir, "docs/screenshots")
        if (!File(screenshotsDir, "01_profiles_dark.png").exists()) {
            renderProfilesScreenshot()
        }
        if (!File(screenshotsDir, "02_routing_rules.png").exists()) {
            renderRoutingScreenshot()
        }
        if (!File(screenshotsDir, "03_tools_reality_keygen.png").exists()) {
            renderToolsScreenshot()
        }

        val w = 1600
        val h = 780
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

        // 1. Base obsidian background (#070B12 -> #0B1220)
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF060A11.toInt(), 0xFF0A111E.toInt(), 0xFF070B12.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // 2. Ambient radial glows (Cyan top-left & center-right, Violet right, Emerald bottom-right)
        val glows = listOf(
            Triple(220f, 120f, 520f to 0x3800E5FF),
            Triple(1160f, 390f, 540f to 0x4238BDF8),
            Triple(1380f, 240f, 500f to 0x408B5CF6),
            Triple(1020f, 640f, 420f to 0x2C34D399),
        )
        for ((gx, gy, radColor) in glows) {
            val (radius, color) = radColor
            paint.shader = android.graphics.RadialGradient(
                gx, gy, radius,
                intArrayOf(color, 0x00000000),
                floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(gx, gy, radius, paint)
        }
        paint.shader = null

        // 3. Subtle 48px cyber grid matching docs/styles.css (.bg-grid)
        val gridPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1038BDF8
            strokeWidth = 1f
        }
        var gx = 0f
        while (gx <= w) {
            canvas.drawLine(gx, 0f, gx, h.toFloat(), gridPaint)
            gx += 48f
        }
        var gy = 0f
        while (gy <= h) {
            canvas.drawLine(0f, gy, w.toFloat(), gy, gridPaint)
            gy += 48f
        }

        // Helper to draw a stylized phone frame around a screenshot
        fun drawPhoneMockup(
            screenBmp: Bitmap,
            cx: Float,
            cy: Float,
            phoneW: Float,
            rotationDeg: Float,
            alpha: Int = 255,
            highlightBorder: Boolean = false,
        ) {
            val aspect = screenBmp.height.toFloat() / screenBmp.width.toFloat()
            val phoneH = phoneW * aspect
            val bezel = 12f
            val outerW = phoneW + bezel * 2
            val outerH = phoneH + bezel * 2
            val outerCorner = 38f
            val innerCorner = 28f

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotationDeg)

            // Drop shadow
            val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb((alpha * 0.65f).toInt(), 0, 0, 0)
                setShadowLayer(42f, 0f, 22f, android.graphics.Color.argb((alpha * 0.75f).toInt(), 0, 0, 0))
            }
            val outerRect = android.graphics.RectF(-outerW / 2f, -outerH / 2f, outerW / 2f, outerH / 2f)
            canvas.drawRoundRect(outerRect, outerCorner, outerCorner, shadowPaint)

            // Phone chassis fill (#0A0F1A)
            val bodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(alpha, 10, 15, 26)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRoundRect(outerRect, outerCorner, outerCorner, bodyPaint)

            // Screen clipped to inner rounded rect
            val innerRect = android.graphics.RectF(-phoneW / 2f, -phoneH / 2f, phoneW / 2f, phoneH / 2f)
            val clipPath = android.graphics.Path().apply {
                addRoundRect(innerRect, innerCorner, innerCorner, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            val bmpPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                this.alpha = alpha
            }
            canvas.drawBitmap(
                screenBmp,
                android.graphics.Rect(0, 0, screenBmp.width, screenBmp.height),
                innerRect,
                bmpPaint,
            )
            canvas.restore()

            // Speaker notch pill at top
            val notchW = phoneW * 0.30f
            val notchH = 14f
            val notchRect = android.graphics.RectF(
                -notchW / 2f,
                -phoneH / 2f + 8f,
                notchW / 2f,
                -phoneH / 2f + 8f + notchH,
            )
            val notchPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(alpha, 6, 10, 18)
            }
            canvas.drawRoundRect(notchRect, 99f, 99f, notchPaint)

            // Outer glowing border
            val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = if (highlightBorder) 2.5f else 1.6f
                if (highlightBorder) {
                    shader = android.graphics.LinearGradient(
                        -outerW / 2f, -outerH / 2f, outerW / 2f, outerH / 2f,
                        intArrayOf(0xFF38BDF8.toInt(), 0xFFA78BFA.toInt(), 0xFF34D399.toInt()),
                        floatArrayOf(0f, 0.55f, 1f),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                } else {
                    color = android.graphics.Color.argb((alpha * 0.45f).toInt(), 148, 163, 184)
                }
            }
            canvas.drawRoundRect(outerRect, outerCorner, outerCorner, borderPaint)
            canvas.restore()
        }

        val screenProfiles = android.graphics.BitmapFactory.decodeFile(File(screenshotsDir, "01_profiles_dark.png").absolutePath)
        val screenRouting = android.graphics.BitmapFactory.decodeFile(File(screenshotsDir, "02_routing_rules.png").absolutePath)
        val screenTools = android.graphics.BitmapFactory.decodeFile(File(screenshotsDir, "03_tools_reality_keygen.png").absolutePath)

        // 4. Draw 3 layered phone mockups on the right side
        if (screenRouting != null) {
            drawPhoneMockup(
                screenBmp = screenRouting,
                cx = 985f,
                cy = 410f,
                phoneW = 248f,
                rotationDeg = -10f,
                alpha = 215,
                highlightBorder = false,
            )
        }
        if (screenTools != null) {
            drawPhoneMockup(
                screenBmp = screenTools,
                cx = 1365f,
                cy = 415f,
                phoneW = 248f,
                rotationDeg = 10f,
                alpha = 215,
                highlightBorder = false,
            )
        }
        if (screenProfiles != null) {
            drawPhoneMockup(
                screenBmp = screenProfiles,
                cx = 1175f,
                cy = 390f,
                phoneW = 286f,
                rotationDeg = 0f,
                alpha = 255,
                highlightBorder = true,
            )
        }

        // 5. Draw floating glassmorphic cards on the phones (matching docs/index.html .floating-card)
        fun drawFloatingCard(
            x: Float,
            y: Float,
            cardW: Float,
            cardH: Float,
            dotColor: Int,
            title: String,
            subtitle: String,
        ) {
            val rect = android.graphics.RectF(x, y, x + cardW, y + cardH)
            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xEC0D1526.toInt()
                setShadowLayer(24f, 0f, 10f, 0x99000000.toInt())
            }
            canvas.drawRoundRect(rect, 16f, 16f, bgPaint)

            val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.5f
                color = 0x6638BDF8
            }
            canvas.drawRoundRect(rect, 16f, 16f, strokePaint)

            val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = dotColor
            }
            canvas.drawCircle(x + 24f, y + cardH / 2f, 6.5f, dotPaint)
            dotPaint.color = (dotColor and 0x00FFFFFF) or 0x44000000
            canvas.drawCircle(x + 24f, y + cardH / 2f, 12f, dotPaint)

            val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF8FAFC.toInt()
                textSize = 16.5f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            }
            val subPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = 13.5f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
            }
            canvas.drawText(title, x + 44f, y + 29f, titlePaint)
            canvas.drawText(subtitle, x + 44f, y + 50f, subPaint)
        }

        drawFloatingCard(
            x = 748f,
            y = 48f,
            cardW = 328f,
            cardH = 66f,
            dotColor = 0xFF34D399.toInt(),
            title = "VLESS · HY2 · TUIC v5",
            subtitle = "REALITY · ML-KEM-768 · QUIC",
        )
        drawFloatingCard(
            x = 1210f,
            y = 666f,
            cardW = 345f,
            cardH = 66f,
            dotColor = 0xFF38BDF8.toInt(),
            title = "Wi-Fi ↔ 5G Seamless Handoff",
            subtitle = "DoH Anti-Spoof · Always-On · Widgets",
        )

        // 6. Left-column hero copy & branding matching docs/index.html
        val leftX = 78f

        // Live pill badge at top
        val pillRect = android.graphics.RectF(leftX, 82f, leftX + 410f, 124f)
        val pillBg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE00F1726.toInt()
        }
        val pillStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.4f
            color = 0x6B38BDF8
        }
        canvas.drawRoundRect(pillRect, 999f, 999f, pillBg)
        canvas.drawRoundRect(pillRect, 999f, 999f, pillStroke)
        val pulsePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF34D399.toInt()
        }
        canvas.drawCircle(leftX + 22f, 103f, 5.5f, pulsePaint)
        val pillTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            textSize = 15.5f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
        canvas.drawText("Xray-core v26.10.31 · Native Android VPN", leftX + 38f, 108.5f, pillTextPaint)

        // App icon avatar + Gradient "NekoXray" title
        val lainFile = File(rootDir, "app/src/main/res/drawable/ic_launcher_lain.png")
        val lainBmp = if (lainFile.exists()) android.graphics.BitmapFactory.decodeFile(lainFile.absolutePath) else null
        if (lainBmp != null) {
            val avatarSize = 78f
            val ax = leftX
            val ay = 156f
            val avatarRect = android.graphics.RectF(ax, ay, ax + avatarSize, ay + avatarSize)
            val avatarPath = android.graphics.Path().apply {
                addRoundRect(avatarRect, 22f, 22f, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(avatarPath)
            canvas.drawBitmap(lainBmp, android.graphics.Rect(0, 0, lainBmp.width, lainBmp.height), avatarRect, paint)
            canvas.restore()
            val avatarStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.5f
                shader = android.graphics.LinearGradient(
                    ax, ay, ax + avatarSize, ay + avatarSize,
                    intArrayOf(0xFF38BDF8.toInt(), 0xFFA78BFA.toInt(), 0xFF34D399.toInt()),
                    floatArrayOf(0f, 0.5f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(avatarRect, 22f, 22f, avatarStroke)
        }

        val brandPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 74f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            letterSpacing = -0.02f
            shader = android.graphics.LinearGradient(
                leftX + 98f, 160f, leftX + 490f, 230f,
                intArrayOf(0xFF38BDF8.toInt(), 0xFFA78BFA.toInt(), 0xFF34D399.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawText("NekoXray", leftX + 98f, 220f, brandPaint)

        // Headline
        val headlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF8FAFC.toInt()
            textSize = 40f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            letterSpacing = -0.015f
        }
        canvas.drawText("Next-Gen Xray-core Tunnel", leftX, 292f, headlinePaint)
        canvas.drawText("in Jetpack Compose & Material 3", leftX, 342f, headlinePaint)

        // Subtitle lines
        val subLinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 20.5f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
        }
        val descLines = listOf(
            "Native Android VPN & proxy client supporting VLESS REALITY,",
            "Hysteria 2, TUIC v5, XHTTP, ML-KEM-768, SSH SOCKS5/DNS bridge,",
            "anti-spoofing DoH DNS, smart GeoIP/GeoSite routing,",
            "and zero-drop Wi-Fi ↔ 5G/LTE network handoff.",
        )
        descLines.forEachIndexed { idx, line ->
            canvas.drawText(line, leftX, 396f + idx * 31f, subLinePaint)
        }

        // Spec chips grid (matching .spec-chip on website)
        val chipRows = listOf(
            listOf("VLESS · REALITY · XHTTP", "Hysteria 2 · TUIC v5", "SSH · WireGuard · Trojan"),
            listOf("ML-KEM-768 + pqv", "DoH + Direct DNS", "GeoIP / GeoSite + Apps"),
            listOf("Wi-Fi ↔ 5G Handoff", "QR v40 (2200+ chars)", "EN · RU · ZH · FA · ES"),
        )
        val chipTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            textSize = 15.5f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
        val chipBgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xD80D1422.toInt()
        }
        val chipBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.2f
            color = 0x3894A3B8
        }

        var chipY = 542f
        for (row in chipRows) {
            var chipX = leftX
            for (label in row) {
                val textW = chipTextPaint.measureText(label)
                val cw = textW + 28f
                val ch = 38f
                val cRect = android.graphics.RectF(chipX, chipY, chipX + cw, chipY + ch)
                canvas.drawRoundRect(cRect, 10f, 10f, chipBgPaint)
                canvas.drawRoundRect(cRect, 10f, 10f, chipBorderPaint)
                canvas.drawText(label, chipX + 14f, chipY + 24.5f, chipTextPaint)
                chipX += cw + 10f
            }
            chipY += 48f
        }

        val outDir = File(rootDir, "docs/images").apply { mkdirs() }
        val outFile = File(outDir, "banner.jpg")
        outFile.outputStream().use { stream ->
            assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream))
        }
        assertTrue(outFile.exists() && outFile.length() > 40_000L)
    }
}
