package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.UsageError
import io.github.jasondorjeshort.dystopo.aircraft.AdsbLolSource
import io.github.jasondorjeshort.dystopo.aircraft.AircraftFetch
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttp
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttpReply
import io.github.jasondorjeshort.dystopo.aircraft.AircraftPoll
import io.github.jasondorjeshort.dystopo.aircraft.AircraftQuery
import io.github.jasondorjeshort.dystopo.aircraft.AircraftRules
import io.github.jasondorjeshort.dystopo.aircraft.AircraftTracker
import io.github.jasondorjeshort.dystopo.aircraft.FlightPhase
import io.github.jasondorjeshort.dystopo.aircraft.GuessError
import io.github.jasondorjeshort.dystopo.aircraft.ReadsbParser
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.server.Caches
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * T365: the developer's count of requests to the live flights feed per UTC day, on disk beside the cache (so every
 * worktree and the main checkout share one count): `{"utcDay": "2026-11-25", "requests": 4}`. The feed allows 500 a UTC
 * day for one address, shared with the phones and emulators behind it, so the devtool keeps itself to [limit]. A day that
 * is not today reads as 0 (the roll). Each change is made under a file lock, so two runs at once count both.
 */
class RequestDay(val file: File, val limit: Int = DAY_LIMIT) {
    data class Count(val utcDay: String, val requests: Int)

    /** Today's count at [nowMs]. */
    fun read(nowMs: Long): Count = locked { readNow(nowMs) }

    /** Throws when [planned] more requests would take today past [limit]; else today's count. */
    fun check(nowMs: Long, planned: Int): Count {
        val c = read(nowMs)
        if (c.requests + planned > limit) {
            throw UsageError("refused: ${c.utcDay} (UTC) has ${c.requests} feed requests counted in $file; $planned more would make ${c.requests + planned}, past the devtool's $limit a day (the feed allows 500 for this address, shared with the phones). Run fewer polls, or after 00:00 UTC.")
        }
        return c
    }

    /** Counts one request at [nowMs], or returns null (nothing counted) when today already has [limit]. */
    fun spend(nowMs: Long): Count? = locked {
        val c = readNow(nowMs)
        if (c.requests >= limit) null else Count(c.utcDay, c.requests + 1).also { write(it) }
    }

    private fun readNow(nowMs: Long): Count {
        val today = utcDay(nowMs)
        val text = if (file.isFile) file.readText() else return Count(today, 0)
        val m = runCatching { MiniJson.parseObject(text) }.getOrNull() ?: return Count(today, 0)
        val day = m["utcDay"] as? String
        val n = (m["requests"] as? Number)?.toInt() ?: 0
        return if (day == today) Count(today, n) else Count(today, 0)
    }

    private fun write(c: Count) {
        file.writeText(JsonWriter.write(linkedMapOf("utcDay" to c.utcDay, "requests" to c.requests, "limit" to limit,
            "what" to "devtool requests to the live flights feed this UTC day (T365)")) + "\n")
    }

    private fun <T> locked(f: () -> T): T {
        file.parentFile?.mkdirs()
        RandomAccessFile(File(file.path + ".lock"), "rw").use { raf -> raf.channel.lock().use { return f() } }
    }

    fun json(nowMs: Long): Map<String, Any?> = read(nowMs).let { linkedMapOf("utcDay" to it.utcDay, "requests" to it.requests, "limit" to limit, "file" to file.path) }

    companion object {
        const val DAY_LIMIT = 240

        fun utcDay(ms: Long): String = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

        /** The shared count: `<cache>/aircraft/feed-requests-per-utc-day.json` (`DYSTOPO_CACHE` or `cache/`). */
        fun shared(): RequestDay = RequestDay(File(Caches.root, "aircraft/feed-requests-per-utc-day.json"))
    }
}

/**
 * T365: a recorded folder of polls. `record.json` (the plan, rewritten with the outcome at the end), `index.jsonl` (one
 * line a poll: its number, time, outcome, status, aircraft and bytes; it grows while the run goes on), and
 * `poll-<epoch ms>.json`, each answer as the feed gave it (readsb JSON), named by the time it was received. Kept in
 * `out/` only: never committed or published (the feed is ODbL; `sources.json` `adsblol-live`).
 */
