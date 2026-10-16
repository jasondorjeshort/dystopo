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
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
 * stay open: the link is the secret. T92: with users configured, `/route` and `/route/...` (route planning,
 * GPX and bundles for the browser) need a known token too (any user, the admin or the T52 token); a server
 * without users keeps them open as before.
 *
 * T111: a request whose body is still arriving [requestTimeoutMs] after the gate saw its headers
 * (`http.maxRequestSeconds`) is answered `408` with `Connection: close`, and the request is counted and logged
 * as a 408 (`outcome: requestTimeout`). Before, the JDK listener's own request timer closed such a connection
 * without a word, and `/metrics` counted the request under status `none` (never answered): the hourly soak
 * probe's short body to `/route/edit` was one such request an hour (finding
 * `docs/findings/2026-10-16-soak-route-requests-without-status.md`). The listener's timer still bounds the
 * headers; [Server.applyHttpLimits] sets it [TIMEOUT_GRACE_S] later, so the 408 goes out first.
 */
class Gate(
    val users: Users,
    corsOrigins: List<String>,
    private val log: JsonLog,
    private val logRequests: Boolean,
    /** T82: per-route request counts and latencies for `/metrics` (null: not recorded). */
    private val metrics: Metrics? = null,
    /** T82: the relay's own blob cap (`relay.maxBlobBytes`); its bodies may be that large and no larger. */
    private val relayMaxBody: Long = 16L shl 20,
    /** T111: a body still arriving this long after the headers gets a 408 (`http.maxRequestSeconds`); 0: never. */
    private val requestTimeoutMs: Long = 0,
) : Filter() {
    /** The T52 form: one token (or none) for everything. */
    constructor(token: String?, corsOrigins: List<String>, log: JsonLog, logRequests: Boolean) :
        this(Users(legacyToken = token?.trim()?.takeIf { it.isNotEmpty() }), corsOrigins, log, logRequests)

    /** T111: one daemon thread that answers 408 to the bodies still arriving at [requestTimeoutMs]. */
    private val timer: ScheduledThreadPoolExecutor? = if (requestTimeoutMs > 0) {
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "request-timeout").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    } else null

    /** T111: requests answered 408 because their body was still arriving at [requestTimeoutMs]. */
    val requestTimeouts = java.util.concurrent.atomic.AtomicLong()

    /** Stops the request timer (the server is stopping). */
    fun close() { timer?.shutdownNow() }

    private val anyOrigin = "*" in corsOrigins
    private val origins = corsOrigins.filter { it != "*" }.map { it.trimEnd('/').lowercase() }.toSet()

    override fun description() = "dystopo gate: bearer tokens on /build, /sync, /admin (and /route with users), CORS allowlist, request log"

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
        // T92: route planning costs CPU and reads every pack's graph; once the server has users it is theirs.
        val route = isRoute(path) && users.ids().isNotEmpty()
        if (!admin && !route && !isProtected(path)) return null
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
        val path = raw.requestURI.path
        val cap = maxBody(path, relayMaxBody)
        val declared = raw.requestHeaders.getFirst("Content-Length")?.trim()
        val ex = Exchange(raw, allowOrigin(origin), origin != null && origins.isNotEmpty() && !anyOrigin, cap, declared?.toLongOrNull())
        var outcome: String? = null
        // T111: a request with a body (declared or chunked) gets a 408 if the body is still arriving at the deadline.
        val chunked = raw.requestHeaders.getFirst("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true
        val deadline = timer?.takeIf { (declared?.toLongOrNull() ?: 0L) > 0 || chunked }?.let { t ->
            runCatching { t.schedule({ if (ex.timeOut(requestTimeoutMs)) requestTimeouts.incrementAndGet() }, requestTimeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
        }
        try {
            when {
                // T82: a body over the route's cap is refused before any route reads it; a chunked body (no
                // Content-Length) is cut off at the cap while the route reads it (BodyTooLarge: a 413 below, or
                // the route's own 500 turned into a 413 by the exchange). Memory stays bounded by the cap.
                declared != null && (declared.toLongOrNull() ?: -1) > cap -> {
                    tooLarge(ex, cap)
                    outcome = "tooLarge"
                }
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
            when {
                ex.timedOut -> {
                    // T111: answered 408 while the route waited for the body; the route then failed reading it or
                    // answering. The 408 is the answer; closing the exchange lets the listener close the connection.
                    outcome = "requestTimeout"
                    runCatching { raw.close() }
                }
                e is BodyTooLarge || ex.bodyCapHit -> {
                    // Over the cap while a route read it (or the route caught that and failed its own way): a 413.
                    outcome = "tooLarge"
                    if (ex.status == 0) runCatching { tooLarge(ex, cap) } else runCatching { raw.close() }
                }
                e is java.io.IOException -> {
                    // T82: the connection, not the server: the client went away mid-answer or the listener's
                    // request timeout (http.maxRequestSeconds) closed a slow upload. Not an error of ours.
                    outcome = "connectionLost"
                    log.warn("connection_lost", mapOf("method" to raw.requestMethod, "path" to logPath(path), "error" to e.toString(), "responseStarted" to (ex.status != 0)))
                    if (ex.status == 0) runCatching { ex.sendResponseHeaders(500, -1); ex.close() } else runCatching { raw.close() }
                }
                else -> {
                    outcome = "exception"
                    log.error("request_failed", mapOf("method" to raw.requestMethod, "path" to logPath(path), "error" to e.toString()))
                    if (ex.status == 0) runCatching { ex.sendResponseHeaders(500, -1); ex.close() }
                }
            }
        } finally {
            deadline?.cancel(false)
            if (ex.timedOut && outcome == null) {
                outcome = "requestTimeout" // the route returned without noticing; close the exchange it left open
                runCatching { raw.close() }
            }
            val status = ex.status
            val ms = (System.nanoTime() - started) / 1e6
            metrics?.request(path, status, ms, ex.length)
            if (logRequests || status >= 500 || outcome == "unauthorized" || outcome == "refused" || outcome == "tooLarge" || outcome == "requestTimeout") {
                val fields = linkedMapOf<String, Any?>(
                    "method" to raw.requestMethod, "path" to logPath(path), "status" to status,
                    "ms" to Math.round(ms * 10) / 10.0,
                    "bytes" to ex.length.takeIf { it >= 0 },
                    "remote" to (raw.requestHeaders.getFirst("X-Forwarded-For")?.substringBefore(',')?.trim() ?: raw.remoteAddress?.address?.hostAddress),
                )
                raw.responseHeaders.getFirst("X-Dystopo-Source")?.let { fields["source"] = it }
                outcome?.let { fields["outcome"] = it }
                when {
                    status >= 500 -> log.error("request", fields)
                    status == 401 || status == 403 || status == 408 || status == 413 || status == 429 -> log.warn("request", fields)
                    else -> log.info("request", fields)
                }
            }
        }
    }

    /**
     * The request's exchange with CORS fixed up at send time, and the status and length recorded for
     * the log. Everything else goes straight to [inner].
     */
    private class Exchange(
        private val inner: HttpExchange, private val allow: String?, private val vary: Boolean, private val cap: Long,
        /** The declared `Content-Length` (null: none, or chunked). */
        private val declared: Long?,
    ) : HttpExchange() {
        @Volatile var status = 0
        @Volatile var length = -1L
        @Volatile var bodyCapHit = false
        /** T111: the whole request body has been read (end of stream, or the declared length). */
        @Volatile var bodyDone = false
        /** T111: answered 408 by [timeOut]; nothing else may answer. */
        @Volatile var timedOut = false
        /** T111: one answer at a time: the route's and the request timer's (which runs on another thread). */
        private val answer = Any()
        private val body: InputStream by lazy { CappedInput(inner.requestBody, cap, declared, { bodyCapHit = true }, { bodyDone = true }) }

        /**
         * T111: answers 408 with `Connection: close` when no answer has started and the body is still arriving;
         * true when it did. Runs on the request timer's thread while the route's thread waits for the body: the
         * answer is written and flushed but the exchange is not closed here (closing reads the rest of the body,
         * which would wait for the client). The route's thread then wakes when the client goes or the listener
         * closes the connection, and the gate closes the exchange.
         */
        fun timeOut(afterMs: Long): Boolean = synchronized(answer) {
            if (status != 0 || bodyDone || timedOut) return false
            timedOut = true
            runCatching {
                val msg = "the request body did not arrive within ${afterMs / 1000} s"
                val bytes = (JsonWriter.write(mapOf("error" to msg, "status" to 408)) + "\n").toByteArray()
                inner.responseHeaders.set("Content-Type", "application/json")
                inner.responseHeaders.set("Connection", "close")
                send(408, bytes.size.toLong())
                inner.responseBody.write(bytes)
                inner.responseBody.flush()
            }
            true
        }

        override fun sendResponseHeaders(code: Int, responseLength: Long) = synchronized(answer) {
            if (timedOut) throw java.io.IOException("the request timed out and was answered 408")
            send(code, responseLength)
        }

        private fun send(code: Int, responseLength: Long) {
            // T82: a route that caught the cap's exception answers with its generic 500; the client is told 413.
            val rCode = if (bodyCapHit && code >= 500) 413 else code
            val h = inner.responseHeaders
            h.remove("Access-Control-Allow-Origin")
            if (allow != null) {
                h.set("Access-Control-Allow-Origin", allow)
                h.set("Access-Control-Expose-Headers", "X-Dystopo-Source, Content-Range, Content-Length, ETag")
            }
            if (vary) h.add("Vary", "Origin")
            inner.sendResponseHeaders(rCode, responseLength)
            // Recorded once sent: a route that fails after its headers went out and tries a 500 does not
            // turn the request into a 500 in the log and the metrics (the client got the first answer).
            status = rCode
            length = if (responseLength > 0) responseLength else if (responseLength == -1L) 0 else -1
        }

        override fun getRequestHeaders(): Headers = inner.requestHeaders
        override fun getResponseHeaders(): Headers = inner.responseHeaders
        override fun getRequestURI(): URI = inner.requestURI
        override fun getRequestMethod(): String = inner.requestMethod
        override fun getHttpContext(): HttpContext = inner.httpContext
        override fun close() = inner.close()
        override fun getRequestBody(): InputStream = body
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

    private fun tooLarge(ex: HttpExchange, cap: Long) {
        val body = (JsonWriter.write(mapOf("error" to "request body larger than $cap bytes", "status" to 413)) + "\n").toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.responseHeaders.add("Connection", "close")
        ex.sendResponseHeaders(413, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    /** T82: thrown by a capped request body once more than the route's cap has been read. */
    class BodyTooLarge(cap: Long) : java.io.IOException("request body larger than $cap bytes")

    /**
     * Reads at most [cap] bytes of [inner]; one more is a [BodyTooLarge]. T111: [onDone] once the end of the stream
     * or the [declared] length has been read.
     */
    private class CappedInput(
        private val inner: InputStream, private val cap: Long, private val declared: Long?,
        private val onHit: () -> Unit, private val onDone: () -> Unit,
    ) : InputStream() {
        private var read = 0L
        private fun count(n: Int): Int {
            if (n > 0) {
                read += n
                if (read > cap) { onHit(); throw BodyTooLarge(cap) }
                if (declared != null && read >= declared) onDone()
            } else if (n < 0) onDone()
            return n
        }
        override fun read(): Int = inner.read().also { count(if (it >= 0) 1 else -1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (len == 0) 0 else count(inner.read(b, off, minOf(len.toLong(), cap + 1 - read).toInt()))
        override fun available(): Int = inner.available()
        override fun close() = inner.close()
    }

    companion object {
        /**
         * T82: the path as logged. A share link's token is the secret that opens it (T67), so `/s/<token>`
         * (and `.gpx`, `.json`) is logged as `/s/…` and `/sync/share/<token>` (a revocation) as `/sync/share/…`.
         */
        fun logPath(path: String): String = when {
            path.startsWith("/s/") && path.length > 3 -> "/s/…"
            path.startsWith("/sync/share/") && path.length > "/sync/share/".length -> "/sync/share/…"
            else -> path
        }

        /**
         * T111: the listener's own request timer (`sun.net.httpserver.maxReqTime`) is set this much later than the
         * gate's 408, so a body still arriving gets the answer before the listener closes the connection.
         */
        const val TIMEOUT_GRACE_S = 5

        /** T82: the sync peer takes bundles up to 256 MiB (SyncRoutes), everything else far less. */
        const val SYNC_MAX_BODY = 256L shl 20
        const val MAX_BODY = 16L shl 20

        /**
         * The largest request body [path] accepts; the routes' own caps are lower where they have one. The
         * relay's is its blob cap (`relay.maxBlobBytes`, which the config may raise).
         */
        fun maxBody(path: String, relayMaxBody: Long = 16L shl 20): Long = when {
            isRelay(path) -> relayMaxBody
            path == "/sync" || path.startsWith("/sync/") -> SYNC_MAX_BODY
            else -> MAX_BODY
        }

        /** T67: user administration, admin token only. */
        fun isAdmin(path: String): Boolean = path == "/admin" || path.startsWith("/admin/")

        /** The decoded value of [name] in a raw query string, or null. */
        fun queryParam(rawQuery: String?, name: String): String? = rawQuery?.split('&')?.firstNotNullOfOrNull {
            val (k, v) = (it.split('=', limit = 2) + "").let { p -> p[0] to p[1] }
            if (java.net.URLDecoder.decode(k, Charsets.UTF_8) == name) java.net.URLDecoder.decode(v, Charsets.UTF_8).takeIf { d -> d.isNotEmpty() } else null
        }

        /** T92: route planning in the browser (T68), behind a token when users are configured. */
        fun isRoute(path: String): Boolean = path == "/route" || path.startsWith("/route/")

        /** T72: the relay's own paths, exactly `/sync/relay` or under `/sync/relay/` (not `/sync/relayanything`). */
        fun isRelay(path: String): Boolean = path == "/sync/relay" || path.startsWith("/sync/relay/")

        /** Routes behind a token: pack builds and the sync peer (not the T72 relay). */
        fun isProtected(path: String): Boolean = !isRelay(path) && ( // T72: the relay holds only ciphertext and is open
            path == "/build" || path.startsWith("/build/") || path == "/area" || path == "/sync" || path.startsWith("/sync/") || // T75: /area starts builds
                path == "/catalog/refresh") // T58: rehashes every pack file, so not for anyone
    }
}
