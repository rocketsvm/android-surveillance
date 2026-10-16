package com.surveil.core.http

import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.URLDecoder

/** Case-insensitive multi-map of HTTP headers. */
class HttpHeaders {
    private val map = LinkedHashMap<String, MutableList<String>>()

    fun add(name: String, value: String) {
        map.getOrPut(name.lowercase()) { ArrayList(1) } += value
    }

    operator fun get(name: String): String? = map[name.lowercase()]?.firstOrNull()
    fun all(name: String): List<String> = map[name.lowercase()] ?: emptyList()
    fun contains(name: String) = map.containsKey(name.lowercase())

    /** True if a comma-separated header (e.g. Connection) contains [token]. */
    fun hasToken(name: String, token: String): Boolean =
        all(name).any { v -> v.split(',').any { it.trim().equals(token, ignoreCase = true) } }
}

class HttpRequest(
    val method: String,
    val target: String,
    val path: String,
    val query: Map<String, String>,
    val headers: HttpHeaders,
    val body: ByteArray,
    val remote: InetAddress,
    val version: String,
) {
    /** Path parameters filled in by [Router]. */
    var params: Map<String, String> = emptyMap()
        internal set

    /** Attributes attached by request filters (e.g. the authenticated user). */
    val attributes = HashMap<String, Any>()

    fun header(name: String): String? = headers[name]
    fun param(name: String): String = params[name] ?: error("missing path parameter $name")
    fun bodyText(): String = String(body, Charsets.UTF_8)

    fun cookie(name: String): String? {
        for (h in headers.all("cookie")) {
            for (part in h.split(';')) {
                val eq = part.indexOf('=')
                if (eq > 0 && part.substring(0, eq).trim() == name) return part.substring(eq + 1).trim()
            }
        }
        return null
    }

    val isHead: Boolean get() = method == "HEAD"

    companion object {
        fun parseQuery(q: String?): Map<String, String> {
            if (q.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in q.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val k = if (eq >= 0) pair.substring(0, eq) else pair
                val v = if (eq >= 0) pair.substring(eq + 1) else ""
                runCatching { out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") }
            }
            return out
        }

        /** Percent-decoding for paths ('+' stays '+'). */
        fun decodePath(p: String): String {
            if (!p.contains('%')) return p
            val src = p.toByteArray(Charsets.UTF_8)
            val out = java.io.ByteArrayOutputStream(src.size)
            var i = 0
            while (i < src.size) {
                if (src[i] == '%'.code.toByte() && i + 2 < src.size) {
                    val hi = Character.digit(src[i + 1].toInt(), 16)
                    val lo = Character.digit(src[i + 2].toInt(), 16)
                    if (hi >= 0 && lo >= 0) {
                        out.write(hi * 16 + lo); i += 3; continue
                    }
                }
                out.write(src[i].toInt()); i++
            }
            return out.toString("UTF-8")
        }
    }
}

sealed class HttpResponse(val status: Int) {
    val headers = ArrayList<Pair<String, String>>()

    fun header(name: String, value: String) = apply { headers += name to value }

    class Bytes(status: Int, val contentType: String?, val body: ByteArray) : HttpResponse(status)

    /** A file, served with Range support. [downloadName] adds Content-Disposition: attachment. */
    class FileBody(val file: File, val contentType: String, val downloadName: String? = null) : HttpResponse(200)

    /** Close-delimited stream of unknown length (live video). [writer] runs on the connection thread. */
    class Stream(val contentType: String, val writer: (OutputStream) -> Unit) : HttpResponse(200)

    class WebSocketUpgrade(val handler: (WebSocket) -> Unit) : HttpResponse(101)

    companion object {
        const val JSON = "application/json; charset=utf-8"

        fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8") =
            Bytes(status, type, text.toByteArray(Charsets.UTF_8))

        fun json(body: String, status: Int = 200) = text(status, body, JSON)
        fun ok() = json("{\"ok\":true}")
        fun error(status: Int, message: String) =
            json("{\"error\":" + jsonString(message) + "}", status)

        fun notFound() = error(404, "not found")
        fun redirect(location: String) = Bytes(302, null, ByteArray(0)).header("Location", location)

        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }
    }
}

fun interface HttpHandler {
    fun handle(request: HttpRequest): HttpResponse
}

object MimeTypes {
    fun of(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ico" -> "image/x-icon"
        "webmanifest" -> "application/manifest+json"
        "mp4" -> "video/mp4"
        "txt", "log" -> "text/plain; charset=utf-8"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}
