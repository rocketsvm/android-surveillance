package com.surveil.core.media

import com.surveil.core.h264.H264
import com.surveil.core.h264.SpsInfo

/** H.264 stream parameters (from the encoder's codec-config output). */
class VideoFormat(val sps: ByteArray, val pps: ByteArray) {
    val info: SpsInfo = H264.parseSps(sps)
    val width: Int get() = info.width
    val height: Int get() = info.height
    val codecString: String get() = info.codecString
    val avcC: ByteArray by lazy { H264.buildAvcC(sps, pps) }

    fun sameAs(other: VideoFormat?): Boolean =
        other != null && sps.contentEquals(other.sps) && pps.contentEquals(other.pps)

    override fun toString() = "VideoFormat(${width}x$height $codecString)"
}

/** AAC-LC stream parameters. [asc] is the AudioSpecificConfig. */
class AudioFormat(val sampleRate: Int, val channels: Int, val asc: ByteArray) {
    val codecString: String get() = "mp4a.40.2"

    fun sameAs(other: AudioFormat?): Boolean =
        other != null && sampleRate == other.sampleRate && channels == other.channels && asc.contentEquals(other.asc)

    companion object {
        /** AudioSpecificConfig for AAC-LC. */
        fun aacLc(sampleRate: Int, channels: Int): AudioFormat {
            val idx = SAMPLE_RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: 4
            val asc = byteArrayOf(
                ((2 shl 3) or (idx ushr 1)).toByte(),
                (((idx and 1) shl 7) or (channels shl 3)).toByte(),
            )
            return AudioFormat(sampleRate, channels, asc)
        }

        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}

enum class Track { VIDEO, AUDIO }

/**
 * One encoded access unit.
 * Video: AVCC (4-byte length-prefixed NAL units, no SPS/PPS/AUD). Audio: raw AAC frame.
 * [ptsUs] is on a monotonic clock shared by both tracks; [wallMs] is the capture wall-clock time.
 */
class MediaFrame(
    val track: Track,
    val data: ByteArray,
    val ptsUs: Long,
    val isKey: Boolean,
    val wallMs: Long,
) {
    val isVideoKey: Boolean get() = track == Track.VIDEO && isKey
}
