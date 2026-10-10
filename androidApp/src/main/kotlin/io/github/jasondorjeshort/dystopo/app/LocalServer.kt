package io.github.jasondorjeshort.dystopo.app

import android.util.Log
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A parsed HTTP request: method, path (decoded), query parameters, body. */
class Request(val method: String, val path: String, val query: Map<String, String>, val body: ByteArray) {
    val bodyText: String get() = body.decodeToString()
}

class Response(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun json(value: Any?, status: Int = 200) = Response(status, "application/json", (JsonWriter.write(value) + "\n").toByteArray())
        fun text(text: String, status: Int = 200) = Response(status, "text/plain; charset=utf-8", text.toByteArray())
        fun notFound() = text("not found\n", 404)
    }
}

/**
 * A minimal HTTP/1.1 server on the loopback interface only (127.0.0.1): one request per connection
 * (`Connection: close`), GET/POST/HEAD, Content-Length bodies. Enough for MapLibre's tile requests
 * and for `curl` through `adb forward`; nothing here is reachable from the network.
 */
class LocalServer(private val port: Int, threads: Int, private val handler: (Request) -> Response) {
    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { r -> Thread(r, "dystopo-http").apply { isDaemon = true } }
    private var socket: ServerSocket? = null
    val requests = AtomicLong()

    fun start() {
        val s = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
        s.reuseAddress = true
        socket = s
        Thread({
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: SocketException) {
                    break
                }
                pool.execute { serve(client) }
            }
        }, "dystopo-http-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on 127.0.0.1:$port")
    }

    fun stop() {
        socket?.close()
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { c ->
            c.soTimeout = 30_000
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val request = try {
                parse(input) ?: return
            } catch (e: Exception) {
                write(out, Response.text("bad request: ${e.message}\n", 400), head = false)
                return
            }
            requests.incrementAndGet()
            val response = try {
                handler(request)
            } catch (e: Exception) {
                Log.w(TAG, "${request.method} ${request.path} failed", e)
                Response.json(mapOf("error" to (e.message ?: e.javaClass.simpleName), "type" to e.javaClass.name), 500)
            }
            try {
                write(out, response, head = request.method == "HEAD")
            } catch (_: SocketException) {
                // Client went away (MapLibre cancels requests for tiles that scrolled off screen).
            }
        }
    }

    private fun parse(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        require(parts.size >= 2) { "request line '$requestLine'" }
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..(1 shl 20)) { "body too large" }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = URLDecoder.decode(if (q < 0) target else target.substring(0, q), "UTF-8")
        val query = if (q < 0) emptyMap() else target.substring(q + 1).split("&").filter { it.isNotEmpty() }.associate {
            val e = it.indexOf('=')
            if (e < 0) URLDecoder.decode(it, "UTF-8") to "" else URLDecoder.decode(it.substring(0, e), "UTF-8") to URLDecoder.decode(it.substring(e + 1), "UTF-8")
        }
        return Request(parts[0].uppercase(), path, query, body)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            require(sb.length < 8192) { "header line too long" }
        }
    }

    private fun write(out: OutputStream, r: Response, head: Boolean) {
        val reason = when (r.status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            503 -> "Service Unavailable"; else -> "Error"
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
    }
}
