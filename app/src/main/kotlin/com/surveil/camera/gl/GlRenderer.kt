package com.surveil.camera.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Render thread that fans every camera frame out to:
 * - the H.264 encoder input surface (rate-limited to the target fps, with the date/time overlay),
 * - the on-screen preview (only while the app shows it),
 * - a tiny luma image for motion detection,
 * - one-off snapshots (JPEG thumbnails / web snapshots).
 * Rotation, mirroring and aspect cropping are applied here, so every consumer sees the same picture.
 */
class GlRenderer(private val callbacks: Callbacks) {

    interface Callbacks {
        fun onFirstFrame()
        fun onLuma(luma: ByteArray, width: Int, height: Int, timeMs: Long)
        fun onError(t: Throwable)
    }

    data class Config(
        /** SurfaceTexture buffer size requested from the camera (sensor orientation). */
        val cameraWidth: Int,
        val cameraHeight: Int,
        /** Whether the camera image becomes portrait after the SurfaceTexture transform. */
        val naturalPortrait: Boolean,
        /** Encoder frame size. */
        val outWidth: Int,
        val outHeight: Int,
        /** Extra rotation (degrees, counter-clockwise device rotation) applied to the natural image. */
        val rotation: Int,
        val fps: Int,
        /** Temporal denoise strength: percent of the previous frame mixed in (0 = off). */
        val denoise: Int = 0,
    )

    data class Overlay(
        val showTime: Boolean = true,
        val name: String? = null,
        /** tl | tr | bl | br */
        val position: String = "tl",
        val sizePercent: Int = 100,
        val mirror: Boolean = false,
    )

    private val thread = HandlerThread("gl-render").apply { start() }
    private val handler = Handler(thread.looper)

    // GL-thread state
    private var egl: EglCore? = null
    private var programs: GlPrograms? = null
    private var pbuffer: EGLSurface? = null
    private var encoderSurface: EGLSurface? = null
    private var previewSurface: EGLSurface? = null
    private var previewSize = 0 to 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var oesTex = 0
    private var cfg: Config? = null
    private var overlay = Overlay()
    private val stMatrix = FloatArray(16)
    private val userMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private var userMatrixDirty = true
    private var nextEncodeNs = 0L
    /** Offset that moves camera timestamps onto CLOCK_MONOTONIC (shared with audio). */
    private var clockOffsetNs: Long? = null
    private var firstFrame = true
    private var released = false

    private var motionIntervalNs = 200_000_000L
    private var lastMotionNs = 0L
    private var lumaFbo: Fbo? = null
    private var lumaBuf: ByteBuffer? = null

    /** Temporal denoise: ping-pong buffers holding the accumulated frame. */
    private var denoiseMix = 0
    private var denoiseA: Fbo? = null
    private var denoiseB: Fbo? = null
    /** Result of the newest denoise pass (null before the first complete pass). */
    private var frameFbo: Fbo? = null

