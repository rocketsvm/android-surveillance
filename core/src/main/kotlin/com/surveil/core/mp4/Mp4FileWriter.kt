package com.surveil.core.mp4

import com.surveil.core.media.AudioFormat
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
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
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToLong

/**
 * Progressive MP4 writer: `ftyp`, a growing 64-bit `mdat`, and `moov` appended on [finish] —
 * the layout of MediaMuxer output, playable and seekable everywhere once finished.
 *
 * Crash safety: every sample is also recorded in a small `<file>.journal` sidecar. If the process
 * dies mid-recording, [recover] rebuilds the `moov` from the journal so the clip is not lost.
 */
class Mp4FileWriter private constructor(
    val file: File,
    val video: VideoFormat,
    val audio: AudioFormat?,
    private val raf: RandomAccessFile,
    private val mdatPos: Long,
    private val journal: DataOutputStream?,
    private val journalFos: FileOutputStream?,
) : Closeable {

    private class Table {
        var count = 0
        var sizes = IntArray(512)
        var offsets = LongArray(512)
        var pts = LongArray(512)
        var keys = BooleanArray(512)

        fun add(size: Int, offset: Long, ptsUs: Long, key: Boolean) {
            if (count == sizes.size) {
                val n = count * 2
                sizes = sizes.copyOf(n); offsets = offsets.copyOf(n); pts = pts.copyOf(n); keys = keys.copyOf(n)
            }
            sizes[count] = size; offsets[count] = offset; pts[count] = ptsUs; keys[count] = key
            count++
        }
    }

    private val videoTable = Table()
    private val audioTable = Table()
    private var dataEnd: Long = mdatPos + MDAT_HEADER
    private var lastSyncNs = System.nanoTime()
    private var finished = false

    /** Bytes written so far (approximate final size minus moov). */
    val bytesWritten: Long get() = dataEnd

    /** Video timeline covered so far, in microseconds. */
    val durationUs: Long
        get() = if (videoTable.count < 2) 0 else videoTable.pts[videoTable.count - 1] - videoTable.pts[0]

    val videoSampleCount: Int get() = videoTable.count

    fun write(frame: MediaFrame) {
        check(!finished) { "writer finished" }
        val table = when (frame.track) {
            Track.VIDEO -> videoTable
            Track.AUDIO -> if (audio != null) audioTable else return
        }
        val data = frame.data
        raf.write(data)
        table.add(data.size, dataEnd, frame.ptsUs, frame.isKey)
        dataEnd += data.size
        journal?.let { j ->
            j.writeByte(if (frame.track == Track.VIDEO) 0 else 1)
            j.writeByte(if (frame.isKey) 1 else 0)
            j.writeInt(data.size)
            j.writeLong(frame.ptsUs)
            if (frame.isVideoKey) j.flush()
        }
        val now = System.nanoTime()
        if (now - lastSyncNs > SYNC_INTERVAL_NS) {
            lastSyncNs = now
            sync()
        }
    }

    /** Flushes data and journal to stable storage. */
    fun sync() {
        runCatching {
            journal?.flush()
            journalFos?.fd?.sync()
            raf.fd.sync()
        }
    }

    /**
     * Writes the `moov` and closes the file. Returns the video duration in milliseconds.
     * A clip without any video samples is deleted and 0 is returned.
     */
    fun finish(): Long {
        if (finished) return 0
        finished = true
        runCatching { journal?.close() }
        if (videoTable.count == 0) {
            raf.close()
            file.delete()
            journalFile(file).delete()
            return 0
        }
        val moov = buildMoov()
        raf.seek(mdatPos + 8)
        raf.writeLong(dataEnd - mdatPos)
        raf.seek(dataEnd)
        raf.write(moov)
        raf.setLength(dataEnd + moov.size)
        raf.fd.sync()
        raf.close()
        journalFile(file).delete()
        return trackDurationUs(videoTable) / 1000
    }

    /** Aborts without finishing: the journal stays, so the clip can still be [recover]ed. */
    override fun close() {
        if (finished) return
        finished = true
        runCatching { journal?.close() }
        runCatching { raf.close() }
    }

    private fun trackDurationUs(t: Table): Long {
        if (t.count == 0) return 0
        val span = t.pts[t.count - 1] - t.pts[0]
        val last = if (t.count >= 2) span / (t.count - 1) else 0
        return span + last
    }

    private fun ticks(t: Table, timescale: Int, defaultDuration: Long): LongArray {
        val n = t.count
        val out = LongArray(n + 1)
        val base = t.pts[0]
        for (i in 0 until n) {
            var v = ((t.pts[i] - base) * timescale / 1_000_000.0).roundToLong()
            if (i > 0 && v <= out[i - 1]) v = out[i - 1] + 1
            out[i] = v
        }
        val lastDur = if (n >= 2) maxOf(1, out[n - 1] - out[n - 2]) else defaultDuration
        out[n] = out[n - 1] + lastDur
        return out
    }

    private fun buildMoov(): ByteArray {
        val hasAudio = audio != null && audioTable.count > 0
        val vTicks = ticks(videoTable, Mp4Boxes.VIDEO_TIMESCALE, 3000)
        val aTicks = if (hasAudio) ticks(audioTable, audio.sampleRate, 1024) else null
        val start = if (hasAudio) minOf(videoTable.pts[0], audioTable.pts[0]) else videoTable.pts[0]
        val vOffsetMs = (videoTable.pts[0] - start) / 1000
        val vDurMs = vTicks[videoTable.count] * 1000 / Mp4Boxes.VIDEO_TIMESCALE
        val aOffsetMs = if (hasAudio) (audioTable.pts[0] - start) / 1000 else 0
        val aDurMs = if (hasAudio) aTicks!![audioTable.count] * 1000 / audio.sampleRate else 0
        val movieMs = maxOf(vOffsetMs + vDurMs, aOffsetMs + aDurMs)

        val w = BoxWriter(4096 + (videoTable.count + audioTable.count) * 24)
        w.box("moov") {
            mvhd(movieMs, if (hasAudio) 3 else 2)
            box("trak") {
                tkhd(Mp4Boxes.VIDEO_TRACK_ID, vOffsetMs + vDurMs, video.width, video.height, audio = false)
                edts(vOffsetMs, vDurMs)
                box("mdia") {
                    mdhd(Mp4Boxes.VIDEO_TIMESCALE, vTicks[videoTable.count])
                    hdlr("vide", "VideoHandler")
                    box("minf") {
                        vmhd(); dinf()
                        box("stbl") {
                            stsd(video)
                            sampleTables(videoTable, vTicks, syncTable = true)
                        }
                    }
                }
            }
            if (hasAudio) {
                val a = audio
                box("trak") {
                    tkhd(Mp4Boxes.AUDIO_TRACK_ID, aOffsetMs + aDurMs, 0, 0, audio = true)
                    edts(aOffsetMs, aDurMs)
                    box("mdia") {
                        mdhd(a.sampleRate, aTicks!![audioTable.count])
                        hdlr("soun", "SoundHandler")
                        box("minf") {
                            smhd(); dinf()
                            box("stbl") {
                                stsd(a)
                                sampleTables(audioTable, aTicks, syncTable = false)
                            }
                        }
                    }
                }
            }
        }
        return w.toByteArray()
    }

    private fun BoxWriter.edts(offsetMs: Long, durationMs: Long) {
        if (offsetMs <= 0) return
        box("edts") {
            fullBox("elst", 1, 0) {
                u32(2)
                u64(offsetMs); u64(-1L); u16(1); u16(0) // empty edit
                u64(durationMs); u64(0); u16(1); u16(0)
            }
        }
    }

    private fun BoxWriter.sampleTables(t: Table, tk: LongArray, syncTable: Boolean) {
        val n = t.count
        // stts: run-length encoded sample durations
        val runs = ArrayList<LongArray>()
        for (i in 0 until n) {
            val d = tk[i + 1] - tk[i]
            val last = runs.lastOrNull()
            if (last != null && last[1] == d) last[0]++ else runs += longArrayOf(1, d)
        }
        fullBox("stts", 0, 0) {
            u32(runs.size)
            runs.forEach { u32(it[0]); u32(it[1]) }
        }
        if (syncTable) {
            var keys = 0
            for (i in 0 until n) if (t.keys[i]) keys++
            if (keys < n) fullBox("stss", 0, 0) {
                u32(keys)
                for (i in 0 until n) if (t.keys[i]) u32(i + 1)
            }
        }
        fullBox("stsz", 0, 0) {
            u32(0); u32(n)
            for (i in 0 until n) u32(t.sizes[i])
        }
        fullBox("stsc", 0, 0) { u32(1); u32(1); u32(1); u32(1) }
        if (t.offsets[n - 1] > 0xffffffffL) {
            fullBox("co64", 0, 0) { u32(n); for (i in 0 until n) u64(t.offsets[i]) }
        } else {
            fullBox("stco", 0, 0) { u32(n); for (i in 0 until n) u32(t.offsets[i]) }
        }
    }

    companion object {
        private const val MDAT_HEADER = 16L
        private const val SYNC_INTERVAL_NS = 5_000_000_000L
        private const val MAGIC = 0x53434a31 // "SCJ1"

        fun journalFile(file: File) = File(file.path + ".journal")

        fun create(file: File, video: VideoFormat, audio: AudioFormat?): Mp4FileWriter {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            val head = BoxWriter(64)
            head.ftyp("isom", listOf("isom", "iso2", "avc1", "mp41"))
            val mdatPos = head.size.toLong()
            head.u32(1); head.fourcc("mdat"); head.u64(0) // largesize patched on finish
            raf.write(head.toByteArray())

            val jfos = FileOutputStream(journalFile(file))
            val j = DataOutputStream(BufferedOutputStream(jfos, 16 * 1024))
            j.writeInt(MAGIC)
            j.writeShort(video.sps.size); j.write(video.sps)
            j.writeShort(video.pps.size); j.write(video.pps)
            if (audio != null) {
                j.writeByte(1); j.writeInt(audio.sampleRate); j.writeByte(audio.channels)
                j.writeByte(audio.asc.size); j.write(audio.asc)
            } else {
                j.writeByte(0)
            }
            j.writeLong(mdatPos)
            j.flush()
            return Mp4FileWriter(file, video, audio, raf, mdatPos, j, jfos)
        }

        /** What [recover] salvaged. */
        class Recovered(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

        /**
         * Finishes a clip left behind by a crash, using its journal. Returns null if nothing could
         * be salvaged (both files are then removed).
         */
        fun recover(file: File): Recovered? {
            val jf = journalFile(file)
            if (!jf.exists() || !file.exists()) {
                jf.delete()
                return null
            }
            val result = runCatching {
                // Read the whole journal first: an open file cannot be deleted on Windows.
                val journalBytes = jf.readBytes()
                DataInputStream(journalBytes.inputStream()).use { j ->
                    require(j.readInt() == MAGIC) { "bad journal" }
                    val sps = ByteArray(j.readUnsignedShort()).also { j.readFully(it) }
                    val pps = ByteArray(j.readUnsignedShort()).also { j.readFully(it) }
                    val audio = if (j.readUnsignedByte() == 1) {
                        val rate = j.readInt()
                        val ch = j.readUnsignedByte()
                        val asc = ByteArray(j.readUnsignedByte()).also { j.readFully(it) }
                        AudioFormat(rate, ch, asc)
                    } else null
                    val mdatPos = j.readLong()
                    val raf = RandomAccessFile(file, "rw")
                    val writer = Mp4FileWriter(file, VideoFormat(sps, pps), audio, raf, mdatPos, null, null)
                    val fileLen = raf.length()
                    try {
                        while (true) {
                            val track = j.readUnsignedByte()
                            val key = j.readUnsignedByte() == 1
                            val size = j.readInt()
                            val pts = j.readLong()
                            if (writer.dataEnd + size > fileLen) break
                            val table = if (track == 0) writer.videoTable else writer.audioTable
                            table.add(size, writer.dataEnd, pts, key)
                            writer.dataEnd += size
                        }
                    } catch (_: EOFException) {
                    }
                    raf.setLength(writer.dataEnd)
                    val ms = writer.finish()
                    if (ms > 0) Recovered(ms, writer.video.width, writer.video.height, writer.audio != null && writer.audioTable.count > 0) else null
                }
            }.getOrNull()
            if (result == null) {
                file.delete()
                jf.delete()
            }
            return result
        }
    }
}