object FlightFolder {
    const val RECORD = "record.json"
    const val INDEX = "index.jsonl"

    fun pollFile(folder: File, ms: Long) = File(folder, "poll-$ms.json")

    fun appendIndex(folder: File, line: Map<String, Any?>) = File(folder, INDEX).appendText(JsonWriter.write(line) + "\n")

    fun writeRecord(folder: File, record: Map<String, Any?>) = File(folder, RECORD).writeText(JsonWriter.write(record) + "\n")

    /** A folder read back: the answers in time order (raw text kept for the field counts), and the index's lines. */
    class Loaded(val polls: List<AircraftPoll>, val raws: List<String>, val index: List<Map<String, Any?>>, val record: Map<String, Any?>?) {
        /** The recording's interval: the record's, else the median gap between answers. */
        val intervalS: Double
            get() = (record?.get("intervalS") as? Number)?.toDouble()
                ?: polls.zipWithNext { a, b -> (b.atMs - a.atMs) / 1000.0 }.sorted().let { if (it.isEmpty()) 30.0 else it[it.size / 2] }
    }

    fun load(folder: File): Loaded {
        if (!folder.isDirectory) throw UsageError("$folder is not a folder of recorded polls")
        val files = folder.listFiles { f -> f.name.startsWith("poll-") && f.name.endsWith(".json") }!!
            .mapNotNull { f -> f.name.removePrefix("poll-").removeSuffix(".json").toLongOrNull()?.let { it to f } }
            .sortedBy { it.first }
        val raws = files.map { it.second.readText() }
        val polls = files.zip(raws).map { (p, text) -> ReadsbParser.parse(text, p.first) }
        val index = File(folder, INDEX).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.map { MiniJson.parseObject(it) }.orEmpty()
        val record = File(folder, RECORD).takeIf { it.isFile }?.let { runCatching { MiniJson.parseObject(it.readText()) }.getOrNull() }
        return Loaded(polls, raws, index, record)
    }
}

/**
 * T365: `aircraft --record`: polls the live feed every [intervalS] for [polls] polls around [query] and saves each answer
 * into [folder] ([FlightFolder]). Every request is counted in [day] before it is sent; the run is refused at the start
 * when it would take the UTC day past the limit, and stops if the day's count reaches it meanwhile (another run). A failed,
 * offline or empty poll is recorded as such and the run goes on; a 429 pauses for its Retry-After (no Retry-After: the
 * run stops). [clock], [sleep] and [http] are passed in so a test can run it without the network.
 */
