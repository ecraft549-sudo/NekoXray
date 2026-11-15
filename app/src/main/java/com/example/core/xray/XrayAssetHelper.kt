package com.example.core.xray

import android.content.Context
import com.example.core.vpn.XrayEngineState
import libv2ray.Libv2ray
import java.io.File

data class GeoAssetStatus(
    val assetDir: String,
    val hasGeoIp: Boolean,
    val hasGeoSite: Boolean,
    val geoIpBytes: Long,
    val geoSiteBytes: Long,
) {
    val hasAllRequired: Boolean
        get() = hasGeoIp && hasGeoSite
}

object XrayAssetHelper {
    private val ASSET_FILES = listOf(
        "geoip.dat",
        "geosite.dat",
        "geoip-only-cn-private.dat",
    )

    @Volatile
    private var initialized = false

    @Volatile
    private var lastStatus: GeoAssetStatus? = null

    fun ensureInitialized(context: Context): GeoAssetStatus {
        val dir = context.filesDir
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val extracted = extractAssetsIfNeeded(context, dir)
                    runCatching { Libv2ray.initCoreEnv(dir.absolutePath, "") }
                        .onFailure { err ->
                            XrayEngineState.appendLog(
                                "WARN",
                                "Libv2ray.initCoreEnv skipped/failed: ${err.message ?: err.javaClass.simpleName}",
                            )
                        }

                    val geoIpFile = File(dir, "geoip.dat")
                    val geoSiteFile = File(dir, "geosite.dat")
                    val geoIpBytes = geoIpFile.takeIf { it.exists() }?.length() ?: 0L
                    val geoSiteBytes = geoSiteFile.takeIf { it.exists() }?.length() ?: 0L
                    val hasGeoIp = geoIpBytes > 0L
                    val hasGeoSite = geoSiteBytes > 0L

                    val status = GeoAssetStatus(
                        assetDir = dir.absolutePath,
                        hasGeoIp = hasGeoIp,
                        hasGeoSite = hasGeoSite,
                        geoIpBytes = geoIpBytes,
                        geoSiteBytes = geoSiteBytes,
                    )
                    lastStatus = status
                    XrayEngineState.updateGeoAssets(hasGeoIp = hasGeoIp, hasGeoSite = hasGeoSite)

                    if (hasGeoIp && hasGeoSite) {
                        val action = if (extracted.isNotEmpty()) "Unpacked" else "Verified"
                        XrayEngineState.appendLog(
                            "INFO",
                            "$action Xray geo assets in ${dir.absolutePath}: " +
                                "geoip.dat (${geoIpBytes / 1024} KB), geosite.dat (${geoSiteBytes / 1024} KB).",
                        )
                    } else {
                        val missing = buildList {
                            if (!hasGeoIp) add("geoip.dat")
                            if (!hasGeoSite) add("geosite.dat")
                        }.joinToString(", ")
                        XrayEngineState.appendLog(
                            "WARN",
                            "Missing geo assets ($missing) in APK assets and ${dir.absolutePath}. " +
                                "Using built-in CIDR fallback and skipping geoip:/geosite: rules so Xray core can start.",
                        )
                    }

                    initialized = true
                }
            }
        }
        return lastStatus ?: GeoAssetStatus(
            assetDir = dir.absolutePath,
            hasGeoIp = false,
            hasGeoSite = false,
            geoIpBytes = 0L,
            geoSiteBytes = 0L,
        )
    }

    fun currentStatus(context: Context): GeoAssetStatus = ensureInitialized(context)

    internal fun resetForTest() {
        synchronized(this) {
            initialized = false
            lastStatus = null
        }
    }

    private fun extractAssetsIfNeeded(context: Context, targetDir: File): List<String> {
        if (!targetDir.exists()) targetDir.mkdirs()
        val assetManager = context.assets
        val available = runCatching { assetManager.list("")?.toSet().orEmpty() }.getOrDefault(emptySet())
        val extracted = mutableListOf<String>()
        for (fileName in ASSET_FILES) {
            if (fileName !in available) continue
            val dest = File(targetDir, fileName)
            if (dest.exists() && dest.length() > 0L) continue
            val tmp = File(targetDir, "$fileName.part")
            val ok = runCatching {
                assetManager.open(fileName).use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (tmp.length() > 0L) {
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    true
                } else {
                    tmp.delete()
                    false
                }
            }.getOrDefault(false)
            if (ok) extracted += fileName
        }
        return extracted
    }
}
