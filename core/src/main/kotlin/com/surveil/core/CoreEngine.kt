package com.surveil.core

import com.surveil.core.media.AudioFormat
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
import com.surveil.core.media.VideoFormat
import com.surveil.core.motion.MotionConfig
import com.surveil.core.motion.MotionDetector
import com.surveil.core.motion.ZoneMask
import com.surveil.core.record.Clip
import com.surveil.core.record.Recorder
import com.surveil.core.record.RecorderListener
import com.surveil.core.record.RecordingMeta
import com.surveil.core.record.RecordingsRepo
import com.surveil.core.settings.Apply
import com.surveil.core.settings.Keys
import com.surveil.core.settings.Schema
import com.surveil.core.settings.Settings
import com.surveil.core.settings.SettingsStore
import com.surveil.core.stream.LiveStreamer
import com.surveil.core.stream.MediaSink
import com.surveil.core.stream.StreamHub
import com.surveil.core.web.AuthManager
import com.surveil.core.web.EventBus
import com.surveil.core.web.WebApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Snapshot of the engine for UIs. */
data class EngineStatus(
    val camera: CameraStatus = CameraStatus(),
    val serverPort: Int = 0,
    val serverError: String? = null,
    val viewers: Int = 0,
    val fps: Float = 0f,
    val kbps: Int = 0,
    val motionEnabled: Boolean = false,
    val motionActive: Boolean = false,
    val motionScore: Float = 0f,
    val motionBox: List<Float>? = null,
    val recordingId: String? = null,
    val recordingTrigger: String? = null,
    val recordingStartMs: Long = 0,
    val manualRecording: Boolean = false,
    val recordMode: String = "off",
)

/**
 * Device-independent heart of the app: stream fan-out, motion detection, recording, settings,
 * events and the web server. The platform feeds it encoded frames and small luma images.
 */
