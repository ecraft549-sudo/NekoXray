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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.model.ProxyGroup
import com.example.ui.components.QrScannerDialog
import com.example.ui.components.TagPill
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.NekoUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GroupsScreen(
    state: NekoUiState,
    onSaveGroup: (ProxyGroup) -> Unit,
    onDeleteGroup: (Long) -> Unit,
    onSyncGroup: (ProxyGroup) -> Unit,
    onImportText: (String) -> Unit = {},
    onNotice: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    var editingGroup by remember { mutableStateOf<ProxyGroup?>(null) }
    var showQrScanner by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.groupsTitle, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = strings.groupsSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { showQrScanner = true },
                modifier = Modifier.testTag("groups_scan_qr_button"),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = strings.scanQrCode)
            }
            FilledTonalButton(
                onClick = { editingGroup = ProxyGroup(name = "", isSubscription = true) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("add_group_button"),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.newBtn, maxLines = 1, softWrap = false)
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(state.groups, key = { it.id }) { group ->
                val count = state.profiles.count { it.groupId == group.id }
                val syncing = group.id in state.syncingGroupIds

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TagPill(
                                        text = if (group.isSubscription) strings.subscriptionTag else strings.localTag,
                                        containerColor = if (group.isSubscription) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        contentColor = if (group.isSubscription) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Text(
                                    text = "$count" + (group.lastUpdatedAt?.let {
                                        " · ${SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(it))}"
                                    } ?: ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            if (group.isSubscription) {
                                IconButton(
                                    onClick = { onSyncGroup(group) },
                                    enabled = !syncing,
                                    modifier = Modifier.testTag("sync_group_${group.id}"),
                                ) {
                                    if (syncing) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Sync, contentDescription = "Update subscription")
                                    }
                                }
                            }

                            IconButton(onClick = { editingGroup = group }) {
                                Icon(Icons.Default.Edit, contentDescription = strings.edit)
                            }

                            if (group.id != 1L) {
                                IconButton(onClick = { onDeleteGroup(group.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = strings.delete,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }

                        if (group.isSubscription && group.subscriptionUrl.isNotBlank()) {
                            Text(
                                text = group.subscriptionUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (group.totalBytes > 0L) {
                            val used = group.uploadBytes + group.downloadBytes
                            val progress = (used.toFloat() / group.totalBytes.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${formatBytes(used)} / ${formatBytes(group.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    editingGroup?.let { target ->
        GroupEditDialog(
            group = target,
            onDismiss = { editingGroup = null },
            onSave = {
                onSaveGroup(it)
                editingGroup = null
            },
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onQrScanned = { decoded -> onImportText(decoded) },
            onError = onNotice,
            onDismiss = { showQrScanner = false },
        )
    }
}

@Composable
private fun GroupEditDialog(
    group: ProxyGroup,
    onDismiss: () -> Unit,
    onSave: (ProxyGroup) -> Unit,
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf(group.name) }
    var isSub by remember { mutableStateOf(group.isSubscription) }
    var url by remember { mutableStateOf(group.subscriptionUrl) }
    var userAgent by remember { mutableStateOf(group.userAgent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (group.id == 0L) strings.newGroupTitle else strings.editGroupTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.groupNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(strings.subscriptionUrlToggle)
                    Switch(checked = isSub, onCheckedChange = { isSub = it })
                }
                if (isSub) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        label = { Text(strings.customUserAgentLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        group.copy(
                            name = name.ifBlank { "Subscription" },
                            isSubscription = isSub,
                            subscriptionUrl = url.trim(),
                            userAgent = userAgent.trim(),
                        )
                    )
                },
                enabled = name.isNotBlank() && (!isSub || url.isNotBlank()),
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
