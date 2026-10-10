package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport
import io.github.jasondorjeshort.dystopo.sync.Sync
import io.github.jasondorjeshort.dystopo.sync.SyncReplica
import io.github.jasondorjeshort.dystopo.sync.SyncReport
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The connecting side of a phone-to-phone sync (T54): address parsing and one exchange over the LAN link. */
object ShareClient {
    /** The port the Share listener tries first (any free port when it is taken). */
    const val DEFAULT_PORT = 8841

    data class Target(val baseUrl: String, val code: String?)

    class WrongCodeException(message: String) : IllegalArgumentException(message)

    /** What the QR code holds: `dystopo://share?addr=<host:port>&code=<6 digits>`. */
    fun shareUri(hostPort: String, code: String) = "dystopo://share?addr=$hostPort&code=$code"

    /**
     * `host`, `host:port`, `http://host:port` or a [shareUri] (whose code is used when [code] is not
     * given) to the peer's base URL and code.
     */
    fun parse(input: String): Target {
        val s = input.trim()
        require(s.isNotEmpty()) { "pass addr=<host[:port]>" }
        if (s.startsWith("dystopo://share")) {
            val q = URI(s).rawQuery.orEmpty().split('&').filter { '=' in it }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val addr = q["addr"] ?: throw IllegalArgumentException("no addr in $s")
            return Target(parse(addr).baseUrl, q["code"])
        }
        val hostPort = s.removePrefix("http://").trimEnd('/')
        require('/' !in hostPort && '?' !in hostPort && '@' !in hostPort && hostPort.isNotEmpty()) { "an address is host or host:port, not $input" }
        val hasPort = hostPort.substringAfterLast(':', "").let { it.isNotEmpty() && it.all(Char::isDigit) } && hostPort.count { it == ':' } == 1
        return Target("http://" + if (hasPort) hostPort else "$hostPort:$DEFAULT_PORT", null)
    }

    fun withCode(url: String, code: String) = url + (if ('?' in url) "&" else "?") + "code=" + URLEncoder.encode(code, "UTF-8")

    /**
     * Checks the code with `GET /share/hello` (a wrong one throws [WrongCodeException] before any data
     * moves), then one T44 exchange (pull, merge, push) through [HttpSyncTransport] over [LanHttp].
     */
    fun exchange(replica: SyncReplica, target: Target, code: String, now: () -> Long, elapsed: () -> Long = { 0L }): Pair<Map<String, Any?>, SyncReport> {
        require(code.length == 6 && code.all(Char::isDigit)) { "the code is 6 digits" }
        val hello = LanHttp.request("GET", withCode("${target.baseUrl}/share/hello", code), readMs = 15_000)
        if (hello.status == 403) throw WrongCodeException("wrong code for ${target.baseUrl.removePrefix("http://")}")
        if (hello.status != 200) throw java.io.IOException("${target.baseUrl}/share/hello: HTTP ${hello.status} ${hello.text.take(200)}")
        val peer = MiniJson.parseObject(hello.text)
        val transport = HttpSyncTransport(target.baseUrl, { url, body -> LanHttp.post(withCode(url, code), body) }, gzip, gunzip)
        return peer to Sync.exchange(replica, transport, now, elapsed)
    }

    val gzip: (ByteArray) -> ByteArray = { b -> ByteArrayOutputStream(b.size / 2 + 64).also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray() }
    val gunzip: (ByteArray) -> ByteArray = { b -> GZIPInputStream(b.inputStream()).use { it.readBytes() } }
}
