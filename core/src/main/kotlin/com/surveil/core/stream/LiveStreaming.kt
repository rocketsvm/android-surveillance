package com.surveil.core.stream

import com.surveil.core.http.HttpResponse
import com.surveil.core.http.WebSocket
import com.surveil.core.media.AudioFormat
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
import com.surveil.core.media.VideoFormat
import com.surveil.core.mp4.Fmp4
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/**
 * Turns the hub stream into fMP4 for one viewer: init segment, then one fragment per frame.
 * Each frame is held back until the next one of the same track arrives, so durations are exact
 * (no gaps in the MSE buffer even with variable frame rate) at the cost of one frame of latency.
 */
class LiveFmp4Muxer {
    sealed class Out {
        class Init(val video: VideoFormat, val audio: AudioFormat?, val segment: ByteArray) : Out()
        class Chunk(val parts: List<ByteArray>) : Out()
        /** The format changed after playback began: the viewer must re-initialise its decoder. */
        object Restart : Out()
        object End : Out()
    }

    private var video: VideoFormat? = null
    private var audio: AudioFormat? = null
    private var started = false
    private var baseUs = 0L
    private var seq = 0
    private var pendingVideo: MediaFrame? = null
    private var pendingAudio: MediaFrame? = null

    fun process(item: StreamItem): List<Out> = when (item) {
        is StreamItem.Format -> {
            if (video != null) listOf(Out.Restart)
            else {
                video = item.video
                audio = item.audio
                listOf(Out.Init(item.video, item.audio, Fmp4.initSegment(item.video, item.audio)))
            }
        }
        is StreamItem.Frame -> frame(item.frame)
        StreamItem.Stop -> listOf(Out.End)
    }

    private fun frame(f: MediaFrame): List<Out> {
        if (video == null) return emptyList()
        if (!started) {
            if (!f.isVideoKey) return emptyList()
            started = true
            baseUs = f.ptsUs
        }
        if (f.track == Track.AUDIO) {
            val a = audio ?: return emptyList()
            if (f.ptsUs < baseUs) return emptyList()
            val prev = pendingAudio
            pendingAudio = f
            return if (prev == null) emptyList() else listOf(fragment(prev, f, Fmp4.AUDIO_TRACK, a.sampleRate))
        }
        val prev = pendingVideo
        pendingVideo = f
        return if (prev == null) emptyList() else listOf(fragment(prev, f, Fmp4.VIDEO_TRACK, Fmp4.VIDEO_TIMESCALE))
    }

    private fun ticks(us: Long, timescale: Int): Long = ((us - baseUs) * timescale / 1_000_000.0).roundToLong()

    private fun fragment(f: MediaFrame, next: MediaFrame, trackId: Int, timescale: Int): Out.Chunk {
        val t0 = ticks(f.ptsUs, timescale)
        val dur = (ticks(next.ptsUs, timescale) - t0).coerceIn(1, timescale.toLong() * 10).toInt()
        val head = Fmp4.fragmentHeader(++seq, trackId, maxOf(0, t0), dur, f.data.size, f.isKey)
        return Out.Chunk(listOf(head, f.data))
    }
}

/** Serves the live stream to browsers (WebSocket + MSE) and to players (progressive fMP4 over HTTP). */
class LiveStreamer(private val hub: StreamHub) {
    private val viewerCount = AtomicInteger()
    val viewers: Int get() = viewerCount.get()

    /** A viewer whose writes stopped progressing (peer vanished without closing TCP) gets cut off. */
    private class Session(val abort: () -> Unit) {
        @Volatile var progressNs = System.nanoTime()
    }

    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Session>()
    private val watchdog by lazy {
        thread(name = "live-watchdog", isDaemon = true) {
            while (true) {
                Thread.sleep(5000)
                val now = System.nanoTime()
                for (s in sessions) if (now - s.progressNs > STALL_NS) {
                    sessions.remove(s)
                    s.abort()
                }
            }
        }
    }

