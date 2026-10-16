package com.surveil.camera.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.surveil.core.h264.H264
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
import com.surveil.core.media.VideoFormat
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder fed through an input Surface (zero-copy from the GL renderer).
 * Emits the stream format (SPS/PPS) once and then AVCC frames.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    private val fps: Int,
    bitrateKbps: Int,
    private val keyframeSec: Int,
    private val profile: String,
    private val bitrateMode: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onFormat(format: VideoFormat)
        fun onFrame(frame: MediaFrame)
        fun onEncoderError(e: Exception)
    }

    private val thread = HandlerThread("encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    lateinit var inputSurface: Surface
        private set
    var codecName: String = ""
        private set
    private var formatSent = false
    @Volatile private var running = false

    /** Configures and starts the codec. Throws if no encoder accepts the configuration. */
    fun start() {
        var lastError: Exception? = null
        for (info in candidates()) {
            val c = try {
                MediaCodec.createByCodecName(info.name)
            } catch (e: Exception) {
                lastError = e; continue
            }
            try {
                val format = buildFormat(info)
                c.setCallback(callback, handler)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
                codec = c
                codecName = info.name
                running = true
                Log.i(TAG, "encoder ${info.name} ${width}x$height@$fps profile=$profile mode=$bitrateMode")
                return
            } catch (e: Exception) {
                Log.w(TAG, "encoder ${info.name} rejected config: $e")
                lastError = e
                runCatching { c.release() }
            }
        }
        thread.quitSafely()
        throw IllegalStateException("Нет подходящего H.264-кодировщика для ${width}×$height", lastError)
    }

    fun requestKeyframe() {
        val c = codec ?: return
        handler.post {
            runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
    }

    fun setBitrate(kbps: Int) {
        val c = codec ?: return
        handler.post {
            runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) }) }
        }
    }

    fun release() {
        running = false
        val c = codec
        codec = null
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            runCatching { c?.stop() }
            runCatching { c?.release() }
            done.countDown()
        }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        if (::inputSurface.isInitialized) runCatching { inputSurface.release() }
        thread.quitSafely()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val sps = format.getByteBuffer("csd-0")?.let(::stripStartCode)
            val pps = format.getByteBuffer("csd-1")?.let(::stripStartCode)
            if (sps != null && pps != null) emitFormat(sps, pps)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!running) return
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(data, 0, info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (!formatSent) {
                            val nals = H264.splitAnnexB(data)
                            val sps = nals.firstOrNull { H264.nalType(it[0]) == H264.NAL_SPS }
                            val pps = nals.firstOrNull { H264.nalType(it[0]) == H264.NAL_PPS }
                            if (sps != null && pps != null) emitFormat(sps, pps)
                        }
                    } else if (formatSent) {
                        val avcc = H264.annexBToAvcc(data, 0, data.size)
                        if (avcc.isNotEmpty()) {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 || H264.avccContainsIdr(avcc)
                            listener.onFrame(MediaFrame(Track.VIDEO, avcc, info.presentationTimeUs, key, System.currentTimeMillis()))
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // codec released concurrently
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "codec error", e)
            if (running) listener.onEncoderError(e)
        }
    }

    private fun emitFormat(sps: ByteArray, pps: ByteArray) {
        if (formatSent) return
        try {
            val f = VideoFormat(sps, pps)
            formatSent = true
            listener.onFormat(f)
        } catch (e: Exception) {
            Log.w(TAG, "bad SPS/PPS", e)
        }
    }

    private fun stripStartCode(b: ByteBuffer): ByteArray {
        val a = ByteArray(b.remaining())
        b.duplicate().get(a)
        return H264.splitAnnexB(a).firstOrNull() ?: a
    }

    private fun buildFormat(info: MediaCodecInfo): MediaFormat {
        val caps = info.getCapabilitiesForType(MIME)
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate * 1000)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeSec)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        val enc = caps.encoderCapabilities
        val mode = if (bitrateMode == "cbr") MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        if (enc?.isBitrateModeSupported(mode) == true) f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
        val wanted = when (profile) {
            "high" -> CodecProfileLevel.AVCProfileHigh
            "main" -> CodecProfileLevel.AVCProfileMain
            "baseline" -> CodecProfileLevel.AVCProfileBaseline
            else -> if (caps.profileLevels.any { it.profile == CodecProfileLevel.AVCProfileHigh }) CodecProfileLevel.AVCProfileHigh else -1
        }
        val pl = caps.profileLevels.filter { it.profile == wanted }.maxByOrNull { it.level }
        if (pl != null) {
            f.setInteger(MediaFormat.KEY_PROFILE, pl.profile)
            f.setInteger(MediaFormat.KEY_LEVEL, pl.level)
        }
        if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        return f
    }

    private var currentBitrate = bitrateKbps

    private fun candidates(): List<MediaCodecInfo> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                runCatching { info.getCapabilitiesForType(MIME).videoCapabilities?.isSizeSupported(width, height) == true }.getOrDefault(false)
        }
        return all.sortedBy { if (isHardware(it)) 0 else 1 }
    }

    companion object {
        private const val TAG = "VideoEncoder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        fun isHardware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

        /** Whether some H.264 encoder on the device accepts [w]×[h]. */
        fun supportsSize(w: Int, h: Int): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                    runCatching { info.getCapabilitiesForType(MIME).videoCapabilities?.isSizeSupported(w, h) == true }.getOrDefault(false)
            }
    }
}
