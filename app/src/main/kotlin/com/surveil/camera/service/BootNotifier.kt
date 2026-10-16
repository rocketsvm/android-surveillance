package com.surveil.camera.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.surveil.camera.App
import com.surveil.camera.R

/**
 * Android does not let a background app open the camera. When the camera service cannot be started
 * automatically, a notification lets the user start it with one tap (a notification tap is an
 * allowed way to start a camera foreground service).
 */
object BootNotifier {
    private const val ID = 2

    fun showStartPrompt(context: Context) {
        val start = PendingIntent.getForegroundService(
            context, 10, Intent(context, CameraService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_boot_title))
            .setContentText(context.getString(R.string.notif_boot_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(start)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, n) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