class FlightRecorder(
    val folder: File,
    val query: AircraftQuery,
    val intervalS: Int,
    val polls: Int,
    val area: String?,
    val day: RequestDay,
    private val http: AircraftHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
) {
    fun plan(nowMs: Long): Map<String, Any?> {
        val c = day.read(nowMs)
        return linkedMapOf(
            "area" to area, "query" to query.json(), "intervalS" to intervalS, "polls" to polls,
            "minutes" to Math.round(polls * intervalS / 6.0) / 10.0, "requests" to polls,
            "utcDay" to c.utcDay, "dayCountBefore" to c.requests, "dayCountAfter" to c.requests + polls, "dayLimit" to day.limit,
            "allowed" to (c.requests + polls <= day.limit), "folder" to folder.path,
        )
    }

    fun run(): Map<String, Any?> {
        val t0 = clock()
        day.check(t0, polls)
        val plan = plan(t0)
        log("aircraft --record: ${JsonWriter.write(plan)}")
        folder.mkdirs()
        FlightFolder.writeRecord(folder, plan + ("startedMs" to t0) + ("startedUtc" to iso(t0)) + ("running" to true))
        var body: ByteArray? = null
        var status: Int? = null
        val capture = AircraftHttp { url, headers -> http.get(url, headers).also { status = it.status; body = it.body } }
        val src = AdsbLolSource(capture, "devtool", clock)
        var made = 0; var ok = 0; var empty = 0; var failed = 0; var limited = 0; var skipped = 0
        var stoppedBy: String? = null
        var pauseUntil = 0L
        val seen = HashSet<String>()
        var fixes = 0L
        for (i in 0 until polls) {
            val due = t0 + i * intervalS * 1000L
            val wait = due - clock()
            if (wait > 0) sleep(wait)
            val now = clock()
            if (now < pauseUntil) {
                skipped++
                FlightFolder.appendIndex(folder, linkedMapOf("i" to i, "ms" to now, "outcome" to "skipped", "reason" to "429 pause"))
                continue
            }
            val counted = day.spend(now)
            if (counted == null) {
                stoppedBy = "dayLimit"
                FlightFolder.appendIndex(folder, linkedMapOf("i" to i, "ms" to now, "outcome" to "stopped", "reason" to "the day's count reached ${day.limit}"))
                break
            }
            made++
            body = null; status = null
            val f = src.poll(query, now)
            val line = linkedMapOf<String, Any?>("i" to i, "ms" to now, "dayCount" to counted.requests)
            when (f) {
                is AircraftFetch.Ok -> {
                    ok++
                    val n = f.poll.aircraft.size
                    if (n == 0) empty++
                    f.poll.aircraft.forEach { seen += it.hex }
                    fixes += n
                    body?.let { FlightFolder.pollFile(folder, f.poll.atMs).writeBytes(it) }
                    line += linkedMapOf("outcome" to if (n == 0) "empty" else "ok", "status" to status, "receivedMs" to f.poll.atMs,
                        "aircraft" to n, "bytes" to f.poll.bytes, "responseMs" to f.ms)
                }
                is AircraftFetch.Limited -> {
                    limited++
                    line += linkedMapOf("outcome" to "limited", "status" to 429, "retryAfterS" to f.retryAfterS)
                    if (f.retryAfterS == null) stoppedBy = "429" else pauseUntil = clock() + f.retryAfterS!! * 1000
                }
                is AircraftFetch.Failed -> { failed++; line += linkedMapOf("outcome" to "failed", "status" to f.status, "reason" to f.reason) }
                is AircraftFetch.Offline -> { failed++; line += linkedMapOf("outcome" to "offline", "reason" to f.reason) }
            }
            FlightFolder.appendIndex(folder, line)
            log("poll ${i + 1}/$polls ${line["outcome"]} aircraft=${line["aircraft"] ?: 0} day=${counted.requests}")
            if (stoppedBy != null) break
        }
        val end = clock()
        val out = linkedMapOf<String, Any?>(
            "folder" to folder.path, "area" to area, "query" to query.json(), "intervalS" to intervalS,
            "planned" to polls, "requests" to made, "ok" to ok, "empty" to empty, "failed" to failed, "limited" to limited, "skipped" to skipped,
            "aircraftSeen" to seen.size, "aircraftEntries" to fixes, "stoppedBy" to stoppedBy, "endedWell" to (stoppedBy == null && made == polls),
            "utcDay" to RequestDay.utcDay(end), "dayCount" to day.read(end).requests, "dayLimit" to day.limit,
            "startedUtc" to iso(t0), "endedUtc" to iso(end), "minutes" to Math.round((end - t0) / 6000.0) / 10.0,
            "kept" to "out/ only, never committed (ODbL feed data; sources.json adsblol-live)",
        )
        FlightFolder.writeRecord(folder, plan + ("startedMs" to t0) + ("startedUtc" to iso(t0)) + ("running" to false) + out)
        return out
    }

    companion object {
        private val NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm'Z'").withZone(ZoneOffset.UTC)

        fun iso(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

        /** `out/flights/<area>-<UTC start, yyyyMMdd-HHmmZ>`. */
        fun defaultFolder(area: String, nowMs: Long): File = File("out/flights/$area-${NAME.format(Instant.ofEpochMilli(nowMs))}")
    }
}

/**
 * T365: the report over a recorded folder: the tracker's guess against the real fix at each horizon ([GuessError]),
 * binned by phase, as metres and as the angle from an observer 10 and 50 km away; the tracker fed every k-th answer (what
 * slower polling would have drawn); and how often the feed sends its turn-rate (`track_rate`) and roll fields. Counts
 * and error figures only: no aircraft is named.
 */
object FlightReport {
    val HORIZONS_S = listOf(30.0, 120.0, 300.0, 600.0)
    val OBSERVERS_M = listOf(10_000.0, 50_000.0)

    /** A cell with fewer aircraft or fixes than these is marked thin in the report (one aircraft's turn can make its p90). */
    const val THIN_AIRCRAFT = 10
    const val THIN_FIXES = 50

    private fun r1(v: Double) = if (v.isFinite()) Math.round(v * 10) / 10.0 else null
    private fun r2(v: Double) = if (v.isFinite()) Math.round(v * 100) / 100.0 else null

    fun statsJson(samples: List<GuessError.Sample>): Map<String, Any?> {
        val s = GuessError.stats(samples)
        val out = linkedMapOf<String, Any?>("aircraft" to s.aircraft, "fixes" to s.n, "medianM" to s.medianM.roundOrNull(), "p90M" to s.p90M.roundOrNull(), "p99M" to s.p99M.roundOrNull())
        for (o in OBSERVERS_M) out["deg${(o / 1000).toInt()}km"] = listOf(s.medianM, s.p90M, s.p99M).map { r2(GuessError.angleDeg(it, o)) }
        when {
            s.n == 0 -> out["note"] = "empty: no fixes in this cell"
            s.aircraft < THIN_AIRCRAFT || s.n < THIN_FIXES -> out["note"] = "thin: fewer than $THIN_AIRCRAFT aircraft or $THIN_FIXES fixes, proves little"
        }
        return out
    }

    private fun Double.roundOrNull(): Long? = if (isFinite()) roundToLong() else null

    private fun binned(samples: List<GuessError.Sample>): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("all" to statsJson(samples))
        for (p in FlightPhase.entries) out[p.json] = statsJson(samples.filter { it.phase == p })
        return out
    }

    /** Each horizon's row: the guess from every fix to the real fix nearest that horizon (within [toleranceS]). */
    fun horizons(polls: List<AircraftPoll>, horizonsS: List<Double> = HORIZONS_S, toleranceS: Double): List<Map<String, Any?>> {
        val tracks = GuessError.tracks(polls)
        return horizonsS.map { h ->
            val s = GuessError.samples(tracks, h, toleranceS)
            linkedMapOf("horizonS" to h, "capS" to h + toleranceS, "medianAgeS" to r1(GuessError.quantile(s.map { it.ageS }.sorted(), 0.5))) + binned(s)
        }
    }

    /**
     * The tracker fed every [k]-th answer (with its refresh set to k intervals: its own move cap, stale and drop times),
     * and at each real fix that arrives in between (and at a fed answer, just before it is fed), what it draws for that
     * aircraft compared with the fix: the error, how many fixes it showed nothing for (not seen yet, or dropped), and how
     * many it showed hollow.
     */
    fun everyK(polls: List<AircraftPoll>, k: Int, intervalS: Double): Map<String, Any?> {
        val refresh = Math.round(k * intervalS).toInt().coerceAtLeast(1)
        val tracker = AircraftTracker()
        val lastAt = HashMap<String, Long>()
        val lastPos = HashMap<String, Pair<Double, Double>>()
        val samples = ArrayList<GuessError.Sample>()
        var compared = 0; var missing = 0; var stale = 0; var fed = 0
        var lastFedMs = Long.MIN_VALUE
        for ((i, p) in polls.withIndex()) {
            val fresh = p.aircraft.mapNotNull { r ->
                val at = p.atMs - (r.seenPosS * 1000).roundToLong()
                val isNew = at > (lastAt[r.hex] ?: Long.MIN_VALUE) && lastPos[r.hex] != (r.lat to r.lon)
                if (isNew) { lastAt[r.hex] = at; lastPos[r.hex] = r.lat to r.lon }
                if (isNew && fed > 0 && at >= lastFedMs) at to r else null
            }.sortedBy { it.first }
            for ((at, r) in fresh) {
                compared++
                val drawn = tracker.view(at, null, null, refreshS = refresh).firstOrNull { it.hex == r.hex }
                if (drawn == null) { missing++; continue }
                if (drawn.stale) stale++
                samples += GuessError.Sample(r.hex, FlightPhase.of(drawn.report), io.github.jasondorjeshort.dystopo.aircraft.AircraftGeo.distanceM(drawn.lat, drawn.lon, r.lat, r.lon), drawn.ageS)
            }
            if (i % k == 0) {
                tracker.onFetch(AircraftFetch.Ok(p, 0, null), p.atMs, null, refresh)
                fed++
                lastFedMs = p.atMs
            }
        }
        return linkedMapOf<String, Any?>(
            "k" to k, "refreshS" to refresh, "moveCapS" to AircraftRules.moveCapS(refresh), "staleS" to AircraftRules.staleS(refresh),
            "dropS" to AircraftRules.dropS(refresh), "fed" to fed, "compared" to compared, "drawn" to samples.size,
            "missing" to missing, "drawnHollow" to stale, "medianAgeS" to r1(GuessError.quantile(samples.map { it.ageS }.sorted(), 0.5)),
        ) + binned(samples)
    }

    /**
     * How many aircraft entries (with a position) carry `track`, `true_heading`, `track_rate` and `roll`, all and airborne
     * only (`alt_baro` not `ground`): the tracker moves a mark along `track` (else `true_heading`); the other two are what a
     * turning guess would need.
     */
    fun fields(raws: List<String>): Map<String, Any?> {
        val keys = linkedMapOf("track" to "track", "trueHeading" to "true_heading", "trackRate" to "track_rate", "roll" to "roll")
        val all = IntArray(keys.size); val air = IntArray(keys.size)
        var entries = 0; var airborne = 0
        for (t in raws) {
            val list = MiniJson.parseObject(t)["ac"] as? List<*> ?: continue
            for (e in list) {
                val m = e as? Map<*, *> ?: continue
                if (m["lat"] !is Number) continue
                entries++
                val up = m["alt_baro"] != "ground"
                if (up) airborne++
                for ((i, k) in keys.values.withIndex()) if (m[k] is Number) { all[i]++; if (up) air[i]++ }
            }
        }
        fun pct(n: Int, of: Int) = if (of == 0) null else Math.round(n * 1000.0 / of) / 10.0
        val out = linkedMapOf<String, Any?>("entries" to entries)
        for ((i, name) in keys.keys.withIndex()) { out[name] = all[i]; out[name + "Pct"] = pct(all[i], entries) }
        val up = linkedMapOf<String, Any?>("entries" to airborne)
        for ((i, name) in keys.keys.withIndex()) { up[name] = air[i]; up[name + "Pct"] = pct(air[i], airborne) }
        out["airborne"] = up
        return out
    }

    /** The whole report over [folder]: horizons (unless [ks] alone is wanted), every-k-th runs for [ks], the field counts. */
    fun report(folder: File, toleranceS: Double?, ks: List<Int>, horizons: Boolean = true): Map<String, Any?> {
        val f = FlightFolder.load(folder)
        val tol = toleranceS ?: (f.intervalS / 2)
        val outcomes = f.index.groupingBy { it["outcome"] as? String ?: "?" }.eachCount()
        val polls = f.polls
        val tracks = GuessError.tracks(polls)
        return linkedMapOf(
            "folder" to folder.path, "area" to f.record?.get("area"), "intervalS" to f.intervalS, "answers" to polls.size,
            "outcomes" to outcomes.ifEmpty { null }, "requests" to f.index.count { it["outcome"] != "skipped" && it["outcome"] != "stopped" }.takeIf { f.index.isNotEmpty() },
            "spanMin" to if (polls.size > 1) Math.round((polls.last().atMs - polls.first().atMs) / 6000.0) / 10.0 else 0.0,
            "aircraft" to tracks.size, "fixes" to tracks.values.sumOf { it.size }, "toleranceS" to tol,
            "horizons" to if (horizons) horizons(polls, HORIZONS_S, tol) else null,
            "everyK" to ks.map { everyK(polls, it, f.intervalS) }.ifEmpty { null },
            "fields" to fields(f.raws),
            "kept" to "counts and error figures only; no aircraft is named",
        )
    }
}

/** T365: the devtool's GET for the feed (no key, the generic User-Agent [AdsbLolSource] sets). */
internal val devtoolAircraftHttp = AircraftHttp { url, headers ->
    val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 5_000
    c.readTimeout = 20_000
    for ((k, v) in headers) c.setRequestProperty(k, v)
    try {
        c.connect()
    } catch (e: java.io.IOException) {
        throw io.github.jasondorjeshort.dystopo.aircraft.AircraftNoConnection("${e.javaClass.simpleName}: ${e.message}")
    }
    val body = if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else ByteArray(0)
    AircraftHttpReply(c.responseCode, body, c.getHeaderField("Retry-After"))
}
