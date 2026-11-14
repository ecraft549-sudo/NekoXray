package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.AppLanguage
import com.example.core.model.IPv6Mode
import com.example.core.model.PerAppProxyMode
import com.example.core.model.ServiceMode
import com.example.core.model.XraySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nekoDataStore: DataStore<Preferences> by preferencesDataStore(name = "nekoxray_settings")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val SERVICE_MODE = stringPreferencesKey("service_mode")
        val SOCKS_PORT = intPreferencesKey("socks_port")
        val HTTP_PORT = intPreferencesKey("http_port")
        val MTU = intPreferencesKey("mtu")
        val IPV6_MODE = stringPreferencesKey("ipv6_mode")
        val REMOTE_DNS = stringPreferencesKey("remote_dns")
        val DIRECT_DNS = stringPreferencesKey("direct_dns")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val SNIFFING_ENABLED = booleanPreferencesKey("sniffing_enabled")
        val ROUTE_ONLY = booleanPreferencesKey("route_only")
        val DOMAIN_STRATEGY = stringPreferencesKey("domain_strategy")
        val FRAGMENT_ENABLED = booleanPreferencesKey("fragment_enabled")
        val FRAGMENT_PACKETS = stringPreferencesKey("fragment_packets")
        val FRAGMENT_LENGTH = stringPreferencesKey("fragment_length")
        val FRAGMENT_INTERVAL = stringPreferencesKey("fragment_interval")
        val GLOBAL_ALLOW_INSECURE = booleanPreferencesKey("global_allow_insecure")
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val PER_APP_MODE = stringPreferencesKey("per_app_mode")
        val PER_APP_PACKAGES = stringSetPreferencesKey("per_app_packages")
        val SELECTED_PROFILE_ID = longPreferencesKey("selected_profile_id")
        val SELECTED_GROUP_ID = longPreferencesKey("selected_group_id")
    }

    private fun readSettings(prefs: Preferences): XraySettings = XraySettings(
        serviceMode = runCatching {
            ServiceMode.valueOf(prefs[Keys.SERVICE_MODE] ?: ServiceMode.VPN.name)
        }.getOrDefault(ServiceMode.VPN),
        socksPort = prefs[Keys.SOCKS_PORT] ?: 10808,
        httpPort = prefs[Keys.HTTP_PORT] ?: 10809,
        mtu = prefs[Keys.MTU] ?: 1500,
        ipv6Mode = runCatching {
            IPv6Mode.valueOf(prefs[Keys.IPV6_MODE] ?: IPv6Mode.ENABLE.name)
        }.getOrDefault(IPv6Mode.ENABLE),
        remoteDns = prefs[Keys.REMOTE_DNS] ?: "https://1.1.1.1/dns-query",
        directDns = prefs[Keys.DIRECT_DNS] ?: "8.8.8.8",
        bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
        sniffingEnabled = prefs[Keys.SNIFFING_ENABLED] ?: true,
        routeOnly = prefs[Keys.ROUTE_ONLY] ?: false,
        domainStrategy = prefs[Keys.DOMAIN_STRATEGY] ?: "IPIfNonMatch",
        fragmentEnabled = prefs[Keys.FRAGMENT_ENABLED] ?: false,
        fragmentPackets = prefs[Keys.FRAGMENT_PACKETS] ?: "tlshello",
        fragmentLength = prefs[Keys.FRAGMENT_LENGTH] ?: "100-200",
        fragmentInterval = prefs[Keys.FRAGMENT_INTERVAL] ?: "10-20",
        globalAllowInsecure = prefs[Keys.GLOBAL_ALLOW_INSECURE] ?: false,
        logLevel = prefs[Keys.LOG_LEVEL] ?: "warning",
        darkTheme = prefs[Keys.DARK_THEME] ?: true,
        appLanguage = runCatching {
            AppLanguage.valueOf(prefs[Keys.APP_LANGUAGE] ?: AppLanguage.SYSTEM.name)
        }.getOrDefault(AppLanguage.SYSTEM),
        perAppMode = runCatching {
            PerAppProxyMode.valueOf(prefs[Keys.PER_APP_MODE] ?: PerAppProxyMode.ALL.name)
        }.getOrDefault(PerAppProxyMode.ALL),
        perAppPackages = prefs[Keys.PER_APP_PACKAGES] ?: emptySet(),
    )

    val settingsFlow: Flow<XraySettings> = context.nekoDataStore.data.map { prefs ->
        readSettings(prefs)
    }

    val selectedProfileIdFlow: Flow<Long> = context.nekoDataStore.data.map { prefs ->
        prefs[Keys.SELECTED_PROFILE_ID] ?: 0L
    }

    val selectedGroupIdFlow: Flow<Long> = context.nekoDataStore.data.map { prefs ->
        prefs[Keys.SELECTED_GROUP_ID] ?: 0L
    }

    suspend fun setSelectedProfileId(id: Long) {
        context.nekoDataStore.edit { it[Keys.SELECTED_PROFILE_ID] = id }
    }

    suspend fun setSelectedGroupId(id: Long) {
        context.nekoDataStore.edit { it[Keys.SELECTED_GROUP_ID] = id }
    }

    suspend fun updateSettings(transform: (XraySettings) -> XraySettings) {
        context.nekoDataStore.edit { prefs ->
            val current = readSettings(prefs)
            val updated = transform(current)
            prefs[Keys.SERVICE_MODE] = updated.serviceMode.name
            prefs[Keys.SOCKS_PORT] = updated.socksPort
            prefs[Keys.HTTP_PORT] = updated.httpPort
            prefs[Keys.MTU] = updated.mtu
            prefs[Keys.IPV6_MODE] = updated.ipv6Mode.name
            prefs[Keys.REMOTE_DNS] = updated.remoteDns
            prefs[Keys.DIRECT_DNS] = updated.directDns
            prefs[Keys.BYPASS_LAN] = updated.bypassLan
            prefs[Keys.SNIFFING_ENABLED] = updated.sniffingEnabled
            prefs[Keys.ROUTE_ONLY] = updated.routeOnly
            prefs[Keys.DOMAIN_STRATEGY] = updated.domainStrategy
            prefs[Keys.FRAGMENT_ENABLED] = updated.fragmentEnabled
            prefs[Keys.FRAGMENT_PACKETS] = updated.fragmentPackets
            prefs[Keys.FRAGMENT_LENGTH] = updated.fragmentLength
            prefs[Keys.FRAGMENT_INTERVAL] = updated.fragmentInterval
            prefs[Keys.GLOBAL_ALLOW_INSECURE] = updated.globalAllowInsecure
            prefs[Keys.LOG_LEVEL] = updated.logLevel
            prefs[Keys.DARK_THEME] = updated.darkTheme
            prefs[Keys.APP_LANGUAGE] = updated.appLanguage.name
            prefs[Keys.PER_APP_MODE] = updated.perAppMode.name
            prefs[Keys.PER_APP_PACKAGES] = updated.perAppPackages
        }
    }
}
