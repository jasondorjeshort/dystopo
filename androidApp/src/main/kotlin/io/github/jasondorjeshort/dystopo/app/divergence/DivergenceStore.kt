package io.github.jasondorjeshort.dystopo.app.divergence

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.DivergenceText
import io.github.jasondorjeshort.dystopo.routing.TrackMatch
import io.github.jasondorjeshort.dystopo.routing.TrailDivergence
import io.github.jasondorjeshort.dystopo.routing.TrailDivergence.Divergence
import io.github.jasondorjeshort.dystopo.routing.TrailDivergence.Pt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * T213: the stretches where the user's own tracks leave the mapped trails ([TrailDivergence]), kept on the phone.
 *
 * - **When it runs**: after a recording ends ([Tracking.stop] calls [runSoon]) and on demand (`POST /divergence/run`).
 *   The T150 rule: nothing in the background; a run asked for while the app is not on screen waits for the next start
 *   ([pending]), and with the setting ("Show trail disagreements", [AppSettings.showDivergence]) off only the debug
 *   route runs it.
 * - **What it reads**: the newest [READ_TRACKS] finished tracks in the store, and the trail network as the planner has
 *   it ([RoutePlanner.router], which loads or builds the packs' graphs as `TerrainSource.prepareGraph` asks for them),
 *   waiting up to a minute for it; a point outside every pack with a ready graph is outside the network's coverage
 *   ([RoutePlanner.archiveAt]). Each track's candidates are cached by its id, update time and size, so a run after a
 *   recording snaps only the new track. Its own low-priority thread.
 * - **What it keeps** (`files/divergence.json`, written whole): the records of the last run, confirmed and not, and the
 *   dismissed stretches, each with its line, so a stretch found again after more tracks (a new midpoint, a new id) stays
 *   dismissed when it agrees with a dismissed line ([TrailDivergence.agree]). Nothing is sent anywhere.
 *
 * [shown] is what [DivergenceLayer] draws and the long-press card ([DivergenceCardLine]) offers: the confirmed records
 * not dismissed, none with the setting off. Compose reads [revision].
 */
object DivergenceStore {
    private const val TAG = "DystopoDivergence"
    private const val FILE = "divergence.json"
    /** At most this many of the newest finished tracks are read per run. */
    const val READ_TRACKS = 200
    /** How long a run waits for the trail network near the tracks. */
    const val GRAPH_WAIT_MS = 60_000L

    data class Dismissed(val id: String, val line: List<Pt>, val atMs: Long) {
        fun json(): Map<String, Any?> = linkedMapOf("id" to id, "atMs" to atMs, "line" to line.map { listOf(it.lat, it.lon) })
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-divergence").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()

    /** Bumped on the main thread after the records or the dismissals change (Compose state). */
    var revision by mutableIntStateOf(0)
        private set

    @Volatile var records: List<Divergence> = emptyList()
        private set
    @Volatile var dismissed: List<Dismissed> = emptyList()
        private set
    @Volatile var running = false
        private set
    /** Why a run waits for the foreground (null: none waits). */
    @Volatile var pending: String? = null
        private set
    @Volatile var lastRun: Map<String, Any?>? = null
        private set
    @Volatile var runs = 0
        private set
    @Volatile private var started = false
    @Volatile private var file: File? = null

    // Run thread only (under [lock]).
    private val cache = HashMap<String, List<TrailDivergence.Stretch>>()
    private var signature: String? = null

    /** Loads the kept records and starts the foreground watch (once; any thread). */
    fun init(app: DystopoApp) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            file = File(app.filesDir, FILE)
            load()
            CoroutineScope(Dispatchers.Default).launch {
                Foreground.flow.collect { on -> if (on) pending?.let { why -> pending = null; runSoon(app, why) } }
            }
        }
    }

    /** What the map draws and the card offers: confirmed, not dismissed; nothing with the setting off. */
    fun shown(): List<Divergence> = if (!AppSettings.showDivergence) emptyList() else records.filter { it.confirmed && !isDismissed(it) }

    fun isDismissed(d: Divergence): Boolean = dismissed.any { it.id == d.id || TrailDivergence.agree(it.line, d.line) }

    /** The shown record within [radiusM] of ([lat], [lon]) along its line, the nearest, or null. */
    fun near(lat: Double, lon: Double, radiusM: Double): Divergence? {
        val p = Pt(lat, lon)
        return shown().map { it to TrailDivergence.distanceToLineM(p, it.line) }.filter { it.second <= radiusM }.minByOrNull { it.second }?.first
    }

    /**
     * After a recording, or another reason: a run on the worker when the app is on screen and the setting is on; in the
     * background it waits for the next start (T150).
     */
    fun runSoon(app: DystopoApp, why: String) {
        init(app)
        if (!AppSettings.showDivergence) return
        if (!Foreground.started) { pending = why; return }
        worker.execute { runCatching { run(app, why, force = false, waitMs = GRAPH_WAIT_MS) }.onFailure { Log.w(TAG, "run ($why) failed", it) } }
    }

    /** Runs now on the calling thread (never the main one); returns the `lastRun` entry. [force] reads every track again. */
    fun run(app: DystopoApp, why: String, force: Boolean, waitMs: Long): Map<String, Any?> = synchronized(lock) {
        init(app)
        running = true
        val t0 = System.currentTimeMillis()
        try {
            val t = Tracking.of(app)
            val list = t.call { t.store.list() }.filter { !it.recording }.sortedByDescending { it.createdAt }.take(READ_TRACKS)
            val sig = list.joinToString("|") { "${it.id}:${it.updatedAt}:${it.points}" }
            if (!force && sig == signature && lastRun != null) return lastRun!!
            if (force) cache.clear()
            val firsts = list.mapNotNull { s ->
                if (cacheKey(s) in cache) null else t.call { t.store.track(s.id) }
            }
            val points = firsts.mapNotNull { tr -> tr.points.firstOrNull()?.let { it.lat to it.lon } }
            val router = try {
                RoutePlanner.router(waitMs, points)
            } catch (e: RoutePlanner.NotReady) {
                return linkedMapOf<String, Any?>("at" to t0, "why" to why, "error" to "no trail network is ready near the tracks").also { lastRun = it }
            }
            val snapper = TrackMatch.snapper(router)
            val covers = { lat: Double, lon: Double -> RoutePlanner.archiveAt(lat, lon) != null }
            val byId = firsts.associateBy { it.id }
            var read = 0
            val all = ArrayList<TrailDivergence.Stretch>()
            val live = HashSet<String>()
            for (s in list) {
                val key = cacheKey(s)
                live += key
                var got = cache[key]
                if (got == null) {
                    val tr = byId[s.id] ?: t.call { t.store.track(s.id) }
                    got = if (tr == null) emptyList() else TrailDivergence.candidates(tr, snapper, covers).also { cache[key] = it; read++ }
                }
                all += got
            }
            cache.keys.retainAll(live)
            val offset = TimeZone.getDefault().getOffset(t0).toLong()
            val found = TrailDivergence.cluster(all, snapper, TrailDivergence.edges(router), offset)
            records = found
            signature = sig
            runs++
            save()
            publish()
            val ms = System.currentTimeMillis() - t0
            Log.i(TAG, "run ($why): ${list.size} tracks (${read} read), ${all.size} candidates, ${found.count { it.confirmed }} confirmed in $ms ms")
            linkedMapOf<String, Any?>(
                "at" to t0, "why" to why, "ms" to ms, "tracks" to list.size, "read" to read, "cached" to list.size - read,
                "candidates" to all.size, "confirmed" to found.count { it.confirmed }, "unconfirmed" to found.count { !it.confirmed },
                "shown" to shown().size,
            ).also { lastRun = it }
        } finally {
            running = false
        }
    }

    private fun cacheKey(s: io.github.jasondorjeshort.dystopo.store.TrackSummary) = "${s.id}:${s.updatedAt}:${s.points}"

    /** The card's Dismiss: [id] stays hidden, and so does any stretch found later along the same line. */
    fun dismiss(id: String): Boolean {
        val d = records.firstOrNull { it.id == id } ?: return false
        if (dismissed.none { it.id == id }) dismissed = dismissed + Dismissed(id, d.line, System.currentTimeMillis())
        save()
        publish()
        return true
    }

    /** `POST /divergence/undismiss`: every dismissed stretch shows again. */
    fun undismissAll(): Int {
        val n = dismissed.size
        dismissed = emptyList()
        save()
        publish()
        return n
    }

    /** The records and dismissals changed: the layer and the card follow (any thread). */
    private fun publish() {
        DivergenceLayer.refresh()
        main.post { revision++ }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val body = linkedMapOf(
                "v" to 1, "records" to records.map { it.json() }, "dismissed" to dismissed.map { it.json() }, "savedAt" to System.currentTimeMillis(),
            )
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(MiniJson.encode(body), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        runCatching {
            val m = MiniJson.parseObject(f.readText(Charsets.UTF_8))
            records = (m["records"] as List<Map<String, Any?>>?).orEmpty().mapNotNull { Divergence.fromJson(it) }
            dismissed = (m["dismissed"] as List<Map<String, Any?>>?).orEmpty().mapNotNull { d ->
                runCatching {
                    Dismissed(d["id"] as String, (d["line"] as List<List<Number>>).map { Pt(it[0].toDouble(), it[1].toDouble()) }, (d["atMs"] as Number).toLong())
                }.getOrNull()
            }
        }.onFailure { Log.w(TAG, "load failed", it) }
        publish()
    }

    /** `GET /divergence`: the setting, the records (with their strings in [units]), what is shown, the last run, the card. */
    fun json(units: ContourUnits, cardPoint: Pair<Double, Double>?, cardRadiusM: Double): Map<String, Any?> {
        val shownIds = shown().map { it.id }.toSet()
        fun row(d: Divergence) = d.json() + linkedMapOf(
            "shown" to (d.id in shownIds), "dismissed" to isDismissed(d),
            "strings" to linkedMapOf("title" to DivergenceText.TITLE, "body" to DivergenceText.body(d, units), "dismiss" to DivergenceText.DISMISS),
        )
        val card = cardPoint?.let { (lat, lon) -> near(lat, lon, cardRadiusM) }
        return linkedMapOf(
            "setting" to AppSettings.showDivergence, "running" to running, "pending" to pending, "runs" to runs,
            "records" to records.filter { it.confirmed }.map(::row),
            "unconfirmed" to records.filter { !it.confirmed }.map(::row),
            "shown" to shownIds.size, "dismissedCount" to dismissed.size, "dismissed" to dismissed.map { linkedMapOf("id" to it.id, "atMs" to it.atMs) },
            "lastRun" to lastRun,
            "card" to linkedMapOf(
                "open" to (cardPoint != null), "radiusM" to Math.round(cardRadiusM * 10) / 10.0, "id" to card?.id,
                "title" to card?.let { DivergenceText.TITLE }, "body" to card?.let { DivergenceText.body(it, units) },
            ),
            "layer" to DivergenceLayer.json(),
            "thresholds" to TrailDivergence.thresholds() + linkedMapOf("readTracks" to READ_TRACKS, "graphWaitMs" to GRAPH_WAIT_MS),
        )
    }
}
