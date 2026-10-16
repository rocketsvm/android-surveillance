package com.surveil.core.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Server side of an RFC 6455 connection. Sending is thread-safe; [receive] must be called
 * from a single reader thread. The socket read timeout doubles as a liveness check: the
 * server pings periodically ([ping]) and browsers answer with pongs.
 */
class WebSocket internal constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val request: HttpRequest,
) {
    sealed class Message {
        class Text(val text: String) : Message()
        class Binary(val data: ByteArray) : Message()
    }

    private val writeLock = ReentrantLock()

    @Volatile var isOpen = true
        private set

    fun sendText(text: String): Boolean = sendFrame(OP_TEXT, listOf(text.toByteArray(Charsets.UTF_8)))
    fun sendBinary(vararg parts: ByteArray): Boolean = sendFrame(OP_BINARY, parts.asList())
    fun sendBinary(parts: List<ByteArray>): Boolean = sendFrame(OP_BINARY, parts)
    fun ping(): Boolean = sendFrame(OP_PING, emptyList())

    /** Blocking receive of the next data message; control frames are handled internally. Null = closed. */
    fun receive(): Message? {
        var fragments: ByteArrayOutputStream? = null
        var fragmentOpcode = 0
        try {
            while (isOpen) {
                val b0 = input.read()
                if (b0 < 0) break
                val b1 = readByte()
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0f
                val masked = b1 and 0x80 != 0
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
                else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or readByte().toLong() }
                }
                if (len > MAX_MESSAGE) {
                    close(1009, "message too big")
                    return null
                }
                val mask = if (masked) ByteArray(4).also { readFully(it) } else null
                val payload = ByteArray(len.toInt())
                readFully(payload)
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

                when (opcode) {
                    OP_CLOSE -> {
                        val code = if (payload.size >= 2) ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff) else 1000
                        close(code, "")
                        return null
                    }
                    OP_PING -> sendFrame(OP_PONG, listOf(payload))
                    OP_PONG -> Unit
                    OP_TEXT, OP_BINARY -> {
                        if (fin) return toMessage(opcode, payload)
                        fragments = ByteArrayOutputStream().also { it.write(payload) }
                        fragmentOpcode = opcode
                    }
                    OP_CONTINUATION -> {
                        val f = fragments ?: continue
                        f.write(payload)
                        if (f.size() > MAX_MESSAGE) {
                            close(1009, "message too big"); return null
                        }
                        if (fin) {
                            fragments = null
                            return toMessage(fragmentOpcode, f.toByteArray())
                        }
                    }
                    else -> {
                        close(1002, "protocol error"); return null
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            // No traffic (not even pongs) within the read timeout: the peer is gone.
        } catch (_: IOException) {
        }
        terminate()
        return null
    }

    fun close(code: Int = 1000, reason: String = "") {
        if (!isOpen) return
        val r = reason.toByteArray(Charsets.UTF_8).take(120).toByteArray()
        val payload = ByteArray(2 + r.size)
        payload[0] = (code ushr 8).toByte(); payload[1] = code.toByte()
        r.copyInto(payload, 2)
        sendFrame(OP_CLOSE, listOf(payload))
        terminate()
    }

    /** Closes the socket immediately, without the close handshake. Unblocks a writer stuck on a dead peer. */
    fun abort() = terminate()

    private fun terminate() {
        isOpen = false
        runCatching { socket.close() }
    }

    private fun sendFrame(opcode: Int, parts: List<ByteArray>): Boolean {
        if (!isOpen) return false
        var len = 0L
        for (p in parts) len += p.size
        val header = ByteArray(10)
        header[0] = (0x80 or opcode).toByte()
        val hl: Int
        when {
            len < 126 -> { header[1] = len.toInt().toByte(); hl = 2 }
            len <= 0xffff -> {
                header[1] = 126; header[2] = (len ushr 8).toInt().toByte(); header[3] = len.toInt().toByte(); hl = 4
            }
            else -> {
                header[1] = 127
                for (i in 0 until 8) header[2 + i] = (len ushr (56 - 8 * i)).toInt().toByte()
                hl = 10
            }
        }
        return try {
            writeLock.withLock {
                output.write(header, 0, hl)
                for (p in parts) output.write(p)
                output.flush()
            }
            true
        } catch (_: IOException) {
            terminate()
            false
        }
    }

    private fun toMessage(opcode: Int, payload: ByteArray): Message =
        if (opcode == OP_TEXT) Message.Text(String(payload, Charsets.UTF_8)) else Message.Binary(payload)

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw IOException("eof")
        return b
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
    }

    companion object {
        const val OP_CONTINUATION = 0
        const val OP_TEXT = 1
        const val OP_BINARY = 2
        const val OP_CLOSE = 8
        const val OP_PING = 9
        const val OP_PONG = 10
        private const val MAX_MESSAGE = 1L shl 20
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun acceptKey(key: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray(Charsets.US_ASCII))
            return Base64.getEncoder().encodeToString(sha1)
        }
    }
}
