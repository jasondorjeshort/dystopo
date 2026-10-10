package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import java.security.SecureRandom

/**
 * What the LAN listener answers (T54), free of Android so it is unit-tested on the JVM. Every request
 * must carry the 6-digit code shown on the Share screen as `code=` in the query; only these routes exist:
 * - `GET /share/hello?code=`: checks the code; JSON with this device's id and name (the connecting
 *   phone calls it first, so a wrong code is reported as such before any sync).
 * - `POST /sync/pull?since=&code=`, `POST /sync/push?code=`, `GET /sync/status?code=`: the T44 sync
 *   peer routes ([sync], a [io.github.jasondorjeshort.dystopo.sync.SyncServer]).
 * Anything else is 404 without looking at the code; a missing or wrong code is 403. After
 * [maxFailures] wrong codes the code is replaced by a new one (the screen shows it), so guessing one of
 * a million codes gets [maxFailures] tries per code shown.
 */
class ShareGate(
    private val hello: () -> Map<String, Any?>,
    private val sync: (Request) -> Response?,
    private val maxFailures: Int = 5,
    private val newCode: () -> String = ::randomCode,
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

    private val routes = setOf("/share/hello", "/sync/pull", "/sync/push", "/sync/status")

    fun handle(r: Request): Response {
        if (r.path !in routes) return Response.json(mapOf("error" to "not found"), 404)
        if (!check(r.query["code"])) return Response.json(mapOf("error" to "wrong code"), 403)
        served++
        if (r.path == "/share/hello") return Response.json(hello() + mapOf("ok" to true))
        return sync(r) ?: Response.json(mapOf("error" to "not found"), 404)
    }

    @Synchronized
    private fun check(given: String?): Boolean {
        val expected = code
        if (given != null && constantTimeEquals(given, expected)) return true
        refused++
        if (++failures >= maxFailures) rotate()
        return false
    }

    /** A new code (also when the screen reopens); the failure count starts again. */
    @Synchronized
    fun rotate() {
        code = newCode()
        failures = 0
        rotations++
    }

    fun json(): Map<String, Any?> = linkedMapOf("refused" to refused, "failures" to failures, "rotations" to rotations, "served" to served)

    companion object {
        private val random = SecureRandom()

        fun randomCode(): String = (random.nextInt(1_000_000)).toString().padStart(6, '0')

        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
