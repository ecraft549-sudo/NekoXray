package com.example.core.xray

import android.content.Context
import libv2ray.Libv2ray
import java.io.File

object XrayAssetHelper {
    private val ASSET_FILES = listOf(
        "geoip.dat",
        "geosite.dat",
        "geoip-only-cn-private.dat",
    )

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context): String {
        val dir = context.filesDir
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    extractAssetsIfNeeded(context, dir)
                    Libv2ray.initCoreEnv(dir.absolutePath, "")
                    initialized = true
                }
            }
        }
        return dir.absolutePath
    }

    private fun extractAssetsIfNeeded(context: Context, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val assetManager = context.assets
        val available = runCatching { assetManager.list("")?.toSet().orEmpty() }.getOrDefault(emptySet())
        for (fileName in ASSET_FILES) {
            if (fileName !in available) continue
            val dest = File(targetDir, fileName)
            if (dest.exists() && dest.length() > 0L) continue
            runCatching {
                assetManager.open(fileName).use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
