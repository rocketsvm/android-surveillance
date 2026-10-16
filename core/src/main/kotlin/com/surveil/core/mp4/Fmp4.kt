package com.surveil.core.mp4

import com.surveil.core.media.AudioFormat
import com.surveil.core.media.VideoFormat
import com.surveil.core.mp4.Mp4Boxes.dinf
import com.surveil.core.mp4.Mp4Boxes.ftyp
import com.surveil.core.mp4.Mp4Boxes.hdlr
import com.surveil.core.mp4.Mp4Boxes.mdhd
import com.surveil.core.mp4.Mp4Boxes.mvhd
import com.surveil.core.mp4.Mp4Boxes.smhd
import com.surveil.core.mp4.Mp4Boxes.stsd
import com.surveil.core.mp4.Mp4Boxes.tkhd
import com.surveil.core.mp4.Mp4Boxes.vmhd

/** Fragmented MP4 (CMAF-style) for Media Source Extensions and progressive live HTTP. */
object Fmp4 {
    const val VIDEO_TRACK = Mp4Boxes.VIDEO_TRACK_ID
    const val AUDIO_TRACK = Mp4Boxes.AUDIO_TRACK_ID
    const val VIDEO_TIMESCALE = Mp4Boxes.VIDEO_TIMESCALE

    fun mimeType(video: VideoFormat, audio: AudioFormat?): String {
        val codecs = if (audio != null) "${video.codecString},${audio.codecString}" else video.codecString
        return "video/mp4; codecs=\"$codecs\""
    }

    fun initSegment(video: VideoFormat, audio: AudioFormat?): ByteArray {
        val w = BoxWriter(1024)
        w.ftyp("isom", listOf("isom", "iso5", "iso6", "avc1", "mp41"))
        w.box("moov") {
            mvhd(0, if (audio != null) 3 else 2)
            box("trak") {
                tkhd(VIDEO_TRACK, 0, video.width, video.height, audio = false)
                box("mdia") {
                    mdhd(VIDEO_TIMESCALE, 0)
                    hdlr("vide", "VideoHandler")
                    box("minf") {
                        vmhd(); dinf()
                        box("stbl") { stsd(video); emptySampleTables() }
                    }
                }
            }
            if (audio != null) {
                box("trak") {
                    tkhd(AUDIO_TRACK, 0, 0, 0, audio = true)
                    box("mdia") {
                        mdhd(audio.sampleRate, 0)
                        hdlr("soun", "SoundHandler")
                        box("minf") {
                            smhd(); dinf()
                            box("stbl") { stsd(audio); emptySampleTables() }
                        }
                    }
                }
            }
            box("mvex") {
                trex(VIDEO_TRACK)
                if (audio != null) trex(AUDIO_TRACK)
            }
        }
        return w.toByteArray()
    }

    /**
     * moof + mdat header for a single-sample fragment; the sample payload must follow immediately.
     * [decodeTime] and [duration] are in the track timescale.
     */
    fun fragmentHeader(sequence: Int, trackId: Int, decodeTime: Long, duration: Int, sampleSize: Int, isKey: Boolean): ByteArray {
        val w = BoxWriter(128)
        var dataOffsetPos = 0
        w.box("moof") {
            fullBox("mfhd", 0, 0) { u32(sequence) }
            box("traf") {
                fullBox("tfhd", 0, 0x020000) { u32(trackId) } // default-base-is-moof
                fullBox("tfdt", 1, 0) { u64(decodeTime) }
                // data-offset | sample-duration | sample-size | sample-flags
                fullBox("trun", 0, 0x000001 or 0x000100 or 0x000200 or 0x000400) {
                    u32(1)
                    dataOffsetPos = this.size
                    u32(0)
                    u32(duration)
                    u32(sampleSize)
                    u32(if (isKey) 0x02000000 else 0x01010000)
                }
            }
        }
        val moofSize = w.size
        w.patchU32(dataOffsetPos, moofSize + 8)
        w.u32(8 + sampleSize); w.fourcc("mdat")
        return w.toByteArray()
    }

    private fun BoxWriter.emptySampleTables() {
        fullBox("stts", 0, 0) { u32(0) }
        fullBox("stsc", 0, 0) { u32(0) }
        fullBox("stsz", 0, 0) { u32(0); u32(0) }
        fullBox("stco", 0, 0) { u32(0) }
    }

    private fun BoxWriter.trex(trackId: Int) = fullBox("trex", 0, 0) {
        u32(trackId); u32(1); u32(0); u32(0); u32(0)
    }
}
