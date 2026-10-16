package com.surveil.core.record

import com.surveil.core.media.AudioFormat
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.VideoFormat
import com.surveil.core.mp4.Mp4FileWriter
import com.surveil.core.settings.Keys
import com.surveil.core.settings.Settings
import com.surveil.core.stream.MediaSink
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Callbacks from the recorder thread. */
interface RecorderListener {
    fun onRecordingStarted(clip: Clip, trigger: String) {}
    fun onRecordingFinished(meta: RecordingMeta) {}
    fun onRecorderError(message: String) {}
}

/**
 * Decides when to record and writes clips. Runs on its own thread, fed by the [MediaSink] callbacks.
 *
 * - Keeps a pre-roll buffer that always starts at a video keyframe, so a motion clip includes
 *   the seconds *before* the trigger.
 * - Motion clips continue for `record.postSec` after the last motion.
 * - Long recordings are split at keyframes every `record.maxClipMin` minutes.
 */
class Recorder(
    private val repo: RecordingsRepo,
    private val settings: () -> Settings,
    private val listener: RecorderListener,
    private val requestKeyframe: () -> Unit,
    private val freeBytes: () -> Long,
) : MediaSink {

    private sealed class Cmd {
        class Format(val video: VideoFormat, val audio: AudioFormat?) : Cmd()
        class Frame(val frame: MediaFrame) : Cmd()
        object Stop : Cmd()
        class Motion(val active: Boolean, val score: Float) : Cmd()
        class Manual(val on: Boolean) : Cmd()
        object Quit : Cmd()
    }

    private val queue = LinkedBlockingQueue<Cmd>()
    private val queuedBytes = AtomicLong()
    @Volatile private var dropUntilKey = false
    private var thread: Thread? = null

    // Recorder-thread state
    private var video: VideoFormat? = null
    private var audio: AudioFormat? = null
    private val preroll = ArrayDeque<MediaFrame>()
    private var prerollBytes = 0L
    private var writer: Mp4FileWriter? = null
    private var clip: Clip? = null
    private var clipTrigger = ""
    private var clipStartWallMs = 0L
    private var clipPeakMotion = 0f
    private var motionActive = false
    private var lastMotionNs = 0L
    private var lastMotionScore = 0f
    private var lastCleanupNs = 0L

    @Volatile var manual = false
        private set

    /** Id of the clip being written, or null. */
    @Volatile var currentClipId: String? = null
        private set
    @Volatile var currentTrigger: String? = null
        private set
    @Volatile var currentStartMs: Long = 0
        private set

    fun start() {
        if (thread != null) return
        thread = Thread(::run, "recorder").apply { start() }
    }

    fun shutdown() {
        queue.put(Cmd.Quit)
        thread?.join(5000)
        thread = null
    }

    fun setManual(on: Boolean) = queue.put(Cmd.Manual(on))
    fun onMotion(active: Boolean, score: Float) = queue.put(Cmd.Motion(active, score))

    override fun onFormat(video: VideoFormat, audio: AudioFormat?) = queue.put(Cmd.Format(video, audio))
    override fun onStreamStop() = queue.put(Cmd.Stop)

    override fun onFrame(frame: MediaFrame) {
        // Protect memory if storage stalls: drop and resynchronise at the next keyframe.
        if (dropUntilKey) {
            if (!frame.isVideoKey || queuedBytes.get() > MAX_QUEUE_BYTES / 2) return
            dropUntilKey = false
        }
        if (queuedBytes.get() + frame.data.size > MAX_QUEUE_BYTES) {
            dropUntilKey = true
            return
        }
        queuedBytes.addAndGet(frame.data.size.toLong())
        queue.put(Cmd.Frame(frame))
    }

    private fun run() {
        while (true) {
            val cmd = queue.poll(1, TimeUnit.SECONDS)
            try {
                when (cmd) {
                    null -> idle()
                    is Cmd.Frame -> {
                        queuedBytes.addAndGet(-cmd.frame.data.size.toLong())
                        frame(cmd.frame)
                    }
                    is Cmd.Format -> {
                        finishClip()
                        clearPreroll()
                        video = cmd.video
                        audio = cmd.audio
                    }
                    Cmd.Stop -> {
                        finishClip()
                        clearPreroll()
                        video = null
                    }
                    is Cmd.Motion -> {
                        motionActive = cmd.active
                        if (cmd.active) {
                            lastMotionNs = System.nanoTime()
                            lastMotionScore = cmd.score
                        }
                        if (writer != null && cmd.score > clipPeakMotion) clipPeakMotion = cmd.score
                    }
                    is Cmd.Manual -> manual = cmd.on
                    Cmd.Quit -> {
                        finishClip()
                        return
                    }
                }
            } catch (e: Exception) {
                listener.onRecorderError("Ошибка записи: ${e.message}")
                runCatching { writer?.close() }
                writer = null
                clip = null
                currentClipId = null
                repo.activeId = null
            }
        }
    }

    private fun shouldRecord(s: Settings, nowNs: Long): Boolean {
        if (manual) return true
        return when (s.string(Keys.REC_MODE)) {
            "continuous" -> true
            "motion" -> s.bool(Keys.MOTION_ENABLED) &&
                (motionActive || nowNs - lastMotionNs < s.int(Keys.REC_POST_SEC) * 1_000_000_000L)
            else -> false
        }
    }

    private fun triggerName(s: Settings): String = when {
        manual -> "manual"
        s.string(Keys.REC_MODE) == "continuous" -> "continuous"
        else -> "motion"
    }

    private fun frame(f: MediaFrame) {
        val v = video ?: return
        val s = settings()
        val now = System.nanoTime()
        addToPreroll(f, s.int(Keys.REC_PRE_SEC))

        val w = writer
        if (w != null) {
            if (f.isVideoKey && w.durationUs >= s.int(Keys.REC_MAX_CLIP_MIN) * 60_000_000L) {
                // Split long recordings at a keyframe; the new file starts with this frame.
                finishClip()
                if (shouldRecord(s, now)) startClip(v, s, listOf(f))
                return
            }
            w.write(f)
            if (!shouldRecord(s, now)) finishClip()
            return
        }
        if (!shouldRecord(s, now)) return
        if (s.int(Keys.REC_PRE_SEC) > 0 && preroll.isNotEmpty() && preroll.first().isVideoKey) {
            startClip(v, s, preroll.toList())
        } else if (f.isVideoKey) {
            startClip(v, s, listOf(f))
        } else {
            requestKeyframe()
        }
    }

    private fun startClip(v: VideoFormat, s: Settings, initial: List<MediaFrame>) {
        maybeCleanup(s, force = true)
        val trigger = triggerName(s)
        val startWall = initial.first().wallMs
        val c = repo.newClip(startWall, trigger)
        val w = Mp4FileWriter.create(c.video, v, audio)
        initial.forEach(w::write)
        writer = w
        clip = c
        clipTrigger = trigger
        clipStartWallMs = startWall
        clipPeakMotion = if (trigger == "motion") lastMotionScore else 0f
        repo.activeId = c.id
        currentClipId = c.id
        currentTrigger = trigger
        currentStartMs = startWall
        listener.onRecordingStarted(c, trigger)
    }

    private fun finishClip() {
        val w = writer ?: return
        val c = clip!!
        writer = null
        clip = null
        currentClipId = null
        currentTrigger = null
        val durationMs = w.finish()
        repo.activeId = null
        if (durationMs > 0) {
            val v = w.video
            val meta = RecordingMeta(
                id = c.id, startMs = clipStartWallMs, durationMs = durationMs, sizeBytes = c.video.length(),
                trigger = clipTrigger, width = v.width, height = v.height, hasAudio = w.audio != null,
                peakMotion = clipPeakMotion,
            )
            repo.save(c, meta)
            listener.onRecordingFinished(meta)
        } else {
            c.thumb.delete()
        }
    }

    private fun addToPreroll(f: MediaFrame, preSec: Int) {
        if (preroll.isEmpty() && !f.isVideoKey) return
        preroll.addLast(f)
        prerollBytes += f.data.size
        // Drop whole GOPs from the front while the next GOP still starts early enough.
        val horizonUs = f.ptsUs - preSec * 1_000_000L
        while (true) {
            val nextKey = preroll.withIndex().firstOrNull { it.index > 0 && it.value.isVideoKey } ?: break
            if (nextKey.value.ptsUs > horizonUs && prerollBytes <= MAX_PREROLL_BYTES) break
            repeat(nextKey.index) { prerollBytes -= preroll.removeFirst().data.size }
        }
    }

    private fun clearPreroll() {
        preroll.clear()
        prerollBytes = 0
    }

    private fun idle() {
        val s = settings()
        val now = System.nanoTime()
        if (writer != null && !shouldRecord(s, now)) finishClip()
        maybeCleanup(s, force = false)
    }

    private fun maybeCleanup(s: Settings, force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastCleanupNs < 60_000_000_000L) return
        lastCleanupNs = now
        repo.cleanup(
            minFreeBytes = (s.double(Keys.REC_MIN_FREE_GB) * GB).toLong(),
            maxUsageBytes = (s.double(Keys.REC_MAX_USAGE_GB) * GB).toLong(),
            maxAgeDays = s.int(Keys.REC_MAX_AGE_DAYS),
            freeBytes = freeBytes,
        )
    }

    companion object {
        private const val MAX_QUEUE_BYTES = 64L * 1024 * 1024
        private const val MAX_PREROLL_BYTES = 48L * 1024 * 1024
        private const val GB = 1024.0 * 1024 * 1024
    }
}