    private var osdTex = 0
    private var osdBitmap: Bitmap? = null
    private var osdKey = ""
    private val osdTime = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)

    private class SnapshotRequest(val maxWidth: Int, val callback: (Bitmap?) -> Unit)
    private val snapshots = ArrayList<SnapshotRequest>()

    /** Sets up EGL and returns the Surface the camera must render into. Blocks the caller. */
    fun start(config: Config, encoderInput: Surface): Surface {
        var result: Surface? = null
        var failure: Throwable? = null
        val latch = CountDownLatch(1)
        handler.post {
            try {
                result = setup(config, encoderInput)
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) error("GL setup timed out")
        failure?.let { throw it }
        return result!!
    }

    fun setOverlay(o: Overlay) {
        handler.post {
            if (o.mirror != overlay.mirror) userMatrixDirty = true
            overlay = o
            osdKey = "" // force re-render
        }
    }

    fun setMotionIntervalMs(ms: Int) {
        handler.post { motionIntervalNs = ms * 1_000_000L }
    }

    /** Turns temporal denoise on/off live (percent of the previous frame, 0 = off). */
    fun setDenoise(percent: Int) {
        handler.post {
            val p = percent.coerceIn(0, MAX_DENOISE)
            if (p == denoiseMix) return@post
            denoiseMix = p
            frameFbo = null
            if (p == 0) {
                denoiseA?.release(); denoiseB?.release()
                denoiseA = null; denoiseB = null
            } else {
                allocateDenoiseBuffers()
            }
        }
    }

    /** Attaches/detaches the on-screen preview. Blocks until done so a destroyed Surface is never used. */
    fun setPreview(surface: Surface?) {
        if (released) return
        val latch = CountDownLatch(1)
        // post() fails if the render thread already quit (pipeline restarting): nothing to wait for.
        val posted = handler.post {
            try {
                val e = egl
                previewSurface?.let { s -> e?.releaseSurface(s) }
                previewSurface = null
                if (surface != null && e != null && surface.isValid) {
                    previewSurface = e.createWindowSurface(surface)
                    previewSize = e.querySize(previewSurface!!)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "preview surface failed", t)
                previewSurface = null
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(2, TimeUnit.SECONDS)
    }

    fun snapshot(maxWidth: Int, callback: (Bitmap?) -> Unit) {
        val posted = handler.post {
            if (egl == null) callback(null) else snapshots += SnapshotRequest(maxWidth, callback)
        }
        if (!posted) callback(null)
    }

    /** Releases everything. Blocks until done. */
    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                teardown()
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    // ---------------- GL thread ----------------

    private fun setup(config: Config, encoderInput: Surface): Surface {
        cfg = config
        val e = EglCore()
        egl = e
        pbuffer = e.createPbufferSurface(1, 1)
        e.makeCurrent(pbuffer!!)
        programs = GlPrograms()
        oesTex = GlPrograms.createOesTexture()
        osdTex = GlPrograms.create2dTexture()
        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(config.cameraWidth, config.cameraHeight)
        st.setOnFrameAvailableListener({ onFrame() }, handler)
        surfaceTexture = st
        encoderSurface = e.createWindowSurface(encoderInput)
        val longSide = 160
        val (lw, lh) = if (config.outWidth >= config.outHeight) {
            longSide to (longSide * config.outHeight / config.outWidth)
        } else {
            (longSide * config.outWidth / config.outHeight) to longSide
        }
        lumaFbo = Fbo(lw, lh)
        lumaBuf = ByteBuffer.allocateDirect(lw * lh * 4).order(ByteOrder.nativeOrder())
        denoiseMix = config.denoise.coerceIn(0, MAX_DENOISE)
        allocateDenoiseBuffers()
        userMatrixDirty = true
        firstFrame = true
        nextEncodeNs = 0
        clockOffsetNs = null
        val s = Surface(st)
        cameraSurface = s
        return s
    }

    private fun teardown() {
        val e = egl ?: return
        runCatching {
            e.makeCurrent(pbuffer!!)
            lumaFbo?.release()
            denoiseA?.release(); denoiseB?.release()
            denoiseA = null; denoiseB = null; frameFbo = null
            denoiseMix = 0
            if (osdTex != 0) GLES20.glDeleteTextures(1, intArrayOf(osdTex), 0)
            if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
            programs?.release()
        }
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        cameraSurface?.release()
        previewSurface?.let { e.releaseSurface(it) }
        encoderSurface?.let { e.releaseSurface(it) }
        pbuffer?.let { e.releaseSurface(it) }
        e.release()
        egl = null
        snapshots.forEach { it.callback(null) }
        snapshots.clear()
        osdBitmap?.recycle()
        osdBitmap = null
    }

    private fun onFrame() {
        val e = egl ?: return
        val st = surfaceTexture ?: return
        val c = cfg ?: return
        try {
            e.makeCurrent(pbuffer!!)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            val raw = st.timestamp
            // Camera timestamps are CLOCK_BOOTTIME on most devices, CLOCK_MONOTONIC on some:
            // detect once and convert, so video and audio share one clock.
            val offset = clockOffsetNs ?: run {
                val mono = System.nanoTime()
                val boot = android.os.SystemClock.elapsedRealtimeNanos()
                (if (kotlin.math.abs(raw - boot) < kotlin.math.abs(raw - mono)) mono - boot else 0L).also { clockOffsetNs = it }
            }
            val ts = raw + offset
            if (userMatrixDirty) {
                buildUserMatrix(c)
                userMatrixDirty = false
            }
            Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, userMatrix, 0)
            val now = System.currentTimeMillis()
            prepareOsd(c, now)
            if (denoiseMix > 0) renderDenoised()

            // Encoder, rate-limited to the target fps (the camera may run faster).
            val interval = 1_000_000_000L / c.fps.coerceAtLeast(1)
            if (ts >= nextEncodeNs - TOLERANCE_NS) {
                nextEncodeNs = if (nextEncodeNs == 0L || ts - nextEncodeNs > interval) ts + interval else nextEncodeNs + interval
                encoderSurface?.let { s ->
                    e.makeCurrent(s)
                    GLES20.glViewport(0, 0, c.outWidth, c.outHeight)
                    drawFrame()
                    drawOsd(c.outWidth, c.outHeight)
                    e.setPresentationTime(s, ts)
                    e.swapBuffers(s)
                }
            }

            previewSurface?.let { s ->
                try {
                    e.makeCurrent(s)
                    val (pw, ph) = previewSize
                    GLES20.glViewport(0, 0, pw, ph)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    // letterbox the output aspect into the preview surface
                    val outAspect = c.outWidth.toFloat() / c.outHeight
                    var vw = pw
                    var vh = (pw / outAspect).toInt()
                    if (vh > ph) { vh = ph; vw = (ph * outAspect).toInt() }
                    GLES20.glViewport((pw - vw) / 2, (ph - vh) / 2, vw, vh)
                    drawFrame()
                    drawOsd(vw, vh)
                    if (!e.swapBuffers(s)) {
                        e.releaseSurface(s)
                        previewSurface = null
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "preview draw failed", t)
                    runCatching { e.releaseSurface(s) }
                    previewSurface = null
                }
            }

            if (ts - lastMotionNs >= motionIntervalNs) {
                lastMotionNs = ts
                e.makeCurrent(pbuffer!!)
                readLuma(now)
            }

            if (snapshots.isNotEmpty()) {
                e.makeCurrent(pbuffer!!)
                val list = ArrayList(snapshots)
                snapshots.clear()
                for (r in list) r.callback(renderSnapshot(c, r.maxWidth))
            }

            if (firstFrame) {
                firstFrame = false
                callbacks.onFirstFrame()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
            callbacks.onError(t)
        }
    }

    private fun buildUserMatrix(c: Config) {
        val natW = if (c.naturalPortrait) c.cameraHeight else c.cameraWidth
        val natH = if (c.naturalPortrait) c.cameraWidth else c.cameraHeight
        val quarter = (c.rotation / 90) % 2 == 1
        val natAspect = if (quarter) natH.toFloat() / natW else natW.toFloat() / natH
        val outAspect = c.outWidth.toFloat() / c.outHeight
        var sx = 1f
        var sy = 1f
        if (natAspect > outAspect) sx = outAspect / natAspect else sy = natAspect / outAspect
        Matrix.setIdentityM(userMatrix, 0)
        Matrix.translateM(userMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(userMatrix, 0, -c.rotation.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(userMatrix, 0, if (overlay.mirror) -sx else sx, sy, 1f)
        Matrix.translateM(userMatrix, 0, -0.5f, -0.5f, 0f)
    }

    private fun drawCamera() {
        val p = programs!!.camera
        p.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(p.sTex, 0)
        GLES20.glUniformMatrix4fv(p.uTex, 1, false, texMatrix, 0)
        programs!!.drawFullQuad(p)
    }

    // ---- temporal denoise ----

    private fun allocateDenoiseBuffers() {
        val c = cfg ?: return
        if (denoiseMix == 0) return
        if (denoiseA?.width == c.outWidth && denoiseA?.height == c.outHeight) return
        denoiseA?.release(); denoiseB?.release()
        denoiseA = Fbo(c.outWidth, c.outHeight)
        denoiseB = Fbo(c.outWidth, c.outHeight)
        frameFbo = null
    }

    /**
     * Accumulates the current camera frame into [frameFbo]: draw the frame into the buffer that is
     * not the previous result, then blend the previous result over it. Noise is averaged out over
     * frames; moving objects pick up ghosting when the mix is high.
     */
    private fun renderDenoised() {
        val a = denoiseA ?: return
        val b = denoiseB ?: return
        val c = cfg ?: return
        val prev = frameFbo
        val dst = if (prev === a) b else a
        dst.bind()
        GLES20.glViewport(0, 0, c.outWidth, c.outHeight)
        drawCamera()
        if (prev != null) {
            val p = programs!!.blend
            p.use()
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prev.tex)
            GLES20.glUniform1i(p.sTex, 0)
            GLES20.glUniform1f(p.uMix, denoiseMix.toFloat() / 100f)
            programs!!.drawFullQuad(p)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        Fbo.unbind()
        frameFbo = dst
    }

    /** The frame every consumer sees: the accumulated one while denoising, the camera frame otherwise. */
    private fun drawFrame() {
        val tex = frameFbo?.tex
        if (tex == null) {
            drawCamera()
            return
        }
        val p = programs!!.frame
        p.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(p.sTex, 0)
        programs!!.drawFullQuad(p)
    }

    // ---- date/time overlay ----

    private fun prepareOsd(c: Config, now: Long) {
        val o = overlay
        val text = buildString {
            if (o.showTime) append(osdTime.format(Date(now)))
            if (!o.name.isNullOrBlank()) {
                if (isNotEmpty()) append("   ")
                append(o.name)
            }
        }
        val key = text + "|" + o.sizePercent + "|" + c.outHeight
        if (key == osdKey) return
        osdKey = key
        osdBitmap?.recycle()
        osdBitmap = null
        if (text.isEmpty()) return
        val shortSide = minOf(c.outWidth, c.outHeight)
        val textSize = (shortSide * 0.036f * o.sizePercent / 100f).coerceAtLeast(10f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val pad = textSize * 0.35f
        val w = (paint.measureText(text) + pad * 2).toInt().coerceAtLeast(1)
        val fm = paint.fontMetrics
        val h = (fm.descent - fm.ascent + pad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), pad, pad, bg)
        canvas.drawText(text, pad, pad - fm.ascent, paint)
        osdBitmap = bmp
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    /** Draws the overlay into the current viewport of size [vw]×[vh] (overlay scaled to the output size). */
    private fun drawOsd(vw: Int, vh: Int) {
        val bmp = osdBitmap ?: return
        val c = cfg ?: return
        val scale = vw.toFloat() / c.outWidth
        val bw = bmp.width * scale
        val bh = bmp.height * scale
        val margin = minOf(vw, vh) * 0.025f
        val left = if (overlay.position.endsWith("r")) vw - margin - bw else margin
        val top = if (overlay.position.startsWith("b")) vh - margin - bh else margin
        // pixel (top-left origin) -> NDC
        val x0 = left / vw * 2f - 1f
        val x1 = (left + bw) / vw * 2f - 1f
        val y1 = 1f - top / vh * 2f
        val y0 = 1f - (top + bh) / vh * 2f
        val p = programs!!.overlay
        p.use()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
        GLES20.glUniform1i(p.sTex, 0)
        programs!!.drawRect(p, x0, y0, x1, y1)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ---- motion analysis readback ----

    private fun readLuma(nowMs: Long) {
        val fbo = lumaFbo ?: return
        val buf = lumaBuf ?: return
        fbo.bind()
        GLES20.glViewport(0, 0, fbo.width, fbo.height)
        val tex = frameFbo?.tex
        if (tex != null) {
            val p = programs!!.luma2d
            p.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1i(p.sTex, 0)
            GLES20.glUniform2f(p.uStep, 1f / fbo.width / 4f, 1f / fbo.height / 4f)
            programs!!.drawFullQuad(p)
        } else {
            val p = programs!!.luma
            p.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniform1i(p.sTex, 0)
            GLES20.glUniformMatrix4fv(p.uTex, 1, false, texMatrix, 0)
            GLES20.glUniform2f(p.uStep, 1f / fbo.width / 4f, 1f / fbo.height / 4f)
            programs!!.drawFullQuad(p)
        }
        buf.clear()
        GLES20.glReadPixels(0, 0, fbo.width, fbo.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        Fbo.unbind()
        val w = fbo.width
        val h = fbo.height
        val luma = ByteArray(w * h)
        for (y in 0 until h) {
            val src = (h - 1 - y) * w * 4 // GL rows are bottom-up
            for (x in 0 until w) luma[y * w + x] = buf.get(src + x * 4)
        }
        callbacks.onLuma(luma, w, h, nowMs)
    }

    // ---- snapshots ----

    private fun renderSnapshot(c: Config, maxWidth: Int): Bitmap? = try {
        val scale = minOf(1f, maxWidth.toFloat() / c.outWidth)
        val w = (c.outWidth * scale).toInt().coerceAtLeast(2) and 1.inv()
        val h = (c.outHeight * scale).toInt().coerceAtLeast(2) and 1.inv()
        val fbo = Fbo(w, h)
        fbo.bind()
        GLES20.glViewport(0, 0, w, h)
        drawFrame()
        drawOsd(w, h)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        Fbo.unbind()
        fbo.release()
        // flip vertically
        val row = ByteArray(w * 4)
        val flipped = ByteBuffer.allocateDirect(w * h * 4)
        for (y in h - 1 downTo 0) {
            buf.position(y * w * 4)
            buf.get(row)
            flipped.put(row)
        }
        flipped.position(0)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(flipped) }
    } catch (t: Throwable) {
        Log.w(TAG, "snapshot failed", t)
        null
    }

    private class Fbo(val width: Int, val height: Int) {
        val tex = GlPrograms.create2dTexture()
        val id: Int

        init {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            id = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete: $status" }
        }

        fun bind() = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id)

        fun release() {
            GLES20.glDeleteFramebuffers(1, intArrayOf(id), 0)
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        }

        companion object {
            fun unbind() = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    companion object {
        private const val TAG = "GlRenderer"
        private const val TOLERANCE_NS = 4_000_000L
        private const val MAX_DENOISE = 80
    }
}
