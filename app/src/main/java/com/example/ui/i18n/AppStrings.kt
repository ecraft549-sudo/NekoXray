package com.example.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.core.model.AppLanguage
import com.example.core.model.IPv6Mode
import com.example.core.model.PerAppProxyMode
import com.example.core.model.RouteAction
import com.example.core.model.ServiceMode
import java.util.Locale

data class AppStrings(
    val isRtl: Boolean = false,
    // Navigation & Main Actions
    val navProfiles: String,
    val navGroups: String,
    val navRouting: String,
    val navTools: String,
    val navSettings: String,
    val connect: String,
    val connecting: String,
    val disconnect: String,
    val cancel: String,
    val save: String,
    val close: String,
    val back: String,
    val add: String,
    val delete: String,
    val duplicate: String,
    val edit: String,

    // Profiles Screen
    val searchNodesPlaceholder: String,
    val allFilter: String,
    val scanQrCode: String,
    val scanQrShort: String,
    val importUri: String,
    val importUriMenu: String,
    val newVlessReality: String,
    val newVmess: String,
    val newTrojan: String,
    val newShadowsocks: String,
    val newHysteria2: String,
    val newTuic: String,
    val newWireGuard: String,
    val newSshTunnel: String,
    val newSocks5: String,
    val newHttpProxy: String,
    val customXrayJson: String,
    val importDialogTitle: String,
    val importDialogDesc: String,
    val pasteFromClipboard: String,
    val importAction: String,
    val emptyProfilesTitle: String,
    val emptyProfilesSubtitle: String,
    val statusConnected: String,
    val statusConnecting: String,
    val statusError: String,
    val statusReady: String,
    val statusIdleSelect: String,
    val statusDirectBypass: String,
    val missingNativeCoreBanner: String,
    val testAllNodes: String,
    val testLatency: String,
    val previewXrayJson: String,
    val shareQrUri: String,
    val copiedUriNotice: String,

    // Groups Screen
    val groupsTitle: String,
    val groupsSubtitle: String,
    val newBtn: String,
    val subscriptionTag: String,
    val localTag: String,
    val newGroupTitle: String,
    val editGroupTitle: String,
    val groupNameLabel: String,
    val subscriptionUrlToggle: String,
    val customUserAgentLabel: String,

    // Routing & Per-App
    val routingTitle: String,
    val strategyLabel: String,
    val bypassLanBadge: String,
    val ruleBtn: String,
    val perAppProxyCardTitle: String,
    val newRuleTitle: String,
    val editRuleTitle: String,
    val ruleNameLabel: String,
    val domainsLabel: String,
    val ipsLabel: String,
    val portsLabel: String,
    val protocolsLabel: String,
    val emptyMatcher: String,
    val perAppTitle: String,
    val copyList: String,
    val pasteList: String,
    val searchAppsPlaceholder: String,
    val showSystemApps: String,
    val noMatchingApps: String,
    val addPackageTitle: String,
    val packageIdLabel: String,
    val importAppListTitle: String,
    val importAppListDesc: String,
    val fromClipboard: String,
    val merge: String,
    val replace: String,

    // Tools Screen
    val engineRuntimeTitle: String,
    val libv2rayLinked: String,
    val configTunReady: String,
    val realityKeygenTitle: String,
    val regenerate: String,
    val privateKeyServer: String,
    val publicKeyPbk: String,
    val shortIdSid: String,
    val copyServerRealityJson: String,
    val sniProbeTitle: String,
    val sniProbeDesc: String,
    val domainPort443: String,
    val probeBtn: String,
    val probeGood: String,
    val probeUnsuitable: String,
    val logsTitle: String,
    val clearLogs: String,

    // Settings Screen
    val settingsTitle: String,
    val languageTitle: String,
    val languageSubtitle: String,
    val languageSystem: String,
    val languageEnglish: String,
    val languageRussian: String,
    val languageZhCn: String,
    val languageZhTw: String,
    val languageFa: String,
    val languageEs: String,
    val serviceModeTitle: String,
    val darkThemeTitle: String,
    val darkThemeSubtitle: String,
    val perAppSettingsTitle: String,
    val inboundsTunTitle: String,
    val socks5Port: String,
    val httpPort: String,
    val ipv6StrategyTitle: String,
    val dnsSniffingTitle: String,
    val remoteDnsLabel: String,
    val directDnsLabel: String,
    val directDnsEnabledTitle: String = "Enable Direct DNS",
    val directDnsEnabledSubtitle: String = "Resolve DIRECT routing rules and local domains outside the proxy tunnel",
    val protectDirectDnsTitle: String = "Anti-Spoofing DoH for Direct DNS",
    val protectDirectDnsSubtitle: String = "Encrypt Direct DNS via https+local:// (port 443) to prevent ISP port 53 DNS hijacking",
    val bypassLanTitle: String,
    val bypassLanSubtitle: String,
    val sniffingTitle: String,
    val sniffingSubtitle: String,
    val routeOnlyTitle: String,
    val routeOnlySubtitle: String,
    val fragmentSectionTitle: String,
    val enableFragmentTitle: String,
    val enableFragmentSubtitle: String,
    val lengthLabel: String,
    val intervalLabel: String,
    val allowInsecureGlobalTitle: String,
    val allowInsecureGlobalSubtitle: String,
    val aboutDesc: String,

    // Profile Editor & Config Preview & QR
    val newProfileTitle: String,
    val editProfileTitle: String,
    val protocolLabel: String,
    val profileNameLabel: String,
    val serverAddressLabel: String,
    val portLabel: String,
    val authTypeLabel: String,
    val usernameLabel: String,
    val passwordLabel: String,
    val privateKeyLabel: String,
    val passphraseOptionalLabel: String,
    val publicKeyOptionalLabel: String,
    val transportNetworkLabel: String,
    val transportSecurityLabel: String,
    val realitySettingsTitle: String,
    val tlsSettingsTitle: String,
    val probeRealitySniBtn: String,
    val hy2SettingsTitle: String,
    val hy2AuthLabel: String,
    val hy2ObfsLabel: String,
    val hy2ObfsPasswordLabel: String,
    val hy2UpMbpsLabel: String,
    val hy2DownMbpsLabel: String,
    val hy2PortsLabel: String,
    val hy2HopIntervalLabel: String,
    val hy2PinSha256Label: String,
    val tuicSettingsTitle: String,
    val tuicCongestionLabel: String,
    val tuicUdpModeLabel: String,
    val tuicZeroRttLabel: String,
    val tuicDisableSniLabel: String,
    val tuicHeartbeatLabel: String,
    val enableMuxTitle: String,
    val enableMuxSubtitle: String,
    val chainSectionTitle: String,
    val chainProxyLabel: String,
    val chainNoneOption: String,
    val chainHint: String,
    val chainTrafficPathLabel: String,
    val chainDeviceLabel: String,
    val chainInternetLabel: String,
    val chainUnsupportedExitHint: String,
    val chainViaPrefix: String,
    val validationChecksTitle: String,
    val saveProfileBtn: String,
    val validConfigBanner: String,
    val tapQrFullscreen: String,
    val qrTooLarge: String,
    val copyUriBtn: String,
    val scannerTitle: String,
    val scannerPermissionPrompt: String,
    val allowCameraBtn: String,
    val fromGalleryBtn: String,
    val scanImageBtn: String,
    val torchOnBtn: String,
    val torchOffBtn: String,
    val qrNotFoundInImage: String,

    // Widgets & Tray & Reconnect
    val switchedProfilePrefix: String,
    val widgetNoProfileTitle: String,
    val widgetNoProfileSubtitle: String,
    val widgetTapToConnect: String,
    val widgetsSectionTitle: String,
    val trayWidgetTitle: String,
    val trayWidgetSubtitle: String,
    val pinStatusWidgetBtn: String,
    val pinCompactWidgetBtn: String,
    val pinMiniWidgetBtn: String,
    val miniWidgetOn: String,
    val miniWidgetOff: String,
    val pinWidgetSentNotice: String,
    val pinWidgetUnsupportedNotice: String,
    val quickSettingsTileHint: String,

    // Enum Labels
    val serviceModeVpnLabel: String,
    val serviceModeProxyOnlyLabel: String,
    val ipv6DisableLabel: String,
    val ipv6EnableLabel: String,
    val ipv6PreferLabel: String,
    val ipv6OnlyLabel: String,
    val routeProxyLabel: String,
    val routeDirectLabel: String,
    val routeBlockLabel: String,
    val perAppAllLabel: String,
    val perAppBypassLabel: String,
    val perAppOnlyLabel: String,
    val perAppAllDesc: String,
    val perAppBypassDesc: String,
    val perAppOnlyDesc: String,
) {
    fun serviceModeName(mode: ServiceMode): String = when (mode) {
        ServiceMode.VPN -> serviceModeVpnLabel
        ServiceMode.PROXY_ONLY -> serviceModeProxyOnlyLabel
    }

    fun ipv6ModeName(mode: IPv6Mode): String = when (mode) {
        IPv6Mode.DISABLE -> ipv6DisableLabel
        IPv6Mode.ENABLE -> ipv6EnableLabel
        IPv6Mode.PREFER -> ipv6PreferLabel
        IPv6Mode.ONLY -> ipv6OnlyLabel
    }

    fun routeActionName(action: RouteAction): String = when (action) {
        RouteAction.PROXY -> routeProxyLabel
        RouteAction.DIRECT -> routeDirectLabel
        RouteAction.BLOCK -> routeBlockLabel
    }

    fun perAppModeName(mode: PerAppProxyMode): String = when (mode) {
        PerAppProxyMode.ALL -> perAppAllLabel
        PerAppProxyMode.BYPASS_SELECTED -> perAppBypassLabel
        PerAppProxyMode.PROXY_SELECTED -> perAppOnlyLabel
    }

    fun perAppModeDesc(mode: PerAppProxyMode): String = when (mode) {
        PerAppProxyMode.ALL -> perAppAllDesc
        PerAppProxyMode.BYPASS_SELECTED -> perAppBypassDesc
        PerAppProxyMode.PROXY_SELECTED -> perAppOnlyDesc
    }

    fun languageOptionName(lang: AppLanguage): String = when (lang) {
        AppLanguage.SYSTEM -> languageSystem
        AppLanguage.EN -> languageEnglish
        AppLanguage.RU -> languageRussian
        AppLanguage.ZH_CN -> languageZhCn
        AppLanguage.ZH_TW -> languageZhTw
        AppLanguage.FA -> languageFa
        AppLanguage.ES -> languageEs
    }
}

