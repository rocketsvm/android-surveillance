package com.surveil.camera.capture

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size
import com.surveil.core.settings.Option
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt

import com.surveil.camera.R

/** Static description of one camera as seen by apps. */
class CameraInfo(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val surfaceSizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val hasFlash: Boolean,
    val maxZoom: Float,
    val zoomRatioRange: Range<Float>?,
    val activeArray: Rect?,
    val evRange: Range<Int>,
    val evStep: Rational,
    val afModes: IntArray,
    val minFocusDistance: Float,
    val awbModes: IntArray,
    val antibandingModes: IntArray,
    val stabilizationModes: IntArray,
    val hfovDeg: Float,
) {
    var label: String = id
        internal set

    val isFront: Boolean get() = facing == CameraMetadata.LENS_FACING_FRONT

    /** Camera image is portrait after the SurfaceTexture transform (typical for phones). */
    val naturalPortrait: Boolean get() = sensorOrientation % 180 == 90
}

/** Enumerates cameras and derives user-facing options (camera list, supported resolutions). */
class CameraCatalog(private val context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    val cameras: List<CameraInfo> by lazy { load() }

    fun find(id: String?): CameraInfo? = cameras.firstOrNull { it.id == id }

    /** The configured camera, or the main back camera, or whatever exists. */
    fun resolve(id: String?): CameraInfo? =
        find(id?.takeIf { it.isNotEmpty() })
            ?: cameras.firstOrNull { it.facing == CameraMetadata.LENS_FACING_BACK }
            ?: cameras.firstOrNull()

    /** Camera choices; the empty value means "the main back camera" and is listed first. */
    fun cameraOptions(): List<Option> {
        val auto = resolve(null)?.label?.substringBefore(" · ")?.lowercase()
        val autoLabel = if (auto != null) context.getString(R.string.camera_auto_label, auto) else context.getString(R.string.camera_auto)
        return listOf(Option("", autoLabel)) + cameras.map { Option(it.id, it.label) }
    }

    private val resolutionCache = HashMap<String, List<Option>>()

    /** Standard resolutions that both the camera and the H.264 encoder can do. */
    @Synchronized
    fun resolutionOptions(cam: CameraInfo?): List<Option> {
        if (cam == null) return STANDARD.map { Option(it.first, it.second) }
        return resolutionCache.getOrPut(cam.id) {
            STANDARD.filter { (value, _) ->
                val (w, h) = parse(value)
                chooseCameraSize(cam, w, h) != null && VideoEncoder.supportsSize(w, h)
            }.map { Option(it.first, it.second) }
        }
    }

    private fun load(): List<CameraInfo> {
        val list = manager.cameraIdList.mapNotNull { id -> runCatching { describe(id) }.getOrNull() }
        // Labels: distinguish multiple back cameras by field of view.
        val backs = list.filter { it.facing == CameraMetadata.LENS_FACING_BACK }
        val mainBack = backs.firstOrNull()
        val fronts = list.filter { it.isFront }
        for (c in list) {
            val base = when (c.facing) {
                CameraMetadata.LENS_FACING_FRONT -> if (fronts.size > 1) context.getString(R.string.camera_front_n, fronts.indexOf(c) + 1) else context.getString(R.string.camera_front)
                CameraMetadata.LENS_FACING_BACK -> when {
                    c === mainBack -> context.getString(R.string.camera_back_main)
                    mainBack != null && c.hfovDeg > mainBack.hfovDeg * 1.2f -> context.getString(R.string.camera_back_wide)
                    mainBack != null && c.hfovDeg < mainBack.hfovDeg * 0.75f -> context.getString(R.string.camera_back_tele)
                    else -> context.getString(R.string.camera_back_n, backs.indexOf(c) + 1)
                }
                else -> context.getString(R.string.camera_external)
            }
            c.label = if (c.hfovDeg > 0) "$base · ${c.hfovDeg.roundToInt()}°" else base
        }
        return list
    }

    private fun describe(id: String): CameraInfo {
        val ch = manager.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val hfov = if (focal > 0 && phys != null) Math.toDegrees(2 * atan(phys.width / (2.0 * focal))).toFloat() else 0f
        val zoomRange = if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        return CameraInfo(
            id = id,
            facing = ch.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL,
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            surfaceSizes = sizes,
            fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList(),
            hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            maxZoom = zoomRange?.upper ?: (ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f),
            zoomRatioRange = zoomRange,
            activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 1),
            afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
            minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0),
            antibandingModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: IntArray(0),
            stabilizationModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0),
            hfovDeg = hfov,
        )
    }

    companion object {
        val STANDARD = listOf(
            "640x360" to "640×360",
            "640x480" to "640×480",
            "960x540" to "960×540",
            "1280x720" to "1280×720 (HD)",
            "1280x960" to "1280×960",
            "1440x1080" to "1440×1080",
            "1920x1080" to "1920×1080 (Full HD)",
            "2560x1440" to "2560×1440 (2K)",
            "3840x2160" to "3840×2160 (4K)",
        )

        fun parse(value: String): Pair<Int, Int> {
            val p = value.lowercase().split('x')
            val w = p.getOrNull(0)?.toIntOrNull() ?: 1280
            val h = p.getOrNull(1)?.toIntOrNull() ?: 720
            return maxOf(w, h) to minOf(w, h)
        }

        /**
         * Camera buffer size (landscape, sensor orientation) for an output of [w]×[h] (landscape form):
         * same aspect ratio, at least as large, as small as possible. Falls back to the closest aspect.
         */
        fun chooseCameraSize(cam: CameraInfo, w: Int, h: Int): Size? {
            val aspect = w.toFloat() / h
            val sizes = cam.surfaceSizes.map { if (it.width >= it.height) it else Size(it.height, it.width) }
            val sameAspect = sizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f }
            sameAspect.filter { it.width >= w && it.height >= h }.minByOrNull { it.width * it.height }?.let { return it }
            return null
        }

        /** Any usable buffer size when no exact-aspect match exists (output gets centre-cropped). */
        fun fallbackCameraSize(cam: CameraInfo, w: Int, h: Int): Size? {
            val sizes = cam.surfaceSizes.map { if (it.width >= it.height) it else Size(it.height, it.width) }
            return sizes.filter { it.width >= w && it.height >= h }.minByOrNull { it.width * it.height }
                ?: sizes.maxByOrNull { it.width * it.height }
        }

        /**
         * AE target fps range for [fps]. With [lowLight] the camera may drop below the target to
         * expose longer; without it we prefer a fixed range.
         */
        fun chooseFpsRange(cam: CameraInfo, fps: Int, lowLight: Boolean): Range<Int>? {
            val r = cam.fpsRanges
            if (r.isEmpty()) return null
            val exactUpper = r.filter { it.upper == fps }
            if (exactUpper.isNotEmpty()) {
                return if (lowLight) exactUpper.minByOrNull { it.lower } else exactUpper.maxByOrNull { it.lower }
            }
            // No range ending at fps: take the smallest upper bound above it; the renderer drops extra frames.
            val above = r.filter { it.upper > fps }
            if (above.isNotEmpty()) {
                val upper = above.minOf { it.upper }
                val cands = above.filter { it.upper == upper }
                return if (lowLight) cands.minByOrNull { it.lower } else cands.maxByOrNull { it.lower }
            }
            return r.maxByOrNull { it.upper }
        }
    }
}
