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

/**
 * In front of every route: the bearer tokens on `/build`, `/sync` and `/admin`, CORS by allowlist,
 * preflight answers, and one JSON log line per request. The routes themselves (serverlib) add
 * `Access-Control-Allow-Origin: *` to their answers; the gate rewrites that header just before the
 * response headers go out ([Exchange.sendResponseHeaders]), so the allowlist holds for every route.
 *
 * T67: tokens come from [Users] (user tokens, the admin token, the T52 single token). A `/sync*` request
 * runs as the token's user: the gate sets [Users.ATTR] on the exchange and `SyncRoutes` opens that
 * user's replica. The admin token reaches `/sync*` only with `?user=<id>` (400 without, 404 for an
 * unknown id) and is the only token for `/admin*` (a user token there is a 403). Share pages (`/s/...`)
 * stay open: the link is the secret.
 */
class Gate(
    val users: Users,
    corsOrigins: List<String>,
    private val log: JsonLog,
    private val logRequests: Boolean,
) : Filter() {
    /** The T52 form: one token (or none) for everything. */
    constructor(token: String?, corsOrigins: List<String>, log: JsonLog, logRequests: Boolean) :
        this(Users(legacyToken = token?.trim()?.takeIf { it.isNotEmpty() }), corsOrigins, log, logRequests)

    private val anyOrigin = "*" in corsOrigins
    private val origins = corsOrigins.filter { it != "*" }.map { it.trimEnd('/').lowercase() }.toSet()

    override fun description() = "dystopo gate: bearer tokens on /build, /sync and /admin, CORS allowlist, request log"

    /** The value for `Access-Control-Allow-Origin` for a request from [origin], or null to send none. */
    fun allowOrigin(origin: String?): String? = when {
        origin == null -> if (anyOrigin) "*" else null
        anyOrigin -> "*"
        origin.trimEnd('/').lowercase() in origins -> origin
        else -> null
    }

    /** True when [header] carries any known token (or the server is open). */
    fun authorized(header: String?): Boolean = users.resolve(header) != null

    /**
     * T67: who the request is, for a protected path; null when it may go on (and [Users.ATTR] is set for
     * `/sync*`), else the refusal: status and message.
     */
    fun refuse(ex: HttpExchange, path: String): Pair<Int, String>? {
        val admin = isAdmin(path)
        if (!admin && !isProtected(path)) return null
        val who = users.resolve(ex.requestHeaders.getFirst("Authorization"))
            ?: return 401 to (if (admin) "this route needs Authorization: Bearer <admin token>" else "this route needs Authorization: Bearer <token>")
        if (admin) return when (who) {
            Users.Principal.Admin -> null
            Users.Principal.Anonymous -> 403 to "no adminToken is configured on this server"
            else -> 403 to "this route needs the admin token"
        }
        if (path == "/sync" || path.startsWith("/sync/")) when (who) {
            is Users.Principal.Member -> ex.setAttribute(Users.ATTR, who.id)
            Users.Principal.Admin -> {
                val user = queryParam(ex.requestURI.rawQuery, "user") ?: return 400 to "the admin token reaches /sync only as a user: add ?user=<id>"
                if (!users.has(user)) return 404 to "no user '$user'"
                ex.setAttribute(Users.ATTR, user)
            }
            else -> Unit // the T52 token or an open server: the default replica
        }
        return null
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
                refuse(ex, path)?.let { (status, message) ->
                    val body = (JsonWriter.write(mapOf("error" to message, "status" to status)) + "\n").toByteArray()
                    if (status == 401) ex.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"dystopo\"")
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(status, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                    outcome = if (status == 401) "unauthorized" else "refused"
                } != null -> Unit
                else -> chain.doFilter(ex)
            }
        } catch (e: Throwable) {
            outcome = "exception"
            log.error("request_failed", mapOf("method" to raw.requestMethod, "path" to path, "error" to e.toString()))
            if (ex.status == 0) runCatching { ex.sendResponseHeaders(500, -1); ex.close() }
        } finally {
            val status = ex.status
            if (logRequests || status >= 500 || outcome == "unauthorized" || outcome == "refused") {
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
        // T67: attributes live on this exchange. The JDK's own exchange attributes are the HttpContext's map,
        // shared by every request on the context, so a user set there could leak into a concurrent request.
        private val attributes = java.util.concurrent.ConcurrentHashMap<String, Any>()
        override fun getAttribute(name: String?): Any? = name?.let { attributes[it] }
        override fun setAttribute(name: String?, value: Any?) {
            if (name == null) return
            if (value == null) attributes.remove(name) else attributes[name] = value
        }
        override fun setStreams(i: InputStream?, o: OutputStream?) = inner.setStreams(i, o)
        override fun getPrincipal(): HttpPrincipal? = inner.principal
    }

    companion object {
        /** T67: user administration, admin token only. */
        fun isAdmin(path: String): Boolean = path == "/admin" || path.startsWith("/admin/")

        /** The decoded value of [name] in a raw query string, or null. */
        fun queryParam(rawQuery: String?, name: String): String? = rawQuery?.split('&')?.firstNotNullOfOrNull {
            val (k, v) = (it.split('=', limit = 2) + "").let { p -> p[0] to p[1] }
            if (java.net.URLDecoder.decode(k, Charsets.UTF_8) == name) java.net.URLDecoder.decode(v, Charsets.UTF_8).takeIf { d -> d.isNotEmpty() } else null
        }

        /** Routes behind a token: pack builds and the sync peer. */
        fun isProtected(path: String): Boolean =
            path == "/build" || path.startsWith("/build/") || path == "/sync" || path.startsWith("/sync/") ||
                path == "/catalog/refresh" // T58: rehashes every pack file, so not for anyone
    }
}