fun resolveAppStrings(
    language: AppLanguage,
    systemLanguageCode: String = Locale.getDefault().language,
    systemScriptOrCountry: String = "${Locale.getDefault().script}_${Locale.getDefault().country}",
): AppStrings = when (language) {
    AppLanguage.EN -> EnglishStrings
    AppLanguage.RU -> RussianStrings
    AppLanguage.ZH_CN -> SimplifiedChineseStrings
    AppLanguage.ZH_TW -> TraditionalChineseStrings
    AppLanguage.FA -> PersianStrings
    AppLanguage.ES -> SpanishStrings
    AppLanguage.SYSTEM -> when {
        systemLanguageCode.equals("ru", ignoreCase = true) -> RussianStrings
        systemLanguageCode.equals("fa", ignoreCase = true) -> PersianStrings
        systemLanguageCode.equals("es", ignoreCase = true) -> SpanishStrings
        systemLanguageCode.equals("zh", ignoreCase = true) -> {
            val tag = systemScriptOrCountry.lowercase()
            if (tag.contains("hant") || tag.contains("tw") || tag.contains("hk") || tag.contains("mo")) {
                TraditionalChineseStrings
            } else {
                SimplifiedChineseStrings
            }
        }
        else -> EnglishStrings
    }
}

val LocalAppStrings = staticCompositionLocalOf { EnglishStrings }
