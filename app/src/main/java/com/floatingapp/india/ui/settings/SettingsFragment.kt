// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.ui.settings

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.floatingapp.india.R
import com.floatingapp.india.manager.GitHubAppUpdater
import com.floatingapp.india.manager.PracticeSessionManager
import com.floatingapp.india.manager.UpdateReleaseInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

/**
 * SettingsFragment inflates the preference hierarchy, displays app information,
 * and handles the interactive GitHub in-app update checks and downloads.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val appUpdater = GitHubAppUpdater()
    private var downloadedApkFile: File? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val currentVersion = getAppVersionName()

        // Setup Check for Updates preference
        findPreference<Preference>("pref_check_updates")?.apply {
            summary = "Current version: v$currentVersion • Tap to check GitHub releases"
            setOnPreferenceClickListener {
                performUpdateCheck(currentVersion)
                true
            }
        }

        // Setup Fork Attribution click listener
        findPreference<Preference>("pref_fork_attribution")?.setOnPreferenceClickListener {
            showAttributionDialog()
            true
        }

        // Setup Reset Question Index preference
        val resetPref = findPreference<Preference>("pref_reset_question_index")
        fun updateResetSummary() {
            val nextQ = PracticeSessionManager.getPrefetchedStartingQuestion(requireContext())
            resetPref?.summary = "Next drill session will start at Q$nextQ. Tap to reset back to Q1."
        }
        updateResetSummary()
        resetPref?.setOnPreferenceClickListener {
            PracticeSessionManager.resetStartingQuestionIndex(requireContext())
            updateResetSummary()
            Toast.makeText(requireContext(), "Next session question index reset to Q1", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // If returning from system Settings to grant install permission and APK is ready
        downloadedApkFile?.let { file ->
            if (file.exists() && appUpdater.canInstallPackages(requireContext())) {
                appUpdater.triggerPackageInstall(requireContext(), file)
                downloadedApkFile = null
            }
        }
    }

    private fun performUpdateCheck(currentVersion: String) {
        val checkingDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Checking for Updates")
            .setMessage("Querying GitHub for latest releases...")
            .setCancelable(false)
            .create()
        checkingDialog.show()

        appUpdater.checkForUpdates(currentVersion) { result ->
            if (!isAdded) return@checkForUpdates
            checkingDialog.dismiss()

            result.onSuccess { info ->
                if (info == null) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("No Release Found")
                        .setMessage("No release assets (.apk) were found in the latest GitHub release.")
                        .setPositiveButton("OK", null)
                        .show()
                } else if (!info.isNewer) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("You're Up to Date!")
                        .setMessage("Practice Timer v$currentVersion is currently the latest release available on GitHub.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    showUpdateAvailableDialog(info)
                }
            }.onFailure { error ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Check Failed")
                    .setMessage("Unable to check for updates:\n${error.localizedMessage ?: "Network error"}\n\nPlease check your internet connection and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showUpdateAvailableDialog(info: UpdateReleaseInfo) {
        val sizeStr = formatFileSize(info.apkSizeBytes)
        val message = StringBuilder()
            .append("A new version of Practice Timer is available:\n\n")
            .append("• New Version: ${info.tagName}\n")
            .append("• Download Size: $sizeStr\n\n")
            .append("Release Notes:\n${info.releaseNotes}")
            .toString()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Update Available: ${info.tagName}")
            .setMessage(message)
            .setPositiveButton("Download & Install") { _, _ ->
                startDownloadAndInstall(info)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startDownloadAndInstall(info: UpdateReleaseInfo) {
        val sizeStr = formatFileSize(info.apkSizeBytes)
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Downloading Update")
            .setMessage("Downloading ${info.apkFileName}...\n0% (0 KB / $sizeStr)")
            .setCancelable(false)
            .create()
        progressDialog.show()

        appUpdater.downloadApk(
            context = requireContext(),
            downloadUrl = info.apkDownloadUrl,
            targetFileName = info.apkFileName,
            onProgress = { percent, downloaded, total ->
                if (isAdded) {
                    val downloadedStr = formatFileSize(downloaded)
                    val totalStr = formatFileSize(total)
                    progressDialog.setMessage("Downloading ${info.apkFileName}...\n$percent% ($downloadedStr / $totalStr)")
                }
            },
            onResult = { result ->
                if (!isAdded) return@downloadApk
                progressDialog.dismiss()

                result.onSuccess { apkFile ->
                    downloadedApkFile = apkFile
                    if (!appUpdater.canInstallPackages(requireContext())) {
                        promptInstallPermission(apkFile)
                    } else {
                        appUpdater.triggerPackageInstall(requireContext(), apkFile)
                    }
                }.onFailure { err ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Download Failed")
                        .setMessage("Failed to download the update APK:\n${err.localizedMessage ?: "Unknown error"}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
    }

    private fun promptInstallPermission(apkFile: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Install Permission Required")
            .setMessage("Android requires permission to install apps downloaded from this app. Please tap 'Settings' and enable 'Allow from this source' to complete the update.")
            .setPositiveButton("Settings") { _, _ ->
                appUpdater.openInstallPermissionSettings(requireContext())
            }
            .setNegativeButton("Cancel") { _, _ ->
                downloadedApkFile = null
            }
            .show()
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "Unknown size"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.0f KB", kb)
        }
    }

    private fun getAppVersionName(): String {
        return try {
            val ctx = requireContext()
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }
            pInfo.versionName ?: "0.20"
        } catch (_: Exception) {
            "0.20"
        }
    }

    private fun showAttributionDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_attribution_title)
            .setMessage(R.string.dialog_attribution_message)
            .setPositiveButton(R.string.dialog_attribution_close) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
