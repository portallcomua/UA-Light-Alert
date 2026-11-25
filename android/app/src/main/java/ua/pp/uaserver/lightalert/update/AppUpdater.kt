package ua.pp.uaserver.lightalert.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import ua.pp.uaserver.lightalert.BuildConfig
import java.io.File
import java.io.FileOutputStream
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

    fun cleanupOldUpdates() {
        try {
            val dir = File(context.cacheDir, "updates")
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile) file.delete()
                }
            }
        } catch (_: Exception) {
        }
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

    fun downloadApk(
        info: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val partFile = File(updatesDir, "ua-light-alert-update.apk.part")
        val targetFile = File(updatesDir, "ua-light-alert-update.apk")
        if (partFile.exists()) partFile.delete()
        if (targetFile.exists()) targetFile.delete()

        var currentUrl = info.downloadUrl
        var redirects = 0
        var connection: HttpURLConnection? = null

        try {
            while (true) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "UA-Light-Alert-Android/${getCurrentVersionName()}")
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                }
                val status = conn.responseCode
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")?.trim().orEmpty()
                    conn.disconnect()
                    if (location.isBlank() || redirects >= 6) {
                        throw IllegalStateException("Забагато перенаправлень при завантаженні APK.")
                    }
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                connection = conn
                break
            }

            val conn = connection ?: throw IllegalStateException("Не вдалося підключитися до сервера.")
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Не вдалося завантажити APK (код $code).")
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: info.assetSizeBytes
            var downloaded = 0L
            val buffer = ByteArray(16 * 1024)

            conn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                    output.fd.sync()
                }
            }

            if (downloaded < 100_000L || !partFile.renameTo(targetFile)) {
                throw IllegalStateException("Завантажений файл пошкоджено.")
            }
            return targetFile
        } finally {
            connection?.disconnect()
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
    private val executor: ExecutorService
) {
    val updater = AppUpdater(activity)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingUpdateAfterPermission: AppUpdateInfo? = null
    private var awaitingInstallPermission = false
    private var progressDialog: AlertDialog? = null

    fun onResume() {
        if (!awaitingInstallPermission) return
        val pending = pendingUpdateAfterPermission ?: return
        awaitingInstallPermission = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) {
            pendingUpdateAfterPermission = null
            downloadAndLaunchInstaller(pending)
        } else {
            pendingUpdateAfterPermission = null
            Toast.makeText(
                activity,
                "Дозвіл на встановлення оновлень не надано.",
                Toast.LENGTH_LONG
            ).show()
        }
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
        messageBuilder.append("\n\nДодаток завантажить оновлення автоматично й одразу відкриє системне вікно встановлення.")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            pendingUpdateAfterPermission = info
            MaterialAlertDialogBuilder(activity)
                .setTitle("Дозвіл на оновлення")
                .setMessage("Щоб додаток оновлювався напряму без браузера, увімкніть перемикач «Дозволити з цього джерела» на наступному екрані.")
                .setPositiveButton("Дозволити") { _, _ ->
                    awaitingInstallPermission = true
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        awaitingInstallPermission = false
                        openInBrowser(info)
                    }
                }
                .setNegativeButton("Скасувати") { _, _ ->
                    pendingUpdateAfterPermission = null
                }
                .show()
            return
        }

        downloadAndLaunchInstaller(info)
    }

    private fun downloadAndLaunchInstaller(info: AppUpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val statusText = TextView(activity).apply {
            text = "Підготовка завантаження v. ${info.version}…"
            textSize = 14f
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            setPadding(0, pad / 2, 0, 0)
        }
        container.addView(statusText)
        container.addView(progressBar)

        progressDialog?.dismiss()
        progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Оновлення додатка")
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }

        executor.execute {
            try {
                val apkFile = updater.downloadApk(info) { downloaded, total ->
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        if (total > 0L) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            progressBar.isIndeterminate = false
                            progressBar.progress = pct
                            val dlMb = downloaded / (1024.0 * 1024.0)
                            val totMb = total / (1024.0 * 1024.0)
                            statusText.text = String.format(
                                Locale.US,
                                "Завантаження v. %s: %d%% (%.1f / %.1f МБ)",
                                info.version,
                                pct,
                                dlMb,
                                totMb
                            )
                        } else {
                            statusText.text = String.format(
                                Locale.US,
                                "Завантаження v. %s: %.1f МБ…",
                                info.version,
                                downloaded / (1024.0 * 1024.0)
                            )
                        }
                    }
                }
                mainHandler.post {
                    progressDialog?.dismiss()
                    progressDialog = null
                    launchSystemInstaller(apkFile, info)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    progressDialog?.dismiss()
                    progressDialog = null
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Помилка завантаження")
                        .setMessage("${e.message ?: "Не вдалося завантажити файл оновлення."}\n\nМожете завантажити оновлення через браузер.")
                        .setPositiveButton("Відкрити в браузері") { _, _ -> openInBrowser(info) }
                        .setNegativeButton("Закрити", null)
                        .show()
                }
            }
        }
    }

    private fun launchSystemInstaller(apkFile: File, info: AppUpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val contentUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                "Не вдалося запустити інсталятор, відкриваємо браузер…",
                Toast.LENGTH_SHORT
            ).show()
            openInBrowser(info)
        }
    }

    private fun openInBrowser(info: AppUpdateInfo) {
        val targetUrl = info.downloadUrl.ifBlank { info.releasePageUrl }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                "Відкрийте реліз у браузері: ${info.releasePageUrl}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
