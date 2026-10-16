package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle.SealException
import io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink
import io.github.jasondorjeshort.dystopo.sync.crypto.ShareCode
import java.security.SecureRandom

/**
 * What the LAN listener answers (T54, encrypted since T72), free of Android so it is unit-tested on the JVM.
 * The only route is `POST /share/sealed`: its body is a [SealedLink] request frame, ChaCha20-Poly1305 under
 * a key derived from the share code on the screen ([ShareCode], 80 bits) and the frame's random salt, and
 * the answer is the reply sealed the same way (T106: a wrong code counts toward the five for [FAILURE_WINDOW_MS]).
 * Inside the frame are the T54 routes:
 * - `GET /share/hello`: this device's id, name and clock (the connecting phone calls it first, so a wrong
 *   code is reported as such before any sync).
 * - `POST /sync/pull?since=`, `POST /sync/push`, `GET /sync/status`: the T44 sync peer routes ([sync], a
 *   [io.github.jasondorjeshort.dystopo.sync.SyncServer]).
 * The code itself never crosses the network. A frame that does not open under the code (a wrong code, or
 * changed bytes) is a 403 and counts as a failure of the address it came from ([Request.peer]). T110: an address with
 * [maxFailures] failures in [failureWindowMs] is refused (429) without its frames being opened, until its failures age
 * or the code changes; other addresses go on (before, every phone's failures added up and the fifth replaced the code,
 * so phones holding stale codes cut off the one using the current code). [globalFailures] failures in the window from
 * all addresses together still replace the code (the screen shows it), so a guesser using many addresses meets a new
 * code. A frame whose salt was already served is a 409 (a recorded request replayed).
 * - T78, when [serve] is set: the peer-source routes of [PeerServe] (`/peer/...`: this phone's pack catalog, area
 *   manifests and pack bytes; `/dem`, `/osm` tiles from its packs), also only inside a sealed frame.
 * The pre-T72 plain routes (`/share/hello?code=`, `/sync/pull?code=` and the other sync routes; T78: `/peer/...`) answer 426:
 * an unencrypted client is refused without looking at anything it sent. Any other path is 404.
 */
