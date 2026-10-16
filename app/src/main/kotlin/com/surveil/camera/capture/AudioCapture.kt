package com.surveil.camera.capture

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import com.surveil.core.media.AudioFormat
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
import java.nio.ByteOrder
import kotlin.math.pow
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Microphone → AAC-LC (mono, 64 kbit/s). Timestamps are on CLOCK_MONOTONIC, the same clock the
 * renderer uses for video frames, so audio and video stay in sync in the stream and in recordings.
 */
class AudioCapture(private val gainDb: Int, private val listener: Listener) {
    interface Listener {
        fun onAudioFrame(frame: MediaFrame)
        fun onAudioError(message: String)
    }

    private var sampleRate = 48000
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    val format: AudioFormat get() = AudioFormat.aacLc(sampleRate, 1)

    /** Opens the microphone and the encoder. Throws if either is unavailable. */
    @SuppressLint("MissingPermission")
    fun start() {
        var minBuf = 0
        for (rate in intArrayOf(48000, 44100)) {
            minBuf = AudioRecord.getMinBufferSize(rate, AndroidAudioFormat.CHANNEL_IN_MONO, AndroidAudioFormat.ENCODING_PCM_16BIT)
            if (minBuf > 0) {
                sampleRate = rate
                break
            }
        }
        if (minBuf <= 0) error("Микрофон не поддерживает нужный формат")
        val bufSize = maxOf(minBuf * 4, CHUNK * 2 * 8)
        val rec = listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { src ->
            runCatching {
                AudioRecord(src, sampleRate, AndroidAudioFormat.CHANNEL_IN_MONO, AndroidAudioFormat.ENCODING_PCM_16BIT, bufSize)
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
        } ?: error("Микрофон недоступен")
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2 * 2)
        }
        val c = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            rec.release()
            throw e
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release(); c.release()
            error("Микрофон занят другим приложением")
        }
        record = rec
        codec = c
        running = true
        thread = Thread({ loop(rec, c) }, "audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "audio capture $sampleRate Hz, gain $gainDb dB")
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        record = null
        codec = null
    }

    private fun loop(rec: AudioRecord, c: MediaCodec) {
        val pcm = ShortArray(CHUNK)
        val gain = 10.0.pow(gainDb / 20.0).toFloat()
        val ts = AudioTimestamp()
        val info = MediaCodec.BufferInfo()
        var framesRead = 0L
        var lastPtsUs = Long.MIN_VALUE
        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n < 0) {
                    listener.onAudioError("Ошибка чтения микрофона ($n)")
                    return
                }
                if (n == 0) continue
                // Time of the first sample of this chunk on CLOCK_MONOTONIC.
                val ptsNs = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / sampleRate
                } else {
                    System.nanoTime() - n * 1_000_000_000L / sampleRate
                }
                framesRead += n
                if (gain != 1f) {
                    for (i in 0 until n) pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
                var ptsUs = ptsNs / 1000
                if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                lastPtsUs = ptsUs
                val idx = c.dequeueInputBuffer(20_000)
                if (idx >= 0) {
                    val buf = c.getInputBuffer(idx)!!
                    buf.clear()
                    buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, n)
                    c.queueInputBuffer(idx, 0, n * 2, ptsUs, 0)
                }
                drain(c, info)
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "audio loop failed", e)
                listener.onAudioError("Сбой записи звука: ${e.message}")
            }
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) return
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val out = c.getOutputBuffer(idx)!!
                val data = ByteArray(info.size)
                out.position(info.offset)
                out.get(data)
                listener.onAudioFrame(MediaFrame(Track.AUDIO, data, info.presentationTimeUs, true, System.currentTimeMillis()))
            }
            c.releaseOutputBuffer(idx, false)
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val CHUNK = 1024
    }
}
