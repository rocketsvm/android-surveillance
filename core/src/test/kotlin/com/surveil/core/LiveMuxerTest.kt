package com.surveil.core

import com.surveil.core.media.AudioFormat
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
import com.surveil.core.stream.LiveFmp4Muxer
import com.surveil.core.stream.StreamItem
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveMuxerTest {
    private val audioFormat = AudioFormat.aacLc(48000, 1)

    /** (trackId, decodeTime, duration) of a fragment produced by [LiveFmp4Muxer]. */
    private fun fragmentInfo(chunk: LiveFmp4Muxer.Out.Chunk): Triple<Int, Long, Int> {
        val moof = Box.parse(chunk.parts[0])[0]
        val traf = moof.child("traf")
        val track = traf.child("tfhd").u32(12).toInt()
        val decodeTime = traf.child("tfdt").u64(12)
        val duration = traf.child("trun").u32(20).toInt()
        return Triple(track, decodeTime, duration)
    }

    private fun audio(ptsUs: Long) = MediaFrame(Track.AUDIO, ByteBuffer.allocate(20).array(), ptsUs, true, 0)

    @Test
    fun framesAreDelayedByOneWithExactDurations() {
        val m = LiveFmp4Muxer()
        assertIs<LiveFmp4Muxer.Out.Init>(m.process(StreamItem.Format(TestMedia.format, null)).single())
        // Non-key frames before the first keyframe are skipped.
        assertTrue(m.process(StreamItem.Frame(TestMedia.frame(1, key = false))).isEmpty())
        assertTrue(m.process(StreamItem.Frame(TestMedia.frame(2, key = true))).isEmpty(), "held until the next frame")
        // Variable frame rate: 50 ms, then 100 ms gap.
        val f3 = TestMedia.frame(3, key = false)
        val out1 = m.process(StreamItem.Frame(f3)).single() as LiveFmp4Muxer.Out.Chunk
        assertEquals(Triple(1, 0L, 4500), fragmentInfo(out1))
        val f5 = TestMedia.frame(5, key = false)
        val out2 = m.process(StreamItem.Frame(f5)).single() as LiveFmp4Muxer.Out.Chunk
        assertEquals(Triple(1, 4500L, 9000), fragmentInfo(out2))
    }

    @Test
    fun audioStartsAtTheFirstVideoKeyframe() {
        val m = LiveFmp4Muxer()
        m.process(StreamItem.Format(TestMedia.format, audioFormat))
        assertTrue(m.process(StreamItem.Frame(audio(0))).isEmpty(), "audio before any video keyframe is dropped")
        m.process(StreamItem.Frame(TestMedia.frame(2, key = true)))         // video base = 100 ms
        assertTrue(m.process(StreamItem.Frame(audio(90_000))).isEmpty(), "audio older than the base is dropped")
        assertTrue(m.process(StreamItem.Frame(audio(110_000))).isEmpty())   // held
        val out = m.process(StreamItem.Frame(audio(131_333))).single() as LiveFmp4Muxer.Out.Chunk
        val (track, decodeTime, duration) = fragmentInfo(out)
        assertEquals(2, track)
        assertEquals(480L, decodeTime)        // 10 ms at 48 kHz
        assertEquals(1024, duration)          // 21.333 ms at 48 kHz
    }

    @Test
    fun formatChangeAfterStartAsksForRestart() {
        val m = LiveFmp4Muxer()
        m.process(StreamItem.Format(TestMedia.format, null))
        assertEquals(listOf<Any>(LiveFmp4Muxer.Out.Restart), m.process(StreamItem.Format(TestMedia.format, null)))
        assertEquals(listOf<Any>(LiveFmp4Muxer.Out.End), m.process(StreamItem.Stop))
    }
}
