package com.surveil.core.mp4

import com.surveil.core.media.AudioFormat
import com.surveil.core.media.VideoFormat

/** Box builders shared by the fragmented (live) and progressive (recording) MP4 writers. */
internal object Mp4Boxes {
    const val MOVIE_TIMESCALE = 1000
    const val VIDEO_TIMESCALE = 90000
    const val VIDEO_TRACK_ID = 1
    const val AUDIO_TRACK_ID = 2

    fun BoxWriter.ftyp(major: String, compatible: List<String>) = box("ftyp") {
        fourcc(major); u32(0x200)
        compatible.forEach { fourcc(it) }
    }

    fun BoxWriter.mvhd(durationMs: Long, nextTrackId: Int) = fullBox("mvhd", 1, 0) {
        u64(0); u64(0) // creation / modification time
        u32(MOVIE_TIMESCALE)
        u64(durationMs)
        u32(0x00010000) // rate 1.0
        u16(0x0100) // volume 1.0
        zeros(10)
        matrix()
        zeros(24) // pre_defined
        u32(nextTrackId)
    }

    fun BoxWriter.tkhd(trackId: Int, durationMs: Long, width: Int, height: Int, audio: Boolean) = fullBox("tkhd", 1, 0x3) {
        u64(0); u64(0)
        u32(trackId)
        u32(0)
        u64(durationMs)
        zeros(8)
        u16(0) // layer
        u16(if (audio) 1 else 0) // alternate_group
        u16(if (audio) 0x0100 else 0) // volume
        u16(0)
        matrix()
        u32(width shl 16); u32(height shl 16)
    }

    fun BoxWriter.mdhd(timescale: Int, duration: Long) = fullBox("mdhd", 1, 0) {
        u64(0); u64(0)
        u32(timescale)
        u64(duration)
        u16(0x55c4) // 'und'
        u16(0)
    }

    fun BoxWriter.hdlr(type: String, name: String) = fullBox("hdlr", 0, 0) {
        u32(0)
        fourcc(type)
        zeros(12)
        bytes(name.toByteArray(Charsets.US_ASCII)); u8(0)
    }

    fun BoxWriter.vmhd() = fullBox("vmhd", 0, 1) { u16(0); zeros(6) }

    fun BoxWriter.smhd() = fullBox("smhd", 0, 0) { u16(0); u16(0) }

    fun BoxWriter.dinf() = box("dinf") {
        fullBox("dref", 0, 0) {
            u32(1)
            fullBox("url ", 0, 1) {}
        }
    }

    fun BoxWriter.stsd(video: VideoFormat) = fullBox("stsd", 0, 0) {
        u32(1)
        box("avc1") {
            zeros(6); u16(1) // reserved, data_reference_index
            u16(0); u16(0); zeros(12)
            u16(video.width); u16(video.height)
            u32(0x00480000); u32(0x00480000) // 72 dpi
            u32(0)
            u16(1) // frame_count
            val name = "SurveillanceCam H.264".toByteArray(Charsets.US_ASCII)
            u8(name.size); bytes(name); zeros(31 - name.size)
            u16(0x0018) // depth
            u16(0xffff) // pre_defined = -1
            box("avcC") { bytes(video.avcC) }
        }
    }

    fun BoxWriter.stsd(audio: AudioFormat) = fullBox("stsd", 0, 0) {
        u32(1)
        box("mp4a") {
            zeros(6); u16(1)
            zeros(8)
            u16(audio.channels); u16(16)
            u16(0); u16(0)
            u32(audio.sampleRate shl 16)
            fullBox("esds", 0, 0) {
                val asc = audio.asc
                // ES_Descriptor
                u8(0x03); u8(3 + 2 + 13 + 2 + asc.size + 3)
                u16(0); u8(0)
                // DecoderConfigDescriptor
                u8(0x04); u8(13 + 2 + asc.size)
                u8(0x40) // MPEG-4 Audio
                u8(0x15) // audio stream
                u24(0); u32(0); u32(0)
                // DecoderSpecificInfo
                u8(0x05); u8(asc.size); bytes(asc)
                // SLConfigDescriptor
                u8(0x06); u8(1); u8(0x02)
            }
        }
    }
}
