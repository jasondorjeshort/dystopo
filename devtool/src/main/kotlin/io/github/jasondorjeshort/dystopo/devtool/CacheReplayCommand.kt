package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.server.reportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * T112: replays the standalone server's request log (`log.file` in its config, one JSON line per request; docs/SERVER.md)
 * against the phone's cache budget, to choose its eviction rule on numbers. Each 200 tile answer becomes a request to a
 * simulated phone cache of the budget's kinds: `/dem`, `/osm` and `/glyphs` are tiles the phone downloads (`online`),
 * `/contours` and `/shade` are tiles it makes from them (`derived`); the phone's own source downloads (`sources`) have no
 * request in a server log. The cache holds `--totals` megabytes over the kinds with the phone's rule around the choice
 * being measured: floors per kind (`--floors`), derived data first, then the kind furthest above its floor, a run frees
 * the slack below the total (1%, at least 4 MB), and nothing used in the last `--protect-seconds` of log time goes (the
 * view). Within a kind, `--rules` picks what goes first: `lru` (least recently used, the phone's rule before and after
 * T112), `gdsf` (a size-weighted frequency score, Greedy-Dual-Size-Frequency: the lowest `L + hits / KB` goes, `L`
 * rising to each evicted score so old favourites age out), `lru-global` (one LRU over every kind, no floors, no
 * derived-first: the reference). Prints hit rate and bytes evicted per kind for each total and rule. A page load in the
 * log (`GET /`) starts a session; `--forget-on-restart` makes `gdsf` forget its hit counts there, as a phone that keeps
 * no index of counts across app restarts would (LRU's order survives in the files' times).
 *
 * ```
 * devtool cache-replay --log out/server-local/server-json.log --totals 64,256
 * ```
 */
class CacheReplayCommand : CliktCommand(name = "cache-replay") {
    override fun help(context: Context) =
        "Replay a server request log against the phone's cache budget (hit rate and evictions per kind, LRU against a size-weighted frequency score); prints a JSON report."

    private val logs by option("--log", help = "the server's JSON request log (comma-separated for rotated files)").split(",").required()
    private val totals by option("--totals", help = "budget totals to replay, MB, comma-separated").split(",").default(listOf("64", "256"))
    private val rules by option("--rules", help = "lru, gdsf, lru-global (comma-separated)").split(",").default(listOf("lru", "gdsf"))
    private val floors by option("--floors", help = "kind=share of the total, comma-separated").default("online=0.25,derived=0.15,sources=0.05")
    private val protectSeconds by option("--protect-seconds", help = "entries used this recently (log time) are not evicted: the view").double().default(5.0)
    private val repeat by option("--repeat", help = "replay the log this many times in a row (days of the same viewing)").int().default(1)
    private val forget by option("--forget-on-restart", help = "gdsf forgets its hit counts at each page load in the log (an app restart with no index of counts kept)").flag()

