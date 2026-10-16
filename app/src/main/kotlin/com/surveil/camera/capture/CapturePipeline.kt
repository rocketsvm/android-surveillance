package com.surveil.camera.capture

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.surveil.camera.gl.GlRenderer
import com.surveil.core.CameraStatus
import com.surveil.core.CoreEngine
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.VideoFormat
import com.surveil.core.settings.Apply
import com.surveil.core.settings.Keys
import com.surveil.core.settings.Schema
import com.surveil.core.settings.Settings
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the capture chain camera → GL renderer → H.264 encoder and keeps it running:
 * applies settings (live where possible, otherwise by restarting the chain), recovers from
 * camera errors (another app took the camera, HAL hiccups) with back-off, and follows the
 * phone's physical orientation when set to "auto".
 */
class CapturePipeline(
    private val context: Context,
    private val engine: CoreEngine,
    val catalog: CameraCatalog,
) {
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val jpegExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "jpeg").apply { isDaemon = true } }
    private val orientation = OrientationProbe(context) { r ->
        handler.post { if (wanted && running?.autoRotation == true && running?.rotation != r) restartNow("orientation") }
    }

    private class Running(
        val camera: CameraSource,
        val renderer: GlRenderer,
        val encoder: VideoEncoder,
        val info: CameraInfo,
        val fpsRange: android.util.Range<Int>?,
        val rotation: Int,
        val autoRotation: Boolean,
        val status: CameraStatus,
    )

    @Volatile private var running: Running? = null
    @Volatile private var preview: Surface? = null
    private var wanted = false
    private var generation = 0
    private var retryDelayMs = INITIAL_RETRY_MS
    private var lastError: String? = null

    private val restartRunnable = Runnable { restartNow("settings") }
    private val retryRunnable = Runnable { if (wanted && running == null) startNow() }

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            // The camera we wait for was released by another app: retry right away.
            if (wanted && running == null && lastError != null) {
                handler.removeCallbacks(retryRunnable)
                handler.postDelayed(retryRunnable, 500)
            }
        }
    }

    init {
        cameraManager.registerAvailabilityCallback(availability, handler)
    }

    val settings: Settings get() = engine.settings

    fun start() = handler.post {
        wanted = true
        if (running == null) startNow()
    }

    /** Stops capturing; blocks until the chain is torn down. */
    fun stop() {
        val latch = CountDownLatch(1)
        handler.post {
            wanted = false
            handler.removeCallbacks(retryRunnable)
            handler.removeCallbacks(restartRunnable)
            stopNow()
            orientation.stop()
            engine.setCameraStatus(CameraStatus("stopped"))
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    fun release() {
        stop()
        cameraManager.unregisterAvailabilityCallback(availability)
        jpegExecutor.shutdown()
        thread.quitSafely()
    }

    fun restart() = handler.post { restartNow("request") }

    /** Attaches the on-screen preview (call from the UI thread; blocks briefly on detach). */
    fun setPreview(surface: Surface?) {
        preview = surface
        running?.renderer?.setPreview(surface)
    }

    fun requestKeyframe() {
        running?.encoder?.requestKeyframe()
    }

    /** JPEG of the current output frame; [callback] runs on a background thread. */
    fun snapshotJpeg(maxWidth: Int, quality: Int = 85, callback: (ByteArray?) -> Unit) {
        val r = running ?: return callback(null)
        r.renderer.snapshot(maxWidth) { bmp ->
            if (bmp == null) {
                callback(null)
                return@snapshot
            }
            jpegExecutor.execute {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bmp.recycle()
                callback(out.toByteArray())
            }
        }
    }

    /** Reacts to settings edits from the phone or the browser. */
    fun onSettingsChanged(changed: Set<String>, s: Settings) {
        handler.post {
            val r = running
            if (changed.any { Schema.byKey[it]?.apply == Apply.PIPELINE }) {
                handler.removeCallbacks(restartRunnable)
                handler.postDelayed(restartRunnable, 400)
                return@post
            }
            if (r == null) return@post
            if (Keys.BITRATE in changed) r.encoder.setBitrate(s.int(Keys.BITRATE))
            if (changed.any { it in CAMERA_KEYS }) {
                val range = CameraCatalog.chooseFpsRange(r.info, s.int(Keys.FPS), s.bool(Keys.LOW_LIGHT_FPS))
                r.camera.update(cameraParams(s, range))
            }
            if (changed.any { it in OVERLAY_KEYS }) r.renderer.setOverlay(overlay(s))
            if (Keys.MOTION_FPS in changed) r.renderer.setMotionIntervalMs(1000 / s.int(Keys.MOTION_FPS).coerceAtLeast(1))
        }
    }

    // ---------------- capture thread ----------------

    private fun restartNow(reason: String) {
        if (!wanted) return
        Log.i(TAG, "restart: $reason")
        stopNow()
        startNow()
    }

    private fun startNow() {
        if (running != null) return
        val gen = ++generation
        val s = settings
        val autoRotation = s.string(Keys.ORIENTATION) == "auto"
        try {
            val cam = catalog.resolve(s.string(Keys.CAMERA_ID)) ?: throw IllegalStateException("Камера не найдена")
            val rotation = OrientationProbe.fromSetting(s.string(Keys.ORIENTATION)) ?: run {
                orientation.start()
                orientation.awaitRotation(600) ?: 90
            }
            if (!autoRotation) orientation.stop()

            var (reqW, reqH) = CameraCatalog.parse(s.string(Keys.RESOLUTION))
            var camSize = CameraCatalog.chooseCameraSize(cam, reqW, reqH)
            if (camSize == null) {
                // The requested size is not available on this camera: take the best standard one that is.
                val fallback = catalog.resolutionOptions(cam).map { CameraCatalog.parse(it.value) }
                    .filter { it.first <= reqW }.maxByOrNull { it.first * it.second }
                    ?: catalog.resolutionOptions(cam).map { CameraCatalog.parse(it.value) }.minByOrNull { it.first * it.second }
                if (fallback != null) {
                    reqW = fallback.first; reqH = fallback.second
                    camSize = CameraCatalog.chooseCameraSize(cam, reqW, reqH)
                }
            }
            camSize = camSize ?: CameraCatalog.fallbackCameraSize(cam, reqW, reqH) ?: throw IllegalStateException("Камера не отдаёт видео")
            val naturalLandscape = !cam.naturalPortrait
            val outLandscape = if (rotation % 180 == 90) !naturalLandscape else naturalLandscape
            var outW = if (outLandscape) reqW else reqH
            var outH = if (outLandscape) reqH else reqW
            if (!VideoEncoder.supportsSize(outW, outH)) {
                // Some encoders only accept landscape sizes: shrink until it fits.
                val alt = CameraCatalog.STANDARD.map { CameraCatalog.parse(it.first) }
                    .map { (w, h) -> if (outLandscape) w to h else h to w }
                    .filter { (w, h) -> w * h < outW * outH && VideoEncoder.supportsSize(w, h) }
                    .maxByOrNull { it.first * it.second } ?: throw IllegalStateException("Кодировщик не поддерживает ${outW}×$outH")
                outW = alt.first; outH = alt.second
            }
            val fps = s.int(Keys.FPS)
            val range = CameraCatalog.chooseFpsRange(cam, fps, s.bool(Keys.LOW_LIGHT_FPS))
            engine.setCameraStatus(CameraStatus("starting", null, cam.id, outW, outH, fps))

            val encoder = VideoEncoder(
                outW, outH, fps, s.int(Keys.BITRATE), s.int(Keys.KEYFRAME_SEC),
                s.string(Keys.PROFILE), s.string(Keys.BITRATE_MODE), encoderListener(gen),
            )
            encoder.start()
            val renderer = GlRenderer(rendererCallbacks(gen))
            val cameraSurface = try {
                renderer.start(
                    GlRenderer.Config(camSize.width, camSize.height, cam.naturalPortrait, outW, outH, rotation, fps),
                    encoder.inputSurface,
                )
            } catch (e: Exception) {
                renderer.release()
                encoder.release()
                throw e
            }
            renderer.setOverlay(overlay(s))
            renderer.setMotionIntervalMs(1000 / s.int(Keys.MOTION_FPS).coerceAtLeast(1))
            preview?.let { renderer.setPreview(it) }
            val camera = CameraSource(cameraManager, handler, cameraListener(gen))
            val status = CameraStatus("running", null, cam.id, outW, outH, fps, encoder.codecName)
            running = Running(camera, renderer, encoder, cam, range, rotation, autoRotation, status)
            camera.open(cam, cameraSurface, cameraParams(s, range))
            Log.i(TAG, "started camera ${cam.id} buffer=${camSize} out=${outW}x$outH rot=$rotation fps=$fps range=$range enc=${encoder.codecName}")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            fail(gen, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stopNow() {
        generation++
        val r = running ?: return
        running = null
        r.camera.close()
        r.renderer.release()
        r.encoder.release()
        engine.onStreamStopped()
    }

    private fun fail(gen: Int, message: String) {
        if (gen != generation) return
        stopNow()
        lastError = message
        engine.setCameraStatus(CameraStatus("error", message))
        if (wanted) {
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private fun encoderListener(gen: Int) = object : VideoEncoder.Listener {
        override fun onFormat(format: VideoFormat) {
            if (gen == generation) engine.onVideoFormat(format, null)
        }

        override fun onFrame(frame: MediaFrame) {
            if (gen == generation) engine.onFrame(frame)
        }

        override fun onEncoderError(e: Exception) {
            handler.post { fail(gen, "Ошибка кодировщика: ${e.message}") }
        }
    }

    private fun rendererCallbacks(gen: Int) = object : GlRenderer.Callbacks {
        override fun onFirstFrame() {
            handler.post {
                if (gen != generation) return@post
                retryDelayMs = INITIAL_RETRY_MS
                lastError = null
                running?.let { engine.setCameraStatus(it.status) }
            }
        }

        override fun onLuma(luma: ByteArray, width: Int, height: Int, timeMs: Long) {
            if (gen == generation) engine.onLumaFrame(luma, width, height, timeMs)
        }

        override fun onError(t: Throwable) {
            handler.post { fail(gen, "Ошибка видеообработки: ${t.message}") }
        }
    }

    private fun cameraListener(gen: Int) = object : CameraSource.Listener {
        override fun onCameraStarted() = Unit

        override fun onCameraError(message: String) {
            handler.post { fail(gen, message) }
        }
    }

    private fun cameraParams(s: Settings, range: android.util.Range<Int>?) = CameraParams(
        fpsRange = range,
        focus = s.string(Keys.FOCUS),
        exposureEv = s.double(Keys.EXPOSURE),
        torch = s.bool(Keys.TORCH),
        zoom = s.double(Keys.ZOOM).toFloat(),
        antibanding = s.string(Keys.ANTIBANDING),
        awb = s.string(Keys.AWB),
        stabilization = s.bool(Keys.STABILIZATION),
    )

    private fun overlay(s: Settings) = GlRenderer.Overlay(
        showTime = s.bool(Keys.OSD_TIME),
        name = if (s.bool(Keys.OSD_NAME)) s.name else null,
        position = s.string(Keys.OSD_POSITION),
        sizePercent = s.int(Keys.OSD_SIZE),
        mirror = s.bool(Keys.MIRROR),
    )

    companion object {
        private const val TAG = "CapturePipeline"
        private const val INITIAL_RETRY_MS = 2000L
        private const val MAX_RETRY_MS = 30_000L
        private val CAMERA_KEYS = setOf(
            Keys.FOCUS, Keys.EXPOSURE, Keys.ZOOM, Keys.TORCH, Keys.ANTIBANDING, Keys.AWB, Keys.STABILIZATION, Keys.LOW_LIGHT_FPS,
        )
        private val OVERLAY_KEYS = setOf(Keys.OSD_TIME, Keys.OSD_NAME, Keys.OSD_POSITION, Keys.OSD_SIZE, Keys.NAME, Keys.MIRROR)
    }
}
