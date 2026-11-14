package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.core.model.ConfigValidationIssue
import com.example.core.model.ProxyProfile
import com.example.core.model.XRAY_CORE_VERSION
import com.example.core.parser.UriParser
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalEmerald

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigPreviewScreen(
    profileId: Long,
    loadProfile: suspend (Long) -> ProxyProfile?,
    buildConfigJson: (ProxyProfile) -> String,
    validateProfile: (ProxyProfile) -> List<ConfigValidationIssue>,
    onCopiedNotice: (String) -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val strings = LocalAppStrings.current
    var profile by remember { mutableStateOf<ProxyProfile?>(null) }

    LaunchedEffect(profileId) {
        profile = loadProfile(profileId)
    }

    val p = profile
    val jsonText = remember(p) { p?.let(buildConfigJson).orEmpty() }
    val issues = remember(p) { p?.let(validateProfile).orEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Xray $XRAY_CORE_VERSION Config") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("config_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                    }
                },
                actions = {
                    if (p != null) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(UriParser.toShareUri(p)))
                                onCopiedNotice(strings.copiedUriNotice)
                            },
                            modifier = Modifier.testTag("config_copy_uri_button"),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = strings.copyUriBtn)
                        }
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(jsonText))
                                onCopiedNotice("Xray config.json")
                            },
                            modifier = Modifier.testTag("config_copy_json_button"),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = strings.previewXrayJson)
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (p == null) {
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
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (issues.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = SignalEmerald, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${strings.validConfigBanner} ($XRAY_CORE_VERSION)",
                                style = MaterialTheme.typography.titleSmall,
                                color = SignalEmerald,
                            )
                        }
                    } else {
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

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = jsonText,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(14.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
