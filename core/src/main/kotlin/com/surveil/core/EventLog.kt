package com.surveil.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class LogEvent(
    val timeMs: Long,
    /** motion | recording | camera | system | settings | access */
    val kind: String,
    val message: String,
    val recordingId: String? = null,
)

/** Persistent ring of recent events (motion, recordings, errors) shown in the web UI. */
class EventLog(private val file: File, private val capacity: Int = 1000) {
    private val json = Json { ignoreUnknownKeys = true }
    private val events = ArrayDeque<LogEvent>()
    private var appendedSinceCompact = 0
    var listener: ((LogEvent) -> Unit)? = null

    init {
        runCatching {
            if (file.exists()) file.forEachLine { line ->
                runCatching { json.decodeFromString(LogEvent.serializer(), line) }.getOrNull()?.let { events.addLast(it) }
            }
            while (events.size > capacity) events.removeFirst()
        }
    }

    fun add(kind: String, message: String, recordingId: String? = null) {
        val e = LogEvent(System.currentTimeMillis(), kind, message, recordingId)
        synchronized(this) {
            events.addLast(e)
            while (events.size > capacity) events.removeFirst()
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(LogEvent.serializer(), e) + "\n")
                if (++appendedSinceCompact > capacity) compact()
            }
        }
        listener?.invoke(e)
    }

    @Synchronized
    fun recent(limit: Int = 200): List<LogEvent> = events.toList().takeLast(limit).reversed()

    private fun compact() {
        appendedSinceCompact = 0
        val tmp = File(file.path + ".tmp")
        tmp.writeText(events.joinToString("") { json.encodeToString(LogEvent.serializer(), it) + "\n" })
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }
}
