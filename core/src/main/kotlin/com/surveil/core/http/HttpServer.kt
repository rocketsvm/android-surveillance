package com.surveil.core.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small blocking HTTP/1.1 server: one thread per connection, keep-alive, Range requests,
 * close-delimited streams and WebSocket upgrades. Meant for a handful of LAN clients.
 */
class HttpServer(
    private val handler: HttpHandler,
    private val log: (String) -> Unit = {},
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val threadIds = AtomicInteger()
    private val pool = ThreadPoolExecutor(
        0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue(),
    ) { r -> Thread(r, "http-" + threadIds.incrementAndGet()).apply { isDaemon = true } }

    @Volatile var port: Int = 0
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Binds to all interfaces. Throws if the port is busy. */
    @Synchronized
    fun start(port: Int) {
        check(serverSocket == null) { "already started" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 64)
        serverSocket = ss
        this.port = ss.localPort
        acceptThread = Thread({ acceptLoop(ss) }, "http-accept").apply { isDaemon = true; start() }
        log("HTTP server listening on :${this.port}")
    }

    @Synchronized
    fun stop() {
        val ss = serverSocket ?: return
        serverSocket = null
        runCatching { ss.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        acceptThread?.join(1000)
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                if (!ss.isClosed) log("accept failed: ${e.message}")
                continue
            }
            try {
                pool.execute { serve(s) }
            } catch (_: RejectedExecutionException) {
                runCatching { s.close() }
            }
        }
    }

    private val currentSocket = ThreadLocal<Socket>()

    private fun serve(socket: Socket) {
        sockets += socket
        currentSocket.set(socket)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = IDLE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            while (!socket.isClosed) {
                val req = try {
                    readRequest(input, output, socket) ?: break
                } catch (e: BadRequest) {
                    writeSimple(output, e.status, e.message ?: "bad request")
                    break
                }
                val resp = try {
                    handler.handle(req)
                } catch (e: Throwable) {
                    log("handler error ${req.method} ${req.path}: $e")
                    HttpResponse.error(500, e.message ?: e.javaClass.simpleName)
                }
                if (resp is HttpResponse.WebSocketUpgrade) {
                    upgrade(req, resp, socket, input, output)
                    break
                }
                if (!writeResponse(req, resp, output)) break
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: SocketException) {
        } catch (e: IOException) {
            // client went away
        } catch (e: Throwable) {
            log("connection error: $e")
        } finally {
            currentSocket.remove()
            sockets -= socket
            runCatching { socket.close() }
        }
    }

    private class BadRequest(val status: Int, message: String) : Exception(message)

    private fun readRequest(input: InputStream, output: OutputStream, socket: Socket): HttpRequest? {
        var line = readLine(input) ?: return null
        while (line.isEmpty()) line = readLine(input) ?: return null // tolerate stray CRLFs
        val parts = line.split(' ')
        if (parts.size != 3) throw BadRequest(400, "bad request line")
        val (method, target, version) = parts
        if (!version.startsWith("HTTP/1.")) throw BadRequest(505, "HTTP version not supported")
        val headers = HttpHeaders()
        var count = 0
        while (true) {
            val h = readLine(input) ?: throw BadRequest(400, "truncated headers")
            if (h.isEmpty()) break
            if (++count > 100) throw BadRequest(431, "too many headers")
            val colon = h.indexOf(':')
            if (colon <= 0) throw BadRequest(400, "bad header")
            headers.add(h.substring(0, colon).trim(), h.substring(colon + 1).trim())
        }
        if (headers["transfer-encoding"] != null) throw BadRequest(501, "chunked request bodies are not supported")
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length < 0 || length > MAX_BODY) throw BadRequest(413, "body too large")
        if (length > 0 && headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        val body = ByteArray(length.toInt())
        var off = 0
        while (off < body.size) {
            val n = input.read(body, off, body.size - off)
            if (n < 0) throw IOException("eof in body")
            off += n
        }
        val q = target.indexOf('?')
        val rawPath = if (q >= 0) target.substring(0, q) else target
        val pathOnly = if (rawPath.contains("://")) "/" + rawPath.substringAfter("://").substringAfter('/', "") else rawPath
        val path = HttpRequest.decodePath(pathOnly)
        if (!path.startsWith("/") || path.contains("/../") || path.endsWith("/..")) throw BadRequest(400, "bad path")
        return HttpRequest(
            method = method.uppercase(),
            target = target,
            path = path,
            query = HttpRequest.parseQuery(if (q >= 0) target.substring(q + 1) else null),
            headers = headers,
            body = body,
            remote = socket.inetAddress,
            version = version,
        )
    }

    /** Reads a CRLF (or LF) terminated ASCII line; null on EOF before any byte. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else throw IOException("eof in line")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw BadRequest(414, "line too long")
        }
        return sb.toString()
    }

    private fun keepAlive(req: HttpRequest): Boolean =
        if (req.version == "HTTP/1.0") req.headers.hasToken("connection", "keep-alive")
        else !req.headers.hasToken("connection", "close")

    /** Writes the response; returns whether the connection may be reused. */
    private fun writeResponse(req: HttpRequest, resp: HttpResponse, out: OutputStream): Boolean {
        val keep = keepAlive(req)
        when (resp) {
            is HttpResponse.Bytes -> {
                val head = StringBuilder()
                statusLine(head, resp.status)
                resp.contentType?.let { header(head, "Content-Type", it) }
                header(head, "Content-Length", resp.body.size.toString())
                commonHeaders(head, resp, keep)
                out.write(head.append("\r\n").toString().toByteArray(Charsets.UTF_8))
                if (!req.isHead) out.write(resp.body)
                out.flush()
                return keep
            }
            is HttpResponse.FileBody -> return writeFile(req, resp, out, keep)
            is HttpResponse.Stream -> {
                val socket = currentSocket.get()
                val head = StringBuilder()
                statusLine(head, 200)
                header(head, "Content-Type", resp.contentType)
                header(head, "Cache-Control", "no-store")
                commonHeaders(head, resp, keep = false)
                out.write(head.append("\r\n").toString().toByteArray(Charsets.UTF_8))
                out.flush()
                if (!req.isHead) {
                    try {
                        resp.writer(if (socket != null) StreamOutput(out, socket) else out)
                        out.flush()
                    } catch (_: IOException) {
                    }
                }
                return false
            }
            is HttpResponse.WebSocketUpgrade -> error("handled separately")
        }
    }

    private fun writeFile(req: HttpRequest, resp: HttpResponse.FileBody, out: OutputStream, keep: Boolean): Boolean {
        val file = resp.file
        if (!file.isFile) return writeResponse(req, HttpResponse.notFound(), out)
        val total = file.length()
        var start = 0L
        var end = total - 1
        var status = 200
        val range = req.header("range")
        if (range != null && total > 0) {
            val r = parseRange(range, total)
            if (r == null) {
                val head = StringBuilder()
                statusLine(head, 416)
                header(head, "Content-Range", "bytes */$total")
                header(head, "Content-Length", "0")
                commonHeaders(head, resp, keep)
                out.write(head.append("\r\n").toString().toByteArray(Charsets.UTF_8))
                out.flush()
                return keep
            }
            start = r.first; end = r.last; status = 206
        }
        val length = if (total == 0L) 0 else end - start + 1
        val head = StringBuilder()
        statusLine(head, status)
        header(head, "Content-Type", resp.contentType)
        header(head, "Content-Length", length.toString())
        header(head, "Accept-Ranges", "bytes")
        header(head, "Last-Modified", httpDate(file.lastModified()))
        if (status == 206) header(head, "Content-Range", "bytes $start-$end/$total")
        resp.downloadName?.let { header(head, "Content-Disposition", "attachment; filename=\"$it\"") }
        commonHeaders(head, resp, keep)
        out.write(head.append("\r\n").toString().toByteArray(Charsets.UTF_8))
        if (!req.isHead && length > 0) {
            FileInputStream(file).use { fis ->
                val ch = fis.channel
                ch.position(start)
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
        out.flush()
        return keep
    }

    private fun upgrade(req: HttpRequest, resp: HttpResponse.WebSocketUpgrade, socket: Socket, input: InputStream, out: OutputStream) {
        val key = req.header("sec-websocket-key")
        if (key == null || !req.headers.hasToken("upgrade", "websocket")) {
            writeResponse(req, HttpResponse.error(400, "websocket upgrade expected"), out)
            return
        }
        val head = StringBuilder()
        statusLine(head, 101)
        header(head, "Upgrade", "websocket")
        header(head, "Connection", "Upgrade")
        header(head, "Sec-WebSocket-Accept", WebSocket.acceptKey(key))
        resp.headers.forEach { (k, v) -> header(head, k, v) }
        out.write(head.append("\r\n").toString().toByteArray(Charsets.UTF_8))
        out.flush()
        socket.soTimeout = WS_READ_TIMEOUT_MS
        val ws = WebSocket(socket, input, out, req)
        try {
            resp.handler(ws)
        } finally {
            ws.close()
        }
    }

    private fun writeSimple(out: OutputStream, status: Int, message: String) {
        runCatching {
            val body = message.toByteArray(Charsets.UTF_8)
            val head = StringBuilder()
            statusLine(head, status)
            header(head, "Content-Type", "text/plain; charset=utf-8")
            header(head, "Content-Length", body.size.toString())
            header(head, "Connection", "close")
            out.write(head.append("\r\n").toString().toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
        }
    }

    private fun statusLine(sb: StringBuilder, status: Int) {
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
    }

    private fun header(sb: StringBuilder, name: String, value: String) {
        sb.append(name).append(": ").append(value).append("\r\n")
    }

    private fun commonHeaders(sb: StringBuilder, resp: HttpResponse, keep: Boolean) {
        header(sb, "Server", "SurveillanceCam")
        header(sb, "Connection", if (keep) "keep-alive" else "close")
        for ((k, v) in resp.headers) header(sb, k, v)
    }

    companion object {
        const val MAX_CONNECTIONS = 64
        private const val IDLE_TIMEOUT_MS = 30_000
        private const val WS_READ_TIMEOUT_MS = 45_000
        private const val MAX_LINE = 8 * 1024
        private const val MAX_BODY = 1L shl 20

        /** Parses a single `bytes=` range; null if unsatisfiable. Multi-range requests get the first range. */
        fun parseRange(header: String, total: Long): LongRange? {
            if (!header.startsWith("bytes=")) return null
            val spec = header.substring(6).split(',')[0].trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            return if (a.isEmpty()) {
                val suffix = b.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                maxOf(0, total - suffix)..(total - 1)
            } else {
                val start = a.toLongOrNull() ?: return null
                val end = if (b.isEmpty()) total - 1 else minOf(b.toLongOrNull() ?: return null, total - 1)
                if (start >= total || start > end) return null
                start..end
            }
        }

        fun httpDate(ms: Long): String {
            val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("GMT")
            return f.format(java.util.Date(ms))
        }

        fun reason(status: Int): String = when (status) {
            100 -> "Continue"
            101 -> "Switching Protocols"
            200 -> "OK"
            204 -> "No Content"
            206 -> "Partial Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            414 -> "URI Too Long"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            505 -> "HTTP Version Not Supported"
            else -> "Status"
        }
    }
}
