package com.surveil.camera.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.surveil.camera.App
import com.surveil.camera.service.BootNotifier
import com.surveil.camera.ui.MainActivity
import com.surveil.core.settings.Keys

/**
 * Restores surveillance after a reboot or an app update.
 * The camera may only be opened by a service started from a visible app, so with the
 * "display over other apps" permission we briefly launch the activity, which starts the
 * service and goes back to the background. Without it we post a one-tap notification.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!App.runRequested) return
        if (action == Intent.ACTION_BOOT_COMPLETED && !App.settings.current.bool(Keys.AUTOSTART)) return
        Log.i("BootReceiver", "auto-start after $action")
        if (Settings.canDrawOverlays(context)) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_AUTOSTART, true),
                )
                return
            }
        }
        BootNotifier.showStartPrompt(context)
    }
}
