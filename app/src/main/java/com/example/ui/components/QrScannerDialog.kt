package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size as AndroidSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.i18n.LocalAppStrings
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object QrBitmapDecoder {
    private val decodeHints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun decodeFromYPlane(yBytes: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || yBytes.size < width * height) return null
        val source = PlanarYUVLuminanceSource(
            yBytes,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val reader = QRCodeReader()
        return runCatching {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), decodeHints).text
        }.getOrNull() ?: runCatching {
            reader.reset()
            reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), decodeHints).text
        }.getOrNull()
    }

    fun decodeFromBitmap(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val reader = QRCodeReader()
        return runCatching {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), decodeHints).text
        }.getOrNull() ?: runCatching {
            reader.reset()
            reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), decodeHints).text
        }.getOrNull()
    }

    fun extractYBytes(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        buffer.rewind()

        if (pixelStride == 1 && rowStride == width) {
            val out = ByteArray(width * height)
            buffer.get(out)
            return out
        }

        val out = ByteArray(width * height)
        val rowBuf = ByteArray(rowStride)
        for (row in 0 until height) {
            val remaining = buffer.remaining()
            val toRead = minOf(rowStride, remaining)
            if (toRead <= 0) break
            buffer.get(rowBuf, 0, toRead)
            val dstOffset = row * width
            if (pixelStride == 1) {
                System.arraycopy(rowBuf, 0, out, dstOffset, minOf(width, toRead))
            } else {
                var col = 0
                var srcIdx = 0
                while (col < width && srcIdx < toRead) {
                    out[dstOffset + col] = rowBuf[srcIdx]
                    col++
                    srcIdx += pixelStride
                }
            }
        }
        return out
    }
}

@Composable
fun QrScannerDialog(
    onQrScanned: (String) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val hasScanned = remember { AtomicBoolean(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var torchEnabled by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                decodeQrFromGalleryUri(context, uri)
            }
            if (!decoded.isNullOrBlank()) {
                if (hasScanned.compareAndSet(false, true)) {
                    onQrScanned(decoded)
                    onDismiss()
                }
            } else {
                onError(strings.qrNotFoundInImage)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Color.Black,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (hasCameraPermission) {
                    CameraQrPreview(
                        onCameraReady = { activeCamera = it },
                        onDecoded = { text ->
                            if (hasScanned.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(context).execute {
                                    onQrScanned(text)
                                    onDismiss()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Viewfinder reticle
                    Box(
                        modifier = Modifier
                            .size(270.dp)
                            .align(Alignment.Center)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(20.dp),
                            ),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = strings.scannerPermissionPrompt,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                modifier = Modifier.testTag("grant_camera_permission_button"),
                            ) {
                                Text(strings.allowCameraBtn)
                            }
                            OutlinedButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.testTag("pick_qr_gallery_fallback_button"),
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.fromGalleryBtn)
                            }
                        }
                    }
                }

                // Top bar overlay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = strings.scannerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .testTag("close_qr_scanner_button"),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = strings.close, tint = Color.White)
                    }
                }

                // Bottom controls overlay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasCameraPermission && activeCamera?.cameraInfo?.hasFlashUnit() == true) {
                        FilledTonalButton(
                            onClick = {
                                val next = !torchEnabled
                                activeCamera?.cameraControl?.enableTorch(next)
                                torchEnabled = next
                            },
                            modifier = Modifier.testTag("toggle_torch_button"),
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Default.FlashOff else Icons.Default.FlashOn,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (torchEnabled) strings.torchOffBtn else strings.torchOnBtn)
                        }
                    }

                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("scan_qr_from_gallery_button"),
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.scanImageBtn)
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            runCatching { activeCamera?.cameraControl?.enableTorch(false) }
        }
    }
}

@Composable
private fun CameraQrPreview(
    onCameraReady: (Camera) -> Unit,
    onDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                AndroidSize(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(analyzerExecutor) { image ->
                        try {
                            val yBytes = QrBitmapDecoder.extractYBytes(image)
                            val decoded = QrBitmapDecoder.decodeFromYPlane(
                                yBytes = yBytes,
                                width = image.width,
                                height = image.height,
                            )
                            if (!decoded.isNullOrBlank()) {
                                onDecoded(decoded)
                            }
                        } finally {
                            image.close()
                        }
                    }

                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    onCameraReady(camera)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private fun decodeQrFromGalleryUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val rawBitmap = BitmapFactory.decodeStream(stream) ?: return null
        val maxDim = 1600
        val scaled = if (rawBitmap.width > maxDim || rawBitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(rawBitmap.width, rawBitmap.height).toFloat()
            Bitmap.createScaledBitmap(
                rawBitmap,
                (rawBitmap.width * scale).toInt().coerceAtLeast(1),
                (rawBitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            rawBitmap
        }
        val result = QrBitmapDecoder.decodeFromBitmap(scaled)
        if (scaled != rawBitmap) scaled.recycle()
        rawBitmap.recycle()
        result
    }
}.getOrNull()
