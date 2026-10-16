package com.surveil.core

import com.surveil.core.record.Clip
import com.surveil.core.record.Recorder
import com.surveil.core.record.RecorderListener
import com.surveil.core.record.RecordingMeta
import com.surveil.core.record.RecordingsRepo
import com.surveil.core.settings.Keys
import com.surveil.core.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecorderTest {
    private val dir: File = Files.createTempDirectory("rec").toFile()
    private val root = File(dir, "recordings").apply { mkdirs() }
    private val settings = SettingsStore(File(dir, "settings.json"))
    private val repo = RecordingsRepo({ listOf(root) }, { root })
    private val finished = CopyOnWriteArrayList<RecordingMeta>()
    private val recorder = Recorder(
        repo, { settings.current },
        object : RecorderListener {
            override fun onRecordingFinished(meta: RecordingMeta) { finished += meta }
            override fun onRecordingStarted(clip: Clip, trigger: String) {}
        },
        requestKeyframe = {}, freeBytes = { Long.MAX_VALUE },
    )

    @Test
    fun motionClipIncludesPrerollAndPostroll() {
        settings.set(Keys.REC_MODE, "motion")
        settings.set(Keys.REC_PRE_SEC, 2)
        settings.set(Keys.REC_POST_SEC, 1)
        recorder.start()
        recorder.onFormat(TestMedia.format, null)
        for (i in 0 until 160) { // 8 s at 20 fps, keyframes every 2 s
            if (i == 60) recorder.onMotion(true, 5f)   // t = 3.0 s
            if (i == 70) recorder.onMotion(false, 0f)  // t = 3.5 s
            recorder.onFrame(TestMedia.frame(i))
        }
        recorder.onStreamStop()
        recorder.shutdown()
        assertEquals(1, finished.size, "one clip expected")
        val m = finished[0]
        // Pre-roll starts at the keyframe at t=0 (the one at t=2 s would leave < 2 s before the trigger);
        // the clip ends 1 s after the last "active" report (t=3 s) -> about 4 s.
        assertTrue(m.durationMs in 3900..4200, "duration ${m.durationMs}")
        assertEquals("motion", m.trigger)
        assertTrue(File(root, RecordingsRepo.dayFormat().format(m.startMs) + "/${m.id}.mp4").isFile)
        assertEquals(listOf(m.id), repo.list().map { it.id })
    }

    @Test
    fun continuousRecordingSplitsAtKeyframes() {
        settings.set(Keys.REC_MODE, "continuous")
        settings.set(Keys.REC_MAX_CLIP_MIN, 1)
        recorder.start()
        recorder.onFormat(TestMedia.format, null)
        for (i in 0 until 20 * 150) recorder.onFrame(TestMedia.frame(i, size = 50)) // 150 s
        recorder.onStreamStop()
        recorder.shutdown()
        assertEquals(3, finished.size, "60 s + 60 s + 30 s")
        assertTrue(finished.take(2).all { it.durationMs in 59_000..61_000 }, finished.map { it.durationMs }.toString())
        assertEquals("continuous", finished[0].trigger)
    }

    @Test
    fun offModeRecordsNothing() {
        settings.set(Keys.REC_MODE, "off")
        recorder.start()
        recorder.onFormat(TestMedia.format, null)
        recorder.onMotion(true, 50f)
        for (i in 0 until 100) recorder.onFrame(TestMedia.frame(i))
        recorder.onStreamStop()
        recorder.shutdown()
        assertTrue(finished.isEmpty())
    }
}
