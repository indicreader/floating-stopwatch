// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.ui.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.floatingapp.india.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * SettingsFragment inflates the preference hierarchy and wires up interactive elements
 * like the Fork Attribution dialog.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Setup Fork Attribution click listener
        findPreference<Preference>("pref_fork_attribution")?.setOnPreferenceClickListener {
            showAttributionDialog()
            true
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
