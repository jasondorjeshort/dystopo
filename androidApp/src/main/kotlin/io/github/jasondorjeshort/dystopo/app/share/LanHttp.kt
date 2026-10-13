package io.github.jasondorjeshort.dystopo.app.share

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * A minimal HTTP/1.1 client over a plain socket for the phone-to-phone link (T54). The app's network
 * security config allows cleartext only to the loopback server, and a LAN peer is an arbitrary private
 * address, so the share link does not go through `HttpURLConnection`. T72: what it carries is encrypted end to end
 * ([io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink]), so the plain transport no longer matters.
 * One request per connection (`Connection: close`), as the peer's [io.github.jasondorjeshort.dystopo.app.LocalServer] answers.
 */
object LanHttp {
    class Reply(val status: Int, val body: ByteArray) {
        val text: String get() = body.decodeToString()
    }

    const val MAX_REPLY = 64 shl 20

    fun request(method: String, url: String, body: ByteArray = ByteArray(0), connectMs: Int = 8_000, readMs: Int = 60_000): Reply {
        val u = URI(url)
        require(u.scheme == "http") { "the share link is plain http: $url" }
        val host = u.host ?: throw IllegalArgumentException("no host in $url")
        val port = if (u.port > 0) u.port else 80
        Socket().use { s ->
            try {
                s.connect(InetSocketAddress(host, port), connectMs)
            } catch (e: java.io.IOException) {
                throw LanConnectException("$host:$port: ${e.javaClass.simpleName}: ${e.message}", e) // T78: no connection at all (the fetcher backs off)
            }
            s.soTimeout = readMs
            val target = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            val head = "$method $target HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            val out = s.getOutputStream()
            out.write(head.encodeToByteArray())
            out.write(body)
            out.flush()
            return readReply(BufferedInputStream(s.getInputStream()))
                ?: throw java.io.IOException("no reply from $host:$port (is the Share screen open on that phone?)")
        }
    }

    /** POST that throws on a non-2xx status: the `post` of [io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport]. */
    fun post(url: String, body: ByteArray): ByteArray {
        val r = request("POST", url, body)
        if (r.status !in 200..299) throw LanHttpException(r.status, "POST ${url.substringBefore('?')}: HTTP ${r.status} ${r.text.take(200).trim()}")
        return r.body
    }

    /** Null when the connection closed before any byte (an adb forward with nothing listening behind it). */
    private fun readReply(input: InputStream): Reply? {
        val first = line(input) ?: return null
        val status = first.split(' ').getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("not an HTTP reply: ${first.take(80)}")
        var length = -1
        while (true) {
            val h = line(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0 && h.substring(0, i).trim().equals("content-length", ignoreCase = true)) length = h.substring(i + 1).trim().toIntOrNull() ?: -1
        }
        if (length > MAX_REPLY) throw java.io.IOException("reply of $length bytes is too large")
        val body = if (length >= 0) {
            val b = ByteArray(length)
            var n = 0
            while (n < length) {
                val k = input.read(b, n, length - n)
                if (k < 0) throw java.io.IOException("reply shorter than its content-length")
                n += k
            }
            b
        } else {
            val o = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (true) {
                val k = input.read(buf)
                if (k < 0) break
                o.write(buf, 0, k)
                if (o.size() > MAX_REPLY) throw java.io.IOException("reply too large")
            }
            o.toByteArray()
        }
        return Reply(status, body)
    }

    private fun line(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) throw java.io.IOException("header line too long")
        }
    }
}

class LanHttpException(val status: Int, message: String) : java.io.IOException(message)

/** T78: the connection itself failed (refused, no route, connect timeout), as opposed to a failed read after it. */
class LanConnectException(message: String, cause: Throwable) : java.io.IOException(message, cause)
