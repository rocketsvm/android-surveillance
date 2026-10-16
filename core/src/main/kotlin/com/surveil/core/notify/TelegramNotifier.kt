package com.surveil.core.notify

import com.surveil.core.settings.Keys
import com.surveil.core.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Sends alerts through a user-owned Telegram bot. All network work happens on one background
 * thread; a failing network never blocks capture or recording.
 */
class TelegramNotifier(
    private val settings: () -> Settings,
    private val log: (String) -> Unit,
    /** Overridable for tests. */
    private val apiBase: String = "https://api.telegram.org",
) {
    private sealed class Job {
        class Text(val text: String) : Job()
        class Photo(val jpeg: ByteArray, val caption: String) : Job()
        class Video(val file: File, val caption: String) : Job()
    }

    private val queue = LinkedBlockingQueue<Job>(32)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var lastMotionAlertMs = 0L
    @Volatile var lastError: String? = null
        private set

    private val worker by lazy {
        thread(name = "telegram", isDaemon = true) {
            while (true) {
                val job = queue.poll(1, TimeUnit.HOURS) ?: continue
                runCatching { execute(job) }.onFailure {
                    lastError = it.message
                    log("telegram: ${it.message}")
                }
            }
        }
    }

    private val s: Settings get() = settings()
    private val enabled: Boolean get() = s.bool(Keys.TG_ENABLED) && token.isNotEmpty() && chat.isNotEmpty()
    private val token: String get() = s.string(Keys.TG_TOKEN).trim()
    private val chat: String get() = s.string(Keys.TG_CHAT).trim()

    /** Motion started: photo (or text) unless an alert was sent within the cooldown. Returns true if queued. */
    fun onMotion(cameraName: String, timeText: String, snapshot: ((ByteArray?) -> Unit) -> Unit): Boolean {
        if (!enabled) return false
        val now = System.currentTimeMillis()
        val cooldown = s.int(Keys.TG_COOLDOWN_MIN) * 60_000L
        if (cooldown > 0 && now - lastMotionAlertMs < cooldown) return false
        lastMotionAlertMs = now
        val caption = "Движение — $cameraName\n$timeText"
        if (s.bool(Keys.TG_PHOTO)) snapshot { jpeg -> enqueue(if (jpeg != null) Job.Photo(jpeg, caption) else Job.Text(caption)) }
        else enqueue(Job.Text(caption))
        return true
    }

    /** A motion clip was saved. */
    fun onClip(file: File, caption: String) {
        if (!enabled || !s.bool(Keys.TG_VIDEO)) return
        if (file.length() > MAX_VIDEO_BYTES) {
            enqueue(Job.Text("$caption\n(видео больше 45 МБ — смотрите в веб-интерфейсе)"))
            return
        }
        enqueue(Job.Video(file, caption))
    }

    /** Power loss, camera failure and similar. */
    fun onSystem(text: String) {
        if (enabled && s.bool(Keys.TG_POWER)) enqueue(Job.Text(text))
    }

    /** Sends a test message right away (blocking). Returns null on success or the error text. */
    fun sendTest(cameraName: String, jpeg: ByteArray?): String? {
        if (token.isEmpty()) return "Не задан токен бота"
        if (chat.isEmpty()) return "Не задан ID чата"
        return runCatching {
            val text = "Проверка связи: камера «$cameraName» на связи"
            if (jpeg != null) execute(Job.Photo(jpeg, text)) else execute(Job.Text(text))
            null
        }.getOrElse { it.message ?: "ошибка" }
    }

    /** Chats that recently wrote to the bot (the user sends any message first). */
    fun findChats(): Result<List<Pair<String, String>>> = runCatching {
        if (token.isEmpty()) error("Не задан токен бота")
        val body = request("getUpdates", emptyMap(), null)
        val root = json.parseToJsonElement(body).jsonObject
        val out = LinkedHashMap<String, String>()
        for (u in root["result"] as? JsonArray ?: JsonArray(emptyList())) {
            val o = u.jsonObject
            val msg = (o["message"] ?: o["channel_post"] ?: o["my_chat_member"]) as? JsonObject ?: continue
            val c = msg["chat"] as? JsonObject ?: continue
            val id = c["id"]?.jsonPrimitive?.longOrNull ?: continue
            val title = c["title"]?.jsonPrimitive?.contentOrNull
                ?: listOfNotNull(c["first_name"]?.jsonPrimitive?.contentOrNull, c["last_name"]?.jsonPrimitive?.contentOrNull).joinToString(" ")
                    .ifEmpty { c["username"]?.jsonPrimitive?.contentOrNull ?: id.toString() }
            out[id.toString()] = title
        }
        out.entries.map { it.key to it.value }
    }

    private fun enqueue(job: Job) {
        worker
        if (!queue.offer(job)) log("telegram: queue full, alert dropped")
    }

    private fun execute(job: Job) {
        when (job) {
            is Job.Text -> request("sendMessage", mapOf("chat_id" to chat, "text" to job.text), null)
            is Job.Photo -> request("sendPhoto", mapOf("chat_id" to chat, "caption" to job.caption),
                Part("photo", "snapshot.jpg", "image/jpeg", job.jpeg.size.toLong()) { it.write(job.jpeg) })
            is Job.Video -> request("sendVideo", mapOf("chat_id" to chat, "caption" to job.caption, "supports_streaming" to "true"),
                Part("video", job.file.name, "video/mp4", job.file.length()) { out -> job.file.inputStream().use { it.copyTo(out) } })
        }
        lastError = null
    }

    private class Part(val field: String, val filename: String, val type: String, val length: Long, val write: (OutputStream) -> Unit)

    private fun request(method: String, fields: Map<String, String>?, file: Part?): String {
        val url = URL("$apiBase/bot$token/$method")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = if (file != null) 120_000 else 30_000
        try {
            if (file != null) {
                val boundary = "----surveilcam" + System.nanoTime()
                // Fixed-length multipart: known size up front (friendlier to proxies than chunked uploads).
                val head = ByteArrayOutputStream()
                fun line(s: String) = head.write((s + "\r\n").toByteArray(Charsets.UTF_8))
                for ((k, v) in fields.orEmpty()) {
                    line("--$boundary")
                    line("Content-Disposition: form-data; name=\"$k\"")
                    line("")
                    line(v)
                }
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"${file.field}\"; filename=\"${file.filename}\"")
                line("Content-Type: ${file.type}")
                line("")
                val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.requestMethod = "POST"
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.setFixedLengthStreamingMode(head.size() + file.length + tail.size)
                c.outputStream.buffered().use { out ->
                    head.writeTo(out)
                    file.write(out)
                    out.write(tail)
                }
            } else if (fields != null) {
                c.doOutput = true
                c.requestMethod = "POST"
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                val body = fields.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { s ->
                ByteArrayOutputStream().also { s.copyTo(it) }.toString("UTF-8")
            } ?: ""
            val ok = runCatching { json.parseToJsonElement(text).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
            if (code !in 200..299 || ok == false) {
                val desc = runCatching { json.parseToJsonElement(text).jsonObject["description"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                error(describe(code, desc))
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun describe(code: Int, desc: String?): String = when {
        code == 401 || code == 404 -> "Неверный токен бота"
        desc?.contains("chat not found", ignoreCase = true) == true -> "Чат не найден: напишите боту и укажите верный ID"
        desc?.contains("blocked", ignoreCase = true) == true -> "Бот заблокирован в этом чате"
        else -> "Telegram: ${desc ?: "ошибка $code"}"
    }

    companion object {
        private const val MAX_VIDEO_BYTES = 45L * 1024 * 1024
    }
}
