package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.LocalServer
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * T90: the share listener's HTTP server, on the LAN (`0.0.0.0`), separate from the loopback tile server
 * ([LocalServer], which the share listener used until T90). One request per connection, as before (`Connection: close`),
 * with the same limits on the request (head within [headTimeoutMs], lines of [LocalServer.MAX_LINE], at most
 * [LocalServer.MAX_HEADERS] headers, a body of at most [LocalServer.MAX_BODY]). What differs is the end of a reply:
 *
 * - **The client closes first.** After the body the listener does not close the socket; it waits for the client to
 *   close its end (the client reads exactly Content-Length bytes, then closes), at most [lingerMs] plus the time the
 *   body needs at [minBytesPerSec], then closes. A reply is never cut by the sender's close: over a slow path with a
 *   relay in the middle (an emulator reaching the other through the host and an `adb forward`) the sender's close
 *   could reach the relay while most of a 4 MiB reply was still queued there, and the client got a reply shorter
 *   than its Content-Length (T78; the finding `docs/findings/2026-10-10-emulator-share-direct-connect.md`).
 * - **The wait is bounded** (T96). At most [maxLingerMs] in all (T90's formula gave a 4 MiB reply 11.6 h at
 *   100 B/s), and no thread per socket: one watcher ([LINGER_POLL_MS]) reads each waiting socket with a 1 ms timeout
 *   to see its close. T90 held a thread and a socket per idle client (300 idle clients, 300 threads, 30 s). When
 *   [maxLingering] sockets wait and one more arrives, T106 closes the oldest **idle** one: one that has waited longer
 *   than its reply needs to drain on any path seen ([idleMs] plus the body at [idleBytesPerSec]; T90 measured whole
 *   4 MiB replies within a second of the last write). T96 closed the oldest whatever it was, which could cut a reply
 *   still queued in a relay (the slow path T90 fixed) when more than 32 replies ended within a few seconds. With none
 *   idle the socket waits too, up to [hardLingering] (a socket and a few kilobytes each, no thread; the watcher's
 *   pass then takes up to that many milliseconds); only there is the oldest closed whatever it is.
 * - **A write deadline.** The body goes out in [SLICE] slices; a client that takes no slice for [stallMs] (it stopped
 *   reading, or the path died) has its socket closed by a watchdog, so a worker is never held by a dead peer.
 *   Progress, not the total time, is what counts: a slow path that keeps moving keeps its reply.
 *
 * Counters for `GET /share/status` are in [json]. Pure JVM (no Android classes), so `ShareListenerTest` runs it.
 */
