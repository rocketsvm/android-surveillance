package com.surveil.camera.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.surveil.camera.App
import com.surveil.camera.capture.CameraCatalog
import com.surveil.camera.platform.DeviceInfo
import com.surveil.camera.service.BootNotifier
import com.surveil.camera.service.CameraService
import com.surveil.camera.service.EngineHolder
import com.surveil.core.EngineStatus
import com.surveil.core.settings.Keys
import com.surveil.core.settings.Option
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val catalog by lazy { CameraCatalog(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleAutostart(intent)
        setContent { SurveilTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    /** Launched by [com.surveil.camera.boot.BootReceiver]: start the service while visible, then step aside. */
    private fun handleAutostart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        intent.removeExtra(EXTRA_AUTOSTART)
        if (hasCamera()) {
            BootNotifier.cancel(this)
            CameraService.start(this)
            window.decorView.postDelayed({ moveTaskToBack(true) }, 1500)
        }
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun options(key: String): List<Option>? {
        val s = App.settings.current
        return when (key) {
            Keys.CAMERA_ID -> catalog.cameraOptions()
            Keys.RESOLUTION -> catalog.resolutionOptions(catalog.resolve(s.string(Keys.CAMERA_ID)))
            Keys.REC_STORAGE -> DeviceInfo.storageRoots(this).map { Option(it.id, "${it.label} (свободно ${it.freeBytes / 1_000_000_000} ГБ)") }
            else -> null
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { openAppSettings() }
    }

    private fun openAppSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    private fun requestOverlay() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    }

    private fun setDim(on: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Composable
    private fun Root() {
        val engine by EngineHolder.current.collectAsStateWithLifecycle()
        val statusFlow = remember(engine) { engine?.core?.status ?: MutableStateFlow(EngineStatus()) }
        val status by statusFlow.collectAsStateWithLifecycle()
        val settings by App.settings.flow.collectAsStateWithLifecycle()
        var cameraGranted by remember { mutableStateOf(hasCamera()) }
        var notificationsGranted by remember { mutableStateOf(hasNotifications()) }
        var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
        var batteryOptimized by remember { mutableStateOf(!DeviceInfo.isIgnoringBatteryOptimizations(this)) }
        var addresses by remember { mutableStateOf(DeviceInfo.networkAddresses(this)) }
        var screen by rememberSaveable { mutableStateOf(if (App.guideSeen) "home" else "guide") }
        var dim by rememberSaveable { mutableStateOf(false) }

        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            cameraGranted = hasCamera()
            notificationsGranted = hasNotifications()
            // Microphone just granted while audio is on: restart capture so audio joins the stream.
            if (res[Manifest.permission.RECORD_AUDIO] == true && App.settings.current.bool(Keys.AUDIO_ENABLED)) {
                EngineHolder.current.value?.pipeline?.restart()
            }
        }
        val requestCamera = {
            val list = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(list.toTypedArray())
        }

        val audioOn = settings.bool(Keys.AUDIO_ENABLED)
        LaunchedEffect(audioOn) {
            if (audioOn && !hasMic()) permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        LifecycleResumeEffect(Unit) {
            // Audio enabled from the browser while the app was in the background: the microphone can
            // only be attached now that the app is visible.
            val e = EngineHolder.current.value
            if (e != null && e.core.status.value.camera.audio == "error" && hasMic()) e.pipeline.restart()
            cameraGranted = hasCamera()
            notificationsGranted = hasNotifications()
            overlayGranted = Settings.canDrawOverlays(this@MainActivity)
            batteryOptimized = !DeviceInfo.isIgnoringBatteryOptimizations(this@MainActivity)
            addresses = DeviceInfo.networkAddresses(this@MainActivity)
            onPauseOrDispose { }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(4000)
                addresses = DeviceInfo.networkAddresses(this@MainActivity)
                overlayGranted = Settings.canDrawOverlays(this@MainActivity)
                batteryOptimized = !DeviceInfo.isIgnoringBatteryOptimizations(this@MainActivity)
            }
        }
        // The camera starts with the app (unless the user stopped it explicitly).
        LaunchedEffect(cameraGranted) {
            if (cameraGranted && App.runRequested && EngineHolder.current.value == null) CameraService.start(this@MainActivity)
            if (!cameraGranted && App.guideSeen) requestCamera()
        }
        DisposableEffect(dim) {
            setDim(dim)
            onDispose { }
        }

        val home = HomeState(engine != null, status, settings, addresses, cameraGranted, batteryOptimized)
        BackHandler(enabled = screen != "home" || dim) {
            when {
                dim -> dim = false
                screen == "zones" -> screen = "settings"   // same as the editor's own back arrow
                else -> screen = "home"
            }
        }
        if (dim) {
            DimOverlay { dim = false }
            return
        }
        when (screen) {
            "guide" -> GuideScreen(
                GuideState(home, notificationsGranted, overlayGranted),
                GuideActions(
                    requestCamera = requestCamera,
                    requestNotifications = requestCamera,
                    requestBattery = ::requestBattery,
                    openAppSettings = ::openAppSettings,
                    requestOverlay = ::requestOverlay,
                    done = {
                        App.guideSeen = true
                        screen = "home"
                        if (hasCamera()) CameraService.start(this) else requestCamera()
                    },
                    back = if (App.guideSeen) ({ screen = "home" }) else null,
                ),
            )
            "settings" -> SettingsScreen(settings, ::options, onBack = { screen = "home" }, onZones = { screen = "zones" })
            "zones" -> ZoneEditor(onClose = { screen = "settings" })
            else -> HomeScreen(
                home,
                HomeActions(
                    start = { if (hasCamera()) CameraService.start(this) else requestCamera() },
                    stop = { CameraService.stop(this) },
                    requestCamera = requestCamera,
                    requestBattery = ::requestBattery,
                    openSettings = { screen = "settings" },
                    openGuide = { screen = "guide" },
                    dimScreen = { dim = true },
                    setManual = { on -> EngineHolder.current.value?.core?.setManualRecording(on) },
                ),
            )
        }
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}
