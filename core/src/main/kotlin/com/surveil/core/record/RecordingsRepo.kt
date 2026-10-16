package com.surveil.core.record

import com.surveil.core.mp4.Mp4FileWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class RecordingMeta(
    val id: String,
    /** Wall-clock start, epoch ms. */
    val startMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    /** "motion" | "continuous" | "manual" | "recovered" */
    val trigger: String,
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = false,
    /** Peak motion score (percent of frame) during the clip. */
    val peakMotion: Float = 0f,
)

/** Files belonging to one recording. */
class Clip(val id: String, val dir: File) {
    val video = File(dir, "$id.mp4")
    val thumb = File(dir, "$id.jpg")
    val meta = File(dir, "$id.json")
}

/**
 * Recordings on disk: `<root>/<yyyy-MM-dd>/<id>.mp4` with `.jpg` thumbnail and `.json` metadata.
 * Several roots may exist (internal memory, SD card); new clips go to [currentRoot], listing covers all.
 */
class RecordingsRepo(
    private val roots: () -> List<File>,
    private val currentRoot: () -> File,
    private val log: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LinkedHashMap<String, RecordingMeta>()
    private val clipDirs = HashMap<String, File>()
    private var scannedRoots: List<File>? = null

    /** Clip currently being written (excluded from cleanup). */
    @Volatile var activeId: String? = null

    @Synchronized
    fun newClip(startMs: Long, trigger: String): Clip {
        val base = idFormat().format(Date(startMs)) + "-" + trigger
        val dir = File(currentRoot(), dayFormat().format(Date(startMs)))
        dir.mkdirs()
        var id = base
        var n = 2
        while (File(dir, "$id.mp4").exists() || File(dir, "$id.json").exists()) id = "$base-${n++}"
        clipDirs[id] = dir
        return Clip(id, dir)
    }

    @Synchronized
    fun save(clip: Clip, meta: RecordingMeta) {
        clip.meta.writeText(json.encodeToString(RecordingMeta.serializer(), meta))
        ensureScanned()
        cache[meta.id] = meta
        clipDirs[meta.id] = clip.dir
    }

    @Synchronized
    fun list(): List<RecordingMeta> {
        ensureScanned()
        return cache.values.sortedByDescending { it.startMs }
    }

    @Synchronized
    fun find(id: String): Clip? {
        if (!ID_RE.matches(id)) return null
        ensureScanned()
        val dir = clipDirs[id] ?: return null
        return Clip(id, dir)
    }

    @Synchronized
    fun meta(id: String): RecordingMeta? {
        ensureScanned()
        return cache[id]
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (id == activeId) return false
        val clip = find(id) ?: return false
        clip.video.delete(); clip.thumb.delete(); clip.meta.delete()
        Mp4FileWriter.journalFile(clip.video).delete()
        cache.remove(id)
        clipDirs.remove(id)
        clip.dir.takeIf { it.list()?.isEmpty() == true }?.delete()
        return true
    }

    @Synchronized
    fun totalBytes(): Long {
        ensureScanned()
        return cache.values.sumOf { it.sizeBytes }
    }

    /** Forces a rescan (e.g. after the storage location changed). */
    @Synchronized
    fun invalidate() {
        scannedRoots = null
    }

    /**
     * Deletes the oldest clips until the policy holds. [freeBytes] reports free space of the
     * current root. Returns the number of deleted clips.
     */
    @Synchronized
    fun cleanup(minFreeBytes: Long, maxUsageBytes: Long, maxAgeDays: Int, freeBytes: () -> Long): Int {
        ensureScanned()
        var deleted = 0
        val oldestFirst = cache.values.sortedBy { it.startMs }.toMutableList()
        if (maxAgeDays > 0) {
            val limit = System.currentTimeMillis() - maxAgeDays * 86_400_000L
            for (m in oldestFirst.toList()) {
                if (m.startMs >= limit) break
                if (delete(m.id)) { oldestFirst.remove(m); deleted++ }
            }
        }
        if (maxUsageBytes > 0) {
            var usage = cache.values.sumOf { it.sizeBytes }
            for (m in oldestFirst.toList()) {
                if (usage <= maxUsageBytes) break
                if (delete(m.id)) { usage -= m.sizeBytes; oldestFirst.remove(m); deleted++ }
            }
        }
        if (minFreeBytes > 0) {
            for (m in oldestFirst.toList()) {
                if (freeBytes() >= minFreeBytes) break
                if (delete(m.id)) { oldestFirst.remove(m); deleted++ }
            }
        }
        if (deleted > 0) log("cleanup: deleted $deleted old recordings")
        return deleted
    }

    /** Finishes clips left unfinalized by a crash. Call once at startup before recording. */
    @Synchronized
    fun recoverInterrupted(): Int {
        var recovered = 0
        for (root in roots()) {
            root.listFiles()?.filter { it.isDirectory }?.forEach { day ->
                day.listFiles { f -> f.name.endsWith(".mp4.journal") }?.forEach { j ->
                    val video = File(j.path.removeSuffix(".journal"))
                    val id = video.name.removeSuffix(".mp4")
                    val durationMs = Mp4FileWriter.recover(video)
                    if (durationMs != null) {
                        val start = runCatching { idFormat().parse(id.substring(0, 15))!!.time }.getOrDefault(video.lastModified())
                        val meta = RecordingMeta(id, start, durationMs, video.length(), "recovered")
                        File(day, "$id.json").writeText(json.encodeToString(RecordingMeta.serializer(), meta))
                        recovered++
                        log("recovered interrupted recording $id (${durationMs / 1000}s)")
                    }
                }
            }
        }
        if (recovered > 0) scannedRoots = null
        return recovered
    }

    private fun ensureScanned() {
        val r = roots()
        if (scannedRoots == r) return
        cache.clear()
        clipDirs.clear()
        for (root in r) {
            root.listFiles()?.filter { it.isDirectory && DAY_RE.matches(it.name) }?.forEach { day ->
                day.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                    val meta = runCatching { json.decodeFromString(RecordingMeta.serializer(), f.readText()) }.getOrNull()
                    if (meta != null && File(day, "${meta.id}.mp4").exists()) {
                        cache[meta.id] = meta
                        clipDirs[meta.id] = day
                    }
                }
            }
        }
        scannedRoots = r
    }

    companion object {
        val ID_RE = Regex("""\d{8}-\d{6}-[a-z]+(-\d+)?""")
        private val DAY_RE = Regex("""\d{4}-\d{2}-\d{2}""")
        fun idFormat() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        fun dayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
