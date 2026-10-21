package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter

/**
 * T123: the phone's request log, one line per request the loopback servers answered: when, the method and path
 * (never the query, so never the per-launch token), the route (`dem`, `osm`, `contours`, `shade`, `glyphs`,
 * `sprite`, `style`, or the path of a debug route), the pack that answered a tile (`online:<source key>` for the
 * online fallback, null when none or not a tile), the status, the body's bytes and the ms the handler took.
 * A ring buffer of the last [capacity] lines in memory only; nothing is written to disk.
 *
 * It is the precondition of ADR 0034's revisit (a request log from a real phone), so [jsonLines] writes the lines
 * as `devtool cache-replay --log` reads a server's `server-json.log` (`event`, `ts`, `method`, `path`, `status`,
 * `bytes`). Debug route: `GET /stats/requests[?limit=][&format=jsonl]` ([Routes]). Pure (no Android), tested.
 */
class RequestLog(val capacity: Int = DEFAULT_CAPACITY, private val clock: () -> Long = System::currentTimeMillis) {
    class Entry(
        val atMs: Long, val method: String, val path: String, val route: String, val pack: String?,
        val status: Int, val bytes: Int, val ms: Long,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "event" to "request", "ts" to iso(atMs), "method" to method, "path" to path, "route" to route,
            "pack" to pack, "status" to status, "bytes" to bytes, "ms" to ms,
        )
    }

    private val ring = arrayOfNulls<Entry>(capacity)
    private var next = 0L

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** Records one answered request; [startedAtMs] is when its handler began (wall clock). */
    fun add(method: String, path: String, status: Int, bytes: Int, startedAtMs: Long, pack: String? = null) {
        val e = Entry(startedAtMs, method, path, routeOf(path), pack, status, bytes, (clock() - startedAtMs).coerceAtLeast(0))
        synchronized(ring) {
            ring[(next % capacity).toInt()] = e
            next++
        }
    }

    /** The newest [limit] lines, oldest first. */
    fun entries(limit: Int = capacity): List<Entry> = synchronized(ring) {
        val n = minOf(next, capacity.toLong(), limit.coerceAtLeast(0).toLong()).toInt()
        (0 until n).map { i -> ring[((next - n + i) % capacity).toInt()]!! }
    }

    /** `GET /stats/requests`: the counts, a summary per route and status, and the newest [limit] lines. */
    fun json(limit: Int = DEFAULT_LIMIT): Map<String, Any?> {
        val (total, kept) = synchronized(ring) { next to entries() }
        val perRoute = kept.groupBy { it.route }.toSortedMap().mapValues { (_, list) ->
            linkedMapOf(
                "requests" to list.size, "bytes" to list.sumOf { it.bytes.toLong() },
                "statuses" to list.groupingBy { it.status.toString() }.eachCount().toSortedMap(),
                "maxMs" to list.maxOf { it.ms },
            )
        }
        val lines = kept.takeLast(limit.coerceAtLeast(0))
        return linkedMapOf(
            "capacity" to capacity, "total" to total, "kept" to kept.size, "dropped" to (total - kept.size).coerceAtLeast(0),
            "perRoute" to perRoute, "shown" to lines.size, "lines" to lines.map { it.json() },
        )
    }

    /** Every kept line as one JSON object per line, the format `devtool cache-replay --log` reads. */
    fun jsonLines(limit: Int = capacity): String = entries(limit).joinToString("") { JsonWriter.write(it.json()) + "\n" }

    companion object {
        const val DEFAULT_CAPACITY = 4096
        const val DEFAULT_LIMIT = 200

        private val MAP_ROUTES = listOf("dem", "osm", "contours", "shade", "glyphs")

        /** The route a path belongs to: the map's tile kinds by their first segment, `sprite`, `style`, else the path. */
        fun routeOf(path: String): String {
            val first = path.trimStart('/').substringBefore('/')
            return when {
                path == "/style.json" -> "style"
                path.startsWith("/sprite") -> "sprite"
                first in MAP_ROUTES && path.count { it == '/' } > 1 -> first
                else -> path
            }
        }

        fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()
    }
}
