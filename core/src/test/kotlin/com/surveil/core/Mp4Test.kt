package com.surveil.core

import com.surveil.core.mp4.Fmp4
import com.surveil.core.mp4.Mp4FileWriter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Mp4Test {
    private fun tmp(): File = Files.createTempDirectory("mp4test").toFile()

    @Test
    fun progressiveFileHasConsistentTables() {
        val file = File(tmp(), "clip.mp4")
        val w = Mp4FileWriter.create(file, TestMedia.format, null)
        repeat(100) { w.write(TestMedia.frame(it)) }
        val ms = w.finish()
        assertEquals(5000, ms) // 100 frames at 20 fps
        assertFalse(Mp4FileWriter.journalFile(file).exists())

        val data = file.readBytes()
        val top = Box.parse(data)
        assertEquals(listOf("ftyp", "mdat", "moov"), top.map { it.type })
        val mdat = top[1]
        assertEquals(data.size.toLong() - top[2].let { it.end - it.start } - top[0].end, (mdat.end - mdat.start).toLong())
        val stbl = top[2].path("trak", "mdia", "minf", "stbl")
        assertEquals(100, stbl.child("stsz").u32(16).toInt())        // sample_count
        assertEquals(3, stbl.child("stss").u32(12).toInt())          // keyframes at 0, 40, 80
        assertEquals(100, stbl.child("stco").u32(12).toInt())
        val stts = stbl.child("stts")
        assertEquals(1, stts.u32(12).toInt())                         // a single run
        assertEquals(4500, stts.u32(20).toInt())                      // 50 ms at 90 kHz
        ffprobeCheck(file, expectFrames = 100)
    }

    @Test
    fun recoversClipAfterCrash() {
        val file = File(tmp(), "crash.mp4")
        val w = Mp4FileWriter.create(file, TestMedia.format, null)
        repeat(61) { w.write(TestMedia.frame(it)) }
        w.sync()
        w.close() // simulate a crash: no moov written
        assertTrue(Mp4FileWriter.journalFile(file).exists())
        // Garbage after the last journaled sample (partially written frame) must be cut off.
        file.appendBytes(ByteArray(123) { 7 })
        val r = assertNotNull(Mp4FileWriter.recover(file))
        assertEquals(1280, r.width)
        assertEquals(3050, r.durationMs)
        assertFalse(Mp4FileWriter.journalFile(file).exists())
        val top = Box.parse(file.readBytes())
        assertEquals(listOf("ftyp", "mdat", "moov"), top.map { it.type })
        ffprobeCheck(file, expectFrames = 61)
    }

    @Test
    fun fragmentDataOffsetPointsAtPayload() {
        val init = Fmp4.initSegment(TestMedia.format, null)
        assertEquals(listOf("ftyp", "moov"), Box.parse(init).map { it.type })
        val f = TestMedia.frame(0)
        val head = Fmp4.fragmentHeader(1, Fmp4.VIDEO_TRACK, 0, 4500, f.data.size, true)
        val bytes = head + f.data
        val boxes = Box.parse(bytes)
        assertEquals(listOf("moof", "mdat"), boxes.map { it.type })
        val trun = boxes[0].path("traf", "trun")
        val dataOffset = trun.u32(16).toInt() // after header(8) + version/flags(4) + sample_count(4)
        assertEquals(boxes[1].start + 8, dataOffset)
        assertEquals(f.data.size, bytes.size - dataOffset)
    }

    private fun ffprobeCheck(file: File, expectFrames: Int) {
        val probe = TestMedia.ffprobe() ?: return
        // The synthetic frames are not decodable: only the container is checked.
        val p = ProcessBuilder(probe, "-v", "fatal", "-count_packets", "-select_streams", "v",
            "-show_entries", "stream=nb_read_packets,codec_name,width,height", "-of", "csv=p=0", file.path)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.waitFor(30, TimeUnit.SECONDS)
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals("h264,1280,720,$expectFrames", out, "ffprobe output: $out")
    }
}
