package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.resolveAppStrings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.vpn.TunnelStatus
import com.example.core.vpn.XrayEngineState
import com.example.core.vpn.XrayVpnService
import com.example.ui.navigation.NekoRoute
import com.example.ui.screens.AppManagerScreen
import com.example.ui.screens.ConfigPreviewScreen
import com.example.ui.screens.GroupsScreen
import com.example.ui.screens.ProfileEditorScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.RoutingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.NekoXrayTheme
import com.example.ui.theme.SignalEmerald
import com.example.ui.viewmodel.NekoViewModel
import kotlinx.coroutines.launch

private data class TopTab(
    val route: NekoRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

class MainActivity : ComponentActivity() {

    private val viewModel: NekoViewModel by viewModels()

    private var autoConnectSignal = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!XrayEngineState.state.value.isNativeCoreLinked &&
            XrayEngineState.state.value.status == TunnelStatus.IDLE
        ) {
            runCatching { stopService(Intent(this, XrayVpnService::class.java)) }
        }
        handleIncomingIntent(intent)
        setContent {
            NekoXrayRoot(
                viewModel = viewModel,
                autoConnectRequested = autoConnectSignal.value,
                onConsumeAutoConnect = { autoConnectSignal.value = false },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(com.example.core.widget.NekoWidgetManager.EXTRA_AUTO_CONNECT, false) == true) {
            intent.removeExtra(com.example.core.widget.NekoWidgetManager.EXTRA_AUTO_CONNECT)
            autoConnectSignal.value = true
        }
        val uriString = intent?.dataString ?: return
        viewModel.importText(uriString)
    }
}

