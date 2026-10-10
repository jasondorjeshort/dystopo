package io.github.jasondorjeshort.dystopo.devtool

import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A plain static file host for `devtool serve` (`/out/...`), so the app's pack download can be tested
 * against something like a real CDN: GET/HEAD, single byte ranges (`Range: bytes=a-b`, `a-`, `-n`)
 * answered with 206 and `Content-Range`, 416 past the end, `ETag`/`Last-Modified`, `If-Range`.
 * `?kbps=N` throttles the body to N kilobytes per second, so a download can be interrupted mid-way.
 */
object ServeStatic {
    fun serve(ex: HttpExchange, root: File, relative: String) {
        try {
            val base = root.canonicalFile
            val file = File(base, relative).canonicalFile
            if (!file.path.startsWith(base.path + File.separator) || !file.isFile) {
                send(ex, 404, "not found\n".toByteArray())
                return
            }
            if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") {
                send(ex, 405, "method not allowed\n".toByteArray())
                return
            }
            val size = file.length()
            val etag = "\"${size.toString(16)}-${file.lastModified().toString(16)}\""
            val lastModified = httpDate(file.lastModified())
            val h = ex.responseHeaders
            h.add("Accept-Ranges", "bytes")
            h.add("ETag", etag)
            h.add("Last-Modified", lastModified)
            h.add("Content-Type", if (file.name.endsWith(".json")) "application/json" else "application/octet-stream")
            h.add("Access-Control-Allow-Origin", "*")

            val rangeHeader = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            val rangeApplies = rangeHeader != null && (ifRange == null || ifRange == etag || ifRange == lastModified)
            var first = 0L
            var last = size - 1
            var status = 200
            if (rangeApplies) {
                val r = parseRange(rangeHeader!!, size)
                if (r == null) {
                    h.add("Content-Range", "bytes */$size")
                    send(ex, 416, ByteArray(0))
                    return
                }
                first = r.first
                last = r.last
                status = 206
                h.add("Content-Range", "bytes $first-$last/$size")
            }
            val length = last - first + 1
            if (ex.requestMethod == "HEAD") {
                h.add("Content-Length", length.toString())
                ex.sendResponseHeaders(status, -1)
                ex.close()
                return
            }
            ex.sendResponseHeaders(status, if (length == 0L) -1 else length)
            val kbps = queryParam(ex, "kbps")?.toLongOrNull()?.takeIf { it > 0 }
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(first)
                ex.responseBody.use { out ->
                    val buf = ByteArray(if (kbps != null) 4096 else 64 * 1024)
                    var left = length
                    val started = System.nanoTime()
                    var sent = 0L
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                        sent += n
                        if (kbps != null) {
                            val due = sent * 1_000_000_000L / (kbps * 1024)
                            val ahead = (due - (System.nanoTime() - started)) / 1_000_000
                            if (ahead > 0) Thread.sleep(ahead)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // The client went away mid-body (a download killed on purpose); nothing to answer.
            runCatching { ex.close() }
        }
    }

    /** One `bytes=` range against a file of [size] bytes; null when unsatisfiable or unparsable. */
    fun parseRange(header: String, size: Long): LongRange? {
        val m = Regex("^\\s*bytes\\s*=\\s*(\\d*)-(\\d*)\\s*$").find(header) ?: return null
        val (a, b) = m.destructured
        return when {
            a.isEmpty() && b.isEmpty() -> null
            a.isEmpty() -> { // suffix: the last b bytes
                val n = b.toLongOrNull() ?: return null
                if (n == 0L || size == 0L) null else (size - minOf(n, size))..(size - 1)
            }
            else -> {
                val first = a.toLongOrNull() ?: return null
                val last = if (b.isEmpty()) size - 1 else minOf(b.toLongOrNull() ?: return null, size - 1)
                if (first >= size || last < first) null else first..last
            }
        }
    }

    private fun queryParam(ex: HttpExchange, key: String): String? =
        ex.requestURI.rawQuery?.split("&")?.firstOrNull { it.startsWith("$key=") }?.substringAfter("=")

    private fun send(ex: HttpExchange, status: Int, body: ByteArray) {
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
    }

    private fun httpDate(ms: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))
}
