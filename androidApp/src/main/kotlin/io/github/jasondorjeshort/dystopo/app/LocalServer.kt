package io.github.jasondorjeshort.dystopo.app

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A parsed HTTP request: method, path (decoded), query parameters, body; [headers] with lower-case
 * names and [loopback] (the peer address is a loopback one) since T49.
 */
class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
    val loopback: Boolean = true,
) {
    val bodyText: String get() = body.decodeToString()

    fun copy(query: Map<String, String> = this.query) = Request(method, path, query, body, headers, loopback)
}

class Response(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun json(value: Any?, status: Int = 200) = Response(status, "application/json", (JsonWriter.write(value) + "\n").toByteArray())
        fun text(text: String, status: Int = 200) = Response(status, "text/plain; charset=utf-8", text.toByteArray())
        fun notFound() = text("not found\n", 404)
    }
}

/** A request the server refuses before it reaches the handler: answered with [status], then closed. */
class HttpError(val status: Int, message: String) : Exception(message)

/**
 * A minimal HTTP/1.1 server on the loopback interface only (127.0.0.1): one request per connection
 * (`Connection: close`), GET/POST/HEAD, Content-Length bodies. Enough for MapLibre's tile requests
 * and for `curl` through `adb forward`; nothing here is reachable from the network.
 *
 * Three stages, so debug requests never queue behind tile work: [readers] threads read and parse
 * request heads; a parsed request then runs on the tile pool (`threads` workers) when [isMapRequest]
 * says it is one of MapLibre's (tiles, glyphs, sprites, the style), otherwise on a separate debug pool
 * ([debugThreads] workers: `/health`, `/screenshot`, ...). Each stage has its own bounded queue.
 *
 * Guards against misbehaving clients (`tools/android-probe.py` exercises each one):
 * - at most [readers] heads are read at once and [maxQueued] connections wait; beyond that a new
 *   connection gets 503 from the accept thread without being read; a full tile or debug queue gets
 *   503 from the reader;
 * - the request head (request line and headers) must arrive within [headTimeoutMs] in total and no
 *   single read may stall longer than [readTimeoutMs]: a slow-drip client gets 408 and frees its thread;
 * - lines are capped at [MAX_LINE] bytes (414 for the request line, 431 for a header), at most
 *   [MAX_HEADERS] headers (431), bodies at [MAX_BODY] bytes (413); anything that is not HTTP gets 400.
 * Refusals are counted per reason in [stats] (`/health` shows them).
 */
