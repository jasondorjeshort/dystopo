package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.sync.ClockSample
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle.SealException
import io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport
import io.github.jasondorjeshort.dystopo.sync.Sync
import io.github.jasondorjeshort.dystopo.sync.SyncReplica
import io.github.jasondorjeshort.dystopo.sync.SyncReport
import io.github.jasondorjeshort.dystopo.sync.SyncTransport
import io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink
import io.github.jasondorjeshort.dystopo.sync.crypto.ShareCode
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The connecting side of a phone-to-phone sync (T54): address parsing and one exchange over the LAN link.
 * T72: every request goes sealed ([SealedLink]) in a `POST /share/sealed`, encrypted under the share code.
 */
object ShareClient {
    /** The port the Share listener tries first (any free port when it is taken). */
    const val DEFAULT_PORT = 8841

    data class Target(val baseUrl: String, val code: String?)

    class WrongCodeException(message: String) : IllegalArgumentException(message)

    /** What the QR code holds: `dystopo://share?addr=<host:port>&code=<16 characters>`. */
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

    /** The canonical code, or an [IllegalArgumentException] that says what a code looks like. */
    fun requireCode(code: String?): String =
        ShareCode.normalize(code) ?: throw IllegalArgumentException("the code is ${ShareCode.LENGTH} letters and digits, as shown on the other phone (like 7K3M-QW2D-9XHP-T4CA)")

    /**
     * One request through the encrypted link: seals [method] [target] and [body] under [code], posts it to
     * `/share/sealed` and opens the sealed reply. A 403 (the frame did not open there) is a [WrongCodeException];
     * a 426 means the other phone runs a build without the encrypted link. T110: a 429 is the other phone refusing this
     * one for a while after five wrong codes from it ([ShareGate.LOCKED_OUT_MESSAGE]); it is not a wrong code.
     */
    fun sealed(peer: Target, code: String, method: String, target: String, body: ByteArray = ByteArray(0), readMs: Int = 60_000): SealedLink.Response {
        val frame = SealedLink.sealRequest(code, SealedLink.Request(method, target, body), ShareGate::randomBytes)
        val r = LanHttp.request("POST", "${peer.baseUrl}${ShareGate.SEALED_PATH}", frame, readMs = readMs)
        when (r.status) {
            200 -> {}
            403 -> throw WrongCodeException("wrong code for ${peer.baseUrl.removePrefix("http://")}")
            404 -> throw java.io.IOException("${peer.baseUrl} has no encrypted link (an older app, or not a Dystopo phone)")
            429 -> throw LanHttpException(429, "${peer.baseUrl.removePrefix("http://")} refused this phone: ${ShareGate.LOCKED_OUT_MESSAGE}")
            else -> throw LanHttpException(r.status, "${peer.baseUrl}${ShareGate.SEALED_PATH}: HTTP ${r.status} ${r.text.take(200).trim()}")
        }
        return try {
            SealedLink.openResponse(code, frame, r.body)
        } catch (e: SealException) {
            throw java.io.IOException("the reply from ${peer.baseUrl} did not decrypt (${e.message})")
        }
    }

    /**
     * Checks the code with a sealed `GET /share/hello` (a wrong one throws [WrongCodeException] before any data
     * moves); returns the peer's hello (device, name and, T57, its clock `now`) and a clock sample.
     */
    fun hello(target: Target, code: String, clock: () -> Long = System::currentTimeMillis): Pair<Map<String, Any?>, ClockSample?> {
        val c = requireCode(code)
        val sent = clock()
        val hello = sealed(target, c, "GET", "/share/hello", readMs = 15_000)
        val received = clock()
        if (hello.status != 200) throw java.io.IOException("${target.baseUrl}/share/hello: HTTP ${hello.status} ${hello.body.decodeToString().take(200)}")
        val peer = MiniJson.parseObject(hello.body.decodeToString())
        val sample = (peer["now"] as? Number)?.toLong()?.let { ClockSample(it, sent, received) }
        return peer to sample
    }

    /** The T44 sync routes of the phone at [target], every request sealed under [code]. */
    fun transport(target: Target, code: String): SyncTransport {
        val c = requireCode(code)
        return HttpSyncTransport(target.baseUrl, { url, body ->
            val r = sealed(target, c, "POST", url.removePrefix(target.baseUrl), body)
            if (r.status !in 200..299) throw LanHttpException(r.status, "POST ${url.substringBefore('?')}: HTTP ${r.status} ${r.body.decodeToString().take(200).trim()}")
            r.body
        }, gzip, gunzip)
    }

    /**
     * [hello], then one T44 exchange (pull, merge, push) through [transport]: T57 pages it like an HTTP sync
     * ([Sync.DEFAULT_PAGE_OPS] operations per request each way; 0 for one bundle). The app goes through
     * [io.github.jasondorjeshort.dystopo.app.sync.AppSync.run] instead; this is the plain protocol (tests).
     */
    fun exchange(replica: SyncReplica, target: Target, code: String, now: () -> Long, elapsed: () -> Long = { 0L }, pageOps: Int = Sync.DEFAULT_PAGE_OPS): Pair<Map<String, Any?>, SyncReport> {
        val (peer, _) = hello(target, code)
        return peer to Sync.exchange(replica, transport(target, code), now, elapsed, pageOps)
    }

    val gzip: (ByteArray) -> ByteArray = { b -> ByteArrayOutputStream(b.size / 2 + 64).also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray() }
    val gunzip: (ByteArray) -> ByteArray = { b -> GZIPInputStream(b.inputStream()).use { it.readBytes() } }
}