    private fun register(abort: () -> Unit): Session {
        watchdog
        return Session(abort).also { sessions += it }
    }

    /** fMP4 over WebSocket: a JSON `init` text message, the init segment, then fragments as binary messages. */
    fun serveWebSocket(ws: WebSocket) {
        val sink = QueueSink(MAX_QUEUE_BYTES)
        val muxer = LiveFmp4Muxer()
        thread(name = "ws-live-reader", isDaemon = true) {
            while (ws.receive() != null) { /* client messages are not used */ }
            sink.queue.close()
        }
        viewerCount.incrementAndGet()
        val session = register { ws.abort() }
        attach(sink)
        try {
            var lastPing = System.nanoTime()
            loop@ while (ws.isOpen) {
                val item = sink.queue.take(1000)
                val now = System.nanoTime()
                if (now - lastPing > PING_INTERVAL_NS) {
                    lastPing = now
                    if (!ws.ping()) break
                    session.progressNs = System.nanoTime()
                }
                if (item == null) {
                    if (sink.queue.closed) break else continue
                }
                for (out in muxer.process(item)) {
                    when (out) {
                        is LiveFmp4Muxer.Out.Init -> {
                            ws.sendText(initMessage(out.video, out.audio))
                            ws.sendBinary(out.segment)
                        }
                        is LiveFmp4Muxer.Out.Chunk -> {
                            if (!ws.sendBinary(out.parts)) break@loop
                            session.progressNs = System.nanoTime()
                        }
                        LiveFmp4Muxer.Out.Restart -> { ws.close(4000, "format changed"); break@loop }
                        LiveFmp4Muxer.Out.End -> { ws.close(4001, "stream stopped"); break@loop }
                    }
                }
            }
        } finally {
            sessions -= session
            hub.removeSink(sink)
            sink.queue.close()
            viewerCount.decrementAndGet()
            ws.close()
        }
    }

    /** Progressive fragmented MP4 over plain HTTP, for VLC/ffplay/NVR software. Ends on format change. */
    fun serveHttp(out: OutputStream) {
        val sink = QueueSink(MAX_QUEUE_BYTES)
        val muxer = LiveFmp4Muxer()
        viewerCount.incrementAndGet()
        val session = register { (out as? com.surveil.core.http.StreamOutput)?.abort() }
        attach(sink)
        try {
            var idle = 0
            while (true) {
                val item = sink.queue.take(1000)
                if (item == null) {
                    if (sink.queue.closed || ++idle > 30) return
                    continue
                }
                idle = 0
                for (o in muxer.process(item)) {
                    when (o) {
                        is LiveFmp4Muxer.Out.Init -> out.write(o.segment)
                        is LiveFmp4Muxer.Out.Chunk -> o.parts.forEach(out::write)
                        LiveFmp4Muxer.Out.Restart, LiveFmp4Muxer.Out.End -> return
                    }
                }
                out.flush()
                session.progressNs = System.nanoTime()
            }
        } finally {
            sessions -= session
            hub.removeSink(sink)
            sink.queue.close()
            viewerCount.decrementAndGet()
        }
    }

    private fun attach(sink: QueueSink) {
        hub.addSink(sink, replayGop = true)
        // If the cached GOP was empty the viewer would wait for the next natural IDR.
        hub.requestKeyframe()
    }

    private fun initMessage(video: VideoFormat, audio: AudioFormat?): String =
        "{\"type\":\"init\",\"mime\":" + HttpResponse.jsonString(Fmp4.mimeType(video, audio)) +
            ",\"width\":${video.width},\"height\":${video.height},\"audio\":${audio != null}}"

    companion object {
        private const val MAX_QUEUE_BYTES = 8L * 1024 * 1024
        private const val PING_INTERVAL_NS = 15_000_000_000L
        private const val STALL_NS = 25_000_000_000L
    }
}
