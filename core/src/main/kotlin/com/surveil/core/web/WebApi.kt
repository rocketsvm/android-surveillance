package com.surveil.core.web

import com.surveil.core.CoreEngine
import com.surveil.core.http.HttpRequest
import com.surveil.core.http.HttpResponse
import com.surveil.core.http.HttpServer
import com.surveil.core.http.MimeTypes
import com.surveil.core.http.Router
import com.surveil.core.record.RecordingsRepo
import com.surveil.core.settings.Schema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/** HTTP routes of the web interface and API. */
class WebApi(private val engine: CoreEngine) {
    private var server: HttpServer? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val assets = ConcurrentHashMap<String, Pair<ByteArray, String>?>()

    val port: Int get() = server?.port ?: 0

    @Synchronized
    fun start(port: Int) {
        if (server != null) return
        val s = HttpServer(router, engine.platform::log)
        s.start(port)
        server = s
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    private val router = Router().apply {
        filter = ::authFilter
        fallback = ::staticFile

        get("/api/session") {
            json(buildJsonObject {
                put("authRequired", engine.auth.required)
                put("authorized", engine.auth.isAuthorized(it))
                put("name", engine.settings.name)
            })
        }
        post("/api/login", ::login)
        post("/api/logout") { req ->
            engine.auth.logout(req)
            HttpResponse.ok().header("Set-Cookie", "${AuthManager.COOKIE}=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
        }

        get("/api/status") { json(engine.statusJson()) }

        get("/api/settings") { settingsResponse() }
        post("/api/settings") { req ->
            val body = parseObject(req) ?: return@post HttpResponse.error(400, "JSON object expected")
            val result = engine.settingsStore.update(body)
            if (result.changed.isNotEmpty()) {
                engine.events.add("settings", "Изменены настройки: " + result.changed.joinToString { Schema.byKey[it]?.title ?: it })
            }
            json(buildJsonObject {
                putJsonArray("changed") { result.changed.forEach { add(JsonPrimitive(it)) } }
                putJsonObject("errors") { result.errors.forEach { (k, v) -> put(k, v) } }
                put("values", engine.settings.publicJson())
            })
        }

        get("/api/recordings") { recordingsResponse() }
        post("/api/recordings/delete") { req ->
            val ids = (parseObject(req)?.get("ids") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val deleted = ids.count { engine.repo.delete(it) }
            if (deleted > 0) engine.events.add("recording", "Удалено записей: $deleted")
            json(buildJsonObject { put("deleted", deleted) })
        }
        delete("/api/recordings/{id}") { req ->
            if (engine.repo.delete(req.param("id"))) HttpResponse.ok() else HttpResponse.notFound()
        }
        get("/rec/{file}") { req -> recordingFile(req) }

        get("/api/snapshot.jpg") { req -> snapshot(req) }
        post("/api/record") { req ->
            val on = parseObject(req)?.get("on")?.jsonPrimitive?.booleanOrNull ?: return@post HttpResponse.error(400, "on expected")
            engine.setManualRecording(on)
            HttpResponse.ok()
        }
        post("/api/camera/restart") {
            engine.platform.restartCamera()
            HttpResponse.ok()
        }
        get("/api/events") { req ->
            val limit = req.query["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 200
            json(buildJsonObject {
                put("events", JsonArray(engine.events.recent(limit).map { engine.eventJson(it) }))
            })
        }

        get("/ws/live") { HttpResponse.WebSocketUpgrade { ws -> engine.live.serveWebSocket(ws) } }
        get("/ws/events") {
            HttpResponse.WebSocketUpgrade { ws ->
                val hello = buildJsonObject {
                    put("type", "hello")
                    put("status", engine.statusJson())
                    put("values", engine.settings.publicJson())
                }
                engine.bus.serve(ws, hello.toString())
            }
        }
        get("/live.mp4") {
            HttpResponse.Stream("video/mp4") { out -> engine.live.serveHttp(out) }
        }
    }

    // ---- auth ----

    private fun authFilter(req: HttpRequest): HttpResponse? {
        if (!engine.auth.required) return null
        val p = req.path
        val public = p == "/" || p == "/index.html" || p.startsWith("/assets/") ||
            p == "/favicon.svg" || p == "/manifest.webmanifest" || p == "/api/session" || p == "/api/login"
        if (public) return null
        return try {
            if (engine.auth.isAuthorized(req)) null
            else HttpResponse.error(401, "Требуется вход").header("WWW-Authenticate", "Basic realm=\"SurveillanceCam\", charset=\"UTF-8\"")
        } catch (e: AuthManager.LockedOut) {
            HttpResponse.error(429, "Слишком много попыток, подождите ${e.seconds} с")
        }
    }

    private fun login(req: HttpRequest): HttpResponse {
        val body = parseObject(req) ?: return HttpResponse.error(400, "JSON object expected")
        val user = body["username"]?.jsonPrimitive?.contentOrNull ?: ""
        val pass = body["password"]?.jsonPrimitive?.contentOrNull ?: ""
        val ip = req.remote.hostAddress ?: ""
        return try {
            val token = engine.auth.login(user, pass, ip)
            if (token == null) {
                engine.events.add("access", "Неудачная попытка входа с $ip")
                HttpResponse.error(401, "Неверное имя пользователя или пароль")
            } else {
                HttpResponse.ok().header(
                    "Set-Cookie",
                    "${AuthManager.COOKIE}=$token; Path=/; Max-Age=${AuthManager.SESSION_MS / 1000}; HttpOnly; SameSite=Strict",
                )
            }
        } catch (e: AuthManager.LockedOut) {
            HttpResponse.error(429, "Слишком много попыток, подождите ${e.seconds} с")
        }
    }

    // ---- settings / recordings ----

    private fun settingsResponse(): HttpResponse {
        val s = engine.settings
        return json(buildJsonObject {
            put("groups", Schema.json(s.values) { key -> engine.platform.dynamicOptions(key, s) })
            put("values", s.publicJson())
            engine.platform.cameraInfo()?.let { put("camera", it) }
        })
    }

    private fun recordingsResponse(): HttpResponse {
        val list = engine.repo.list()
        val root = engine.selectedRoot()
        return json(buildJsonObject {
            put("recordings", buildJsonArray {
                for (m in list) addJsonObject {
                    put("id", m.id)
                    put("startMs", m.startMs)
                    put("durationMs", m.durationMs)
                    put("sizeBytes", m.sizeBytes)
                    put("trigger", m.trigger)
                    put("width", m.width)
                    put("height", m.height)
                    put("peakMotion", m.peakMotion)
                    put("thumb", engine.repo.find(m.id)?.thumb?.exists() == true)
                }
            })
            engine.recorder.currentClipId?.let { id ->
                putJsonObject("current") {
                    put("id", id)
                    put("startMs", engine.recorder.currentStartMs)
                    engine.recorder.currentTrigger?.let { put("trigger", it) }
                }
            }
            putJsonObject("storage") {
                put("label", root.label)
                put("freeBytes", root.freeBytes)
                put("totalBytes", root.totalBytes)
                put("usedBytes", list.sumOf { it.sizeBytes })
            }
        })
    }

    private fun recordingFile(req: HttpRequest): HttpResponse {
        val name = req.param("file")
        val id = name.substringBeforeLast('.')
        if (!RecordingsRepo.ID_RE.matches(id)) return HttpResponse.notFound()
        val clip = engine.repo.find(id) ?: return HttpResponse.notFound()
        return when (name.substringAfterLast('.')) {
            "mp4" -> HttpResponse.FileBody(
                clip.video, "video/mp4",
                downloadName = if (req.query["download"] == "1") "${engine.settings.name.filter { it.isLetterOrDigit() }.ifEmpty { "camera" }}-$id.mp4" else null,
            )
            "jpg" -> HttpResponse.FileBody(clip.thumb, "image/jpeg").header("Cache-Control", "max-age=86400")
            else -> HttpResponse.notFound()
        }
    }

    private fun snapshot(req: HttpRequest): HttpResponse {
        val width = req.query["w"]?.toIntOrNull()?.coerceIn(64, 4096) ?: 1280
        val latch = CountDownLatch(1)
        var jpeg: ByteArray? = null
        engine.platform.snapshot(width) { jpeg = it; latch.countDown() }
        if (!latch.await(4, TimeUnit.SECONDS) || jpeg == null) return HttpResponse.error(503, "Нет кадра с камеры")
        val r = HttpResponse.Bytes(200, "image/jpeg", jpeg!!).header("Cache-Control", "no-store")
        if (req.query["download"] == "1") r.header("Content-Disposition", "attachment; filename=\"snapshot-${System.currentTimeMillis()}.jpg\"")
        return r
    }

    // ---- static web UI from classpath resources ----

    private fun staticFile(req: HttpRequest): HttpResponse {
        if (req.method != "GET" && req.method != "HEAD") return HttpResponse.notFound()
        val path = if (req.path == "/" || req.path.isEmpty()) "/index.html" else req.path
        if (path.contains("..")) return HttpResponse.notFound()
        val asset = (if (devWebRoot != null) loadAsset(path) else assets.getOrPut(path) { loadAsset(path) })
            ?: return HttpResponse.notFound()
        val (bytes, etag) = asset
        if (req.header("if-none-match") == etag) {
            return HttpResponse.Bytes(304, null, ByteArray(0)).header("ETag", etag)
        }
        return HttpResponse.Bytes(200, MimeTypes.of(path), bytes)
            .header("ETag", etag)
            .header("Cache-Control", "no-cache")
    }

    private fun loadAsset(path: String): Pair<ByteArray, String>? {
        val stream = devWebRoot?.let { root -> java.io.File(root, path.removePrefix("/")).takeIf { it.isFile }?.inputStream() }
            ?: WebApi::class.java.getResourceAsStream("/web$path") ?: return null
        val bytes = stream.use { it.readBytes() }
        val crc = CRC32().apply { update(bytes) }.value
        return bytes to "\"${crc.toString(16)}\""
    }

    // ---- helpers ----

    private fun json(obj: JsonElement) = HttpResponse.json(obj.toString())

    private fun parseObject(req: HttpRequest): JsonObject? =
        runCatching { json.parseToJsonElement(req.bodyText()) as? JsonObject }.getOrNull()

    companion object {
        /** Development only: serve the web UI straight from this directory, uncached. */
        @Volatile var devWebRoot: java.io.File? = null
    }
}