@Composable
private fun NekoXrayRoot(
    viewModel: NekoViewModel,
    autoConnectRequested: Boolean = false,
    onConsumeAutoConnect: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startTunnelConfirmed()
        } else {
            scope.launch { snackbarHostState.showSnackbar("VPN permission denied") }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.toggleConnection { vpnIntent ->
            vpnPermissionLauncher.launch(vpnIntent)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val strings = remember(state.settings.appLanguage) {
        resolveAppStrings(state.settings.appLanguage)
    }

    val topTabs = remember(strings) {
        listOf(
            TopTab(NekoRoute.Profiles, strings.navProfiles, Icons.Filled.Dns, Icons.Outlined.Dns, "nav_profiles"),
            TopTab(NekoRoute.Groups, strings.navGroups, Icons.Filled.FolderCopy, Icons.Outlined.FolderCopy, "nav_groups"),
            TopTab(NekoRoute.Routing, strings.navRouting, Icons.AutoMirrored.Filled.AltRoute, Icons.AutoMirrored.Outlined.AltRoute, "nav_routing"),
            TopTab(NekoRoute.Tools, strings.navTools, Icons.Filled.Build, Icons.Outlined.Build, "nav_tools"),
            TopTab(NekoRoute.Settings, strings.navSettings, Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings"),
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isTopLevel = currentDest == null || topTabs.any { currentDest.hasRoute(it.route::class) }

    val onConnectClick: () -> Unit = {
        val isRunning = state.engineState.status == TunnelStatus.CONNECTED ||
            state.engineState.status == TunnelStatus.CONNECTING
        if (!isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.toggleConnection { vpnIntent -> vpnPermissionLauncher.launch(vpnIntent) }
            }
        } else {
            viewModel.toggleConnection { vpnIntent -> vpnPermissionLauncher.launch(vpnIntent) }
        }
    }

    LaunchedEffect(autoConnectRequested, state.profiles) {
        if (autoConnectRequested && state.profiles.isNotEmpty()) {
            onConsumeAutoConnect()
            val isRunning = state.engineState.status == TunnelStatus.CONNECTED ||
                state.engineState.status == TunnelStatus.CONNECTING
            if (!isRunning) {
                onConnectClick()
            }
        }
    }

    val layoutDirection = if (strings.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalAppStrings provides strings,
        LocalLayoutDirection provides layoutDirection,
    ) {
    NekoXrayTheme(darkTheme = state.settings.darkTheme) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 700.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (useRail && isTopLevel) {
                    NavigationRail {
                        topTabs.forEach { tab ->
                            val selected = currentDest?.hasRoute(tab.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag),
                            )
                        }
                    }
                }

                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!useRail && isTopLevel) {
                            NavigationBar {
                                topTabs.forEach { tab ->
                                    val selected = currentDest?.hasRoute(tab.route::class) == true
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(tab.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tab.label,
                                            )
                                        },
                                        label = { Text(tab.label) },
                                        modifier = Modifier.testTag(tab.testTag),
                                    )
                                }
                            }
                        }
                    },
                    floatingActionButton = {
                        if (isTopLevel) {
                            val active = state.engineState.status == TunnelStatus.CONNECTED ||
                                state.engineState.status == TunnelStatus.CONNECTING
                            ExtendedFloatingActionButton(
                                onClick = onConnectClick,
                                icon = {
                                    Icon(
                                        imageVector = if (active) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                        contentDescription = if (active) "Disconnect Xray" else "Connect Xray",
                                    )
                                },
                                text = {
                                    Text(
                                        when (state.engineState.status) {
                                            TunnelStatus.CONNECTED -> strings.disconnect
                                            TunnelStatus.CONNECTING -> "${strings.connecting}..."
                                            TunnelStatus.IDLE, TunnelStatus.ERROR -> strings.connect
                                        }
                                    )
                                },
                                containerColor = if (active) SignalEmerald else MaterialTheme.colorScheme.primary,
                                contentColor = if (active) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.testTag("connect_fab"),
                            )
                        }
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = NekoRoute.Profiles,
                        ) {
                            composable<NekoRoute.Profiles> { backEntry ->
                                ProfilesScreen(
                                    state = state,
                                    onSelectProfile = viewModel::selectProfile,
                                    onSelectGroup = viewModel::selectGroup,
                                    onSearchChange = viewModel::updateSearchQuery,
                                    onImportText = viewModel::importText,
                                    onCreateProfile = { proto ->
                                        navController.navigateSubScreenSafely(
                                            fromEntry = backEntry,
                                            route = NekoRoute.ProfileEditor(profileId = 0L, initialProtocol = proto.name),
                                        )
                                    },
                                    onEditProfile = { id ->
                                        navController.navigateSubScreenSafely(
                                            fromEntry = backEntry,
                                            route = NekoRoute.ProfileEditor(profileId = id),
                                        )
                                    },
                                    onPreviewConfig = { id ->
                                        navController.navigateSubScreenSafely(
                                            fromEntry = backEntry,
                                            route = NekoRoute.ConfigPreview(profileId = id),
                                        )
                                    },
                                    onTestProfile = viewModel::testProfile,
                                    onTestAll = viewModel::testAllVisibleProfiles,
                                    onDuplicateProfile = viewModel::duplicateProfile,
                                    onDeleteProfile = viewModel::deleteProfile,
                                    onCopiedNotice = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    },
                                )
                            }

                            composable<NekoRoute.Groups> {
                                GroupsScreen(
                                    state = state,
                                    onSaveGroup = viewModel::saveGroup,
                                    onDeleteGroup = viewModel::deleteGroup,
                                    onSyncGroup = viewModel::syncSubscription,
                                    onImportText = viewModel::importText,
                                    onNotice = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    },
                                )
                            }

                            composable<NekoRoute.Routing> { backEntry ->
                                RoutingScreen(
                                    state = state,
                                    onToggleRule = viewModel::toggleRoutingRule,
                                    onSaveRule = viewModel::saveRoutingRule,
                                    onDeleteRule = viewModel::deleteRoutingRule,
                                    onOpenAppManager = {
                                        navController.navigateSubScreenSafely(
                                            fromEntry = backEntry,
                                            route = NekoRoute.AppManager,
                                        )
                                    },
                                )
                            }

                            composable<NekoRoute.Tools> {
                                ToolsScreen(
                                    state = state,
                                    onGenerateKeys = viewModel::generateRealityKeys,
                                    onProbeSni = viewModel::probeSniTarget,
                                    onClearLogs = viewModel::clearLogs,
                                    onCopiedNotice = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    },
                                )
                            }

                            composable<NekoRoute.Settings> { backEntry ->
                                SettingsScreen(
                                    state = state,
                                    onUpdateSettings = viewModel::updateSettings,
                                    onOpenAppManager = {
                                        navController.navigateSubScreenSafely(
                                            fromEntry = backEntry,
                                            route = NekoRoute.AppManager,
                                        )
                                    },
                                    onNotice = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    },
                                )
                            }

                            composable<NekoRoute.AppManager> { backEntry ->
                                AppManagerScreen(
                                    settings = state.settings,
                                    onUpdateSettings = viewModel::updateSettings,
                                    onTogglePackage = viewModel::togglePerAppPackage,
                                    onBack = { navController.popBackStackSafely(backEntry) },
                                    onNotice = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    },
                                )
                            }

                            composable<NekoRoute.ProfileEditor> { backEntry ->
                                val route = backEntry.toRoute<NekoRoute.ProfileEditor>()
                                ProfileEditorScreen(
                                    profileId = route.profileId,
                                    initialProtocol = route.initialProtocol,
                                    defaultGroupId = state.selectedGroupId,
                                    allProfiles = state.profiles,
                                    loadProfile = viewModel::loadProfile,
                                    onSave = { edited ->
                                        if (backEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                            viewModel.saveProfile(edited) {
                                                navController.popBackStackSafely(backEntry)
                                            }
                                        }
                                    },
                                    onPreviewConfig = { edited ->
                                        if (backEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                            viewModel.saveProfile(edited) { savedId ->
                                                navController.navigateSubScreenSafely(
                                                    fromEntry = backEntry,
                                                    route = NekoRoute.ConfigPreview(savedId),
                                                )
                                            }
                                        }
                                    },
                                    onBack = { navController.popBackStackSafely(backEntry) },
                                )
                            }

                            composable<NekoRoute.ConfigPreview> { backEntry ->
                                val route = backEntry.toRoute<NekoRoute.ConfigPreview>()
                                ConfigPreviewScreen(
                                    profileId = route.profileId,
                                    loadProfile = viewModel::loadProfile,
                                    buildConfigJson = viewModel::buildConfigJson,
                                    validateProfile = viewModel::validateProfile,
                                    onCopiedNotice = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    },
                                    onBack = { navController.popBackStackSafely(backEntry) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

internal fun canPopBackStackSafely(
    entryLifecycleState: Lifecycle.State?,
    hasPreviousEntry: Boolean,
): Boolean {
    if (!hasPreviousEntry) return false
    if (entryLifecycleState != null && !entryLifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        return false
    }
    return true
}

private fun NavHostController.popBackStackSafely(fromEntry: NavBackStackEntry? = null): Boolean {
    if (!canPopBackStackSafely(
            entryLifecycleState = fromEntry?.lifecycle?.currentState,
            hasPreviousEntry = previousBackStackEntry != null,
        )
    ) {
        return false
    }
    return popBackStack()
}

private fun NavHostController.navigateSubScreenSafely(
    fromEntry: NavBackStackEntry?,
    route: NekoRoute,
) {
    if (fromEntry != null && !fromEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        return
    }
    navigate(route) {
        launchSingleTop = true
    }
}
