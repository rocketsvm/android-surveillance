package com.surveil.camera.capture

import android.annotation.SuppressLint
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** Live-adjustable capture parameters. */
data class CameraParams(
    val fpsRange: Range<Int>?,
    /** continuous | infinity */
    val focus: String = "continuous",
    val exposureEv: Double = 0.0,
    val torch: Boolean = false,
    val zoom: Float = 1f,
    /** auto | 50hz | 60hz | off */
    val antibanding: String = "auto",
    /** auto | daylight | cloudy | incandescent | fluorescent */
    val awb: String = "auto",
    val stabilization: Boolean = false,
)

/**
 * Camera2 device + session streaming into one Surface. All callbacks run on [handler].
 * Errors are reported to [listener]; the owner decides about retries.
 */
class CameraSource(
    private val manager: CameraManager,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onCameraStarted()
        fun onCameraError(message: String)
    }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var info: CameraInfo? = null
    private var target: Surface? = null
    private var params: CameraParams? = null
    @Volatile private var closed = false

    @SuppressLint("MissingPermission")
    fun open(cam: CameraInfo, surface: Surface, p: CameraParams) {
        info = cam
        target = surface
        params = p
        closed = false
        try {
            manager.openCamera(cam.id, stateCallback, handler)
        } catch (e: SecurityException) {
            listener.onCameraError("Нет разрешения на камеру")
        } catch (e: Exception) {
            listener.onCameraError("Не удалось открыть камеру: ${e.message}")
        }
    }

    /** Applies new parameters to the running session without restarting it. */
    fun update(p: CameraParams) {
        params = p
        handler.post { applyRepeating() }
    }

    fun close() {
        closed = true
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (closed) {
                camera.close(); return
            }
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (device === camera) device = null
            if (!closed) listener.onCameraError("Камеру заняло другое приложение")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (device === camera) device = null
            val msg = when (error) {
                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Камера занята другим приложением"
                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Камера отключена политикой устройства"
                CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Сбой камеры"
                CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Сбой системной службы камеры"
                else -> "Ошибка камеры $error"
            }
            if (!closed) listener.onCameraError(msg)
        }
    }

    private fun createSession(camera: CameraDevice) {
        val surface = target ?: return
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) {
                    s.close(); return
                }
                session = s
                if (applyRepeating()) listener.onCameraStarted()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (!closed) listener.onCameraError("Камера не поддерживает выбранный режим")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val executor = Executor { r -> handler.post(r) }
                camera.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), executor, cb),
                )
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(surface), cb, handler)
            }
        } catch (e: Exception) {
            listener.onCameraError("Не удалось настроить камеру: ${e.message}")
        }
    }

    private fun applyRepeating(): Boolean {
        val s = session ?: return false
        val d = device ?: return false
        val p = params ?: return false
        val cam = info ?: return false
        val surface = target ?: return false
        return try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(surface)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            p.fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            // Focus
            val canInfinity = cam.minFocusDistance > 0f
            if (p.focus == "infinity" && canInfinity) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            } else if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in cam.afModes) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            // Exposure compensation
            val step = cam.evStep.toDouble()
            if (step > 0) {
                val idx = (p.exposureEv / step).roundToInt().coerceIn(cam.evRange.lower, cam.evRange.upper)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
            }
            // Torch
            if (cam.hasFlash) {
                b.set(CaptureRequest.FLASH_MODE, if (p.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            }
            // Zoom
            val zoom = p.zoom.coerceIn(1f, cam.maxZoom.coerceAtLeast(1f))
            if (Build.VERSION.SDK_INT >= 30 && cam.zoomRatioRange != null) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(cam.zoomRatioRange.lower, cam.zoomRatioRange.upper))
            } else if (zoom > 1.01f && cam.activeArray != null) {
                val a = cam.activeArray
                val cw = (a.width() / zoom).toInt()
                val chh = (a.height() / zoom).toInt()
                val l = a.left + (a.width() - cw) / 2
                val t = a.top + (a.height() - chh) / 2
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + cw, t + chh))
            }
            // Anti-banding
            val ab = when (p.antibanding) {
                "50hz" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
                "60hz" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
                "off" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
                else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
            }
            if (ab in cam.antibandingModes) b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, ab)
            // White balance
            val awb = when (p.awb) {
                "daylight" -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                "cloudy" -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                "incandescent" -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                "fluorescent" -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
            }
            if (awb in cam.awbModes) b.set(CaptureRequest.CONTROL_AWB_MODE, awb)
            // Stabilisation
            val stab = if (p.stabilization) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            if (stab in cam.stabilizationModes) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stab)

            s.setRepeatingRequest(b.build(), null, handler)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setRepeatingRequest failed", e)
            if (!closed) listener.onCameraError("Камера отклонила параметры: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "CameraSource"
    }
}
