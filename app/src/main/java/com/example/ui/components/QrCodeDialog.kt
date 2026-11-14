package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.i18n.LocalAppStrings
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode
import kotlin.math.ceil

data class QrMatrixResult(
    val matrixWidth: Int,
    val matrixHeight: Int,
    val modules: BooleanArray,
    val versionNumber: Int,
    val errorCorrectionLevel: ErrorCorrectionLevel,
)

object QrMatrixEncoder {
    /**
     * Encodes arbitrarily large proxy URIs (including ~2200+ byte VLESS ML-KEM-768 + pqv URIs)
     * into an exact-module QR matrix without blurry bitmap pre-scaling.
     * Automatically uses ErrorCorrectionLevel.L for long payloads (> 400 chars) so Version 40
     * supports up to 2,953 UTF-8 bytes cleanly.
     */
    fun encode(content: String): Result<QrMatrixResult> = runCatching {
        val ecc = if (content.length > 400) ErrorCorrectionLevel.L else ErrorCorrectionLevel.M
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        val qr: QRCode = Encoder.encode(content, ecc, hints)
        val byteMatrix = qr.matrix ?: error("QR matrix generation returned null")
        val w = byteMatrix.width
        val h = byteMatrix.height
        val flat = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                flat[y * w + x] = byteMatrix.get(x, y).toInt() == 1
            }
        }
        QrMatrixResult(
            matrixWidth = w,
            matrixHeight = h,
            modules = flat,
            versionNumber = qr.version?.versionNumber ?: 0,
            errorCorrectionLevel = ecc,
        )
    }
}

@Composable
fun QrCodeDialog(
    title: String,
    uri: String,
    onDismiss: () -> Unit,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val strings = LocalAppStrings.current
    val encodedResult = remember(uri) { QrMatrixEncoder.encode(uri) }
    var isFullscreen by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (isFullscreen) {
            Surface(
                color = Color.White,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { isFullscreen = false },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val qrData = encodedResult.getOrNull()
                    if (qrData != null) {
                        VectorQrCanvas(
                            qr = qrData,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                        )
                    }
                    IconButton(
                        onClick = { isFullscreen = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "Exit fullscreen QR",
                            tint = Color.Black,
                        )
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 520.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val qrData = encodedResult.getOrNull()
                            if (qrData != null) {
                                Text(
                                    text = "QR v${qrData.versionNumber} (${qrData.matrixWidth}×${qrData.matrixHeight}) · ECC ${qrData.errorCorrectionLevel.name} · ${uri.length} chars",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (encodedResult.isSuccess) {
                            IconButton(
                                onClick = { isFullscreen = true },
                                modifier = Modifier.testTag("qr_fullscreen_button"),
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen QR")
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = strings.close)
                        }
                    }

                    val qrData = encodedResult.getOrNull()
                    if (qrData != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .clickable { isFullscreen = true }
                                .padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            VectorQrCanvas(
                                qr = qrData,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            text = strings.tapQrFullscreen,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(16.dp),
                        ) {
                            Text(
                                text = "${strings.qrTooLarge} (${uri.length})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }

                    Text(
                        text = uri,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(strings.close)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(uri))
                                onCopied()
                                onDismiss()
                            },
                            modifier = Modifier.testTag("qr_copy_uri_button"),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.copyUriBtn)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VectorQrCanvas(
    qr: QrMatrixResult,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawRect(color = Color.White)
        val quietModules = 2
        val totalModules = qr.matrixWidth + quietModules * 2
        if (totalModules <= 0) return@Canvas
        val side = minOf(size.width, size.height)
        val offsetX = (size.width - side) / 2f
        val offsetY = (size.height - side) / 2f
        val cell = side / totalModules.toFloat()
        // Slight sub-pixel overlap prevents hairline gaps between adjacent dark modules
        val drawCell = ceil(cell.toDouble()).toFloat().coerceAtLeast(cell + 0.35f)

        for (y in 0 until qr.matrixHeight) {
            val rowOff = y * qr.matrixWidth
            val py = offsetY + (y + quietModules) * cell
            for (x in 0 until qr.matrixWidth) {
                if (qr.modules[rowOff + x]) {
                    val px = offsetX + (x + quietModules) * cell
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(px, py),
                        size = Size(drawCell, drawCell),
                    )
                }
            }
        }
    }
}
