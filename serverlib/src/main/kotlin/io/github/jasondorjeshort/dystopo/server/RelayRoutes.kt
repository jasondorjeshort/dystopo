package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpExchange
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle
import io.github.jasondorjeshort.dystopo.sync.crypto.RelayProtocol
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The sync relay (T72): phones that are not on the same network exchange their logs through this server
 * without it merging them into any replica and without it being able to read them. Each phone posts its
 * whole log as an end-to-end encrypted frame ([EncryptedBundle], ChaCha20-Poly1305 under a key derived
 * from the 80-bit share code); the server files it under a *slot* (an HKDF of the code, so it never sees
 * the code) and a *side* (a random id per phone and session), hands it to the other sides of the slot,
 * and forgets it [Relay.Config.ttlSeconds] after its last write. Nothing is written to disk.
 *
 * Routes (wire format in [RelayProtocol]):
 * - `POST /sync/relay?slot=<32 hex>&side=<16 hex>[&after=<seq>]`: a non-empty body (one `DSEAL` relay
 *   frame) replaces this side's blob; the answer is every other side's blob newer than `after` (200,
 *   `application/octet-stream`, a `DRLY` reply) or 204. `X-Dystopo-Relay-Ttl` gives the seconds a blob
 *   lives. `&forget=1` deletes this side's blob instead (200 JSON).
 * - `GET /sync/relay/status`: counts only (slots, blobs, bytes, requests, refusals per reason) and the
 *   limits; never a slot or side id.
 * Limits: a blob larger than `maxBlobBytes` is a 413; more than `maxSides` sides in a slot, more than
 * `maxSlots` slots or more than `maxTotalBytes` held are a 507; more than `requestsPerMinute` requests
 * from one address (`X-Forwarded-For` is believed only from a loopback or private proxy) are a 429 with
 * `Retry-After`. A body that is not a relay frame is a 400. Disabled (`enabled: false`): 404.
 *
 * The standalone server's config block `relay` sets [config] (docs/SERVER.md, The relay); `devtool
 * serve --live` runs it with the defaults. The relay is open (no bearer token, by design: it only ever
 * holds ciphertext under unguessable slots).
 */
object RelayRoutes {
    @Volatile var relay: Relay = Relay(Relay.Config())
        private set

    /** Replaces the relay with one using [config] (the server calls this once at startup). */
    fun configure(config: Relay.Config) {
        relay = Relay(config)
    }

    /** Answers `/sync/relay*` and returns true; false for any other path. */
    fun handle(ex: HttpExchange): Boolean {
        val path = ex.requestURI.path
        if (path != RelayProtocol.PATH && !path.startsWith(RelayProtocol.PATH + "/")) return false
        val query = (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
        val r = relay
        val length = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0L
        val body = if (length > r.config.maxBlobBytes) {
            ex.requestBody.close()
            null
        } else ex.requestBody.use { it.readNBytes(r.config.maxBlobBytes + 1) }
        val reply = r.handle(ex.requestMethod, path, query, body, clientAddress(ex), System.currentTimeMillis())
        reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.responseHeaders.add("Content-Type", reply.contentType)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Cache-Control", "no-store")
        if (reply.status == 204 || reply.body.isEmpty()) {
            ex.sendResponseHeaders(reply.status, -1)
            ex.close()
        } else {
            ex.sendResponseHeaders(reply.status, reply.body.size.toLong())
            ex.responseBody.use { it.write(reply.body) }
        }
        return true
    }

    /** The caller's address; a proxy's `X-Forwarded-For` counts only when the connection comes from loopback or a private address. */
    fun clientAddress(ex: HttpExchange): String {
        val remote: InetAddress? = ex.remoteAddress?.address
        val forwarded = ex.requestHeaders.getFirst("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        if (forwarded != null && remote != null && (remote.isLoopbackAddress || remote.isSiteLocalAddress)) return forwarded
        return remote?.hostAddress ?: "unknown"
    }
}

/** The relay's state and rules, free of the HTTP server so tests drive it directly. Thread-safe. */
class Relay(val config: Config) {
    data class Config(
        val enabled: Boolean = true,
        /** A blob is dropped this long after its last write. */
        val ttlSeconds: Int = 900,
        val maxBlobBytes: Int = 16 shl 20,
        val maxTotalBytes: Long = 256L shl 20,
        val maxSlots: Int = 10_000,
        val maxSides: Int = 4,
        /** Requests per minute from one address (a phone polls every few seconds while its Share screen is open). */
        val requestsPerMinute: Int = 120,
    )

    class Reply(val status: Int, val contentType: String, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private class Blob(val seq: Long, val bytes: ByteArray, val writtenAt: Long)

    private class Bucket(var tokens: Double, var at: Long)

    private val slots = HashMap<String, HashMap<String, Blob>>()
    private val buckets = HashMap<String, Bucket>()
    private var seq = 0L
    private var held = 0L
    private var lastSweep = 0L

    // Counters for /sync/relay/status.
    private var posts = 0L
    private var stored = 0L
    private var delivered = 0L
    private var expired = 0L
    private val refused = linkedMapOf("badRequest" to 0L, "tooLarge" to 0L, "full" to 0L, "rateLimited" to 0L, "notAFrame" to 0L)

    private fun json(status: Int, m: Map<String, Any?>, headers: Map<String, String> = emptyMap()) =
        Reply(status, "application/json", (JsonWriter.write(m) + "\n").toByteArray(), headers)

    private fun refuse(reason: String, status: Int, message: String, headers: Map<String, String> = emptyMap()): Reply {
        refused[reason] = (refused[reason] ?: 0L) + 1
        return json(status, mapOf("error" to message), headers)
    }

    /** [body] null means the request declared a body over [Config.maxBlobBytes]. */
    @Synchronized
    fun handle(method: String, path: String, query: Map<String, String>, body: ByteArray?, client: String, now: Long): Reply {
        if (!config.enabled) return json(404, mapOf("error" to "this server has no relay"))
        sweep(now)
        if (path == RelayProtocol.PATH + "/status" && method == "GET") return json(200, status())
        if (path != RelayProtocol.PATH) return json(404, mapOf("error" to "POST ${RelayProtocol.PATH}?slot=&side=, GET ${RelayProtocol.PATH}/status"))
        if (method != "POST") return json(405, mapOf("error" to "POST ${RelayProtocol.PATH}?slot=<32 hex>&side=<hex>[&after=<seq>]"))
        if (!allow(client, now)) {
            return refuse("rateLimited", 429, "more than ${config.requestsPerMinute} relay requests a minute from one address", mapOf("Retry-After" to "10"))
        }
        val slotId = query["slot"]
        val side = query["side"]
        if (!RelayProtocol.isSlot(slotId) || !RelayProtocol.isSide(side)) return refuse("badRequest", 400, "slot must be 32 hex digits and side 8 to 64 (lowercase)")
        slotId!!
        side!!
        val after = query["after"]?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return refuse("badRequest", 400, "after must be a whole number >= 0") } ?: 0L
        posts++
        if (query["forget"] == "1") {
            val removed = slots[slotId]?.remove(side)
            if (removed != null) held -= removed.bytes.size
            if (slots[slotId]?.isEmpty() == true) slots.remove(slotId)
            return json(200, mapOf("forgotten" to (removed != null)))
        }
        if (body == null || body.size > config.maxBlobBytes) return refuse("tooLarge", 413, "a relay blob is at most ${config.maxBlobBytes} bytes")
        if (body.isNotEmpty()) {
            if (EncryptedBundle.purpose(body) != EncryptedBundle.Purpose.RELAY || body.size < EncryptedBundle.OVERHEAD) {
                return refuse("notAFrame", 400, "the body must be an encrypted relay frame (DSEAL)")
            }
            val slot = slots[slotId]
            val previous = slot?.get(side)
            if (slot == null && slots.size >= config.maxSlots) return refuse("full", 507, "the relay holds ${config.maxSlots} slots; try again later")
            if (previous == null && slot != null && slot.size >= config.maxSides) return refuse("full", 507, "a slot holds at most ${config.maxSides} phones")
            if (held - (previous?.bytes?.size ?: 0) + body.size > config.maxTotalBytes) return refuse("full", 507, "the relay is full; try again later")
            held += body.size - (previous?.bytes?.size ?: 0)
            slots.getOrPut(slotId) { HashMap() }[side] = Blob(++seq, body, now)
            stored++
        }
        val others = slots[slotId].orEmpty().filter { it.key != side && it.value.seq > after }.values.sortedBy { it.seq }
        val headers = mapOf("X-Dystopo-Relay-Ttl" to config.ttlSeconds.toString())
        if (others.isEmpty()) return Reply(204, "application/octet-stream", ByteArray(0), headers)
        delivered += others.size
        return Reply(200, "application/octet-stream", RelayProtocol.encodeReply(others.map { RelayProtocol.Blob(it.seq, it.bytes) }), headers)
    }

    /** Token bucket per address: [Config.requestsPerMinute] a minute, bursts up to the same number. */
    private fun allow(client: String, now: Long): Boolean {
        val cap = config.requestsPerMinute.toDouble()
        val b = buckets.getOrPut(client) { Bucket(cap, now) }
        b.tokens = minOf(cap, b.tokens + (now - b.at) * cap / 60_000.0)
        b.at = now
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }

    /** Drops expired blobs and idle rate-limit buckets (at most once a second). */
    private fun sweep(now: Long) {
        if (now - lastSweep < 1000) return
        lastSweep = now
        val ttl = config.ttlSeconds * 1000L
        val it = slots.values.iterator()
        while (it.hasNext()) {
            val slot = it.next()
            val dead = slot.filterValues { now - it.writtenAt >= ttl }.keys
            for (k in dead) { held -= slot.remove(k)!!.bytes.size; expired++ }
            if (slot.isEmpty()) it.remove()
        }
        buckets.entries.removeIf { now - it.value.at > 120_000 }
    }

    /** What the relay holds for [slot], as stored (tests: it must not open without the code). */
    @Synchronized
    fun stored(slot: String): List<ByteArray> = slots[slot].orEmpty().values.sortedBy { it.seq }.map { it.bytes }

    @Synchronized
    fun status(): Map<String, Any?> = linkedMapOf(
        "enabled" to config.enabled,
        "slots" to slots.size,
        "blobs" to slots.values.sumOf { it.size },
        "bytes" to held,
        "posts" to posts,
        "stored" to stored,
        "delivered" to delivered,
        "expired" to expired,
        "refused" to LinkedHashMap(refused),
        "limits" to linkedMapOf(
            "ttlSeconds" to config.ttlSeconds, "maxBlobBytes" to config.maxBlobBytes, "maxTotalBytes" to config.maxTotalBytes,
            "maxSlots" to config.maxSlots, "maxSides" to config.maxSides, "requestsPerMinute" to config.requestsPerMinute,
        ),
    )
}
