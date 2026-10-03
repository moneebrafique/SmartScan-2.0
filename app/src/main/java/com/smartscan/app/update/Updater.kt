package com.smartscan.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.smartscan.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Checks the GitHub repo's latest Release (published by the build workflow) and installs newer APKs.
 * Release tags look like "v1.0.<build number>"; the build number is the app's versionCode.
 */
object Updater {
    private const val PREFS = "updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // at most every 6 hours

    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    fun shouldAutoCheck(context: Context): Boolean {
        if (!enabled) return false
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /** Returns the newer release, or null if this app is already up to date. Throws on network problems. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!enabled) throw IOException("Updates are not set up for this build")
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SmartScan-Updater")
        }
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> throw IOException("No published version found. The GitHub repo must be public.")
                else -> throw IOException("GitHub returned error ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull()
                ?: throw IOException("Unexpected release name: $tag")
            if (code <= BuildConfig.VERSION_CODE) return@withContext null

            val assets = json.optJSONArray("assets") ?: throw IOException("Release has no files")
            var apkUrl: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
            UpdateInfo(
                versionCode = code,
                versionName = tag.removePrefix("v"),
                notes = json.optString("body").trim(),
                apkUrl = apkUrl ?: throw IOException("Release has no APK"),
                sizeBytes = size,
            )
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "SmartScan-${info.versionName}.apk")
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "SmartScan-Updater")
            }
            try {
                if (conn.responseCode !in 200..299) throw IOException("Download failed (${conn.responseCode})")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0 && read - lastReport > 256 * 1024) {
                                lastReport = read
                                onProgress((read.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                }
                onProgress(1f)
            } catch (e: Exception) {
                file.delete()
                throw e
            } finally {
                conn.disconnect()
            }
            file
        }

    /** Android requires the user to allow "Install unknown apps" for SmartScan once. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Opens Android's installer for the downloaded APK (user taps "Update"). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