class LocalServer(
    private val port: Int,
    threads: Int,
    private val maxQueued: Int = 64,
    private val headTimeoutMs: Long = 5_000,
    private val readTimeoutMs: Int = 5_000,
    private val readers: Int = 4,
    private val debugThreads: Int = 3,
    private val isMapRequest: (Request) -> Boolean = { true },
    private val handler: (Request) -> Response,
    private val bindAddress: String = "127.0.0.1", // T54: the share listener binds the LAN (0.0.0.0)
) {
    private val readQueue = ArrayBlockingQueue<Runnable>(maxQueued)
    private val readPool = ThreadPoolExecutor(readers, readers, 30, TimeUnit.SECONDS, readQueue) { r ->
        Thread(r, "dystopo-http-read").apply { isDaemon = true }
    }
    private val queue = ArrayBlockingQueue<Runnable>(maxQueued)
    private val pool = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, queue) { r ->
        Thread(r, "dystopo-http").apply { isDaemon = true }
    }
    private val debugQueue = ArrayBlockingQueue<Runnable>(16)
    private val debugPool = ThreadPoolExecutor(debugThreads, debugThreads, 30, TimeUnit.SECONDS, debugQueue) { r ->
        Thread(r, "dystopo-http-debug").apply { isDaemon = true }
    }
    private var socket: ServerSocket? = null
    val requests = AtomicLong()

    /** The bound port (T54: port 0 picks a free one). */
    val localPort: Int get() = socket?.localPort ?: port

    /** Connections refused per reason: `busy`, `timeout`, `tooLarge`, `badRequest`. */
    private val rejected = ConcurrentHashMap<String, AtomicLong>()

    /** Requests being handled right now, by first path segment (`dem`, `osm`, `contours`, `glyphs`, ...). */
    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()

    /** elapsedRealtime when the last request of each kind finished. */
    private val lastDone = ConcurrentHashMap<String, Long>()

    fun inFlight(kind: String): Int = inFlight[kind]?.get() ?: 0

    fun lastDone(kind: String): Long = lastDone[kind] ?: 0L

    /** Connections accepted but not yet read, plus map requests waiting for a tile worker. */
    val queued: Int get() = readQueue.size + queue.size

    fun stats(): Map<String, Any?> = mapOf(
        "requests" to requests.get(),
        "active" to pool.activeCount,
        "queued" to queue.size,
        "maxQueued" to maxQueued,
        "reading" to readPool.activeCount,
        "readQueued" to readQueue.size,
        "debugActive" to debugPool.activeCount,
        "debugQueued" to debugQueue.size,
        "inFlight" to inFlight.filterValues { it.get() > 0 }.mapValues { it.value.get() },
        "rejected" to rejected.mapValues { it.value.get() },
    )

    private fun reject(reason: String) {
        rejected.getOrPut(reason) { AtomicLong() }.incrementAndGet()
    }

    fun start() {
        val s = ServerSocket(port, 64, InetAddress.getByName(bindAddress))
        s.reuseAddress = true
        socket = s
        Thread({
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: SocketException) {
                    break
                } catch (e: IOException) {
                    Log.w(TAG, "accept failed", e)
                    continue
                }
                try {
                    readPool.execute { serve(client) }
                } catch (e: RejectedExecutionException) {
                    // Queue full: answer from the accept thread without reading the request.
                    reject("busy")
                    runCatching {
                        client.use { c ->
                            c.soTimeout = 1000
                            write(c.getOutputStream(), Response.text("busy\n", 503), head = false)
                        }
                    }
                }
            }
        }, "dystopo-http-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on $bindAddress:${s.localPort}")
    }

    fun stop() {
        socket?.close()
        readPool.shutdownNow()
        pool.shutdownNow()
        debugPool.shutdownNow()
    }

    /** Reader stage: parses the head, then hands the connection to the tile pool or the debug pool. */
    private fun serve(client: Socket) {
        var handedOff = false
        try {
            val request = read(client) ?: return
            val target = if (isMapRequest(request)) pool else debugPool
            try {
                target.execute { client.use { respond(it, request) } }
                handedOff = true
            } catch (e: RejectedExecutionException) {
                reject("busy")
                runCatching { write(client.getOutputStream(), Response.text("busy\n", 503), head = false) }
            }
        } finally {
            if (!handedOff) runCatching { client.close() }
        }
    }

    /** Reads and parses one request; a bad or slow one is answered with its error and gives null. */
    private fun read(c: Socket): Request? {
        c.soTimeout = readTimeoutMs
        val input = BufferedInputStream(c.getInputStream())
        return try {
            parse(input, SystemClock.elapsedRealtime() + headTimeoutMs, c.inetAddress?.isLoopbackAddress == true)
        } catch (e: Exception) {
            val error = when (e) {
                is HttpError -> e
                is SocketTimeoutException -> HttpError(408, "request timeout")
                else -> HttpError(400, "bad request: ${e.message}")
            }
            reject(
                when (error.status) {
                    408 -> "timeout"
                    413, 414, 431 -> "tooLarge"
                    else -> "badRequest"
                },
            )
            runCatching { write(c.getOutputStream(), Response.text("${error.message}\n", error.status), head = false) }
            null
        }
    }

    /** Worker stage (tile or debug pool): runs the handler and writes the response. */
    private fun respond(c: Socket, request: Request) {
        requests.incrementAndGet()
        val kind = request.path.trimStart('/').substringBefore('/')
        val counter = inFlight.getOrPut(kind) { AtomicInteger() }
        counter.incrementAndGet()
        val response = try {
            handler(request)
        } catch (e: Exception) {
            Log.w(TAG, "${request.method} ${request.path} failed", e)
            Response.json(mapOf("error" to (e.message ?: e.javaClass.simpleName), "type" to e.javaClass.name), 500)
        } finally {
            counter.decrementAndGet()
            lastDone[kind] = SystemClock.elapsedRealtime()
        }
        try {
            write(c.getOutputStream(), response, head = request.method == "HEAD")
        } catch (_: IOException) {
            // Client went away (MapLibre cancels requests for tiles that scrolled off screen).
        }
    }

    /** Parses one request; its head must be complete before [deadline] (elapsedRealtime ms). */
    private fun parse(input: InputStream, deadline: Long, loopback: Boolean): Request? {
        val requestLine = readLine(input, deadline, 414) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/") || parts[0].isEmpty() || !parts[0].all { it in 'A'..'Z' } ||
            !parts[1].startsWith("/")
        ) {
            throw HttpError(400, "not an HTTP request line")
        }
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input, deadline, 431) ?: break
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) throw HttpError(431, "more than $MAX_HEADERS headers")
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.let { it.toLongOrNull() ?: throw HttpError(400, "bad content-length") } ?: 0L
        if (length < 0) throw HttpError(400, "negative content-length")
        if (length > MAX_BODY) throw HttpError(413, "body larger than $MAX_BODY bytes")
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            if (SystemClock.elapsedRealtime() > deadline + readTimeoutMs) throw HttpError(408, "body too slow")
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw HttpError(400, "body shorter than content-length")
            read += n
        }
        val target = parts[1]
        val q = target.indexOf('?')
        try {
            val path = URLDecoder.decode(if (q < 0) target else target.substring(0, q), "UTF-8")
            val query = if (q < 0) emptyMap() else target.substring(q + 1).split("&").filter { it.isNotEmpty() }.associate {
                val e = it.indexOf('=')
                if (e < 0) URLDecoder.decode(it, "UTF-8") to "" else URLDecoder.decode(it.substring(0, e), "UTF-8") to URLDecoder.decode(it.substring(e + 1), "UTF-8")
            }
            return Request(parts[0], path, query, body, headers, loopback)
        } catch (e: IllegalArgumentException) {
            throw HttpError(400, "bad URL encoding")
        }
    }

    /** One LF- or CRLF-terminated line, or null at end of stream before any byte. */
    private fun readLine(input: InputStream, deadline: Long, tooLongStatus: Int): String? {
        val sb = StringBuilder()
        while (true) {
            if (SystemClock.elapsedRealtime() > deadline) throw HttpError(408, "request head too slow")
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw HttpError(tooLongStatus, "line longer than $MAX_LINE bytes")
        }
    }

    private fun write(out: OutputStream, r: Response, head: Boolean) {
        val reason = when (r.status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            408 -> "Request Timeout"; 413 -> "Payload Too Large"; 414 -> "URI Too Long"
            431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
            else -> "Error"
        }
        val header = "HTTP/1.1 ${r.status} $reason\r\n" +
            "Content-Type: ${r.contentType}\r\n" +
            "Content-Length: ${r.body.size}\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        if (!head) out.write(r.body)
        out.flush()
    }

    companion object {
        const val TAG = "DystopoServer"
        const val MAX_LINE = 8192
        const val MAX_HEADERS = 64
        const val MAX_BODY = 1 shl 20
    }
}
