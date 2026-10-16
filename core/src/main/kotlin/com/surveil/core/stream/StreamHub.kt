package com.surveil.core.stream

import com.surveil.core.media.AudioFormat
import com.surveil.core.media.MediaFrame
import com.surveil.core.media.Track
import com.surveil.core.media.VideoFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Receives the encoded stream. Implementations must not block: they are called on the encoder thread. */
interface MediaSink {
    /** A (new) stream begins; all following frames belong to this format until the next call. */
    fun onFormat(video: VideoFormat, audio: AudioFormat?)
    fun onFrame(frame: MediaFrame)
    /** The stream stopped (camera closed or reconfiguring). */
    fun onStreamStop() {}
}

/**
 * Fan-out point between the encoder and all consumers (live viewers, recorder, RTSP...).
 * Keeps the current GOP so that a new viewer can start decoding immediately.
 */
class StreamHub {
    private val lock = Any()
    private val sinks = ArrayList<MediaSink>()
    private val gop = ArrayList<MediaFrame>()
    private var gopBytes = 0L

    @Volatile var video: VideoFormat? = null
        private set
    @Volatile var audio: AudioFormat? = null
        private set

    /** Set by the platform: asks the encoder for an IDR frame as soon as possible. */
    @Volatile var keyframeRequester: (() -> Unit)? = null
    private var lastKeyRequestNs = 0L

    val sinkCount: Int get() = synchronized(lock) { sinks.size }

    fun setFormat(video: VideoFormat, audio: AudioFormat?): Unit = synchronized(lock) {
        this.video = video
        this.audio = audio
        gop.clear(); gopBytes = 0
        sinks.forEach { it.onFormat(video, audio) }
    }

    fun push(frame: MediaFrame) {
        synchronized(lock) { pushLocked(frame) }
    }

    private fun pushLocked(frame: MediaFrame) {
        if (video == null) return
        if (frame.isVideoKey) {
            gop.clear(); gopBytes = 0
        }
        if (gop.isNotEmpty() || frame.isVideoKey) {
            if (gopBytes + frame.data.size <= MAX_GOP_BYTES && gop.size < MAX_GOP_FRAMES) {
                gop += frame; gopBytes += frame.data.size
            } else {
                gop.clear(); gopBytes = 0 // unusually long GOP: new viewers wait for the next IDR
            }
        }
        for (s in sinks) s.onFrame(frame)
    }

    fun stop(): Unit = synchronized(lock) {
        video = null
        audio = null
        gop.clear(); gopBytes = 0
        sinks.forEach { it.onStreamStop() }
    }

    /** Registers [sink]; if a stream is running it immediately receives the format and the cached GOP. */
    fun addSink(sink: MediaSink, replayGop: Boolean = true) {
        synchronized(lock) {
            sinks += sink
            val v = video ?: return
            sink.onFormat(v, audio)
            if (replayGop) gop.forEach(sink::onFrame)
        }
    }

    fun removeSink(sink: MediaSink) {
        synchronized(lock) { sinks.remove(sink) }
    }

    /** Rate-limited IDR request (a reconnect storm must not turn the stream into all-keyframes). */
    fun requestKeyframe() {
        val now = System.nanoTime()
        synchronized(lock) {
            if (now - lastKeyRequestNs < 1_000_000_000L) return
            lastKeyRequestNs = now
        }
        keyframeRequester?.invoke()
    }

    companion object {
        private const val MAX_GOP_BYTES = 24L * 1024 * 1024
        private const val MAX_GOP_FRAMES = 600
    }
}

/** Items flowing through a per-consumer queue. */
sealed class StreamItem {
    class Format(val video: VideoFormat, val audio: AudioFormat?) : StreamItem()
    class Frame(val frame: MediaFrame) : StreamItem()
    object Stop : StreamItem()
}

/**
 * Bounded queue for one consumer. When the consumer falls behind and the byte budget is exceeded,
 * pending frames are discarded and delivery resumes at the next video keyframe, so a slow viewer
 * gets a jump instead of unbounded memory growth or a corrupt picture.
 */
class FrameQueue(private val maxBytes: Long) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val items = ArrayDeque<StreamItem>()
    private var bytes = 0L
    private var waitKey = false
    @Volatile var closed = false
        private set
    var dropped = 0L
        private set

    fun offer(item: StreamItem) {
        lock.withLock { if (offerLocked(item)) notEmpty.signalAll() }
    }

    private fun offerLocked(item: StreamItem): Boolean {
        if (closed) return false
        when (item) {
            is StreamItem.Frame -> {
                val f = item.frame
                if (waitKey) {
                    if (!f.isVideoKey) { dropped++; return false }
                    waitKey = false
                }
                if (bytes + f.data.size > maxBytes) {
                    // Drop everything that is not a control item and resync on a keyframe.
                    val keep = items.filter { it !is StreamItem.Frame }
                    dropped += items.size - keep.size
                    items.clear(); items.addAll(keep); bytes = 0
                    if (!f.isVideoKey) { waitKey = true; dropped++; return false }
                }
                items.addLast(item); bytes += f.data.size
            }
            is StreamItem.Format -> { waitKey = false; items.addLast(item) }
            StreamItem.Stop -> items.addLast(item)
        }
        return true
    }

    /** Returns the next item, or null on timeout / close. */
    fun take(timeoutMs: Long): StreamItem? {
        lock.withLock {
            var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (items.isEmpty() && !closed) {
                if (left <= 0) return null
                left = notEmpty.awaitNanos(left)
            }
            if (closed) return null
            val it = items.removeFirst()
            if (it is StreamItem.Frame) bytes -= it.frame.data.size
            return it
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            items.clear(); bytes = 0
            notEmpty.signalAll()
        }
    }
}

/** A [MediaSink] that feeds a [FrameQueue]; the consumer drains it on its own thread. */
open class QueueSink(maxBytes: Long) : MediaSink {
    val queue = FrameQueue(maxBytes)
    override fun onFormat(video: VideoFormat, audio: AudioFormat?) = queue.offer(StreamItem.Format(video, audio))
    override fun onFrame(frame: MediaFrame) = queue.offer(StreamItem.Frame(frame))
    override fun onStreamStop() = queue.offer(StreamItem.Stop)
}

internal fun MediaFrame.isAudio() = track == Track.AUDIO
