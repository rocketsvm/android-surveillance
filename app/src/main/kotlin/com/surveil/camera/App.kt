package com.surveil.camera

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.surveil.core.settings.SettingsStore
import java.io.File

class App : Application() {
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(File(filesDir, "settings.json"))
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_ALERTS = "alerts"
        private const val PREFS = "state"
        private const val KEY_RUN = "runRequested"
        private const val KEY_GUIDE_SEEN = "guideSeen"

        lateinit var instance: App
            private set

        val settings: SettingsStore get() = instance.settings

        private fun prefs() = instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Whether the user wants the camera running (false after an explicit stop). */
        var runRequested: Boolean
            get() = prefs().getBoolean(KEY_RUN, true)
            set(v) = prefs().edit().putBoolean(KEY_RUN, v).apply()

        var guideSeen: Boolean
            get() = prefs().getBoolean(KEY_GUIDE_SEEN, false)
            set(v) = prefs().edit().putBoolean(KEY_GUIDE_SEEN, v).apply()
    }
}
