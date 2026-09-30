package com.zekikoese

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Ein veröffentlichtes App-Release auf GitHub. */
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

/**
 * Update-Prüfung für die per Sideload installierte App (kein Play Store): fragt das neueste
 * GitHub-Release ab und installiert dessen APK über den System-Installer. Updates müssen mit
 * demselben Schlüssel signiert sein wie die installierte Version.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_API = "https://api.github.com/repos/kaveyro/ZekIPTV/releases/latest"

    /** Neuestes Release oder null (kein Release / keine APK darin). Blockierend — auf IO aufrufen. */
    fun fetchLatest(fetchText: (String) -> String = Http::readText): AppRelease? {
        val json = try {
            fetchText(LATEST_RELEASE_API)
        } catch (e: IOException) {
            if (e.message == "HTTP 404") return null // noch kein Release veröffentlicht
            throw e
        }
        return parseRelease(json)
    }

    fun parseRelease(json: String): AppRelease? {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val version = root.optString("tag_name").removePrefix("v").ifEmpty { return null }
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return AppRelease(
                    version = version,
                    notes = root.optString("body").trim(),
                    apkUrl = asset.optString("browser_download_url"),
                    apkSize = asset.optLong("size")
                )
            }
        }
        return null
    }

    /** Semantischer Versionsvergleich ("1.10.0" > "1.9.2"); Zusätze wie "-beta" werden ignoriert. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Lädt die APK in den Cache; [onProgress] erhält 0..1 (sofern die Größe bekannt ist). */
    fun download(context: Context, release: AppRelease, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "ZekIPTV-${release.version}.apk")
        Http.openStream(release.apkUrl).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    if (release.apkSize > 0) onProgress((total.toFloat() / release.apkSize).coerceIn(0f, 1f))
                }
            }
        }
        if (target.length() < 1024) throw IOException("Download unvollständig")
        return target
    }

    /** True, wenn die App Installationen starten darf (ab Android 8 pro App freizugeben). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Öffnet die Systemeinstellung "Unbekannte Apps installieren" für ZekIPTV. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Übergibt die heruntergeladene APK an den System-Installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
