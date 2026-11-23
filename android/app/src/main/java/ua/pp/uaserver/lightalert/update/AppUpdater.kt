package ua.pp.uaserver.lightalert.update

import android.app.Activity
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import ua.pp.uaserver.lightalert.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService

data class AppUpdateInfo(
    val version: String,
    val downloadUrl: String,
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
                    releaseNotes = root.optString("body", "").trim(),
                    assetName = preferredName,
                    assetSizeBytes = preferredSize
                )
            )
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: "Помилка перевірки оновлення")
        }
    }

    fun downloadApk(downloadUrl: String, onProgress: (Int) -> Unit): Result<File> {
        return try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val tempFile = File(updatesDir, "ua-light-alert.apk.part")
            val targetFile = File(updatesDir, "ua-light-alert.apk")
            if (tempFile.exists()) tempFile.delete()

            val conn = openFollowingRedirects(downloadUrl, 0)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return Result.failure(IllegalStateException("Помилка завантаження APK (HTTP $code)"))
                }

                val totalBytes = conn.contentLengthLong
                var downloaded = 0L
                val buffer = ByteArray(16 * 1024)

                conn.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0L) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                onProgress(pct)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (!tempFile.exists() || tempFile.length() < 100_000L || !hasZipSignature(tempFile)) {
                tempFile.delete()
                return Result.failure(IllegalStateException("Завантажений файл пошкоджено або це не APK."))
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        }
    }

    fun launchInstaller(activity: Activity, apkFile: File): Boolean {
        if (!canInstallPackages()) {
            requestInstallPermission(activity)
            return false
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(installIntent)
        return true
    }

    private fun openFollowingRedirects(urlStr: String, depth: Int): HttpURLConnection {
        if (depth > 6) throw IllegalStateException("Занадто багато перенаправлень під час завантаження APK")
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "UA-Light-Alert-Android/${getCurrentVersionName()}")
            setRequestProperty("Accept", "application/octet-stream")
        }
        val code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrBlank()) {
                throw IllegalStateException("Порожнє перенаправлення від GitHub")
            }
            val nextUrl = URL(URL(urlStr), location).toString()
            return openFollowingRedirects(nextUrl, depth + 1)
        }
        return conn
    }

    private fun hasZipSignature(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(2)
                fis.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            }
        } catch (_: Exception) {
            false
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
    private var pendingInstallApk: File? = null
    private var progressDialog: AlertDialog? = null

    fun onResume() {
        val pending = pendingInstallApk
        if (pending != null && pending.exists() && updater.canInstallPackages()) {
            pendingInstallApk = null
            updater.launchInstaller(activity, pending)
        }
    }

    fun showUpdateDialog(info: AppUpdateInfo, onDismissedLater: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) return

        val sizeMb = if (info.assetSizeBytes > 0L) {
            String.format(java.util.Locale.US, "%.1f МБ", info.assetSizeBytes / (1024.0 * 1024.0))
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

        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val statusView = TextView(activity).apply {
            text = "Завантаження v. ${info.version}… 0%"
            textSize = 14f
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, pad / 2, 0, 0)
        }
        container.addView(statusView)
        container.addView(progressBar)

        progressDialog?.dismiss()
        progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Оновлення додатка")
            .setView(container)
            .setCancelable(false)
            .show()

        executor.execute {
            val result = updater.downloadApk(info.downloadUrl) { pct ->
                mainHandler.post {
                    progressBar.progress = pct
                    statusView.text = "Завантаження v. ${info.version}… $pct%"
                }
            }

            mainHandler.post {
                progressDialog?.dismiss()
                progressDialog = null
                if (activity.isFinishing || activity.isDestroyed) return@post

                result.onSuccess { apkFile ->
                    if (!updater.canInstallPackages()) {
                        pendingInstallApk = apkFile
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("Потрібен дозвіл на встановлення")
                            .setMessage("Надайте додатку дозвіл на встановлення оновлень («Встановлення невідомих додатків»), а потім поверніться назад.")
                            .setPositiveButton("Відкрити налаштування") { _, _ ->
                                updater.requestInstallPermission(activity)
                            }
                            .setNegativeButton("Скасувати", null)
                            .show()
                    } else {
                        updater.launchInstaller(activity, apkFile)
                    }
                }.onFailure { err ->
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Не вдалося оновити")
                        .setMessage(err.message ?: "Помилка завантаження APK з GitHub Releases.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
}
