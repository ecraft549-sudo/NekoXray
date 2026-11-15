package com.example.ui.screens

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.model.PerAppProxyMode
import com.example.core.model.XraySettings
import com.example.ui.components.TagPill
import com.example.ui.i18n.LocalAppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PerAppImportResult(
    val mode: PerAppProxyMode,
    val packages: Set<String>,
    val explicitBypassFlag: Boolean?,
)

object PerAppClipboardCodec {
    private val packageRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /**
     * Formats selected Per-App Proxy apps using the standard NekoBox clipboard pattern:
     * Line 1: "true" (Bypass Selected) or "false" (Only Selected)
     * Lines 2..N: sorted package names, one per line.
     */
    fun format(mode: PerAppProxyMode, packages: Set<String>): String {
        val bypassFlag = mode == PerAppProxyMode.BYPASS_SELECTED
        val sorted = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        return buildString {
            append(if (bypassFlag) "true" else "false")
            sorted.forEach { pkg ->
                append('\n')
                append(pkg)
            }
        }
    }

    /**
     * Parses NekoBox Per-App Proxy clipboard text (`false\npkg1\npkg2...` or `true\npkg1...`),
     * or a plain list of package names without a leading boolean flag.
     */
    fun parse(
        raw: String,
        fallbackMode: PerAppProxyMode = PerAppProxyMode.PROXY_SELECTED,
        selfPackage: String = "",
    ): PerAppImportResult {
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.isEmpty()) {
            val safeFallback = if (fallbackMode == PerAppProxyMode.ALL) PerAppProxyMode.PROXY_SELECTED else fallbackMode
            return PerAppImportResult(mode = safeFallback, packages = emptySet(), explicitBypassFlag = null)
        }

        val first = lines.first().lowercase()
        val (explicitFlag, bodyLines) = when (first) {
            "false" -> false to lines.drop(1)
            "true" -> true to lines.drop(1)
            else -> null to lines
        }

        val targetMode = when (explicitFlag) {
            false -> PerAppProxyMode.PROXY_SELECTED
            true -> PerAppProxyMode.BYPASS_SELECTED
            null -> if (fallbackMode == PerAppProxyMode.ALL) PerAppProxyMode.PROXY_SELECTED else fallbackMode
        }

        val parsedPackages = bodyLines
            .flatMap { line -> line.split(',', ';', ' ', '\t') }
            .map { it.trim() }
            .filter { token ->
                token.isNotEmpty() &&
                    token != selfPackage &&
                    packageRegex.matches(token)
            }
            .toSet()

        return PerAppImportResult(
            mode = targetMode,
            packages = parsedPackages,
            explicitBypassFlag = explicitFlag,
        )
    }
}

