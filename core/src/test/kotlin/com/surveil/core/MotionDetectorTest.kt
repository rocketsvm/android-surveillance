package com.surveil.core

import com.surveil.core.motion.MotionConfig
import com.surveil.core.motion.MotionDetector
import com.surveil.core.motion.ZoneMask
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionDetectorTest {
    private val w = 160
    private val h = 90
    private val rnd = Random(42)
    private val scene = ByteArray(w * h) { i -> (50 + (i % w) / 2 + (i / w)).toByte() }
    private val cfg = MotionConfig(sensitivity = 60, minAreaPercent = 0.8, ignoreLightChanges = true, zones = null)

    private fun frame(noise: Int = 4, gain: Float = 1f, box: IntArray? = null): ByteArray {
        val f = ByteArray(w * h) { i ->
            (((scene[i].toInt() and 0xff) * gain) + rnd.nextInt(-noise, noise + 1)).toInt().coerceIn(0, 255).toByte()
        }
        if (box != null) for (y in box[1] until box[1] + box[3]) for (x in box[0] until box[0] + box[2]) f[y * w + x] = 230.toByte()
        return f
    }

    private fun run(d: MotionDetector, frames: Int, t0: Long, make: (Int) -> ByteArray, c: MotionConfig = cfg) =
        (0 until frames).map { d.process(make(it), w, h, t0 + it * 200L, c) }

    @Test
    fun staticNoisySceneIsQuiet() {
        val d = MotionDetector()
        val results = run(d, 60, 0, { frame(noise = 6) })
        assertTrue(results.none { it.active }, "noise must not trigger motion")
    }

    @Test
    fun movingObjectTriggersAndReleases() {
        val d = MotionDetector()
        run(d, 20, 0, { frame() })
        val moving = run(d, 10, 4000, { i -> frame(box = intArrayOf(10 + i * 8, 30, 20, 30)) })
        assertTrue(moving.last().active)
        val box = assertNotNull(moving.last().box)
        assertTrue(box[2] < 0.6f && box[3] < 0.6f, "box should hug the object: ${box.toList()}")
        val after = run(d, 15, 6000, { frame() })
        assertFalse(after.last().active, "motion must end after the object leaves")
    }

    @Test
    fun lightSwitchIsIgnored() {
        val d = MotionDetector()
        run(d, 20, 0, { frame() })
        // A partial lighting change that brightness normalisation cannot cancel: half the frame lights up.
        val lit = run(d, 5, 4000, { frame().also { f -> for (i in 0 until w * h / 2) f[i] = 250.toByte() } })
        assertTrue(lit.first().lightChange || !lit.first().active)
        assertTrue(lit.none { it.active })
        // Uniform exposure change is compensated entirely.
        val d2 = MotionDetector()
        run(d2, 20, 0, { frame() })
        assertTrue(run(d2, 10, 4000, { frame(gain = 1.4f) }).none { it.active })
    }

    @Test
    fun excludedZoneSuppressesMotion() {
        val bits = BooleanArray(16 * 9) { i -> i % 16 < 8 } // left half excluded
        val zones = ZoneMask(16, 9, bits)
        val c = cfg.copy(zones = zones)
        val d = MotionDetector()
        run(d, 20, 0, { frame() }, c)
        val left = run(d, 10, 4000, { i -> frame(box = intArrayOf(5 + i * 3, 30, 20, 30)) }, c)
        assertTrue(left.none { it.active }, "motion in the excluded half must be ignored")
    }

    @Test
    fun zoneMaskRoundTrip() {
        val bits = BooleanArray(16 * 9) { i -> i % 7 == 0 }
        val s = ZoneMask(16, 9, bits).format()
        val back = assertNotNull(ZoneMask.parse(s))
        assertEquals(16, back.cols)
        assertTrue(bits.contentEquals(back.excluded))
        assertNull(ZoneMask.parse(""))
        assertEquals("", ZoneMask(4, 4, BooleanArray(16)).format())
    }
}
