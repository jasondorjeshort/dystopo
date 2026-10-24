package io.github.jasondorjeshort.dystopo.app.areas

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.routing.UiPublish
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TripCorridor
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * T130 (the owner's answer of 2026-10-23 to the auto-download idea): every trip keeps a corridor offline, [widthM] each side
 * (the `tripCorridorM` setting, 1 km by default, 0 off; set on the Offline areas sheet or `POST /settings?tripCorridorM=`),
 * at the mode's zooms (z8 to z13 for a drive, z8 to z15 otherwise). It is T75's corridor download unchanged: an
 * [Areas.Record] linked to the trip (`tripId`), fetched by [AreaDownload] into the tile cache and pinned, so it counts
 * against the offline areas' budget (`pinnedCacheMb`, ADR 0030) and is refused, visibly, when it would pass it. A trip
 * is a route in the trip editor ([Trips]): made, kept from the route card, imported, edited or synced; the route card's
 * unsaved plan keeps T75's ahead prefetch only.
 *
 * [reconcile] makes what is on the phone match the trips, one at a time on its own thread:
 * - a trip with no corridor at the current width and shape gets one (planned first: over the budget, nothing is
 *   recorded and the trip card says why; no online source, the same; ADR 0023: no Wi-Fi condition, the visible budget
 *   is the limit);
 * - a trip edited, or the width changed, gets its new corridor; the old one is unpinned first (its tiles stay in the
 *   cache, so the new download finds the shared ones there) and removed once the new one is done;
 * - a trip deleted releases its corridor at once ([release]);
 * - a trip the user stopped keeping (the trip card's Stop keeping, or Unpin or Remove on its area in Offline areas) is
 *   left alone until Keep offline or Pin ([notKept], kept in `files/areas/trip-corridors.json`);
 * - width 0 (off) makes no new corridor and leaves the ones there (Remove in Offline areas frees them).
 *
 * The benchmark survey of 2026-10-23 (ADR 0039; the PR cites it) found no established app that keeps a corridor without
 * being asked, and none with a designed budget message; the card's line, Stop keeping and the refusal follow its
 * recommendation, the release on delete follows the task (the survey suggested asking).
 */
object TripCorridors {
    private const val TAG = "DystopoCorridors"
    private val app: DystopoApp get() = DystopoApp.instance
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val ui by lazy { UiPublish({ Looper.myLooper() == Looper.getMainLooper() }, { main.post(it) }) }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-corridors").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    private val runs = AtomicInteger()
    private val file: File get() = File(File(app.filesDir, "areas").apply { mkdirs() }, "trip-corridors.json")

    /** Trips the user stopped keeping offline (Compose state, written on the main thread). */
    var notKept by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Why a trip has no corridor (over the budget, no source), by trip id (Compose state). */
    var notes by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Trips waiting for their corridor's download behind another one, and the one downloading now (Compose state). */
    var queued by mutableStateOf<Set<String>>(emptySet())
        private set

    /** A refusal by the budget: the area refused, with the budget and the pinned bytes then (tried again when either drops or the area changes). */
    private data class Refusal(val areaId: String, val budgetMb: Long, val pinnedBytes: Long)
    private val refusals = java.util.concurrent.ConcurrentHashMap<String, Refusal>()

    @Volatile private var loaded = false
    @Volatile var lastRun: Map<String, Any?>? = null
        private set

    fun widthM(): Double = AppSettings.tripCorridorM.toDouble().takeIf { it > 0 } ?: TripCorridor.DEFAULT_WIDTH_M.toDouble()

    /** The corridor of [p] at [widthM] (zooms from its mode unless given). */
    fun areaOf(p: RoutePlan, widthM: Double, minZoom: Int? = null, maxZoom: Int? = null, name: String? = null): AreaOfInterest {
        val pts = p.path.points.map { AreaPoint(it.lat, it.lon) }
        require(pts.isNotEmpty()) { "route ${p.id} has no points" }
        return TripCorridor.area(pts, p.type ?: p.path.mode, widthM, name ?: p.name.ifBlank { "Route" }, minZoom, maxZoom)
    }

    // ---- the setting ----

    /** `POST /settings?tripCorridorM=`: validates, saves and applies the width; the error text, or null. */
    fun setWidth(raw: Any?): String? {
        val v = (raw as? Number)?.toDouble() ?: raw?.toString()?.trim()?.toDoubleOrNull()
        val m = v?.takeIf { it.isFinite() }?.let { Math.round(it).toInt() }
        if (m == null || !TripCorridor.validWidth(m)) return "tripCorridorM must be 0 (off) or ${TripCorridor.WIDTH_RANGE.first} to ${TripCorridor.WIDTH_RANGE.last} m (got $raw)"
        if (m != AppSettings.tripCorridorM) {
            AppSettings.setTripCorridorM(m)
            request("width $m")
        }
        return null
    }

    // ---- the user's choices ----

    /** The trip card's Keep offline (true) or Stop keeping (false); Stop keeping removes the corridor and frees its share of the budget. */
    fun keep(tripId: String, on: Boolean) {
        load()
        setNotKept(tripId, !on)
        if (on) {
            refusals.remove(tripId)
            setNote(tripId, null)
            if (AppSettings.tripCorridorM > 0) request("keep $tripId")
            else worker.execute { // the setting is off: this trip alone, at the default width
                runCatching { Trips.plan(tripId)?.let { p -> setQueued(p.id, true); make(p, areaOf(p, widthM())); setQueued(p.id, false) } }.onFailure { Log.w(TAG, "keep $tripId", it) }
            }
        } else {
            for (r in Areas.all().filter { it.tripId == tripId }) runCatching { Areas.remove(r.id) }
            setQueued(tripId, false)
        }
    }

    /** The trip's Offline download by hand (T95's offer): the trip is kept again, with no run of its own (the download is the corridor). */
    fun optIn(tripId: String) {
        load()
        setNotKept(tripId, false)
        refusals.remove(tripId)
        setNote(tripId, null)
    }

    /** Pin or Unpin on an area card (`POST /areas/{id}/pin`): on a trip's corridor it is also Keep offline or Stop keeping for that trip. */
    fun userPin(id: String, on: Boolean): Areas.Record {
        val r = Areas.pin(id, on)
        r.tripId?.let { load(); setNotKept(it, !on); if (on) request("pin") }
        return r
    }

    /** Remove on an area card (`DELETE /areas/{id}`): a trip's corridor removed by hand is not made again until Keep offline. */
    fun userRemove(id: String): Map<String, Any?> {
        val r = Areas.get(id)
        val out = Areas.remove(id)
        r?.tripId?.let { load(); setNotKept(it, true) }
        return out
    }

    /** Trip [tripId] was deleted: its corridors go now (a running download of one is cancelled first). */
    fun release(tripId: String): Int {
        load()
        var n = 0
        for (r in Areas.all().filter { it.tripId == tripId }) {
            runCatching { Areas.remove(r.id) }.onSuccess { n++ }
        }
        refusals.remove(tripId)
        setNote(tripId, null)
        setQueued(tripId, false)
        if (tripId in notKept) setNotKept(tripId, false)
        return n
    }

    // ---- reconciling ----

    /** Asks for a [reconcile] on the corridors' thread (several asks while one waits make one run). */
    fun request(why: String) {
        if (pending.compareAndSet(false, true)) worker.execute {
            pending.set(false)
            runCatching { reconcile(why) }.onFailure { Log.w(TAG, "trip corridors ($why)", it) }
        }
    }

    /** Waits until every run asked for so far has finished (debug routes), at most [timeoutMs]. */
    fun drain(timeoutMs: Long) {
        runCatching { worker.submit { }.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /** See the class comment. Runs on the corridors' thread; returns what it did (also `lastRun`). */
    fun reconcile(why: String): Map<String, Any?> {
        load()
        val t0 = System.currentTimeMillis()
        val plans = Trips.store { it.routePlans() }.filter { it.path.points.isNotEmpty() }
        val ids = plans.map { it.id }.toSet()
        var released = 0
        for (r in Areas.all().filter { it.tripId != null && it.tripId !in ids }) runCatching { Areas.remove(r.id) }.onSuccess { released++ }
        val width = AppSettings.tripCorridorM
        val made = ArrayList<String>()
        val refused = ArrayList<String>()
        var current = 0
        if (width > 0) {
            val wants = plans.filter { it.id !in notKept }.map { it to areaOf(it, width.toDouble()) }
            val todo = wants.filter { (p, want) ->
                val mine = Areas.all().filter { it.tripId == p.id }
                mine.none { it.id == want.id }.also { if (!it) current++ }
            }
            setQueuedAll(todo.map { it.first.id }.toSet())
            for ((p, want) in todo) {
                if (pending.get()) break // a newer ask: start over with what is true now
                when (val r = make(p, want)) {
                    null -> made += p.id
                    else -> if (r.startsWith("refused")) refused += p.id
                }
                setQueued(p.id, false)
            }
            setQueuedAll(emptySet())
        }
        return linkedMapOf(
            "why" to why, "run" to runs.incrementAndGet(), "widthM" to width, "trips" to plans.size, "current" to current,
            "made" to made, "refused" to refused, "released" to released, "ms" to System.currentTimeMillis() - t0,
        ).also { lastRun = it; Log.i(TAG, "reconcile: $it") }
    }

    /** Makes [want] the corridor of [p]: null when it downloaded (or is downloading), else why not. */
    private fun make(p: RoutePlan, want: AreaOfInterest): String? {
        val stale = Areas.all().filter { it.tripId == p.id && it.id != want.id && it.pack == null }
        refusals[p.id]?.let { f ->
            val cache = OnlineService.get(app).cache
            if (f.areaId == want.id && f.budgetMb == AppSettings.pinnedCacheMb && cache.pinnedBytes >= f.pinnedBytes) return "refused (unchanged)"
        }
        waitIdle()
        // The old corridor first loses its pin (its tiles stay in the cache for the new download to find), and goes when the new one is done.
        for (s in stale) {
            if (AreaDownload.job?.let { it.id == s.id && it.running } == true) { AreaDownload.cancel(s.id); waitIdle() }
            runCatching { if (s.pinned) Areas.pin(s.id, false) }
        }
        val plan = try {
            AreaDownload.plan(want)
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            setNote(p.id, why)
            return "no plan: $why"
        }
        plan.refusal?.let { why ->
            refusals[p.id] = Refusal(want.id, AppSettings.pinnedCacheMb, OnlineService.get(app).cache.pinnedBytes)
            for (s in stale) runCatching { Areas.remove(s.id) }
            setNote(p.id, why)
            return "refused: $why"
        }
        refusals.remove(p.id)
        setNote(p.id, null)
        try {
            AreaDownload.start(want, want.name, p.id, manifest = plan.manifest)
        } catch (e: IllegalStateException) {
            setNote(p.id, e.message ?: "could not start")
            return "not started: ${e.message}"
        }
        waitIdle()
        val done = Areas.get(want.id)
        if (done == null) {
            setNote(p.id, AreaDownload.job?.error ?: "the download did not finish")
            return "gone: ${AreaDownload.job?.state}"
        }
        for (s in stale) runCatching { Areas.remove(s.id) }
        return null
    }

    /** Waits while an area download runs (one at a time), at most an hour. */
    private fun waitIdle() {
        val t0 = System.currentTimeMillis()
        while (AreaDownload.job?.running == true && System.currentTimeMillis() - t0 < 3_600_000) Thread.sleep(250)
    }

    // ---- state ----

    @Volatile private var wantMemo: Triple<RoutePlan, Int, String?>? = null

    /** The id of [p]'s corridor at [width] (remembered for the last trip asked, as the card asks every recomposition). */
    private fun wantId(p: RoutePlan, width: Int): String? {
        wantMemo?.takeIf { it.first === p && it.second == width }?.let { return it.third }
        val id = if (width > 0) runCatching { areaOf(p, width.toDouble()).id }.getOrNull() else null
        wantMemo = Triple(p, width, id)
        return id
    }

    /** What the trip card and `GET /routes/{id}` show for trip [p] (no store call: safe on the main thread). */
    fun status(p: RoutePlan, units: ContourUnits = AppSettings.units): CorridorStatus {
        val tripId = p.id
        val recs = Areas.list.filter { it.tripId == tripId && it.pack == null }
        val width = AppSettings.tripCorridorM
        val want = wantId(p, width)
        val r = recs.firstOrNull { it.id == want } ?: recs.firstOrNull { it.pinned } ?: recs.firstOrNull()
        val job = AreaDownload.job?.takeIf { j -> r != null && j.id == r.id && j.running }
        return corridorStatus(
            width, tripId in notKept, tripId in queued, notes[tripId],
            r?.let { RecordView(it.state, it.pinned, it.bytes, it.estimateBytes, it.error, TripCorridor.widthOf((it.area.shape as? AreaShape.Corridor)?.bufferM ?: 0.0)) },
            job?.let { it.done.get() to it.planned.get() }, units,
        )
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "widthM" to AppSettings.tripCorridorM, "notKept" to notKept.toList(), "notes" to notes, "queued" to queued.toList(),
        "refusals" to refusals.mapValues { (_, f) -> mapOf("area" to f.areaId, "budgetMb" to f.budgetMb, "pinnedBytes" to f.pinnedBytes) },
        "pending" to pending.get(), "lastRun" to lastRun,
        "corridors" to Areas.all().filter { it.tripId != null }.map { mapOf("id" to it.id, "tripId" to it.tripId, "state" to it.state, "pinned" to it.pinned, "bytes" to it.bytes, "widthM" to TripCorridor.widthOf((it.area.shape as? AreaShape.Corridor)?.bufferM ?: 0.0)) },
    )

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.isFile) {
                val doc = MiniJson.parseObject(file.readText())
                val set = (doc["notKept"] as? List<*>).orEmpty().mapNotNull { it as? String }.toSet()
                ui.publish(2_000) { notKept = set }
            }
        }.onFailure { Log.w(TAG, "trip-corridors.json unreadable", it) }
    }

    private fun save(set: Set<String>) {
        runCatching { file.writeText(MiniJson.encode(mapOf("version" to 1, "notKept" to set.sorted()))) }.onFailure { Log.w(TAG, "saving trip-corridors.json", it) }
    }

    private fun setNotKept(tripId: String, off: Boolean) {
        ui.publish(2_000) {
            val next = if (off) notKept + tripId else notKept - tripId
            if (next != notKept) { notKept = next; Thread { save(next) }.start() }
        }
    }

    private fun setNote(tripId: String, text: String?) = ui.publish(2_000) { notes = if (text == null) notes - tripId else notes + (tripId to text) }
    private fun setQueued(tripId: String, on: Boolean) = ui.publish(2_000) { queued = if (on) queued + tripId else queued - tripId }
    private fun setQueuedAll(ids: Set<String>) = ui.publish(2_000) { queued = ids }
}

/** T130: what [corridorStatus] needs of a trip's area record. */
internal data class RecordView(val state: String, val pinned: Boolean, val bytes: Long, val estimateBytes: Long, val error: String?, val widthM: Int)

/** T130: the trip card's corridor line: [state] for scripts, [text] as shown, and the [actions] it offers (`keep`, `stop`, `retry`, `areas`). */
data class CorridorStatus(val state: String, val text: String, val actions: List<String>) {
    fun json(): Map<String, Any?> = linkedMapOf("state" to state, "text" to text, "actions" to actions)
}

/**
 * T130: the trip card's corridor line from the setting's [widthM] (0: off), whether the user stopped keeping this trip
 * ([notKept]), whether it waits for another download ([queued]), why it has none ([note]), its area [r] and the running
 * download's tiles done and planned ([progress]). Pure, tested.
 */
internal fun corridorStatus(widthM: Int, notKept: Boolean, queued: Boolean, note: String?, r: RecordView?, progress: Pair<Int, Int>?, units: ContourUnits): CorridorStatus {
    fun side(m: Int) = UnitFormat.distance(m.toDouble(), units) + " each side"
    val kept = r != null && r.pinned
    return when {
        notKept && !kept -> CorridorStatus("notKept", CORRIDOR_NOT_KEPT, listOf("keep"))
        r != null && kept && (progress != null || r.state == "downloading") ->
            CorridorStatus("downloading", "Keeping offline, ${side(r.widthM)}: " + (progress?.let { (d, p) -> "$d of $p tiles" } ?: "starting"), listOf("stop"))
        r != null && kept && r.state == "done" -> CorridorStatus("kept", "Kept offline, ${side(r.widthM)}: ${aboutMb(r.bytes)}", listOf("stop"))
        r != null && kept -> CorridorStatus("partial", "Kept offline in part, ${side(r.widthM)}" + (r.error?.let { ": $it" } ?: ""), listOf("retry", "stop"))
        queued && widthM > 0 -> CorridorStatus("queued", "Waiting to keep offline, ${side(widthM)}", listOf("stop"))
        note != null -> CorridorStatus("refused", "Not kept offline: $note", listOf("areas", "keep"))
        widthM == 0 -> CorridorStatus("off", CORRIDOR_OFF, listOf("keep", "areas"))
        else -> CorridorStatus("none", CORRIDOR_NONE, listOf("keep"))
    }
}

/** T130: the trip card when the user stopped keeping this trip offline. */
internal const val CORRIDOR_NOT_KEPT = "Not kept offline."

/** T130: the trip card when the width setting is off. */
internal const val CORRIDOR_OFF = "Trips are not kept offline automatically (Offline areas)."

/** T130: the trip card before its corridor is planned (a moment after a save). */
internal const val CORRIDOR_NONE = "Not kept offline yet."