private data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val icon: ImageBitmap?,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppManagerScreen(
    settings: XraySettings,
    onUpdateSettings: ((XraySettings) -> XraySettings) -> Unit,
    onTogglePackage: (String) -> Unit,
    onBack: () -> Unit,
    onNotice: (String) -> Unit = {},
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var apps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var manualPkg by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            loadLaunchableApps(context.packageManager, context.packageName)
        }
        isLoading = false
    }

    // Merge manually added/imported packages that may not have launcher icons
    var resolvedCustomApps by remember { mutableStateOf<Map<String, InstalledAppItem>>(emptyMap()) }
    LaunchedEffect(settings.perAppPackages, apps) {
        val knownPkgs = apps.mapTo(HashSet()) { it.packageName }
        val unknown = settings.perAppPackages.filter { it !in knownPkgs }
        resolvedCustomApps = withContext(Dispatchers.IO) {
            unknown.associateWith { pkg -> resolveSinglePackage(context.packageManager, pkg) }
        }
    }

    val allDisplayApps = remember(apps, resolvedCustomApps, settings.perAppPackages, searchQuery, showSystemApps) {
        val knownPkgs = apps.mapTo(HashSet()) { it.packageName }
        val customEntries = settings.perAppPackages
            .filter { it !in knownPkgs }
            .map { pkg ->
                resolvedCustomApps[pkg] ?: InstalledAppItem(
                    packageName = pkg,
                    label = pkg,
                    isSystem = false,
                    icon = null,
                )
            }
        (customEntries + apps)
            .filter { item ->
                val sysOk = showSystemApps || !item.isSystem || (item.packageName in settings.perAppPackages)
                val qOk = searchQuery.isBlank() ||
                    item.label.contains(searchQuery, ignoreCase = true) ||
                    item.packageName.contains(searchQuery, ignoreCase = true)
                sysOk && qOk
            }
            .sortedWith(
                compareByDescending<InstalledAppItem> { it.packageName in settings.perAppPackages }
                    .thenBy { it.label.lowercase() }
            )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(strings.perAppTitle)
                        Text(
                            text = "${settings.perAppPackages.size} · ${strings.perAppModeName(settings.perAppMode)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("app_manager_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showManualDialog = true },
                        modifier = Modifier.testTag("add_manual_package_button"),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add package manually")
                    }
                    IconButton(
                        onClick = {
                            val visiblePkgs = allDisplayApps.map { it.packageName }.toSet()
                            onUpdateSettings { it.copy(perAppPackages = it.perAppPackages + visiblePkgs) }
                        },
                        enabled = settings.perAppMode != PerAppProxyMode.ALL && allDisplayApps.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = "Select all visible")
                    }
                    IconButton(
                        onClick = { onUpdateSettings { it.copy(perAppPackages = emptySet()) } },
                        enabled = settings.perAppPackages.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.ClearAll, contentDescription = "Clear selection")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // Mode selector + Copy / Paste pattern actions
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PerAppProxyMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.perAppMode == mode,
                                onClick = { onUpdateSettings { it.copy(perAppMode = mode) } },
                                label = { Text(strings.perAppModeName(mode), maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("per_app_mode_${mode.name}"),
                            )
                        }
                    }
                    Text(
                        text = strings.perAppModeDesc(settings.perAppMode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val formatted = PerAppClipboardCodec.format(
                                    mode = settings.perAppMode,
                                    packages = settings.perAppPackages,
                                )
                                clipboard.setText(AnnotatedString(formatted))
                                val flagLabel = if (settings.perAppMode == PerAppProxyMode.BYPASS_SELECTED) "true" else "false"
                                onNotice("${settings.perAppPackages.size} ($flagLabel)")
                            },
                            enabled = settings.perAppPackages.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("copy_apps_button"),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.copyList, maxLines = 1, softWrap = false)
                        }

                        FilledTonalButton(
                            onClick = {
                                val clip = clipboard.getText()?.text.orEmpty()
                                importText = clip
                                showImportDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("paste_apps_button"),
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.pasteList, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(strings.searchAppsPlaceholder) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = strings.close)
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_apps_input"),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.showSystemApps,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = showSystemApps,
                    onCheckedChange = { showSystemApps = it },
                    modifier = Modifier.testTag("toggle_system_apps"),
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (allDisplayApps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = strings.noMatchingApps,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(allDisplayApps, key = { it.packageName }) { app ->
                        val checked = app.packageName in settings.perAppPackages
                        val enabled = settings.perAppMode != PerAppProxyMode.ALL

                        Card(
                            onClick = { if (enabled) onTogglePackage(app.packageName) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (checked && enabled) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("app_item_${app.packageName}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = app.label,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Apps,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (app.isSystem) {
                                            TagPill(
                                                text = "SYS",
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onTogglePackage(app.packageName) },
                                    enabled = enabled,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text(strings.addPackageTitle) },
            text = {
                OutlinedTextField(
                    value = manualPkg,
                    onValueChange = { manualPkg = it },
                    label = { Text(strings.packageIdLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pkg = manualPkg.trim()
                        if (pkg.isNotEmpty()) {
                            onUpdateSettings { s ->
                                s.copy(
                                    perAppMode = if (s.perAppMode == PerAppProxyMode.ALL) PerAppProxyMode.PROXY_SELECTED else s.perAppMode,
                                    perAppPackages = s.perAppPackages + pkg,
                                )
                            }
                        }
                        manualPkg = ""
                        showManualDialog = false
                    },
                    enabled = manualPkg.isNotBlank(),
                ) {
                    Text(strings.add)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) { Text(strings.cancel) }
            },
        )
    }

    if (showImportDialog) {
        val parsed = remember(importText, settings.perAppMode) {
            PerAppClipboardCodec.parse(
                raw = importText,
                fallbackMode = settings.perAppMode,
                selfPackage = context.packageName,
            )
        }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(strings.importAppListTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.importAppListDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text("false\ncom.android.chrome\norg.telegram.messenger") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_apps_input"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val flagText = when (parsed.explicitBypassFlag) {
                            false -> "false · ${strings.perAppModeName(PerAppProxyMode.PROXY_SELECTED)}"
                            true -> "true · ${strings.perAppModeName(PerAppProxyMode.BYPASS_SELECTED)}"
                            null -> strings.perAppModeName(parsed.mode)
                        }
                        Text(
                            text = "${parsed.packages.size} · $flagText",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(
                            onClick = {
                                importText = clipboard.getText()?.text.orEmpty()
                            },
                        ) {
                            Text(strings.fromClipboard)
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onUpdateSettings { s ->
                                s.copy(
                                    perAppMode = parsed.mode,
                                    perAppPackages = s.perAppPackages + parsed.packages,
                                )
                            }
                            onNotice("${parsed.packages.size} · ${strings.perAppModeName(parsed.mode)}")
                            showImportDialog = false
                        },
                        enabled = parsed.packages.isNotEmpty(),
                        modifier = Modifier.testTag("import_apps_merge_button"),
                    ) {
                        Text(strings.merge)
                    }
                    Button(
                        onClick = {
                            onUpdateSettings { s ->
                                s.copy(
                                    perAppMode = parsed.mode,
                                    perAppPackages = parsed.packages,
                                )
                            }
                            onNotice("${parsed.packages.size} · ${strings.perAppModeName(parsed.mode)}")
                            showImportDialog = false
                        },
                        enabled = parsed.packages.isNotEmpty() || parsed.explicitBypassFlag != null,
                        modifier = Modifier.testTag("import_apps_replace_button"),
                    ) {
                        Text(strings.replace)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}

private fun resolveSinglePackage(pm: PackageManager, pkg: String): InstalledAppItem =
    runCatching {
        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
        val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
        val iconBmp = runCatching { drawableToImageBitmap(pm.getApplicationIcon(appInfo)) }.getOrNull()
        InstalledAppItem(packageName = pkg, label = label, isSystem = isSys, icon = iconBmp)
    }.getOrElse {
        InstalledAppItem(packageName = pkg, label = pkg, isSystem = false, icon = null)
    }

private val ESSENTIAL_SYSTEM_PACKAGES = listOf(
    "com.android.vending",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.android.providers.downloads",
    "com.android.providers.downloads.ui",
)

private fun loadLaunchableApps(pm: PackageManager, selfPackage: String): List<InstalledAppItem> {
    val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcherIntent, 0)
    }

    val seen = HashSet<String>()
    val result = mutableListOf<InstalledAppItem>()
    for (ri in resolveList) {
        val pkg = ri.activityInfo?.packageName ?: continue
        if (pkg == selfPackage || !seen.add(pkg)) continue
        val appInfo = ri.activityInfo.applicationInfo
        val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
        val iconBmp = runCatching { drawableToImageBitmap(ri.loadIcon(pm)) }.getOrNull()
        result += InstalledAppItem(
            packageName = pkg,
            label = label,
            isSystem = isSys,
            icon = iconBmp,
        )
    }

    // Also include essential non-launcher system packages (e.g., DownloadManager, Play Services) if installed
    for (sysPkg in ESSENTIAL_SYSTEM_PACKAGES) {
        if (sysPkg == selfPackage || sysPkg in seen) continue
        runCatching {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(sysPkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(sysPkg, 0)
            }
            seen.add(sysPkg)
            val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(sysPkg)
            val iconBmp = runCatching { drawableToImageBitmap(pm.getApplicationIcon(appInfo)) }.getOrNull()
            result += InstalledAppItem(
                packageName = sysPkg,
                label = label,
                isSystem = isSys,
                icon = iconBmp,
            )
        }
    }
    return result
}

private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bmp.asImageBitmap()
}