class ShareGate(
    private val hello: () -> Map<String, Any?>,
    private val sync: (Request) -> Response?,
    /** T78: the peer-source routes ([PeerServe]); null answers them 404 inside the frame. */
    private val serve: ((Request) -> Response?)? = null,
    private val maxFailures: Int = 5,
    private val newCode: () -> String = ::randomCode,
    private val random: (Int) -> ByteArray = ::randomBytes,
    /** T106: a wrong code counts toward [maxFailures] for this long, then it is forgotten. */
    private val failureWindowMs: Long = FAILURE_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** T110: wrong codes in the window from all addresses together that replace the code. */
    private val globalFailures: Int = GLOBAL_FAILURES,
) {
    @Volatile var code: String = newCode()
        private set

    /** T106: when each wrong code still counted came, oldest first; T110: per address ([Request.peer]; [UNKNOWN_PEER] without one). */
    private val failureTimes = HashMap<String, ArrayDeque<Long>>()

    /**
     * Wrong codes in the last [failureWindowMs], all addresses together (T106: they age; before, every wrong code counted
     * until the code changed, so phones that each spent one failure now and then while the screen stayed open added up
     * to a new code).
     */
    val failures: Int @Synchronized get() = prune().let { failureTimes.values.sumOf { it.size } }

    /** T110: wrong codes in the window from [peer]. */
    @Synchronized
    fun failuresFrom(peer: String?): Int = prune().let { failureTimes[peer ?: UNKNOWN_PEER]?.size ?: 0 }

    /** T110: whether requests from [peer] are refused now ([maxFailures] wrong codes from it in the window). */
    fun lockedOut(peer: String?): Boolean = failuresFrom(peer) >= maxFailures

    /** T106: wrong codes forgotten because they were older than [failureWindowMs]. */
    @Volatile var failuresAged = 0L
        private set

    /** T110: sealed requests refused (429) because their address was locked out; their frames were not opened. */
    @Volatile var lockedRefused = 0L
        private set

    private fun prune() {
        val cutoff = clock() - failureWindowMs
        val each = failureTimes.values.iterator()
        while (each.hasNext()) {
            val times = each.next()
            while (times.isNotEmpty() && times.first() <= cutoff) {
                times.removeFirst()
                failuresAged++
            }
            if (times.isEmpty()) each.remove()
        }
    }
    @Volatile var refused = 0L
        private set
    @Volatile var rotations = 0
        private set
    @Volatile var served = 0L
        private set
    /** T72: requests refused for coming unencrypted (old clients, curl) and replayed frames. */
    @Volatile var plainRefused = 0L
        private set
    @Volatile var replays = 0L
        private set

    private val replay = SealedLink.ReplayGuard()
    private val innerRoutes = setOf("/share/hello", "/sync/pull", "/sync/push", "/sync/status")

    fun handle(r: Request): Response {
        if (r.path in innerRoutes || r.path.startsWith(PEER_PREFIX)) {
            plainRefused++
            return Response.json(mapOf("error" to "this phone takes only encrypted requests (POST $SEALED_PATH); update the app on the other phone"), 426)
        }
        if (r.path != SEALED_PATH) return Response.json(mapOf("error" to "not found"), 404)
        if (r.method != "POST") return Response.json(mapOf("error" to "POST an encrypted request"), 405)
        if (lockedOut(r.peer)) { // T110: this address spent its five; the others go on
            synchronized(this) { lockedRefused++ }
            return Response.json(mapOf("error" to LOCKED_OUT_MESSAGE), 429)
        }
        val codeNow = code
        val inner = try {
            SealedLink.openRequest(codeNow, r.body)
        } catch (e: SealException) {
            if (!e.authFailed) return Response.json(mapOf("error" to "not an encrypted request"), 400)
            failed(r.peer)
            return Response.json(mapOf("error" to "wrong code"), 403)
        }
        synchronized(replay) {
            if (!replay.firstUse(r.body)) {
                replays++
                return Response.json(mapOf("error" to "replayed request"), 409)
            }
        }
        served++
        val reply = if (inner.path !in innerRoutes) {
            serve?.invoke(Request(inner.method, inner.path, inner.query, inner.body, loopback = false, peer = r.peer)) ?: Response.json(mapOf("error" to "not found"), 404)
        } else if (inner.path == "/share/hello") {
            Response.json(hello() + mapOf("ok" to true))
        } else {
            sync(Request(inner.method, inner.path, inner.query, inner.body, loopback = false, peer = r.peer)) ?: Response.json(mapOf("error" to "not found"), 404)
        }
        val sealed = SealedLink.sealResponse(codeNow, r.body, SealedLink.Response(reply.status, reply.contentType, reply.body), random)
        return Response(200, "application/octet-stream", sealed)
    }

    @Synchronized
    private fun failed(peer: String?) {
        refused++
        prune()
        failureTimes.getOrPut(peer ?: UNKNOWN_PEER) { ArrayDeque() }.addLast(clock())
        if (failureTimes.values.sumOf { it.size } >= globalFailures) rotate()
    }

    /** A new code (also when the screen reopens); the failure counts start again and no address stays locked out. */
    @Synchronized
    fun rotate() {
        code = newCode()
        failureTimes.clear()
        rotations++
    }

    @Synchronized
    fun json(): Map<String, Any?> {
        prune()
        return linkedMapOf(
            "refused" to refused, "failures" to failureTimes.values.sumOf { it.size }, "rotations" to rotations, "served" to served,
            "plainRefused" to plainRefused, "replays" to replays, "encrypted" to true,
            "failureWindowMs" to failureWindowMs, "failuresAged" to failuresAged, // T106
            // T110: counted per address; the addresses themselves are not listed
            "maxFailures" to maxFailures, "globalFailures" to globalFailures, "addressesFailing" to failureTimes.size,
            "addressesLockedOut" to failureTimes.values.count { it.size >= maxFailures }, "lockedRefused" to lockedRefused,
        )
    }

    companion object {
        const val SEALED_PATH = "/share/sealed"

        /**
         * T106: how long a wrong code counts toward the five that replace the code. Guessing an 80-bit code stays
         * hopeless either way (five wrong ones within ten minutes still replace it), while phones whose kept codes went
         * stale, each spending one failure when it relaunches or rediscovers this one, no longer add up over an
         * afternoon with the screen open.
         */
        const val FAILURE_WINDOW_MS = 10 * 60_000L

        /**
         * T110: wrong codes within [FAILURE_WINDOW_MS] from all addresses together that replace the code. A phone holding a
         * stale code spends one now and then (T106's cap of four unanswered requests), so fifty within ten minutes is a
         * guesser using many addresses, not phones that kept an old code.
         */
        const val GLOBAL_FAILURES = 50

        /** T110: requests that carry no address count their failures under this one key. */
        const val UNKNOWN_PEER = "?"

        /** T110: what a locked-out address gets with its 429 (the connecting phone shows it). */
        const val LOCKED_OUT_MESSAGE = "too many wrong codes from this phone: check the code, or wait ten minutes (a new code on the other phone also clears it)"

        /** T78: the peer-source routes ([PeerServe]); refused (426) when they arrive unencrypted. */
        const val PEER_PREFIX = "/peer/"

        private val secure = SecureRandom()

        fun randomBytes(n: Int): ByteArray = ByteArray(n).also(secure::nextBytes)

        /** A new 80-bit share code ([ShareCode]). */
        fun randomCode(): String = ShareCode.generate(::randomBytes)
    }
}
