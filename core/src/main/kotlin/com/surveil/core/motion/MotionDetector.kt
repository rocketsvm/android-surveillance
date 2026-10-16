package com.surveil.core.motion

import kotlin.math.abs

/** Grid of detection cells; `true` = the cell is excluded from detection. */
class ZoneMask(val cols: Int, val rows: Int, val excluded: BooleanArray) {
    init {
        require(cols in 1..64 && rows in 1..64 && excluded.size == cols * rows)
    }

    fun isExcluded(col: Int, row: Int) = excluded[row * cols + col]

    /** `16x9:<hex>`; bit i (row-major, MSB first per nibble) set = excluded. */
    fun format(): String {
        if (excluded.none { it }) return ""
        val sb = StringBuilder("${cols}x$rows:")
        var i = 0
        while (i < excluded.size) {
            var nib = 0
            for (b in 0 until 4) nib = (nib shl 1) or (if (i + b < excluded.size && excluded[i + b]) 1 else 0)
            sb.append("0123456789abcdef"[nib])
            i += 4
        }
        return sb.toString()
    }

    companion object {
        fun parse(s: String?): ZoneMask? {
            if (s.isNullOrBlank()) return null
            val colon = s.indexOf(':')
            if (colon < 0) return null
            val dims = s.substring(0, colon).split('x')
            val cols = dims.getOrNull(0)?.toIntOrNull() ?: return null
            val rows = dims.getOrNull(1)?.toIntOrNull() ?: return null
            if (cols !in 1..64 || rows !in 1..64) return null
            val hex = s.substring(colon + 1)
            val bits = BooleanArray(cols * rows)
            for (i in bits.indices) {
                val nib = hex.getOrNull(i / 4)?.digitToIntOrNull(16) ?: 0
                bits[i] = (nib shr (3 - i % 4)) and 1 == 1
            }
            return if (bits.any { it }) ZoneMask(cols, rows, bits) else null
        }
    }
}

data class MotionConfig(
    /** 1..100 */
    val sensitivity: Int,
    /** Changed share of the active area (percent) that counts as motion. */
    val minAreaPercent: Double,
    val ignoreLightChanges: Boolean,
    val zones: ZoneMask?,
)

class MotionResult(
    /** Changed share of the active area, percent. */
    val score: Float,
    /** Debounced motion state. */
    val active: Boolean,
    /** Bounding box of the changed area, normalised [x, y, w, h], or null. */
    val box: FloatArray?,
    /** The frame was treated as a global lighting change and ignored. */
    val lightChange: Boolean,
)

/**
 * Background-subtraction motion detector working on a small luma image (e.g. 160×90).
 * - Exposure changes are compensated by matching mean brightness to the background.
 * - The per-pixel threshold adapts to the sensor noise level (night noise is much higher).
 * - Isolated changed pixels are discarded; motion must persist for two analysed frames.
 * - Objects that stop moving are slowly absorbed into the background.
 */
class MotionDetector {
    private var bg: FloatArray? = null
    private var width = 0
    private var height = 0
    private var activeMask: BooleanArray? = null
    private var maskKey: String? = null
    private var noise = 3f
    private var hits = 0
    private var active = false
    private var lastMotionMs = 0L
    private var changed = BooleanArray(0)

    fun reset() {
        bg = null
        hits = 0
        active = false
    }

    /** [luma]: width*height bytes, rows top to bottom. [timeMs]: monotonic time of the frame. */
    fun process(luma: ByteArray, width: Int, height: Int, timeMs: Long, cfg: MotionConfig): MotionResult {
        val n = width * height
        require(luma.size >= n)
        if (width != this.width || height != this.height) {
            this.width = width; this.height = height
            bg = null
            changed = BooleanArray(n)
            maskKey = null
        }
        val mask = activeMask(cfg.zones)
        val b = bg
        if (b == null) {
            bg = FloatArray(n) { (luma[it].toInt() and 0xff).toFloat() }
            return MotionResult(0f, false, null, false)
        }

        // Brightness compensation over the active area.
        var sumF = 0.0
        var sumB = 0.0
        var activeCount = 0
        for (i in 0 until n) if (mask[i]) {
            sumF += luma[i].toInt() and 0xff
            sumB += b[i]
            activeCount++
        }
        if (activeCount == 0) return MotionResult(0f, false, null, false)
        val gain = if (sumF < 1) 1f else (sumB / sumF).toFloat().coerceIn(0.5f, 2f)

        // Noise estimate: median absolute difference (robust while motion covers < 50%).
        val hist = IntArray(256)
        for (i in 0 until n) if (mask[i]) {
            val d = abs((luma[i].toInt() and 0xff) * gain - b[i]).toInt().coerceAtMost(255)
            hist[d]++
        }
        var acc = 0
        var median = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc * 2 >= activeCount) { median = v; break }
        }
        noise = noise * 0.9f + median * 0.1f

        val base = 40f - (cfg.sensitivity.coerceIn(1, 100) - 1) * (34f / 99f)
        val threshold = base + 2.5f * noise

        for (i in 0 until n) {
            changed[i] = mask[i] && abs((luma[i].toInt() and 0xff) * gain - b[i]) > threshold
        }

        // Keep changed pixels that have support in their 3x3 neighbourhood.
        var count = 0
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (!changed[i]) continue
                var support = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= height) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= width) continue
                        if (changed[yy * width + xx]) support++
                    }
                }
                if (support >= 4) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val score = count * 100f / activeCount

        if (cfg.ignoreLightChanges && score > LIGHT_CHANGE_PERCENT) {
            // A light was switched on/off: adopt the new image as the background.
            for (i in 0 until n) b[i] = (luma[i].toInt() and 0xff).toFloat()
            hits = 0
            return MotionResult(score, active, null, true)
        }

        // Background update: fast where static, slow where something changed.
        for (i in 0 until n) {
            val f = (luma[i].toInt() and 0xff) * gain
            val alpha = if (changed[i]) 0.004f else 0.05f
            b[i] += (f - b[i]) * alpha
        }

        val frameMotion = score >= cfg.minAreaPercent
        if (frameMotion) {
            hits++
            if (hits >= 2) {
                active = true
                lastMotionMs = timeMs
            }
        } else {
            hits = 0
            if (active && timeMs - lastMotionMs > RELEASE_MS) active = false
        }
        val box = if (maxX >= 0 && frameMotion) floatArrayOf(
            minX.toFloat() / width, minY.toFloat() / height,
            (maxX - minX + 1).toFloat() / width, (maxY - minY + 1).toFloat() / height,
        ) else null
        return MotionResult(score, active, box, false)
    }

    private fun activeMask(zones: ZoneMask?): BooleanArray {
        val key = zones?.format() ?: ""
        activeMask?.let { if (key == maskKey && it.size == width * height) return it }
        val m = BooleanArray(width * height) { i ->
            if (zones == null) true
            else {
                val col = (i % width) * zones.cols / width
                val row = (i / width) * zones.rows / height
                !zones.isExcluded(col, row)
            }
        }
        activeMask = m
        maskKey = key
        return m
    }

    companion object {
        private const val LIGHT_CHANGE_PERCENT = 55f
        private const val RELEASE_MS = 1500L
    }
}
