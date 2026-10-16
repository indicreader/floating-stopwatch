// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.floatingapp.india.R
import com.floatingapp.india.databinding.ActivitySettingsBinding

/**
 * SettingsActivity hosts the Preferences fragment using Material 3 DayNight theming.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
}
