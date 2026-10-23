// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Metadata representing an available GitHub release.
 */
data class UpdateReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val isNewer: Boolean
)

/**
 * Zero-telemetry in-app self-updater querying public GitHub Releases API.
 */
class GitHubAppUpdater(
    private val repoOwner: String = "indicreader",
    private val repoName: String = "floating-stopwatch"
) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks the GitHub Releases API for the latest published release.
     */
    fun checkForUpdates(
        currentVersionName: String,
        onResult: (Result<UpdateReleaseInfo?>) -> Unit
    ) {
        executor.execute {
            try {
                val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "PracticeTimer-AppUpdater")
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    val err = "GitHub API responded with code: ${connection.responseCode}"
                    mainHandler.post { onResult(Result.failure(Exception(err))) }
                    return@execute
                }

                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)

                val tagName = root.optString("tag_name", "")
                val releaseTitle = root.optString("name", tagName)
                val releaseNotes = root.optString("body", "No release notes provided.")
                val remoteVersionName = tagName.trimStart('v', 'V')

                val assets = root.optJSONArray("assets")
                var apkUrl = ""
                var apkName = ""
                var apkSize = 0L

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "")
                            apkName = name
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (apkUrl.isEmpty()) {
                    mainHandler.post { onResult(Result.success(null)) }
                    return@execute
                }

                val isNewer = isNewerVersion(currentVersionName, remoteVersionName)
                val info = UpdateReleaseInfo(
                    tagName = tagName,
                    versionName = remoteVersionName,
                    releaseTitle = releaseTitle,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = apkUrl,
                    apkFileName = apkName,
                    apkSizeBytes = apkSize,
                    isNewer = isNewer
                )

                mainHandler.post { onResult(Result.success(info)) }
            } catch (e: Exception) {
                mainHandler.post { onResult(Result.failure(e)) }
            }
        }
    }

    /**
     * Downloads the APK file to the app's internal cache directory with progress reporting.
     */
    fun downloadApk(
        context: Context,
        downloadUrl: String,
        targetFileName: String,
        onProgress: (percent: Int, bytesDownloaded: Long, totalBytes: Long) -> Unit,
        onResult: (Result<File>) -> Unit
    ) {
        executor.execute {
            try {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                // Clear any prior temp APKs
                updatesDir.listFiles()?.forEach { it.delete() }

                val destinationFile = File(updatesDir, targetFileName)
                val connection = openConnectionFollowRedirects(downloadUrl)

                val totalBytes = connection.contentLengthLong
                var bytesDownloaded = 0L

                connection.inputStream.use { input: InputStream ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var lastReportedPercent = -1

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesDownloaded += read

                            if (totalBytes > 0L) {
                                val percent = ((bytesDownloaded * 100) / totalBytes).toInt()
                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    mainHandler.post {
                                        onProgress(percent, bytesDownloaded, totalBytes)
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                mainHandler.post { onResult(Result.success(destinationFile)) }
            } catch (e: Exception) {
                mainHandler.post { onResult(Result.failure(e)) }
            }
        }
    }

    /**
     * Follows HTTP 301/302/307/308 redirects from GitHub releases to AWS S3.
     */
    private fun openConnectionFollowRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "PracticeTimer-AppUpdater")
            }

            when (conn.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    currentUrl = location
                    redirects++
                }
                HttpURLConnection.HTTP_OK -> return conn
                else -> throw Exception("Download failed with HTTP ${conn.responseCode}: ${conn.responseMessage}")
            }
        }
        throw Exception("Too many HTTP redirects following download URL")
    }

    /**
     * Checks if the app currently has permission to install unknown apps (Android 8.0+).
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Directs the user to the system Settings page to authorize install permissions.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Launches Android's native package installer for the downloaded APK file.
     */
    fun triggerPackageInstall(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(installIntent)
    }

    /**
     * Compares standard dot-separated version strings (e.g. 0.20 vs 0.21 or 1.0.1).
     */
    fun isNewerVersion(currentVersion: String, remoteVersion: String): Boolean {
        val cleanCurrent = currentVersion.trimStart('v', 'V').split(".")
        val cleanRemote = remoteVersion.trimStart('v', 'V').split(".")
        val length = maxOf(cleanCurrent.size, cleanRemote.size)

        for (i in 0 until length) {
            val cur = cleanCurrent.getOrNull(i)?.toIntOrNull() ?: 0
            val rem = cleanRemote.getOrNull(i)?.toIntOrNull() ?: 0
            if (rem > cur) return true
            if (rem < cur) return false
        }
        return false
    }
}