class CoreEngine(
    val platform: Platform,
    val settingsStore: SettingsStore,
    dataDir: File,
) {
    val hub = StreamHub()
    val live = LiveStreamer(hub)
    val bus = EventBus()
    val events = EventLog(File(dataDir, "events.jsonl"))
    val auth = AuthManager(settingsStore, File(dataDir, "sessions.txt"))
    val repo = RecordingsRepo(
        roots = { platform.storageRoots().map { it.dir } },
        currentRoot = { selectedRoot().dir },
        log = platform::log,
    )
    val recorder = Recorder(
        repo = repo,
        settings = { settingsStore.current },
        listener = RecorderEvents(),
        requestKeyframe = { hub.requestKeyframe() },
        freeBytes = { selectedRoot().freeBytes },
    )
    private val web = WebApi(this)
    val rtsp = com.surveil.core.rtsp.RtspServer(hub, { h -> auth.checkBasic(h, "rtsp") }, platform::log)
    @Volatile private var rtspError: String? = null
    private val detector = MotionDetector()
    private val motionExec = Executors.newSingleThreadExecutor { r -> Thread(r, "motion").apply { isDaemon = true } }
    private val motionBusy = AtomicBoolean(false)
    private val stats = StatsSink()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startedAtMs = System.currentTimeMillis()

    private val state = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = state.asStateFlow()

    @Volatile private var cameraStatus = CameraStatus()
    @Volatile private var motionActive = false
    @Volatile private var motionScore = 0f
    @Volatile private var motionBox: FloatArray? = null
    @Volatile private var motionWarmupUntilMs = Long.MAX_VALUE
    private var motionStartMs = 0L
    private var motionPeak = 0f
    private var lastMotionPushNs = 0L
    @Volatile private var serverError: String? = null
    private var ticker: Thread? = null
    @Volatile private var running = false

    val settings: Settings get() = settingsStore.current

    fun start() {
        if (running) return
        running = true
        runCatching { repo.recoverInterrupted() }.onFailure { platform.log("recovery failed: $it") }
        hub.keyframeRequester = { platform.requestKeyframe() }
        hub.addSink(recorder, replayGop = false)
        hub.addSink(stats, replayGop = false)
        recorder.start()
        events.listener = { e -> bus.broadcast(eventJson(e).toString()) }
        startServer(settings.port)
        startRtsp(settings)
        scope.launch {
            var previous = settingsStore.current
            settingsStore.flow.collect { s ->
                val changed = s.diff(previous)
                previous = s
                if (changed.isNotEmpty()) onSettingsChanged(changed, s)
            }
        }
        ticker = Thread({
            while (running) {
                runCatching { publishStatus() }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }, "status-ticker").apply { isDaemon = true; start() }
        events.add("system", "Сервис запущен")
    }

    fun stop() {
        if (!running) return
        running = false
        events.add("system", "Сервис остановлен")
        ticker?.interrupt()
        hub.stop()
        recorder.shutdown()
        web.stop()
        rtsp.stop()
        motionExec.shutdownNow()
        scope.cancel()
    }

    // ---- inputs from the platform ----

    fun onVideoFormat(video: VideoFormat, audio: AudioFormat?) {
        // Exposure and white balance settle during the first seconds: keep the detector quiet.
        motionWarmupUntilMs = System.currentTimeMillis() + MOTION_WARMUP_MS
        hub.setFormat(video, audio)
    }

    fun onFrame(frame: MediaFrame) = hub.push(frame)

    fun onStreamStopped() {
        motionWarmupUntilMs = Long.MAX_VALUE
        hub.stop()
    }

    fun setCameraStatus(s: CameraStatus) {
        val prev = cameraStatus
        cameraStatus = s
        if (s.state == "error" && (prev.state != "error" || prev.error != s.error)) {
            events.add("camera", "Ошибка камеры: ${s.error ?: "неизвестно"}")
        }
        if (s.state == "running" && prev.state != "running") {
            events.add("camera", "Камера работает: ${s.width}×${s.height}, ${s.fps} к/с")
        }
        publishStatus()
    }

    /** Small grey image (rows top to bottom) for motion analysis; dropped if the detector is busy. */
    fun onLumaFrame(luma: ByteArray, width: Int, height: Int, timeMs: Long) {
        if (!running || !motionBusy.compareAndSet(false, true)) return
        try {
            motionExec.execute {
                try {
                    processMotion(luma, width, height, timeMs)
                } catch (e: Exception) {
                    platform.log("motion error: $e")
                } finally {
                    motionBusy.set(false)
                }
            }
        } catch (e: Exception) {
            motionBusy.set(false)
        }
    }

    fun setManualRecording(on: Boolean) {
        recorder.setManual(on)
        events.add("recording", if (on) "Ручная запись включена" else "Ручная запись выключена")
    }

    // ---- motion ----

    private fun processMotion(luma: ByteArray, width: Int, height: Int, timeMs: Long) {
        val s = settings
        if (System.currentTimeMillis() < motionWarmupUntilMs) {
            detector.reset()
            if (motionActive) setMotion(false, 0f, null)
            return
        }
        if (!s.bool(Keys.MOTION_ENABLED)) {
            if (motionActive) setMotion(false, 0f, null)
            detector.reset()
            motionScore = 0f
            return
        }
        val cfg = MotionConfig(
            sensitivity = s.int(Keys.MOTION_SENSITIVITY),
            minAreaPercent = s.double(Keys.MOTION_MIN_AREA),
            ignoreLightChanges = s.bool(Keys.MOTION_IGNORE_LIGHT),
            zones = ZoneMask.parse(s.string(Keys.MOTION_ZONES)),
        )
        val r = detector.process(luma, width, height, timeMs, cfg)
        motionScore = r.score
        motionBox = r.box
        if (r.active != motionActive) setMotion(r.active, r.score, r.box)
        else if (r.active) {
            recorder.onMotion(true, r.score)
            if (r.score > motionPeak) motionPeak = r.score
        }
        val now = System.nanoTime()
        if (now - lastMotionPushNs > 200_000_000L) {
            lastMotionPushNs = now
            bus.broadcast(motionJson().toString())
        }
    }

    private fun setMotion(active: Boolean, score: Float, box: FloatArray?) {
        motionActive = active
        recorder.onMotion(active, score)
        if (active) {
            motionStartMs = System.currentTimeMillis()
            motionPeak = score
            events.add("motion", "Обнаружено движение")
        } else {
            val sec = (System.currentTimeMillis() - motionStartMs) / 1000
            events.add("motion", "Движение закончилось: ${sec} с, пик %.1f%%".format(motionPeak), recorder.currentClipId)
        }
        bus.broadcast(motionJson().toString())
        publishStatus()
    }

    // ---- settings ----

    private fun onSettingsChanged(changed: Set<String>, s: Settings) {
        bus.broadcast(buildJsonObject {
            put("type", "settings")
            put("values", s.publicJson())
            putJsonArray("changed") { changed.forEach { add(JsonPrimitive(it)) } }
        }.toString())
        if (Keys.REC_STORAGE in changed) repo.invalidate()
        if (changed.any { Schema.byKey[it]?.apply == Apply.SERVER }) {
            // Give the HTTP response to the settings request a moment to go out first.
            scope.launch {
                kotlinx.coroutines.delay(500)
                if (Keys.PORT in changed) {
                    web.stop()
                    startServer(s.port)
                }
                if (Keys.RTSP_ENABLED in changed || Keys.RTSP_PORT in changed) {
                    rtsp.stop()
                    startRtsp(s)
                }
            }
        }
        if (Keys.MOTION_ZONES in changed || Keys.MOTION_ENABLED in changed) detector.reset()
    }

    private fun startServer(port: Int) {
        serverError = try {
            web.start(port)
            null
        } catch (e: Exception) {
            platform.log("server start failed on $port: $e")
            "Порт $port занят или недоступен"
        }
        publishStatus()
    }

    private fun startRtsp(s: Settings) {
        rtspError = null
        if (!s.bool(Keys.RTSP_ENABLED)) return
        try {
            rtsp.start(s.int(Keys.RTSP_PORT))
        } catch (e: Exception) {
            platform.log("rtsp start failed: $e")
            rtspError = "Порт RTSP ${s.int(Keys.RTSP_PORT)} занят"
        }
    }

    val serverPort: Int get() = web.port

    // ---- status ----

    fun selectedRoot(): StorageRoot {
        val roots = platform.storageRoots()
        val want = settings.string(Keys.REC_STORAGE)
        return roots.firstOrNull { it.id == want } ?: roots.first()
    }

    private fun publishStatus() {
        val s = settings
        state.value = EngineStatus(
            camera = cameraStatus,
            serverPort = web.port,
            serverError = serverError,
            viewers = live.viewers,
            fps = stats.fps,
            kbps = stats.kbps,
            motionEnabled = s.bool(Keys.MOTION_ENABLED),
            motionActive = motionActive,
            motionScore = motionScore,
            motionBox = motionBox?.toList(),
            recordingId = recorder.currentClipId,
            recordingTrigger = recorder.currentTrigger,
            recordingStartMs = recorder.currentStartMs,
            manualRecording = recorder.manual,
            recordMode = s.string(Keys.REC_MODE),
        )
        if (bus.size > 0) bus.broadcast(buildJsonObject {
            put("type", "status")
            put("status", statusJson())
        }.toString())
    }

    fun statusJson(): JsonObject {
        val st = state.value
        val s = settings
        val dev = platform.deviceStatus()
        val root = selectedRoot()
        return buildJsonObject {
            put("name", s.name)
            put("version", platform.appVersion)
            put("uptimeSec", (System.currentTimeMillis() - startedAtMs) / 1000)
            put("serverTime", System.currentTimeMillis())
            put("tzOffsetMin", tzOffsetMin())
            putJsonObject("camera") {
                put("state", st.camera.state)
                st.camera.error?.let { put("error", it) }
                st.camera.cameraId?.let { put("id", it) }
                put("width", st.camera.width)
                put("height", st.camera.height)
                put("fps", st.camera.fps)
                st.camera.encoder?.let { put("encoder", it) }
                put("audio", st.camera.audio)
                st.camera.audioError?.let { put("audioError", it) }
            }
            putJsonObject("stream") {
                put("fps", Math.round(st.fps * 10) / 10.0)
                put("kbps", st.kbps)
                put("viewers", st.viewers)
            }
            put("motion", motionJson().let { m -> JsonObject(m.filterKeys { it != "type" }) })
            putJsonObject("recording") {
                put("mode", st.recordMode)
                put("manual", st.manualRecording)
                put("active", st.recordingId != null)
                st.recordingId?.let { put("id", it) }
                st.recordingTrigger?.let { put("trigger", it) }
                if (st.recordingId != null) put("startMs", st.recordingStartMs)
            }
            putJsonObject("storage") {
                put("root", root.id)
                put("label", root.label)
                put("freeBytes", root.freeBytes)
                put("totalBytes", root.totalBytes)
                put("usedBytes", repo.totalBytes())
                put("count", repo.list().size)
            }
            putJsonObject("device") {
                dev.batteryPercent?.let { put("battery", it) }
                dev.charging?.let { put("charging", it) }
                dev.batteryTempC?.let { put("tempC", it) }
                put("model", dev.model)
                put("os", dev.os)
            }
            put("network", buildJsonArray {
                platform.networkAddresses().forEach { a ->
                    addJsonObject {
                        put("iface", a.iface); put("kind", a.kind); put("address", a.address); put("ipv6", a.ipv6)
                    }
                }
            })
            put("port", st.serverPort)
            st.serverError?.let { put("serverError", it) }
            putJsonObject("rtsp") {
                put("enabled", s.bool(Keys.RTSP_ENABLED))
                put("port", rtsp.port)
                put("clients", rtsp.clients)
                rtspError?.let { put("error", it) }
            }
            put("authRequired", s.authEnabled)
        }
    }

    /** The phone's current UTC offset: the UI shows times as the camera (and its OSD) sees them. */
    fun tzOffsetMin(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    fun motionJson(): JsonObject = buildJsonObject {
        put("type", "motion")
        put("enabled", settings.bool(Keys.MOTION_ENABLED))
        put("active", motionActive)
        put("score", Math.round(motionScore * 100) / 100.0)
        motionBox?.let { b -> putJsonArray("box") { b.forEach { add(JsonPrimitive(Math.round(it * 1000) / 1000.0)) } } }
    }

    fun eventJson(e: LogEvent): JsonObject = buildJsonObject {
        put("type", "event")
        put("timeMs", e.timeMs)
        put("kind", e.kind)
        put("message", e.message)
        e.recordingId?.let { put("recordingId", it) }
    }

    private inner class RecorderEvents : RecorderListener {
        override fun onRecordingStarted(clip: Clip, trigger: String) {
            val what = when (trigger) {
                "manual" -> "ручная"
                "continuous" -> "непрерывная"
                else -> "по движению"
            }
            events.add("recording", "Начата запись ($what)", clip.id)
            platform.snapshot(480) { jpeg -> if (jpeg != null) runCatching { clip.thumb.writeBytes(jpeg) } }
            publishStatus()
        }

        override fun onRecordingFinished(meta: RecordingMeta) {
            events.add("recording", "Запись сохранена: ${meta.durationMs / 1000} с, ${meta.sizeBytes / 1024 / 1024} МБ", meta.id)
            bus.broadcast(buildJsonObject { put("type", "recordings") }.toString())
            publishStatus()
        }

        override fun onRecorderError(message: String) {
            events.add("recording", message)
        }
    }

    companion object {
        private const val MOTION_WARMUP_MS = 3000L
    }

    /** Measures delivered fps and bitrate over a sliding window. */
    private class StatsSink : MediaSink {
        private val times = LongArray(256)
        private val sizes = IntArray(256)
        private var n = 0
        private var head = 0

        override fun onFormat(video: VideoFormat, audio: AudioFormat?) {}

        @Synchronized
        override fun onFrame(frame: MediaFrame) {
            if (frame.track != Track.VIDEO) return
            times[head] = System.nanoTime(); sizes[head] = frame.data.size
            head = (head + 1) % times.size
            if (n < times.size) n++
        }

        @Synchronized
        override fun onStreamStop() {
            n = 0
        }

        private fun window(): Pair<Int, Long> {
            val now = System.nanoTime()
            var count = 0
            var bytes = 0L
            for (i in 0 until n) {
                val idx = (head - 1 - i + times.size) % times.size
                if (now - times[idx] > 2_000_000_000L) break
                count++; bytes += sizes[idx]
            }
            return count to bytes
        }

        val fps: Float @Synchronized get() = window().first / 2f
        val kbps: Int @Synchronized get() = (window().second * 8 / 2 / 1000).toInt()
    }
}