class ShareListener(
    private val port: Int,
    private val handler: (Request) -> Response,
    private val bindAddress: String = "0.0.0.0",
    threads: Int = 4,
    maxQueued: Int = 32,
    private val headTimeoutMs: Int = 5_000,
    private val bodyTimeoutMs: Int = 30_000,
    private val stallMs: Long = 60_000,
    private val lingerMs: Long = 30_000,
    private val minBytesPerSec: Long = 100,
    /** T96: the longest a socket waits for the client's close, whatever the body. */
    private val maxLingerMs: Long = MAX_LINGER_MS,
    /** T96: sockets waiting for the client's close at once before one more closes another (T106: an idle one). */
    private val maxLingering: Int = MAX_LINGERING,
    /** T106: the most sockets waiting at once in any case; one more closes the oldest, idle or not. */
    private val hardLingering: Int = HARD_LINGERING,
    /** T106: a waiting socket is idle (closed first beyond [maxLingering]) after this long plus its body at [idleBytesPerSec]. */
    private val idleMs: Long = IDLE_MS,
    private val idleBytesPerSec: Long = IDLE_BYTES_PER_SEC,
    private val log: (String) -> Unit = {},
) {
    private val queue = ArrayBlockingQueue<Runnable>(maxQueued)
    private val pool = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, queue) { r -> Thread(r, "dystopo-share").apply { isDaemon = true } }

    /** T96: the one thread that watches every waiting socket for its client's close ([pollLingers]). */
    private val lingerWatch = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dystopo-share-linger").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dystopo-share-watchdog").apply { isDaemon = true } }

    /** Replies being written: socket to the time of its last progress (ms). */
    private val writing = ConcurrentHashMap<Socket, Long>()

    private class Lingering(val socket: Socket, val input: InputStream, val start: Long, val deadline: Long, val idleAt: Long) {
        var extra = 0
    }

    /** Replies written, waiting for the client's close, oldest first, at most [hardLingering] (closed by [stop] too). */
    private val lingering = ArrayDeque<Lingering>()

    @Volatile private var socket: ServerSocket? = null

    val localPort: Int get() = socket?.localPort ?: -1

    val requests = AtomicLong()
    val bytesOut = AtomicLong()
    val busy = AtomicLong()
    val badRequests = AtomicLong()
    /** Replies whose socket the watchdog closed (no progress for [stallMs]). */
    val stalled = AtomicLong()
    /** Replies after which the client closed first (the normal end); [lingerTimeouts] did not within the time allowed. */
    val clientClosed = AtomicLong()
    val lingerTimeouts = AtomicLong()
    /** T96: waiting sockets closed early because [maxLingering] others were waiting (T106: idle ones only). */
    val lingerEvicted = AtomicLong()
    /** T106: waiting sockets closed early at [hardLingering], idle or not (a reply could be cut there). */
    val lingerEvictedHard = AtomicLong()
    /** T96: the most sockets that waited at once. */
    val lingerPeak = AtomicLong()
    /** Replies the client stopped reading before the end (it closed or reset the connection while the body was going). */
    val writeFailures = AtomicLong()
    val lingerMsTotal = AtomicLong()

    fun start() {
        val s = ServerSocket(port, 64, InetAddress.getByName(bindAddress))
        s.reuseAddress = true
        socket = s
        watchdog.scheduleWithFixedDelay(::sweep, WATCH_MS, WATCH_MS, TimeUnit.MILLISECONDS)
        lingerWatch.scheduleWithFixedDelay(::pollLingers, LINGER_POLL_MS, LINGER_POLL_MS, TimeUnit.MILLISECONDS)
        Thread({
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: SocketException) {
                    break
                } catch (e: IOException) {
                    log("accept failed: ${e.message}")
                    continue
                }
                try {
                    pool.execute { serve(client) }
                } catch (e: RejectedExecutionException) {
                    busy.incrementAndGet()
                    runCatching {
                        client.soTimeout = 1000
                        write(client, client.getOutputStream(), Response.text("busy\n", 503))
                    }
                    runCatching { client.close() }
                }
            }
        }, "dystopo-share-accept").apply { isDaemon = true }.start()
    }

    /** Closes the port and every connection in progress. */
    fun stop() {
        runCatching { socket?.close() }
        pool.shutdownNow()
        lingerWatch.shutdownNow()
        watchdog.shutdownNow()
        writing.keys.forEach { runCatching { it.close() } }
        val waiting = synchronized(lingering) { lingering.toList().also { lingering.clear() } }
        waiting.forEach { runCatching { it.socket.close() } }
    }

    private fun serve(c: Socket) {
        var handedOff = false
        try {
            c.soTimeout = headTimeoutMs
            val input = BufferedInputStream(c.getInputStream())
            val request = try {
                parse(input, System.currentTimeMillis() + headTimeoutMs, c.inetAddress?.isLoopbackAddress == true, c.inetAddress?.hostAddress)
            } catch (e: Exception) {
                badRequests.incrementAndGet()
                val (status, text) = when (e) {
                    is ShareHttpError -> e.status to (e.message ?: "bad request")
                    is SocketTimeoutException -> 408 to "request timeout"
                    else -> 400 to "bad request: ${e.message}"
                }
                runCatching { write(c, c.getOutputStream(), Response.text("$text\n", status)) }
                return
            } ?: return
            requests.incrementAndGet()
            val response = try {
                handler(request)
            } catch (e: Exception) {
                log("${request.method} ${request.path} failed: ${e.message}")
                Response.text("error: ${e.message ?: e.javaClass.simpleName}\n", 500)
            }
            try {
                write(c, c.getOutputStream(), response)
            } catch (e: IOException) {
                writeFailures.incrementAndGet()
                return
            }
            handedOff = linger(c, input, response.body.size.toLong())
        } finally {
            if (!handedOff) runCatching { c.close() }
        }
    }

    /**
     * Hands [c] to the watcher, which closes it once the client has closed its end ([pollLingers]); true when it was
     * handed off. The request was read completely, so anything the client still sends is discarded. T96: at most
     * [lingerWait] ([maxLingerMs] in all). T106: when [maxLingering] sockets are waiting already the oldest idle one is
     * closed now ([idleAfter]); with none idle this one waits too, and only at [hardLingering] does the oldest go.
     */
    private fun linger(c: Socket, input: InputStream, bodyBytes: Long): Boolean {
        if (lingerWatch.isShutdown) return false
        try {
            c.soTimeout = 1 // the watcher's reads only look: data, the client's close, or nothing yet
        } catch (_: IOException) {
            return false
        }
        val now = System.currentTimeMillis()
        val l = Lingering(c, input, now, now + lingerWait(bodyBytes, lingerMs, minBytesPerSec, maxLingerMs), now + idleAfter(bodyBytes, idleMs, idleBytesPerSec))
        var hard = false
        val evicted = synchronized(lingering) {
            val out = when {
                lingering.size < maxLingering -> null
                else -> lingering.firstOrNull { now >= it.idleAt }?.also { lingering.remove(it) }
                    ?: if (lingering.size >= hardLingering) lingering.removeFirst().also { hard = true } else null
            }
            lingering.addLast(l)
            lingerPeak.accumulateAndGet(lingering.size.toLong()) { a, b -> maxOf(a, b) }
            out
        }
        evicted?.let { end(it, if (hard) lingerEvictedHard else lingerEvicted) }
        return true
    }

    /**
     * T96: one pass of the watcher over the waiting sockets: a client that closed (or reset) is done; a socket past its
     * deadline, or whose client sent more than [MAX_AFTER] after its request, is closed. Each read waits at most 1 ms.
     */
    private fun pollLingers() {
        val list = synchronized(lingering) { lingering.toList() }
        if (list.isEmpty()) return
        val buf = ByteArray(8192)
        for (l in list) {
            val counter = try {
                val n = l.input.read(buf)
                when {
                    n < 0 -> clientClosed
                    else -> {
                        l.extra += n
                        if (l.extra > MAX_AFTER || System.currentTimeMillis() > l.deadline) lingerTimeouts else null
                    }
                }
            } catch (_: SocketTimeoutException) {
                if (System.currentTimeMillis() > l.deadline) lingerTimeouts else null
            } catch (_: IOException) {
                clientClosed // a reset after the body: the client is gone, which is all we wait for (or [stop] closed it)
            }
            if (counter != null && synchronized(lingering) { lingering.remove(l) }) end(l, counter)
        }
    }

    /** Closes a waiting socket that left the queue, counting why in [counter]. */
    private fun end(l: Lingering, counter: AtomicLong) {
        counter.incrementAndGet()
        lingerMsTotal.addAndGet(System.currentTimeMillis() - l.start)
        runCatching { l.socket.close() }
    }

    /** Closes the sockets of replies that made no progress for [stallMs]. */
    private fun sweep() {
        val now = System.currentTimeMillis()
        for ((s, last) in writing) {
            if (now - last > stallMs && writing.remove(s) != null) {
                stalled.incrementAndGet()
                log("reply to ${s.inetAddress?.hostAddress} made no progress for ${now - last} ms: closed")
                runCatching { s.close() }
            }
        }
    }

    private fun write(c: Socket, out: OutputStream, r: Response) {
        val reason = when (r.status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            408 -> "Request Timeout"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 426 -> "Upgrade Required"
            500 -> "Internal Server Error"; 503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = "HTTP/1.1 ${r.status} $reason\r\nContent-Type: ${r.contentType}\r\nContent-Length: ${r.body.size}\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        writing[c] = System.currentTimeMillis()
        try {
            out.write(head.toByteArray())
            var off = 0
            while (off < r.body.size) {
                val n = minOf(SLICE, r.body.size - off)
                out.write(r.body, off, n)
                off += n
                writing[c] = System.currentTimeMillis()
            }
            out.flush()
            bytesOut.addAndGet(r.body.size.toLong())
        } finally {
            writing.remove(c)
        }
    }

    private fun parse(input: InputStream, deadline: Long, loopback: Boolean, peer: String?): Request? {
        val requestLine = readLine(input, deadline, 414) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/") || parts[0].isEmpty() || !parts[0].all { it in 'A'..'Z' } || !parts[1].startsWith("/")) {
            throw ShareHttpError(400, "not an HTTP request line")
        }
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input, deadline, 431) ?: break
            if (line.isEmpty()) break
            if (++count > LocalServer.MAX_HEADERS) throw ShareHttpError(431, "more than ${LocalServer.MAX_HEADERS} headers")
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.let { it.toLongOrNull() ?: throw ShareHttpError(400, "bad content-length") } ?: 0L
        if (length < 0) throw ShareHttpError(400, "negative content-length")
        if (length > LocalServer.MAX_BODY) throw ShareHttpError(413, "body larger than ${LocalServer.MAX_BODY} bytes")
        val body = ByteArray(length.toInt())
        var read = 0
        val bodyDeadline = System.currentTimeMillis() + bodyTimeoutMs
        while (read < body.size) {
            if (System.currentTimeMillis() > bodyDeadline) throw ShareHttpError(408, "body too slow")
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw ShareHttpError(400, "body shorter than content-length")
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
            return Request(parts[0], path, query, body, headers, loopback, peer)
        } catch (e: IllegalArgumentException) {
            throw ShareHttpError(400, "bad URL encoding")
        }
    }

    private fun readLine(input: InputStream, deadline: Long, tooLongStatus: Int): String? {
        val sb = StringBuilder()
        while (true) {
            if (System.currentTimeMillis() > deadline) throw ShareHttpError(408, "request head too slow")
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > LocalServer.MAX_LINE) throw ShareHttpError(tooLongStatus, "line longer than ${LocalServer.MAX_LINE} bytes")
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "requests" to requests.get(), "bytesOut" to bytesOut.get(), "busy" to busy.get(), "badRequests" to badRequests.get(),
        "writeFailures" to writeFailures.get(), "stalled" to stalled.get(), "clientClosed" to clientClosed.get(),
        "lingerTimeouts" to lingerTimeouts.get(), "lingerMsTotal" to lingerMsTotal.get(), "writing" to writing.size,
        "lingering" to synchronized(lingering) { lingering.size }, "lingerEvicted" to lingerEvicted.get(), "lingerPeak" to lingerPeak.get(), // T96
        "maxLingering" to maxLingering, "maxLingerMs" to maxLingerMs,
        "lingerEvictedHard" to lingerEvictedHard.get(), "hardLingering" to hardLingering, "idleMs" to idleMs, // T106
    )

    companion object {
        /** The body is written in slices of this size; each one written is progress for the write deadline. */
        const val SLICE = 8 shl 10

        /** How often the watchdog looks for stalled replies. */
        const val WATCH_MS = 1_000L

        /** Bytes a client may send after its request before the listener stops waiting for its close. */
        const val MAX_AFTER = 64 shl 10

        /** T96: the longest wait for a client's close (T90 measured whole 4 MiB replies within a second of the last write). */
        const val MAX_LINGER_MS = 120_000L

        /** T96: sockets waiting at once before an idle one is closed (T106): a pack download keeps about four in flight per phone. */
        const val MAX_LINGERING = 32

        /** T106: the most sockets waiting at once, idle or not (each is a socket and a few kilobytes, no thread). */
        const val HARD_LINGERING = 256

        /** T106: a waiting socket counts as idle after this long plus its body at [IDLE_BYTES_PER_SEC] ([idleAfter]). */
        const val IDLE_MS = 5_000L
        const val IDLE_BYTES_PER_SEC = 256L shl 10

        /** T106: how long after its last write a reply of [bodyBytes] may still be draining through a relay. */
        fun idleAfter(bodyBytes: Long, idleMs: Long, bytesPerSec: Long): Long = idleMs + bodyBytes * 1000 / bytesPerSec.coerceAtLeast(1)

        /** T96: how often the watcher looks at the waiting sockets. */
        const val LINGER_POLL_MS = 20L

        /** T96: how long a reply of [bodyBytes] waits for the client's close: T90's rule, capped at [maxMs]. */
        fun lingerWait(bodyBytes: Long, lingerMs: Long, minBytesPerSec: Long, maxMs: Long): Long =
            (lingerMs + bodyBytes * 1000 / minBytesPerSec.coerceAtLeast(1)).coerceAtMost(maxMs)
    }
}

class ShareHttpError(val status: Int, message: String) : Exception(message)
