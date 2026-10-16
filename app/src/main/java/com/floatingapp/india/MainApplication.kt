// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * MainApplication initializes global Material 3 Dynamic Colors.
 */
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply Material You Dynamic Colors across all activities if supported by OS (Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
