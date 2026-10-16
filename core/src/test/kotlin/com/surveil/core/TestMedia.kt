package com.surveil.core

import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
import com.surveil.core.media.VideoFormat
import java.io.File
import java.nio.ByteBuffer

/** Shared fixtures: a real x264 SPS/PPS (1280×720 High@3.1) and synthetic AVCC frames. */
object TestMedia {
    val SPS = hex("6764001facb200a00b760220000003002000000501e30649")
    val PPS = hex("68ebccb22c")
    val format = VideoFormat(SPS, PPS)

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A fake access unit: one NAL of [size] bytes (IDR or non-IDR slice header byte). */
    fun frame(index: Int, fps: Int = 20, key: Boolean = index % (2 * fps) == 0, size: Int = 300, wallMs: Long = 1_700_000_000_000L): MediaFrame {
        val nal = ByteArray(size) { (it * 31 + index).toByte() }
        nal[0] = if (key) 0x65 else 0x41
        val avcc = ByteBuffer.allocate(4 + size).putInt(size).put(nal).array()
        val pts = index * 1_000_000L / fps
        return MediaFrame(Track.VIDEO, avcc, pts, key, wallMs + pts / 1000)
    }

    fun ffprobe(): String? = listOf("ffprobe", "ffprobe.exe").firstNotNullOfOrNull { name ->
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.map { File(it, name) }?.firstOrNull { it.isFile }?.path
    }
}

/** Minimal ISO-BMFF reader for assertions. */
class Box(val type: String, val data: ByteArray, val start: Int, val end: Int) {
    val children: List<Box> by lazy { parse(data, start + 8 + if (type in FULL) 4 else 0, end) }
    fun child(t: String) = children.first { it.type == t }
    fun path(vararg types: String): Box = types.fold(this) { b, t -> b.child(t) }
    fun u32(off: Int): Long = ByteBuffer.wrap(data, start + off, 4).int.toLong() and 0xffffffffL
    fun u64(off: Int): Long = ByteBuffer.wrap(data, start + off, 8).long

    companion object {
        private val FULL = setOf("meta")
        fun parse(data: ByteArray, from: Int = 0, to: Int = data.size): List<Box> {
            val out = ArrayList<Box>()
            var p = from
            while (p + 8 <= to) {
                var size = ByteBuffer.wrap(data, p, 4).int.toLong() and 0xffffffffL
                val type = String(data, p + 4, 4, Charsets.US_ASCII)
                if (size == 1L) size = ByteBuffer.wrap(data, p + 8, 8).long
                if (size == 0L) size = (to - p).toLong()
                if (size < 8 || p + size > to) break
                out += Box(type, data, p, (p + size).toInt())
                p += size.toInt()
            }
            return out
        }
    }
}
