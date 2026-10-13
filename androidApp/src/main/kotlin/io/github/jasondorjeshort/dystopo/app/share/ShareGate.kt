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
 * the answer is the reply sealed the same way. Inside the frame are the T54 routes:
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
) {
    @Volatile var code: String = newCode()
        private set
    @Volatile var failures = 0
        private set
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
        if (++failures >= maxFailures) rotate()
    }

    /** A new code (also when the screen reopens); the failure count starts again. */
    @Synchronized
    fun rotate() {
        code = newCode()
        failures = 0
        rotations++
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "refused" to refused, "failures" to failures, "rotations" to rotations, "served" to served,
        "plainRefused" to plainRefused, "replays" to replays, "encrypted" to true,
    )

    companion object {
        const val SEALED_PATH = "/share/sealed"

        /** T78: the peer-source routes ([PeerServe]); refused (426) when they arrive unencrypted. */
        const val PEER_PREFIX = "/peer/"

        private val secure = SecureRandom()

        fun randomBytes(n: Int): ByteArray = ByteArray(n).also(secure::nextBytes)

        /** A new 80-bit share code ([ShareCode]). */
        fun randomCode(): String = ShareCode.generate(::randomBytes)
    }
}
