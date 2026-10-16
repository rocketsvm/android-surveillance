package com.surveil.core

import com.surveil.core.http.HttpResponse
import com.surveil.core.http.HttpServer
import com.surveil.core.http.Router
import com.surveil.core.http.WebSocket
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import java.net.http.WebSocket as ClientWebSocket

class HttpServerTest {
    private val file: File = Files.createTempFile("range", ".bin").toFile().apply { writeBytes(ByteArray(1000) { it.toByte() }) }
    private val router = Router().apply {
        get("/hello/{name}") { HttpResponse.text(200, "hi " + it.param("name")) }
        post("/echo") { HttpResponse.text(200, it.bodyText()) }
        get("/file") { HttpResponse.FileBody(file, "application/octet-stream") }
        get("/ws") {
            HttpResponse.WebSocketUpgrade { ws: WebSocket ->
                while (true) {
                    when (val m = ws.receive() ?: break) {
                        is WebSocket.Message.Text -> ws.sendText("echo:" + m.text)
                        is WebSocket.Message.Binary -> ws.sendBinary(m.data, m.data)
                    }
                }
            }
        }
    }
    private val server = HttpServer(router).apply { start(0) }
    private val base = "http://127.0.0.1:${server.port}"
    private val client = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()

    @AfterTest
    fun stop() = server.stop()

    private fun get(path: String, vararg headers: String): java.net.http.HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI(base + path))
        if (headers.isNotEmpty()) b.headers(*headers)
        return client.send(b.build(), BodyHandlers.ofByteArray())
    }

    @Test
    fun routingAndKeepAlive() {
        repeat(3) {
            val r = get("/hello/%D0%BC%D0%B8%D1%80")
            assertEquals(200, r.statusCode())
            assertEquals("hi мир", String(r.body()))
        }
        assertEquals(404, get("/nope").statusCode())
        val post = client.send(
            HttpRequest.newBuilder(URI("$base/echo")).POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}")).build(),
            BodyHandlers.ofString(),
        )
        assertEquals("{\"a\":1}", post.body())
        assertEquals(405, client.send(HttpRequest.newBuilder(URI("$base/echo")).GET().build(), BodyHandlers.discarding()).statusCode())
    }

    @Test
    fun rangeRequests() {
        val full = get("/file")
        assertEquals(1000, full.body().size)
        val part = get("/file", "Range", "bytes=100-199")
        assertEquals(206, part.statusCode())
        assertEquals("bytes 100-199/1000", part.headers().firstValue("content-range").get())
        assertContentEquals(ByteArray(100) { (it + 100).toByte() }, part.body())
        val suffix = get("/file", "Range", "bytes=-10")
        assertEquals(10, suffix.body().size)
        assertEquals(416, get("/file", "Range", "bytes=5000-").statusCode())
    }

    @Test
    fun webSocketEcho() {
        val texts = CompletableFuture<String>()
        val binary = CompletableFuture<Int>()
        val listener = object : ClientWebSocket.Listener {
            override fun onText(ws: ClientWebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                texts.complete(data.toString()); ws.request(1); return null
            }
            var received = 0
            override fun onBinary(ws: ClientWebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                received += data.remaining()
                if (last) binary.complete(received)
                ws.request(1); return null
            }
        }
        val ws = client.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:${server.port}/ws"), listener).get(5, TimeUnit.SECONDS)
        ws.sendText("привет", true)
        assertEquals("echo:привет", texts.get(5, TimeUnit.SECONDS))
        ws.sendBinary(ByteBuffer.wrap(ByteArray(70_000) { 1 }), true) // 64-bit length path
        assertEquals(140_000, binary.get(5, TimeUnit.SECONDS))
        ws.sendClose(ClientWebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS)
    }

    @Test
    fun parsesRanges() {
        assertEquals(0L..499L, HttpServer.parseRange("bytes=0-499", 1000))
        assertEquals(900L..999L, HttpServer.parseRange("bytes=900-", 1000))
        assertEquals(990L..999L, HttpServer.parseRange("bytes=-10", 1000))
        assertEquals(0L..999L, HttpServer.parseRange("bytes=0-5000", 1000))
        assertEquals(null, HttpServer.parseRange("bytes=1000-", 1000))
        assertEquals(null, HttpServer.parseRange("items=0-1", 1000))
    }
}
