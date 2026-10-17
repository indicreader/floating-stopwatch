// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Repository for managing drill session tags (<Tag> - <Number>) and custom saved presets.
 */
class DrillTagRepository(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    companion object {
        const val KEY_TAG_PRESETS = "pref_drill_tag_presets"
        const val KEY_ACTIVE_TAG = "pref_drill_active_tag"
        const val KEY_ACTIVE_NUMBER = "pref_drill_active_number"

        val DEFAULT_PRESETS = linkedSetOf("Type", "Chapter", "Exercise", "Set")
        const val DEFAULT_TAG = "Type"
        const val DEFAULT_NUMBER = 1
    }

    fun getTagPresets(): List<String> {
        val stored = prefs.getStringSet(KEY_TAG_PRESETS, null)
        val result = LinkedHashSet(DEFAULT_PRESETS)
        if (stored != null) {
            result.addAll(stored)
        }
        return result.toList()
    }

    fun getActiveTag(): String {
        return prefs.getString(KEY_ACTIVE_TAG, DEFAULT_TAG) ?: DEFAULT_TAG
    }

    fun setActiveTag(tag: String) {
        prefs.edit().putString(KEY_ACTIVE_TAG, tag).apply()
    }

    fun getActiveNumber(): Int {
        return prefs.getInt(KEY_ACTIVE_NUMBER, DEFAULT_NUMBER)
    }

    fun setActiveNumber(number: Int) {
        val safeNum = if (number < 1) 1 else number
        prefs.edit().putInt(KEY_ACTIVE_NUMBER, safeNum).apply()
    }

    fun addCustomTag(tag: String): Boolean {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return false
        val currentPresets = LinkedHashSet(getTagPresets())
        currentPresets.add(trimmed)
        prefs.edit()
            .putStringSet(KEY_TAG_PRESETS, currentPresets)
            .putString(KEY_ACTIVE_TAG, trimmed)
            .apply()
        return true
    }

    fun incrementNumber(): Int {
        val next = getActiveNumber() + 1
        setActiveNumber(next)
        return next
    }

    fun decrementNumber(): Int {
        val current = getActiveNumber()
        val prev = if (current > 1) current - 1 else 1
        setActiveNumber(prev)
        return prev
    }

    fun getFormattedSessionTag(): String {
        return "${getActiveTag()} - ${getActiveNumber()}"
    }
}