    override fun run() {
        val started = System.nanoTime()
        val files = logs.map { File(it.trim()) }
        files.firstOrNull { !it.isFile }?.let { throw UsageError("no log file $it") }
        val parsed = CacheReplay.parse(files.asSequence().flatMap { it.readLines(Charsets.UTF_8).asSequence() })
        if (parsed.events.isEmpty()) throw UsageError("no cacheable tile requests in ${files.joinToString()}")
        val events = CacheReplay.repeat(parsed.events, repeat)
        val floorShares = CacheReplay.parseFloors(floors)
        val ruleList = rules.map { r -> CacheReplay.Rule.entries.firstOrNull { it.key == r.trim() } ?: throw UsageError("unknown rule '$r' (lru, gdsf, lru-global)") }
        val totalList = totals.map { it.trim().toLongOrNull()?.takeIf { mb -> mb > 0 } ?: throw UsageError("--totals must be MB, e.g. 64,256 (got '$it')") }
        val runs = ArrayList<Map<String, Any?>>()
        val table = ArrayList<String>()
        table += "| total | rule | online hits | derived hits | all hits | bytes hit | evicted online | evicted derived |"
        table += "|---|---|---|---|---|---|---|---|"
        for (mb in totalList) for (rule in ruleList) {
            val r = CacheReplay.run(events, mb * CacheReplay.MB, rule, floorShares, (protectSeconds * 1000).toLong(), forget)
            runs += r.json() + mapOf("totalMb" to mb)
            fun k(kind: CacheReplay.Kind) = r.perKind.getValue(kind)
            table += "| $mb MB | ${rule.key} | ${pct(k(CacheReplay.Kind.ONLINE).hitRate)} | ${pct(k(CacheReplay.Kind.DERIVED).hitRate)} | " +
                "${pct(r.all.hitRate)} | ${pct(r.all.byteHitRate)} | ${mbText(k(CacheReplay.Kind.ONLINE).bytesEvicted)} | ${mbText(k(CacheReplay.Kind.DERIVED).bytesEvicted)} |"
        }
        val report = linkedMapOf(
            "logs" to files.map { it.path },
            "lines" to parsed.lines,
            "requests" to parsed.requests,
            "skipped" to parsed.skipped,
            "events" to events.size,
            "repeat" to repeat,
            "spanMinutes" to (events.last().atMs - events.first().atMs) / 60_000.0,
            "workingSet" to CacheReplay.workingSet(events),
            "floors" to floorShares.mapKeys { it.key.key },
            "protectSeconds" to protectSeconds,
            "sessions" to (events.maxOf { it.session } + 1),
            "forgetOnRestart" to forget,
            "runs" to runs,
            "table" to table,
            "ms" to (System.nanoTime() - started) / 1_000_000,
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)
    private fun mbText(b: Long) = String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
}

/** T112: the replay itself, pure so it is unit-tested (`CacheReplayTest`). */
object CacheReplay {
    const val MB = 1024L * 1024

    /** The phone's budget kinds ([floorShare] as on the phone, `androidApp/.../cachebudget/BudgetPlan.kt`). */
    enum class Kind(val key: String, val derived: Boolean, val floorShare: Double) {
        ONLINE("online", false, 0.25),
        DERIVED("derived", true, 0.15),
        SOURCES("sources", false, 0.05),
    }

    enum class Rule(val key: String) { LRU("lru"), GDSF("gdsf"), LRU_GLOBAL("lru-global") }

    /** One tile answer of the log: when, of which kind, its key (the path) and its size; [session] counts page loads before it. */
    data class Event(val atMs: Long, val kind: Kind, val key: String, val bytes: Long, val session: Int = 0)

    class Parsed(val events: List<Event>, val lines: Int, val requests: Int, val skipped: Map<String, Int>)

    /** The phone's kind of a server path, or null for what the phone does not keep in the budget's caches. */
    fun kindOf(path: String): Kind? {
        val route = path.trimStart('/').substringBefore('/').substringBefore('?')
        return when (route) {
            "dem", "osm", "glyphs" -> Kind.ONLINE
            "contours", "shade" -> Kind.DERIVED
            else -> null
        }
    }

