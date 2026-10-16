package com.surveil.core

import com.surveil.core.http.HttpResponse
import com.surveil.core.http.HttpServer
import com.surveil.core.http.Router
import com.surveil.core.notify.TelegramNotifier
import com.surveil.core.settings.Keys
import com.surveil.core.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the notifier against a fake Bot API served by our own HTTP server. */
class TelegramTest {
    private class Call(val method: String, val contentType: String, val body: String)

    private val calls = LinkedBlockingQueue<Call>()
    private val router = Router().apply {
        post("/{bot}/{method}") { req ->
            if (req.param("bot") != "bot123:ABC") return@post HttpResponse.json("{\"ok\":false,\"description\":\"Unauthorized\"}", 401)
            calls += Call(req.param("method"), req.header("content-type") ?: "", String(req.body, Charsets.ISO_8859_1))
            when (req.param("method")) {
                "getUpdates" -> HttpResponse.json(
                    "{\"ok\":true,\"result\":[{\"update_id\":1,\"message\":{\"chat\":{\"id\":42,\"first_name\":\"Иван\"},\"text\":\"hi\"}}]}",
                )
                else -> HttpResponse.json("{\"ok\":true,\"result\":{}}")
            }
        }
    }
    private val server = HttpServer(router).apply { start(0) }
    private val settings = SettingsStore(File(Files.createTempDirectory("tg").toFile(), "settings.json"))
    private val logs = CopyOnWriteArrayList<String>()
    private val notifier = TelegramNotifier({ settings.current }, { logs += it }, "http://127.0.0.1:${server.port}")

    @AfterTest
    fun stop() = server.stop()

    private fun configure() {
        settings.set(Keys.TG_ENABLED, true)
        settings.set(Keys.TG_TOKEN, "123:ABC")
        settings.set(Keys.TG_CHAT, "42")
    }

    @Test
    fun tokenIsStoredAsIsButHiddenFromClients() {
        configure()
        assertEquals("123:ABC", settings.current.string(Keys.TG_TOKEN))
        assertFalse(settings.current.publicJson().containsKey(Keys.TG_TOKEN))
    }

    @Test
    fun findsChatAndSendsTestPhoto() {
        configure()
        val chats = notifier.findChats().getOrThrow()
        assertEquals(listOf("42" to "Иван"), chats)
        calls.clear()
        assertNull(notifier.sendTest("Дача", ByteArray(100) { 1 }))
        val call = assertNotNull(calls.poll(5, TimeUnit.SECONDS))
        assertEquals("sendPhoto", call.method)
        assertTrue(call.contentType.startsWith("multipart/form-data"))
        assertTrue(call.body.contains("name=\"chat_id\"\r\n\r\n42"))
        assertTrue(call.body.contains("filename=\"snapshot.jpg\""))
    }

    @Test
    fun wrongTokenGivesReadableError() {
        configure()
        settings.set(Keys.TG_TOKEN, "999:BAD")
        assertEquals("Неверный токен бота", notifier.sendTest("Дача", null))
    }

    @Test
    fun motionAlertsRespectCooldown() {
        configure()
        settings.set(Keys.TG_COOLDOWN_MIN, 5)
        settings.set(Keys.TG_PHOTO, false)
        assertTrue(notifier.onMotion("Дача", "17.10.2026 03:48:07") { it(null) })
        assertFalse(notifier.onMotion("Дача", "17.10.2026 03:48:10") { it(null) }, "second alert within the cooldown")
        val call = assertNotNull(calls.poll(5, TimeUnit.SECONDS))
        assertEquals("sendMessage", call.method)
        assertTrue(call.body.contains("chat_id=42"))
    }

    @Test
    fun disabledSendsNothing() {
        configure()
        settings.set(Keys.TG_ENABLED, false)
        assertFalse(notifier.onMotion("Дача", "x") { it(null) })
        notifier.onSystem("Зарядка отключена")
        assertNull(calls.poll(500, TimeUnit.MILLISECONDS))
    }
}
