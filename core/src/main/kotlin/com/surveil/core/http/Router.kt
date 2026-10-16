package com.surveil.core.http

/**
 * Method + path-pattern routing. Patterns: `/api/recordings/{id}` binds `id`;
 * a trailing `/{rest...}` captures the remainder of the path.
 */
class Router : HttpHandler {
    private class Route(val method: String, val segments: List<String>, val handler: (HttpRequest) -> HttpResponse)

    private val routes = ArrayList<Route>()

    /** Runs before routing; may return a response to short-circuit (e.g. authentication). */
    var filter: ((HttpRequest) -> HttpResponse?)? = null

    /** Handles requests no route matched. */
    var fallback: (HttpRequest) -> HttpResponse = { HttpResponse.notFound() }

    fun get(pattern: String, handler: (HttpRequest) -> HttpResponse) = add("GET", pattern, handler)
    fun post(pattern: String, handler: (HttpRequest) -> HttpResponse) = add("POST", pattern, handler)
    fun put(pattern: String, handler: (HttpRequest) -> HttpResponse) = add("PUT", pattern, handler)
    fun delete(pattern: String, handler: (HttpRequest) -> HttpResponse) = add("DELETE", pattern, handler)

    fun add(method: String, pattern: String, handler: (HttpRequest) -> HttpResponse) {
        routes += Route(method, split(pattern), handler)
    }

    override fun handle(request: HttpRequest): HttpResponse {
        filter?.invoke(request)?.let { return it }
        val segs = split(request.path)
        var methodMismatch = false
        for (r in routes) {
            val params = match(r.segments, segs) ?: continue
            val methodOk = r.method == request.method || (r.method == "GET" && request.method == "HEAD")
            if (!methodOk) {
                methodMismatch = true
                continue
            }
            request.params = params
            return r.handler(request)
        }
        return if (methodMismatch) HttpResponse.error(405, "method not allowed") else fallback(request)
    }

    private fun split(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    private fun match(pattern: List<String>, path: List<String>): Map<String, String>? {
        val params = HashMap<String, String>()
        for (i in pattern.indices) {
            val p = pattern[i]
            if (p.startsWith("{") && p.endsWith("...}")) {
                params[p.substring(1, p.length - 4)] = path.drop(i).joinToString("/")
                return params
            }
            if (i >= path.size) return null
            if (p.startsWith("{") && p.endsWith("}")) params[p.substring(1, p.length - 1)] = path[i]
            else if (p != path[i]) return null
        }
        return if (pattern.size == path.size) params else null
    }
}
