package io.github.ryoaonetsuki.mira

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase

class MiraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize Database
        MiraDatabase.getInstance(this)

        // Initialize Notification Channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val micChannel = NotificationChannel(
                "mira_mic_channel",
                "Mira Live Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Mira AI voice assistant active in the background"
            }

            val alertChannel = NotificationChannel(
                "mira_alerts_channel",
                "Mira App Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notices, updates and security alerts"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(micChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }
}
