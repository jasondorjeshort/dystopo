package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.Filter
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest

/**
 * In front of every route: the bearer token on `/build` and `/sync`, CORS by allowlist, preflight
 * answers, and one JSON log line per request. The routes themselves (serverlib) add
 * `Access-Control-Allow-Origin: *` to their answers; the gate rewrites that header just before the
 * response headers go out ([Exchange.sendResponseHeaders]), so the allowlist holds for every route.
 */
class Gate(
    private val token: String?,
    corsOrigins: List<String>,
    private val log: JsonLog,
    private val logRequests: Boolean,
) : Filter() {
    private val anyOrigin = "*" in corsOrigins
    private val origins = corsOrigins.filter { it != "*" }.map { it.trimEnd('/').lowercase() }.toSet()
    private val tokenBytes = token?.toByteArray()

    override fun description() = "dystopo gate: bearer token on /build and /sync, CORS allowlist, request log"

    /** The value for `Access-Control-Allow-Origin` for a request from [origin], or null to send none. */
    fun allowOrigin(origin: String?): String? = when {
        origin == null -> if (anyOrigin) "*" else null
        anyOrigin -> "*"
        origin.trimEnd('/').lowercase() in origins -> origin
        else -> null
    }

    fun authorized(header: String?): Boolean {
        val expected = tokenBytes ?: return true
        val given = header?.trim()?.takeIf { it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) }?.substring(7)?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), expected)
    }

    override fun doFilter(raw: HttpExchange, chain: Chain) {
        val started = System.nanoTime()
        val origin = raw.requestHeaders.getFirst("Origin")
        val ex = Exchange(raw, allowOrigin(origin), origin != null && origins.isNotEmpty() && !anyOrigin)
        val path = raw.requestURI.path
        var outcome: String? = null
        try {
            when {
                raw.requestMethod == "OPTIONS" && raw.requestHeaders.getFirst("Access-Control-Request-Method") != null -> {
                    // CORS preflight: answered here for every route (the routes' own OPTIONS answers lack Allow-Headers).
                    ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, HEAD, POST, DELETE, OPTIONS")
                    ex.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, Content-Type, Range, If-Range")
                    ex.responseHeaders.add("Access-Control-Max-Age", "600")
                    ex.sendResponseHeaders(204, -1)
                    ex.close()
                    outcome = "preflight"
                }
                isProtected(path) && !authorized(raw.requestHeaders.getFirst("Authorization")) -> {
                    val body = (JsonWriter.write(mapOf("error" to "this route needs Authorization: Bearer <token>", "status" to 401)) + "\n").toByteArray()
                    ex.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"dystopo\"")
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(401, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                    outcome = "unauthorized"
                }
                else -> chain.doFilter(ex)
            }
        } catch (e: Throwable) {
            outcome = "exception"
            log.error("request_failed", mapOf("method" to raw.requestMethod, "path" to path, "error" to e.toString()))
            if (ex.status == 0) runCatching { ex.sendResponseHeaders(500, -1); ex.close() }
        } finally {
            val status = ex.status
            if (logRequests || status >= 500 || outcome == "unauthorized") {
                val fields = linkedMapOf<String, Any?>(
                    "method" to raw.requestMethod, "path" to path, "status" to status,
                    "ms" to Math.round((System.nanoTime() - started) / 1e5) / 10.0,
                    "bytes" to ex.length.takeIf { it >= 0 },
                    "remote" to (raw.requestHeaders.getFirst("X-Forwarded-For")?.substringBefore(',')?.trim() ?: raw.remoteAddress?.address?.hostAddress),
                )
                raw.responseHeaders.getFirst("X-Dystopo-Source")?.let { fields["source"] = it }
                outcome?.let { fields["outcome"] = it }
                when {
                    status >= 500 -> log.error("request", fields)
                    status == 401 || status == 403 || status == 429 -> log.warn("request", fields)
                    else -> log.info("request", fields)
                }
            }
        }
    }

    /**
     * The request's exchange with CORS fixed up at send time, and the status and length recorded for
     * the log. Everything else goes straight to [inner].
     */
    private class Exchange(private val inner: HttpExchange, private val allow: String?, private val vary: Boolean) : HttpExchange() {
        @Volatile var status = 0
        @Volatile var length = -1L

        override fun sendResponseHeaders(rCode: Int, responseLength: Long) {
            val h = inner.responseHeaders
            h.remove("Access-Control-Allow-Origin")
            if (allow != null) {
                h.set("Access-Control-Allow-Origin", allow)
                h.set("Access-Control-Expose-Headers", "X-Dystopo-Source, Content-Range, Content-Length, ETag")
            }
            if (vary) h.add("Vary", "Origin")
            status = rCode
            length = if (responseLength > 0) responseLength else if (responseLength == -1L) 0 else -1
            inner.sendResponseHeaders(rCode, responseLength)
        }

        override fun getRequestHeaders(): Headers = inner.requestHeaders
        override fun getResponseHeaders(): Headers = inner.responseHeaders
        override fun getRequestURI(): URI = inner.requestURI
        override fun getRequestMethod(): String = inner.requestMethod
        override fun getHttpContext(): HttpContext = inner.httpContext
        override fun close() = inner.close()
        override fun getRequestBody(): InputStream = inner.requestBody
        override fun getResponseBody(): OutputStream = inner.responseBody
        override fun getResponseCode(): Int = inner.responseCode
        override fun getRemoteAddress(): InetSocketAddress = inner.remoteAddress
        override fun getLocalAddress(): InetSocketAddress = inner.localAddress
        override fun getProtocol(): String = inner.protocol
        override fun getAttribute(name: String?): Any? = inner.getAttribute(name)
        override fun setAttribute(name: String?, value: Any?) = inner.setAttribute(name, value)
        override fun setStreams(i: InputStream?, o: OutputStream?) = inner.setStreams(i, o)
        override fun getPrincipal(): HttpPrincipal? = inner.principal
    }

    companion object {
        /** Routes behind the token: pack builds and the sync peer. */
        fun isProtected(path: String): Boolean =
            path == "/build" || path.startsWith("/build/") || path == "/sync" || path.startsWith("/sync/") ||
                path == "/catalog/refresh" // T58: rehashes every pack file, so not for anyone
    }
}
