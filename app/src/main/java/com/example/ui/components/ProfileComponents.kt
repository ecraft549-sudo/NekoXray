package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.model.FlowType
import com.example.core.model.ProxyProfile
import com.example.core.model.ProxyProtocol
import com.example.core.model.SecurityType
import com.example.core.model.SshAuthType
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalEmerald

@Composable
fun TagPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProxyProfileCard(
    profile: ProxyProfile,
    isSelected: Boolean,
    isTesting: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onPreviewConfig: () -> Unit,
    onShareQr: () -> Unit,
    onTest: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val strings = LocalAppStrings.current

    val border = if (isSelected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }

    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = border,
        modifier = modifier
            .fillMaxWidth()
            .testTag("profile_card_${profile.id}"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            if (profile.protocol == ProxyProtocol.SSH) {
                                val user = profile.username.ifBlank { "root" }
                                append("$user@${profile.serverAddress}:${profile.serverPort}")
                            } else {
                                append("${profile.serverAddress}:${profile.serverPort}")
                                if (profile.sni.isNotBlank() && profile.sni != profile.serverAddress) {
                                    append(" · SNI ${profile.sni}")
                                }
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    LatencyBadge(
                        latencyMs = profile.latencyMs,
                        tlsLatencyMs = profile.tlsLatencyMs,
                    )
                }

                Spacer(Modifier.width(4.dp))

                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_profile_${profile.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = strings.edit,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("profile_menu_${profile.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = strings.edit,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.testLatency) },
                            leadingIcon = { Icon(Icons.Default.Speed, null) },
                            onClick = {
                                menuExpanded = false
                                onTest()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.previewXrayJson) },
                            leadingIcon = { Icon(Icons.Default.Code, null) },
                            onClick = {
                                menuExpanded = false
                                onPreviewConfig()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.shareQrUri) },
                            leadingIcon = { Icon(Icons.Default.QrCode2, null) },
                            onClick = {
                                menuExpanded = false
                                onShareQr()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.duplicate) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.delete, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TagPill(
                    text = profile.protocol.displayName,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                if (profile.security == SecurityType.REALITY) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = "REALITY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                } else if (profile.security == SecurityType.TLS) {
                    TagPill(
                        text = "TLS",
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                        contentColor = MaterialTheme.colorScheme.tertiary,
                    )
                }

                if (profile.protocol == ProxyProtocol.SSH) {
                    val authBadge = when (profile.sshAuthType) {
                        SshAuthType.PRIVATE_KEY -> "SSH-KEY"
                        SshAuthType.PASSWORD -> "SSH-PASS"
                        SshAuthType.NONE -> "SSH-NONE"
                    }
                    TagPill(
                        text = authBadge,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (profile.sshPublicKey.isNotBlank()) {
                        TagPill(
                            text = "HOST-PIN",
                            containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                            contentColor = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                } else {
                    TagPill(
                        text = profile.transport.wireValue.uppercase(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (profile.flow != FlowType.NONE) {
                    TagPill(
                        text = if (profile.flow == FlowType.VISION) "VISION" else "VISION-443",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                }

                if (profile.fingerprint.isNotBlank() && profile.security != SecurityType.NONE) {
                    TagPill(
                        text = "fp:${profile.fingerprint}",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyBadge(latencyMs: Int?, tlsLatencyMs: Int?) {
    if (latencyMs == null) return
    val color = when {
        latencyMs < 150 -> SignalEmerald
        latencyMs < 350 -> SignalAmber
        else -> SignalCrimson
    }
    val label = if (tlsLatencyMs != null) "${latencyMs}ms · TLS ${tlsLatencyMs}ms" else "${latencyMs}ms"
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
