package io.github.jasondorjeshort.dystopo.app.firstrun

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.packs.PackManager

/**
 * T79: Settings › Sources: the Dystopo server URL and its Test. [test] fetches the server's catalog (T53's
 * client: `/catalog`, else `/out/catalog.json`) and reports the pack count or the error, in [test] for the
 * sheet and `GET /firstrun` (`serverTest`). The URL is validated as `POST /settings?server=` validates it,
 * [CleartextPolicy] included, before anything is fetched.
 */
object Sources {
    /** The last test: `state` (testing, ok, failed), `server`, `packs`, `error`, `ms`, `saved` (the URL tested is the saved one). */
    @Volatile var test: Map<String, Any?> = linkedMapOf("state" to "idle")
        private set

    /** Tests [raw] (default: the saved server). Blocks; call off the main thread. */
    fun test(raw: String? = null): Map<String, Any?> {
        val started = System.currentTimeMillis()
        val given = (raw ?: AppSettings.server).trim()
        fun done(m: Map<String, Any?>) = (linkedMapOf<String, Any?>("ms" to System.currentTimeMillis() - started) + m).also { test = it }
        val server = AppSettings.normalizeServer(given)
        if (server == null) return done(mapOf("state" to "failed", "server" to given, "error" to "not a server URL: want http(s)://host[:port]"))
        if (server.isEmpty()) return done(mapOf("state" to "failed", "server" to null, "error" to "no server set"))
        CleartextPolicy.problem(server)?.let { return done(mapOf("state" to "failed", "server" to server, "error" to it)) }
        test = linkedMapOf("state" to "testing", "server" to server)
        return try {
            val c = PackManager.get(DystopoApp.instance).catalogClient.refresh(server)
            done(mapOf("state" to "ok", "server" to server, "packs" to c.packs.size, "names" to c.packs.map { it.name }.take(20), "saved" to (server == AppSettings.server)))
        } catch (e: Exception) {
            done(mapOf("state" to "failed", "server" to server, "error" to (e.message ?: e.javaClass.simpleName), "saved" to (server == AppSettings.server)))
        }
    }

    /** One line for the sheet: "3 packs on http://…" or the error. */
    fun summary(t: Map<String, Any?> = test): String? = when (t["state"]) {
        "testing" -> "Testing ${t["server"]}..."
        "ok" -> {
            val n = (t["packs"] as? Number)?.toInt() ?: 0
            "Connected: ${if (n == 1) "1 pack" else "$n packs"} on ${t["server"]}" + if (t["saved"] == false) " (not saved yet: tap Save)" else ""
        }
        "failed" -> "Test failed: ${t["error"]}"
        else -> null
    }
}