    fun parse(lines: Sequence<String>): Parsed {
        val events = ArrayList<Event>()
        val skipped = LinkedHashMap<String, Int>()
        var n = 0
        var requests = 0
        var session = 0
        fun skip(why: String) { skipped[why] = (skipped[why] ?: 0) + 1 }
        for (line in lines) {
            if (line.isBlank()) continue
            n++
            val o = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: run { skip("not JSON"); null } ?: continue
            if ((o["event"] as? JsonPrimitive)?.content != "request") continue
            requests++
            val path = (o["path"] as? JsonPrimitive)?.content ?: run { skip("no path"); null } ?: continue
            if ((path == "/" || path.startsWith("/?")) && events.isNotEmpty()) session++ // a page load: a new viewing session
            val kind = kindOf(path) ?: run { skip("not a tile kind the phone caches"); null } ?: continue
            if ((o["method"] as? JsonPrimitive)?.content != "GET") { skip("not GET"); continue }
            if ((o["status"] as? JsonPrimitive)?.content != "200") { skip("status not 200"); continue }
            val bytes = (o["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L
            if (bytes <= 0) { skip("no body"); continue }
            val at = (o["ts"] as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: run { skip("no ts"); null } ?: continue
            events += Event(at, kind, path, bytes, session)
        }
        events.sortBy { it.atMs }
        return Parsed(events, n, requests, skipped)
    }

    /** [events] [times] in a row, each copy an hour after the last ends (the same viewing again the next day). */
    fun repeat(events: List<Event>, times: Int): List<Event> {
        require(times >= 1) { "--repeat must be at least 1" }
        if (times == 1) return events
        val span = events.last().atMs - events.first().atMs + 3_600_000L
        val sessions = events.maxOf { it.session } + 1
        return (0 until times).flatMap { i -> events.map { it.copy(atMs = it.atMs + i * span, session = it.session + i * sessions) } }
    }

    fun parseFloors(spec: String): Map<Kind, Double> {
        val out = Kind.entries.associateWith { 0.0 }.toMutableMap()
        for (part in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val (k, v) = part.split('=').takeIf { it.size == 2 } ?: throw UsageError("--floors takes kind=share, e.g. online=0.25 (got '$part')")
            val kind = Kind.entries.firstOrNull { it.key == k.trim() } ?: throw UsageError("unknown kind '$k' (online, derived, sources)")
            out[kind] = v.trim().toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: throw UsageError("a floor share is 0 to 1 (got '$v')")
        }
        if (out.values.sum() > 1.0) throw UsageError("the floors add up to more than the total")
        return out
    }

    /** Distinct tiles and their bytes per kind: what a cache large enough for everything would hold. */
    fun workingSet(events: List<Event>): Map<String, Any?> = Kind.entries.associate { k ->
        val mine = events.filter { it.kind == k }
        val unique = mine.associateBy { it.key }
        k.key to linkedMapOf(
            "requests" to mine.size, "tiles" to unique.size, "bytes" to unique.values.sumOf { it.bytes },
            "maxHitRate" to if (mine.isEmpty()) null else 1.0 - unique.size.toDouble() / mine.size,
        )
    }

    /** The phone's slack: what a run frees below the total (`BudgetPlan.slackBytes`). */
    fun slackBytes(total: Long): Long = maxOf(total / 100, 4 * MB).coerceAtMost(total / 4)

    class KindStats {
        var requests = 0L
        var hits = 0L
        var bytesRequested = 0L
        var bytesHit = 0L
        var evictions = 0L
        var bytesEvicted = 0L
        val hitRate get() = if (requests == 0L) 0.0 else hits.toDouble() / requests
        val byteHitRate get() = if (bytesRequested == 0L) 0.0 else bytesHit.toDouble() / bytesRequested

        fun json(): Map<String, Any?> = linkedMapOf(
            "requests" to requests, "hits" to hits, "hitRate" to round(hitRate), "bytesRequested" to bytesRequested,
            "bytesHit" to bytesHit, "byteHitRate" to round(byteHitRate), "missBytes" to bytesRequested - bytesHit,
            "evictions" to evictions, "bytesEvicted" to bytesEvicted,
        )

        private fun round(v: Double) = Math.round(v * 10000) / 10000.0
    }

    class Result(
        val rule: Rule, val totalBytes: Long, val perKind: Map<Kind, KindStats>, val all: KindStats,
        val runs: Int, val stuckRuns: Int, val peakBytes: Long, val endBytes: Map<Kind, Long>,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "rule" to rule.key, "totalBytes" to totalBytes,
            "all" to all.json(), "perKind" to perKind.mapKeys { it.key.key }.mapValues { it.value.json() },
            "budgetRuns" to runs, "stuckRuns" to stuckRuns, "peakBytes" to peakBytes, "endBytes" to endBytes.mapKeys { it.key.key },
        )
    }

    private class Entry(val key: String, val kind: Kind, val bytes: Long, var usedAt: Long, var seq: Long, var hits: Int, var score: Double)

    /** Replays [events] through a cache of [totalBytes] with [rule] within each kind and the phone's rule across kinds. */
    fun run(
        events: List<Event>, totalBytes: Long, rule: Rule, floors: Map<Kind, Double> = Kind.entries.associateWith { it.floorShare },
        protectMs: Long = 5_000, forgetOnRestart: Boolean = false,
    ): Result {
        val stats = Kind.entries.associateWith { KindStats() }
        val all = KindStats()
        val entries = HashMap<String, Entry>()
        // Per kind in least recently used order (LRU), and for gdsf the inflation L of each kind.
        val lru = Kind.entries.associateWith { LinkedHashMap<String, Entry>(256, 0.75f, true) }
        val global = LinkedHashMap<String, Entry>(256, 0.75f, true)
        val used = Kind.entries.associateWith { 0L }.toMutableMap()
        val inflation = Kind.entries.associateWith { 0.0 }.toMutableMap()
        var seq = 0L
        var runs = 0
        var stuck = 0
        var peak = 0L
        fun score(e: Entry) = inflation.getValue(e.kind) + e.hits / (e.bytes / 1024.0).coerceAtLeast(0.25)
        fun floor(k: Kind) = (totalBytes * (floors[k] ?: 0.0)).toLong()
        fun remove(e: Entry) {
            entries.remove(e.key)
            lru.getValue(e.kind).remove(e.key)
            global.remove(e.key)
            used[e.kind] = used.getValue(e.kind) - e.bytes
            stats.getValue(e.kind).let { it.evictions++; it.bytesEvicted += e.bytes }
            all.evictions++
            all.bytesEvicted += e.bytes
        }
        /** Evicts up to [bytes] of [kind] by the rule within the kind, sparing what was used since [protectFrom]; returns bytes freed. */
        fun evictKind(kind: Kind, bytes: Long, protectFrom: Long): Long {
            var freed = 0L
            val candidates = lru.getValue(kind).values.filter { it.usedAt < protectFrom }
            val order = if (rule == Rule.GDSF) candidates.sortedWith(compareBy<Entry>({ it.score }, { it.seq })) else candidates
            for (e in order) {
                if (freed >= bytes) break
                if (rule == Rule.GDSF) inflation[kind] = e.score
                freed += e.bytes
                remove(e)
            }
            return freed
        }
        fun enforce(now: Long) {
            val sum = used.values.sum()
            if (sum <= totalBytes) return
            runs++
            val target = totalBytes - slackBytes(totalBytes)
            val protectFrom = now - protectMs
            if (rule == Rule.LRU_GLOBAL) {
                var over = sum - target
                for (e in global.values.filter { it.usedAt < protectFrom }) {
                    if (over <= 0) break
                    over -= e.bytes
                    remove(e)
                }
                if (over > 0) stuck++
                return
            }
            val exhausted = HashSet<Kind>()
            while (true) {
                val over = used.values.sum() - target
                if (over <= 0) return
                fun above(k: Kind) = used.getValue(k) - floor(k)
                val k = listOf(true, false).firstNotNullOfOrNull { derived ->
                    Kind.entries.filter { it.derived == derived && it !in exhausted && above(it) > 0 }.maxByOrNull { above(it) }
                } ?: run { stuck++; return }
                if (evictKind(k, minOf(over, above(k)), protectFrom) <= 0) exhausted += k
            }
        }
        var session = events.firstOrNull()?.session ?: 0
        for (ev in events) {
            seq++
            if (forgetOnRestart && ev.session != session) {
                // An app restart that kept no counts: every entry is as if used once, the inflation starts again.
                for (k in Kind.entries) inflation[k] = 0.0
                for (e in entries.values) { e.hits = 1; e.score = score(e) }
            }
            session = ev.session
            val s = stats.getValue(ev.kind)
            s.requests++; s.bytesRequested += ev.bytes
            all.requests++; all.bytesRequested += ev.bytes
            val e = entries[ev.key]
            if (e != null) {
                s.hits++; s.bytesHit += ev.bytes
                all.hits++; all.bytesHit += ev.bytes
                e.hits++
                e.usedAt = ev.atMs
                e.seq = seq
                e.score = score(e)
                lru.getValue(e.kind)[e.key] // access order
                global[e.key]
                continue
            }
            val n = Entry(ev.key, ev.kind, ev.bytes, ev.atMs, seq, 1, 0.0)
            n.score = score(n)
            entries[ev.key] = n
            lru.getValue(ev.kind)[ev.key] = n
            global[ev.key] = n
            used[ev.kind] = used.getValue(ev.kind) + ev.bytes
            peak = maxOf(peak, used.values.sum())
            enforce(ev.atMs)
        }
        return Result(rule, totalBytes, stats, all, runs, stuck, peak, used.toMap())
    }
}
