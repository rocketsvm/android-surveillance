package com.surveil.camera.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.surveil.camera.App
import com.surveil.camera.R
import com.surveil.camera.capture.CameraCatalog
import com.surveil.camera.capture.CapturePipeline
import com.surveil.camera.platform.AndroidPlatform
import com.surveil.camera.platform.DeviceInfo
import com.surveil.camera.ui.MainActivity
import com.surveil.core.CoreEngine
import com.surveil.core.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The running engine, for the UI (preview, status). Null while the service is stopped. */
class RunningEngine(val core: CoreEngine, val pipeline: CapturePipeline, val catalog: CameraCatalog)

object EngineHolder {
    internal val state = MutableStateFlow<RunningEngine?>(null)
    val current: StateFlow<RunningEngine?> = state.asStateFlow()
}

/**
 * Foreground service of type "camera": keeps capture, recording and the web server alive with the
 * screen off. Started from the visible app (required for camera access on Android 14+), stopped
 * only explicitly (app button or notification action).
 */
class CameraService : LifecycleService() {
    private var engine: RunningEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotificationText = ""

    override fun onCreate() {
        super.onCreate()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no camera permission, not starting")
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Запуск…"),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
            )
        } catch (e: Exception) {
            // Started from the background without an exemption (Android 12+/14+ rules).
            Log.e(TAG, "startForeground refused", e)
            BootNotifier.showStartPrompt(this)
            stopSelf()
            return
        }
        BootNotifier.cancel(this)
        acquireLocks()
        val catalog = CameraCatalog(this)
        val platform = AndroidPlatform(this, catalog)
        val core = CoreEngine(platform, App.settings, filesDir)
        val pipeline = CapturePipeline(this, core, catalog)
        platform.pipeline = pipeline
        core.start()
        pipeline.start()
        val running = RunningEngine(core, pipeline, catalog)
        engine = running
        EngineHolder.state.value = running

        lifecycleScope.launch {
            var previous = App.settings.current
            App.settings.flow.collect { s ->
                val changed = s.diff(previous)
                previous = s
                if (changed.isNotEmpty()) pipeline.onSettingsChanged(changed, s)
            }
        }
        lifecycleScope.launch {
            core.status.collect { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            App.runRequested = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        EngineHolder.state.value = null
        engine?.let {
            it.pipeline.release()
            it.core.stop()
        }
        engine = null
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SurveillanceCam:capture").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching {
            wm.createWifiLock(mode, "SurveillanceCam:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun updateNotification(st: EngineStatus) {
        val addr = DeviceInfo.networkAddresses(this).firstOrNull { !it.ipv6 }
        val parts = ArrayList<String>()
        if (addr != null && st.serverPort > 0) parts += "http://${addr.address}:${st.serverPort}"
        when (st.camera.state) {
            "error" -> parts += "ошибка: ${st.camera.error}"
            "starting" -> parts += "запуск камеры…"
        }
        if (st.recordingId != null) parts += "● запись"
        if (st.viewers > 0) parts += "зрителей: ${st.viewers}"
        val text = parts.joinToString(" · ")
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "SurveilService"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.surveil.camera.STOP"

        fun start(context: Context) {
            App.runRequested = true
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))
        }

        fun stop(context: Context) {
            App.runRequested = false
            context.stopService(Intent(context, CameraService::class.java))
        }
    }
}
