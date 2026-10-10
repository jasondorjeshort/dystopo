package io.github.jasondorjeshort.dystopo.app

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * T49: the loopback server's per-launch secret. Any app on the device can open 127.0.0.1:8686, so
 * every request must carry this token, as `?t=<token>` (the tile, glyph and shade URLs in the style
 * MapLibre loads) or an `X-Dystopo-Token` header; anything else gets 401. The token is random per
 * process start and lives only in memory.
 *
 * Bootstrap for the debug tools (`android-check.py`, `android-probe.py`, `android-emu.ps1`): in builds
 * with debug endpoints, `GET /health` without the token answers only `{"token": "..."}` to a loopback
 * caller (through `adb forward` that is the host). Release builds have no `/health`, so there the token
 * never leaves the process. In debug builds other apps on the device could read it the same way; the
 * debug routes are for development only.
 */
class LoopbackToken(val value: String = generate()) {
    /** Requests refused for a missing or wrong token. */
    val refused = AtomicLong()

    private val expected = value.toByteArray()

    /** True when [r] carries the token (query `t` or header `X-Dystopo-Token`). */
    fun accepts(r: Request): Boolean {
        val given = r.query[QUERY] ?: r.headers[HEADER] ?: return false
        return MessageDigest.isEqual(given.toByteArray(), expected)
    }

    /**
     * Admits [r] or answers it: (the request without its `t` parameter, null) when the token is good;
     * (null, the token-only `/health` answer) for the debug bootstrap; (null, 401) otherwise.
     */
    fun admit(r: Request, debugEndpoints: Boolean): Pair<Request?, Response?> {
        if (accepts(r)) return (if (QUERY in r.query) r.copy(query = r.query - QUERY) else r) to null
        if (debugEndpoints && r.method == "GET" && r.path == "/health" && r.loopback) {
            return null to Response.json(mapOf("token" to value))
        }
        refused.incrementAndGet()
        return null to Response.json(mapOf("error" to "token required (query t or header X-Dystopo-Token)"), 401)
    }

    /** Appends the token to a style URL template ([url] may already have a query). */
    fun url(url: String): String = url + (if ('?' in url) "&" else "?") + QUERY + "=" + value

    companion object {
        const val QUERY = "t"

        /** Lower-case, as [LocalServer] stores header names. */
        const val HEADER = "x-dystopo-token"

        private fun generate(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
