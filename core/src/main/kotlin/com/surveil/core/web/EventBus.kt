package com.surveil.core.web

import com.surveil.core.http.WebSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Pushes JSON messages (status, motion, settings, events) to connected browsers over WebSocket. */
class EventBus {
    private class Client(val ws: WebSocket) {
        val queue = LinkedBlockingQueue<String>(256)
    }

    private val clients = CopyOnWriteArrayList<Client>()

    val size: Int get() = clients.size

    /** Blocks for the lifetime of the connection. [hello] is sent first. */
    fun serve(ws: WebSocket, hello: String) {
        val c = Client(ws)
        clients += c
        thread(name = "ws-events-reader", isDaemon = true) {
            while (ws.receive() != null) { /* ignore */ }
        }
        try {
            if (!ws.sendText(hello)) return
            var lastPing = System.nanoTime()
            while (ws.isOpen) {
                val msg = c.queue.poll(1, TimeUnit.SECONDS)
                if (msg != null && !ws.sendText(msg)) break
                val now = System.nanoTime()
                if (now - lastPing > 15_000_000_000L) {
                    lastPing = now
                    if (!ws.ping()) break
                }
            }
        } finally {
            clients -= c
            ws.close()
        }
    }

    /** Non-blocking; a client that cannot keep up simply misses messages. */
    fun broadcast(message: String) {
        for (c in clients) c.queue.offer(message)
    }
}
