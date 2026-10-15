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
 * changed bytes) is a 403 and counts as a failure; after [maxFailures] the code is replaced by a new one
 * (the screen shows it). A frame whose salt was already served is a 409 (a recorded request replayed).
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
) {
    @Volatile var code: String = newCode()
        private set

    /** T106: when each wrong code still counted came, oldest first (at most [maxFailures]). */
    private val failureTimes = ArrayDeque<Long>()

    /**
     * Wrong codes in the last [failureWindowMs] (T106: they age; before, every wrong code counted until the code changed,
     * so phones that each spent one failure now and then while the screen stayed open added up to a new code).
     */
    val failures: Int @Synchronized get() = prune().let { failureTimes.size }

    /** T106: wrong codes forgotten because they were older than [failureWindowMs]. */
    @Volatile var failuresAged = 0L
        private set

    private fun prune() {
        val cutoff = clock() - failureWindowMs
        while (failureTimes.isNotEmpty() && failureTimes.first() <= cutoff) {
            failureTimes.removeFirst()
            failuresAged++
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
        val codeNow = code
        val inner = try {
            SealedLink.openRequest(codeNow, r.body)
        } catch (e: SealException) {
            if (!e.authFailed) return Response.json(mapOf("error" to "not an encrypted request"), 400)
            failed()
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
            serve?.invoke(Request(inner.method, inner.path, inner.query, inner.body, loopback = false)) ?: Response.json(mapOf("error" to "not found"), 404)
        } else if (inner.path == "/share/hello") {
            Response.json(hello() + mapOf("ok" to true))
        } else {
            sync(Request(inner.method, inner.path, inner.query, inner.body, loopback = false)) ?: Response.json(mapOf("error" to "not found"), 404)
        }
        val sealed = SealedLink.sealResponse(codeNow, r.body, SealedLink.Response(reply.status, reply.contentType, reply.body), random)
        return Response(200, "application/octet-stream", sealed)
    }

    @Synchronized
    private fun failed() {
        refused++
        prune()
        failureTimes.addLast(clock())
        if (failureTimes.size >= maxFailures) rotate()
    }

    /** A new code (also when the screen reopens); the failure count starts again. */
    @Synchronized
    fun rotate() {
        code = newCode()
        failureTimes.clear()
        rotations++
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "refused" to refused, "failures" to failures, "rotations" to rotations, "served" to served,
        "plainRefused" to plainRefused, "replays" to replays, "encrypted" to true,
        "failureWindowMs" to failureWindowMs, "failuresAged" to failuresAged, // T106
    )

    companion object {
        const val SEALED_PATH = "/share/sealed"

        /**
         * T106: how long a wrong code counts toward the five that replace the code. Guessing an 80-bit code stays
         * hopeless either way (five wrong ones within ten minutes still replace it), while phones whose kept codes went
         * stale, each spending one failure when it relaunches or rediscovers this one, no longer add up over an
         * afternoon with the screen open.
         */
        const val FAILURE_WINDOW_MS = 10 * 60_000L

        /** T78: the peer-source routes ([PeerServe]); refused (426) when they arrive unencrypted. */
        const val PEER_PREFIX = "/peer/"

        private val secure = SecureRandom()

        fun randomBytes(n: Int): ByteArray = ByteArray(n).also(secure::nextBytes)

        /** A new 80-bit share code ([ShareCode]). */
        fun randomCode(): String = ShareCode.generate(::randomBytes)
    }
}
