package ua.pp.uaserver.lightalert.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import ua.pp.uaserver.lightalert.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService

data class AppUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val releaseNotes: String,
    val assetName: String,
    val assetSizeBytes: Long
)

class AppUpdater(private val context: Context) {

    sealed class CheckResult {
        data class UpdateAvailable(val info: AppUpdateInfo) : CheckResult()
        data class UpToDate(val currentVersion: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    fun getCurrentVersionName(): String {
        return BuildConfig.VERSION_NAME.removeSuffix("-debug").trim()
    }

    fun checkForUpdate(): CheckResult {
        val currentVersion = getCurrentVersionName()
        return try {
            val apiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "UA-Light-Alert-Android/$currentVersion")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }

            val code = conn.responseCode
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally {
                conn.disconnect()
            }

            if (code !in 200..299 || body.isBlank()) {
                return CheckResult.Error("Сервер GitHub відповів кодом $code")
            }

            val root = JSONObject(body)
            if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
                return CheckResult.UpToDate(currentVersion)
            }

            val rawTag = root.optString("tag_name", "").trim()
            val remoteVersion = rawTag.removePrefix("v").removePrefix("V").trim()
            if (remoteVersion.isBlank() || !isNewerVersion(remoteVersion, currentVersion)) {
                return CheckResult.UpToDate(currentVersion)
            }

            val releasePageUrl = root.optString(
                "html_url",
                "https://github.com/${BuildConfig.GITHUB_REPO}/releases/tag/$rawTag"
            ).trim()

            val assets = root.optJSONArray("assets")
            var preferredUrl = ""
            var preferredName = ""
            var preferredSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").trim()
                    val url = asset.optString("browser_download_url", "").trim()
                    val size = asset.optLong("size", 0L)
                    if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                        if (name.equals("ua-light-alert.apk", ignoreCase = true) || preferredUrl.isBlank()) {
                            preferredUrl = url
                            preferredName = name
                            preferredSize = size
                        }
                    }
                }
            }

            if (preferredUrl.isBlank()) {
                return CheckResult.Error("У релізі v$remoteVersion ще не прикріплено APK-файл.")
            }

            CheckResult.UpdateAvailable(
                AppUpdateInfo(
                    version = remoteVersion,
                    downloadUrl = preferredUrl,
                    releasePageUrl = releasePageUrl,
                    releaseNotes = root.optString("body", "").trim(),
                    assetName = preferredName,
                    assetSizeBytes = preferredSize
                )
            )
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: "Помилка перевірки оновлення")
        }
    }

    companion object {
        fun isNewerVersion(remote: String, current: String): Boolean {
            val rParts = parseVersionParts(remote)
            val cParts = parseVersionParts(current)
            if (rParts.isEmpty() || cParts.isEmpty()) return false
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        private fun parseVersionParts(raw: String): List<Int> {
            val clean = raw.substringBefore('-').substringBefore('+').trim()
            if (clean.isBlank()) return emptyList()
            return clean.split('.').mapNotNull { it.toIntOrNull() }
        }
    }
}

class AppUpdateController(
    private val activity: AppCompatActivity,
    @Suppress("UNUSED_PARAMETER") executor: ExecutorService
) {
    val updater = AppUpdater(activity)

    fun onResume() {
        // Browser handles APK download & installation without requiring REQUEST_INSTALL_PACKAGES.
    }

    fun showUpdateDialog(info: AppUpdateInfo, onDismissedLater: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) return

        val sizeMb = if (info.assetSizeBytes > 0L) {
            String.format(Locale.US, "%.1f МБ", info.assetSizeBytes / (1024.0 * 1024.0))
        } else {
            ""
        }

        val notes = info.releaseNotes
            .replace(Regex("[#*`]+"), "")
            .trim()
            .take(500)

        val messageBuilder = StringBuilder()
        messageBuilder.append("Вийшла нова версія UA Light Alert v. ${info.version}")
        if (sizeMb.isNotBlank()) {
            messageBuilder.append(" ($sizeMb)")
        }
        messageBuilder.append(".\nПоточна версія: v. ${updater.getCurrentVersionName()}")
        messageBuilder.append("\n\nПісля натискання «Оновити» браузер завантажить свіжий APK із GitHub Releases для встановлення.")
        if (notes.isNotBlank()) {
            messageBuilder.append("\n\nЗміни:\n").append(notes)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Доступне оновлення v. ${info.version}")
            .setMessage(messageBuilder.toString())
            .setPositiveButton("Оновити") { _, _ ->
                startDownloadAndInstall(info)
            }
            .setNegativeButton("Пізніше") { _, _ ->
                onDismissedLater?.invoke()
            }
            .show()
    }

    fun startDownloadAndInstall(info: AppUpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val targetUrl = info.downloadUrl.ifBlank { info.releasePageUrl }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                "Відкрийте реліз у браузері: ${info.releasePageUrl}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
