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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.example.core.model.RouteAction
import com.example.core.model.RoutingRule
import com.example.ui.components.TagPill
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalEmerald
import com.example.ui.viewmodel.NekoUiState

@Composable
fun RoutingScreen(
    state: NekoUiState,
    onToggleRule: (RoutingRule) -> Unit,
    onSaveRule: (RoutingRule) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onOpenAppManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    var editingRule by remember { mutableStateOf<RoutingRule?>(null) }

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
                Text(strings.routingTitle, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${strings.strategyLabel}: ${state.settings.domainStrategy} · ${strings.bypassLanBadge}: ${if (state.settings.bypassLan) "ON" else "OFF"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = { editingRule = RoutingRule(name = "", priority = state.routingRules.size + 1) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("add_rule_button"),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.ruleBtn, maxLines = 1, softWrap = false)
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            onClick = onOpenAppManager,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("routing_open_app_manager"),
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
                    Text(strings.perAppProxyCardTitle, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${strings.perAppModeName(state.settings.perAppMode)} (${state.settings.perAppPackages.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(state.routingRules, key = { it.id }) { rule ->
                val (badgeBg, badgeFg) = when (rule.action) {
                    RouteAction.PROXY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                    RouteAction.DIRECT -> SignalEmerald.copy(alpha = 0.16f) to SignalEmerald
                    RouteAction.BLOCK -> SignalCrimson.copy(alpha = 0.16f) to SignalCrimson
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(rule.name, style = MaterialTheme.typography.titleMedium)
                                TagPill(strings.routeActionName(rule.action).uppercase(), badgeBg, badgeFg)
                            }
                            Spacer(Modifier.height(4.dp))
                            val summary = buildList {
                                if (rule.domains.isNotBlank()) add("domain: ${rule.domains}")
                                if (rule.ips.isNotBlank()) add("ip: ${rule.ips}")
                                if (rule.ports.isNotBlank()) add("port: ${rule.ports}")
                                if (rule.protocols.isNotBlank()) add("proto: ${rule.protocols}")
                            }.joinToString(" · ")
                            Text(
                                text = summary.ifBlank { strings.emptyMatcher },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        IconButton(onClick = { editingRule = rule }) {
                            Icon(Icons.Default.Edit, contentDescription = strings.edit)
                        }
                        IconButton(onClick = { onDeleteRule(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = strings.delete, tint = MaterialTheme.colorScheme.error)
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { onToggleRule(rule) },
                            modifier = Modifier.testTag("rule_switch_${rule.id}"),
                        )
                    }
                }
            }
        }
    }

    editingRule?.let { target ->
        RuleEditDialog(
            rule = target,
            onDismiss = { editingRule = null },
            onSave = {
                onSaveRule(it)
                editingRule = null
            },
        )
    }
}

@Composable
private fun RuleEditDialog(
    rule: RoutingRule,
    onDismiss: () -> Unit,
    onSave: (RoutingRule) -> Unit,
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf(rule.name) }
    var domains by remember { mutableStateOf(rule.domains) }
    var ips by remember { mutableStateOf(rule.ips) }
    var ports by remember { mutableStateOf(rule.ports) }
    var protocols by remember { mutableStateOf(rule.protocols) }
    var action by remember { mutableStateOf(rule.action) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) strings.newRuleTitle else strings.editRuleTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.ruleNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RouteAction.entries.forEach { act ->
                        FilterChip(
                            selected = action == act,
                            onClick = { action = act },
                            label = { Text(strings.routeActionName(act)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = domains,
                    onValueChange = { domains = it },
                    label = { Text(strings.domainsLabel) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rule_domains_input"),
                )
                OutlinedTextField(
                    value = ips,
                    onValueChange = { ips = it },
                    label = { Text(strings.ipsLabel) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ports,
                        onValueChange = { ports = it },
                        label = { Text(strings.portsLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = protocols,
                        onValueChange = { protocols = it },
                        label = { Text(strings.protocolsLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        rule.copy(
                            name = name.ifBlank { "Custom Rule" },
                            domains = domains.trim(),
                            ips = ips.trim(),
                            ports = ports.trim(),
                            protocols = protocols.trim(),
                            action = action,
                        )
                    )
                },
                enabled = domains.isNotBlank() || ips.isNotBlank() || ports.isNotBlank() || protocols.isNotBlank(),
                modifier = Modifier.testTag("save_rule_button"),
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}
