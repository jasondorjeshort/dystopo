package io.github.jasondorjeshort.dystopo.app.areas

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy
import io.github.jasondorjeshort.dystopo.app.online.DirectSources
import io.github.jasondorjeshort.dystopo.app.online.Fetch
import io.github.jasondorjeshort.dystopo.app.online.OnlineFetcher
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.areas.AreaDetail
import io.github.jasondorjeshort.dystopo.areas.AreaLimit
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.AssetItem
import io.github.jasondorjeshort.dystopo.areas.DetailChange
import io.github.jasondorjeshort.dystopo.areas.LocalItem
import io.github.jasondorjeshort.dystopo.areas.ManifestItem
import io.github.jasondorjeshort.dystopo.areas.TileCover
import io.github.jasondorjeshort.dystopo.areas.TileRow
import io.github.jasondorjeshort.dystopo.areas.TileSet
import io.github.jasondorjeshort.dystopo.areas.TilesItem
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * T75: downloads an area of interest. A box is "Download this region" ([OnlineService.region], a pack built on the
 * server or on the phone, then recorded in [Areas]); a corridor is fetched tile by tile into the online tile cache:
 *
 * 1. The manifest: `POST <server>/area` (the server's tile lists for the corridor) when a server is set, else (no
 *    server, or a server without `/area`) the same lists made here ([localManifest]).
 * 2. Every DEM and vector tile of the manifest goes through the online fetcher (the same path the map uses, so the
 *    map later finds them in the cache with the network off), six at a time, low zooms first. Tiles a pack already
 *    holds are skipped; tiles already cached are not fetched again, so running the download again resumes it.
 *    Each fetched tile is checked (a DEM tile must be a PNG, a vector tile must not fail to unzip); a bad one is
 *    dropped from the cache and counted under `invalid`. Failed tiles are tried once more at the end.
 * 3. With `contours`, the contour tiles of the corridor are made and cached too (they are otherwise made on the
 *    phone when first shown, from the cached DEM, also offline).
 *
 * The area is pinned from the start, so nothing fetched for it is evicted while it downloads. One job at a time;
 * [json] is the progress (`GET /areas/download`).
 *
 * T95: [plan] is the manifest, its size and the pinned budget check before anything starts (the trip's Offline
 * button shows it and refuses a download over the budget). A download fetches the current version of every tile
 * (`fresh`), so running it again on an area older than its source ("Update") refreshes it: when it completes with
 * nothing failed and no version change on the way, the area records the versions it now holds, the tiles of its old
 * version that no other pin holds are deleted, and so are the contour and shade tiles made in it at the current
 * version (they may have been made from the old DEM the pin served).
 *
 * T140 (ADR 0041 §3): a polygon is fetched the same way. Its [plan] (and a disc's, the one-point corridor) is the phone's
 * own manifest, with the size line, the time from the last completed download and the less-detail alternative; the
 * server is asked once, at Download, and a server that does not read polygons (no `polygon` in `GET /area`'s `shapes`,
 * or a 400 starting "kind must be") leaves the manifest to the phone. Over the pinned budget, a drawn area or a disc says
 * its size, what is in use and the remedies ([areaBudgetError]).
 *
 * T152 (ADR 0041 §3, its T152 note): the heap is no size limit. The manifest's tile sets are rows ([TileSet]), and the
 * download walks them row by row ([AreaManifest.walk], [TileRow]: zoom, then route, then row) with at most [IN_FLIGHT]
 * tiles queued, so nothing it holds grows with the area: the plan always carries its estimate (tiles times
 * [AreaManifest.estimate]), says when the area passes the tile cache index's comfortable size ([AreaLimit.pastHeap],
 * `pastHeapLimit`) and Download proceeds; the pinned budget is the one limit. `GET /areas/download` `rows` shows the
 * walk: rows planned, started and whole, the row being fetched and the last row of the whole prefix. Failed tiles are
 * kept as row sets per route and zoom and walked again at the end. In direct mode the download is exempt from the
 * sources' session cap ([DirectSources.exempt]).
 *
 * T151 ([ResumeCheck]): the record keeps a cursor ([DownloadCursor]: the last row of the whole prefix and the failed tiles
 * in the rows through it), saved every [CURSOR_SAVE_MS] and when the run ends; a start of an unfinished (or cancelled) area
 * over its saved manifest walks the failed tiles and then the rows after the cursor, counting the rest as done, unless the
 * source's versions moved on since. The walk stops as stalled after [STALL_TILES] tiles in a row could not be fetched (the
 * network stopped answering; the record says so and the resume check picks it up later) or [MAX_FAILED_TILES] in a run.
 * [cancel] keeps the user's cancel in the record at once. A budget refusal at the start is a [BudgetRefused].
 *
 * T156 ([DownloadWorker], ADR 0046): every start hands the download over to the WorkManager chain ([DownloadWorker.handOver]:
 * a tap replaces the chain so its worker runs now and shows the notification, any other start keeps the chain there is). The
 * worker watches the job and keeps the process alive; when the system stops the worker with the app in the background,
 * [pause] ends the walk like a stall (the cursor saved, the record partial, not counted as a failure) and the chain runs it
 * again from the cursor. [Job.handedOn] tells the worker that the run's end, its record and the resume check's request are done.
 *
 * T369 (the public vector source's terms, ADR 0060's owner paragraph): with no server set, a download takes at most
 * [AreaLimit.DIRECT_MAX_TILES] vector tiles and [AreaLimit.DIRECT_MAX_BYTES] of them. The plan's vector count refuses a start
 * over the first ([Plan.capRefusal], [capRefusalLine]) on every start, the user's and the resume check's: a record already on
 * the phone keeps its tiles, says the sentence and is not resumed by itself ([AutoResume.STOP_CAP]). The bytes are counted as
 * the vector tiles are fetched, and a run that reaches the second stops there ([Job.capStopped], [CAP_STOPPED]: partial, its
 * tiles kept, not resumed by itself). With a server set neither applies.
 *
 * T386 (a saved area's detail, [changeDetail]): a lower re-keys the record and frees the dropped zooms ([Areas.applyDetail]); a
 * raise re-keys it with its [Areas.Record.Raise] and downloads the added tiles alone: [start] of such a record takes its raise
 * manifest ([Areas.raiseManifest]) as the job's ([Job.raise]), so the cap counts only those ([raiseCapRefusalLine]), its own
 * bytes are not counted against the budget twice, and the run's end ([raiseEnded]) counts the area's bytes again. A raise left
 * for later is not resumed by itself ([ResumeCheck]).
 */
object AreaDownload {
    private val app: DystopoApp get() = DystopoApp.instance

    class Job(
        val id: String, val name: String, val area: AreaOfInterest, val source: String, val contours: Boolean, val planned0: AreaManifest? = null, val isNew: Boolean = true,
        /** T151: what started it: [TRIGGER_USER], or the resume check's triggers ([ResumeCheck]). */
        val trigger: String = TRIGGER_USER,
        /** T151: the record's cursor it may resume from (over [planned0], the saved manifest); dropped when the source's versions moved. */
        val cursor0: DownloadCursor? = null,
        /** T386: a raise of detail: [planned0] is the added tiles alone ([Areas.raiseManifest]), the record's bytes are counted again after. */
        val raise: Boolean = false,
    ) {
        @Volatile var state = "starting" // starting, manifest, tiles, retry, contours, done, partial, failed, refused, cancelled
        /** T95: what the area held before and after (versions per source), and the tiles deleted after a refresh. */
        @Volatile var versionsBefore: Map<String, Map<String, String>>? = null
        @Volatile var versionsAfter: Map<String, Map<String, String>>? = null
        @Volatile var refreshDropped: Int? = null
        /** T95: the record's state before this run (kept when the budget refuses it). */
        @Volatile var stateBefore: String? = null
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        @Volatile var manifest: AreaManifest? = null
        @Volatile var manifestFrom: String? = null
        /** T152: why the server's manifest was not used (its 413), when the phone made it instead. */
        @Volatile var serverNote: String? = null
        val planned = AtomicInteger()
        val done = AtomicInteger()
        val fetched = AtomicInteger()
        val cached = AtomicInteger()
        val inPacks = AtomicInteger()
        val missing = AtomicInteger()
        val failed = AtomicInteger()
        val invalid = AtomicInteger()
        val bytes = AtomicLong()
        /** T95: bytes of the tiles found already cached (with [bytes], the area's tiles on the phone after a whole run). */
        val cachedBytes = AtomicLong()
        val contourTiles = AtomicInteger()
        val startedAt = System.currentTimeMillis()
        @Volatile var manifestMs: Long? = null
        @Volatile var finishedAt = 0L
        val running get() = state !in setOf("done", "partial", "failed", "refused", "cancelled")

        /** T152: the walk's rows ([TileRow]): planned, started, whole, the row being fetched and the last of the whole prefix. */
        val rowsPlanned = AtomicInteger()
        val rowsStarted = AtomicInteger()
        val rowsDone = AtomicInteger()
        @Volatile var rowNow: TileRow? = null
        /** T152: the rows of the walk before this index are all whole; [lastWhole] is the last of them (where a resume may start after). */
        @Volatile var wholePrefix = 0
        @Volatile var lastWhole: TileRow? = null

        /** T151: the cursor this run resumed from (null: the whole walk), and the tiles before it counted as done. */
        @Volatile var resumedFrom: DownloadCursor? = null
        val skipped = AtomicInteger()
        /** T151: the earlier failed tiles walked again before the rows after the cursor; [againDone] once they all were. */
        @Volatile var again = 0
        @Volatile var againDone = true
        /** T151: tiles in a row that could not be fetched (reset by any tile that could), and the last reason. */
        val unavailableRun = AtomicInteger()
        @Volatile var lastUnavailable: String? = null
        /** T151: why the walk stopped early (the network stopped answering, or too many failures), and whether the network was the cause. */
        @Volatile var stalled: String? = null
        @Volatile var stalledByNetwork = false
        /** T151: tiles that failed in this run's walk so far (the cap on what the cursor keeps). */
        val failedNow = AtomicInteger()
        /** T151: the failed tiles of the walk as rows, per route and zoom (guarded by itself). */
        val failedRows = HashMap<String, HashMap<Int, TileSet>>()
        @Volatile var lastCursorSave = 0L
        /** T151: the record's bytes when this run started (earlier runs'; the cursor's saves and the end add this run's). */
        @Volatile var bytes0 = 0L
        @Volatile var cursorSaves = 0
        @Volatile var savedCursor: String? = null
        /** T156: why the worker paused the walk ([pause]); null when it was not. */
        @Volatile var pausedBy: String? = null
        /** T156: the run's end is written and the resume check asked ([DownloadWorker] waits for it before deciding the chain's next step). */
        @Volatile var handedOn = false
        // T369
        /** T369: no server set: the public vector source's cap applies to this run. */
        @Volatile var direct = false
        /** T369: the vector bytes this run fetched from the source (not those found cached), against [AreaLimit.DIRECT_MAX_BYTES]. */
        val vectorBytes = AtomicLong()
        /** T369: the largest vector tile this run fetched (the room kept for the tiles in flight). */
        val largestTile = AtomicLong()
        /** T369: the run reached [AreaLimit.DIRECT_MAX_BYTES] and stopped there. */
        @Volatile var capStopped = false

        fun capJson(): Map<String, Any?> = linkedMapOf(
            "direct" to direct, "vectorBytes" to vectorBytes.get(), "maxBytes" to AreaLimit.DIRECT_MAX_BYTES, "maxTiles" to AreaLimit.DIRECT_MAX_TILES,
            "vectorTiles" to manifest?.vectorTileCount, "stopped" to capStopped,
        )
        // end T369

        fun json(): Map<String, Any?> {
            val now = if (finishedAt != 0L) finishedAt else System.currentTimeMillis()
            val p = planned.get()
            return linkedMapOf(
                "id" to id, "name" to name, "state" to state, "error" to error, "source" to source, "manifestFrom" to manifestFrom, "serverNote" to serverNote,
                "planned" to p, "done" to done.get(), "percent" to if (p == 0) null else Math.round(done.get() * 1000.0 / p) / 10.0,
                "fetched" to fetched.get(), "alreadyCached" to cached.get(), "inPacks" to inPacks.get(), "missing" to missing.get(),
                "failed" to failed.get(), "invalid" to invalid.get(), "bytes" to bytes.get(), "contours" to contours, "contourTiles" to contourTiles.get(), // T170: contours
                "rows" to linkedMapOf(
                    "planned" to rowsPlanned.get(), "started" to rowsStarted.get(), "done" to rowsDone.get(), "wholePrefix" to wholePrefix,
                    "current" to rowNow?.json(), "lastWhole" to lastWhole?.json(), "order" to "zoom, route, y, x0",
                ),
                "estimateBytes" to manifest?.bytes, "manifestMs" to manifestMs, "elapsedMs" to now - startedAt,
                "versionsBefore" to versionsBefore, "versionsAfter" to versionsAfter, "refreshDropped" to refreshDropped, // T95
                "manifest" to manifest?.summary(),
                // T151: what started it, the cursor it resumed from, the tiles skipped, the stall, the cursor saved.
                "resume" to linkedMapOf(
                    "trigger" to trigger, "from" to resumedFrom?.json()?.minus("failed"), "skippedTiles" to skipped.get(), "failedAgain" to again,
                    "stalled" to stalled, "stalledByNetwork" to stalledByNetwork, "cursorSaves" to cursorSaves, "cursor" to savedCursor,
                    "pausedBy" to pausedBy, // T156
                ),
                "cap" to capJson(), // T369
                "raise" to raise, // T386
            )
        }
    }

    /** T151: a start the pinned budget refuses (its sentence); an [IllegalStateException], so routes answer 409 as before. */
    class BudgetRefused(message: String) : IllegalStateException(message)

    /** T369: a start the public vector source's cap refuses ([capRefusalLine]); a 409 at the routes, as a budget refusal. */
    class CapRefused(message: String) : IllegalStateException(message)

    /** T369: true when the phone reads the public sources itself (no server set): the cap applies. */
    fun direct(): Boolean = OnlineService.get(app).fetcher?.server == DirectSources.SERVER

    @Volatile var job: Job? = null
        private set

    /** T136: the last few downloads that ended before the current one (id, name, state, tiles), newest first: a cancelled one stays visible after the next starts. */
    private val recent = java.util.concurrent.ConcurrentLinkedDeque<Map<String, Any?>>()

    /** T140 (T136's follow-up): `recent` without the job shown above it, which ends up first in it once it finishes. */
    fun json(): Map<String, Any?>? = job?.let { j -> j.json() + ("recent" to recentWithout(recent.toList(), j.id, j.finishedAt)) }

    /** T140, T152: the tiles the tile cache's index holds comfortably in this process's heap ([AreaLimit.maxTiles]); never a refusal. */
    fun heapTiles(): Long = AreaLimit.maxTiles(Runtime.getRuntime().maxMemory())

    fun cancel(id: String? = null): Boolean {
        val j = job ?: return false
        if (id != null && j.id != id) return false
        if (!j.running) return false
        j.cancelled = true
        // T151: the cancel is the user's and is kept in the record at once, so no trigger resumes it (even if the process ends
        // before the job does); Retry or Download again clears it.
        Areas.update(j.id) { it.copy(cancelled = true) }
        return true
    }

    /**
     * T156: the worker was stopped with the app in the background ([DownloadWorker]): the walk ends as a stall would (the
     * cursor saved, the record partial, no failure counted) so the chain's next run goes on from the cursor. False when no
     * job runs.
     */
    fun pause(reason: String): Boolean {
        val j = job ?: return false
        if (!j.running || j.cancelled) return false
        j.pausedBy = reason
        j.stalledByNetwork = true
        j.stalled = reason
        return true
    }

    /** T151: the resume check's start of [r]'s download, from its cursor when it has one ([start] does the rest). */
    fun resume(r: Areas.Record, trigger: String): Map<String, Any?> = start(r.area, r.name, r.tripId, trigger = trigger)

    /**
     * T95: what downloading corridor [area] would take, before anything starts: the manifest (from the server, else
     * made here), its estimated bytes and tiles, and [Plan.refusal] when the pinned budget would be passed.
     */
    class Plan(
        val area: AreaOfInterest, val manifest: AreaManifest, val manifestFrom: String, val pinnedBytes: Long, val alreadyBytes: Long, val budgetMb: Long,
        /** T140: the last completed download's bytes per millisecond, for the time line; null before the first. */
        val bytesPerMs: Double? = null,
        /** T140: the same area at less detail (z8 to z13), for a drawn area or a disc deeper than z13. */
        val lessDetail: Plan? = null,
        /** T157: the same area at medium detail (elevation to z13, vector to z14), for a drawn area or a disc at full detail. */
        val mediumDetail: Plan? = null,
        /** T152: the tiles the tile cache's index holds comfortably ([heapTiles]); past it the plan says so and still proceeds. */
        val heapTiles: Long = Long.MAX_VALUE,
        /** T152: milliseconds the manifest took to make (the covers filled row by row); null for a server's. */
        val manifestMs: Long? = null,
        /** T249: [pinnedBytes] is what the tile cache's index had counted so far (planned on the main thread while it is built). */
        val pinnedPartial: Boolean = false,
        /** T369: planned with no server set: the public vector source's cap applies ([capRefusal]). */
        val direct: Boolean = false,
    ) {
        val bytes: Long get() = manifest.bytes
        val tiles: Int get() = manifest.tileCount
        /** T369: the public vector source's refusal (no server set, over [AreaLimit.DIRECT_MAX_TILES] vector tiles); null otherwise. */
        val capRefusal: String? get() = if (direct) capRefusalLine(manifest.vectorTileCount.toLong(), lessDetailHelps(area)) else null
        /** T152: the budget's refusal: the heap no longer refuses an area ([AreaLimit]). T369: the cap's first, which no budget lifts. */
        val refusal: String? get() = capRefusal ?: if (userShape(area)) areaBudgetError(pinnedBytes, bytes, alreadyBytes, budgetMb) else budgetError(pinnedBytes, bytes, alreadyBytes, budgetMb)
        val pastHeapLimit: Boolean get() = AreaLimit.pastHeap(tiles.toLong(), heapTiles)

        fun json(): Map<String, Any?> = linkedMapOf(
            "id" to area.id, "name" to area.name, "kind" to area.kind, "shape" to shapeName(area), "manifestVersion" to area.manifestVersion,
            "bytes" to bytes, "mb" to Math.round(bytes / 104_857.6) / 10.0, "tiles" to tiles, "estimated" to !manifest.bytesExact, "rows" to manifest.rowCount,
            "perRoute" to manifest.items.filterIsInstance<TilesItem>().associate { it.route to mapOf("tiles" to it.count, "bytes" to it.bytes, "zooms" to it.zooms?.let { z -> listOf(z.first, z.last) }) },
            "zooms" to listOf(area.minZoom, area.maxZoom), "demMaxZoom" to area.demMaxZoom, "detail" to AreaDetail.of(area)?.key, // T157
            "bufferM" to bufferOf(area), "lengthM" to area.lengthM,
            "manifestFrom" to manifestFrom, "manifestMs" to manifestMs, "pinnedBytes" to pinnedBytes, "alreadyBytes" to alreadyBytes, "budgetMb" to budgetMb,
            "pinnedPartial" to pinnedPartial, // T249
            "fits" to (refusal == null), "refusal" to refusal, "refused" to (refusal != null),
            // T369: the public vector source's cap (no server set): the plan's vector tiles against it, and its sentence.
            "cap" to linkedMapOf(
                "direct" to direct, "vectorTiles" to manifest.vectorTileCount, "maxTiles" to AreaLimit.DIRECT_MAX_TILES, "maxBytes" to AreaLimit.DIRECT_MAX_BYTES,
                "over" to (capRefusal != null), "refusal" to capRefusal,
            ),
            // T152: past the tile cache index's comfortable size the download still proceeds (ADR 0041 §3's T152 note).
            "pastHeapLimit" to pastHeapLimit, "heapTiles" to heapTiles.takeIf { it != Long.MAX_VALUE },
            // T140 (ADR 0041 §3, §5): what the sheet shows before anything starts.
            "sizeLine" to sizeLine(bytes, tiles, area.minZoom, area.maxZoom, area.demMaxZoom),
            "timeLine" to timeLine(bytes, bytesPerMs), "seconds" to bytesPerMs?.let { Math.round(bytes / it / 1000) },
            "lessDetail" to lessDetail?.let { l ->
                linkedMapOf(
                    "zooms" to listOf(l.area.minZoom, l.area.maxZoom), "id" to l.area.id, "bytes" to l.bytes, "tiles" to l.tiles, "line" to lessDetailLine(l.bytes),
                    "fits" to (l.refusal == null), "refusal" to l.refusal,
                )
            },
            "offerLessDetail" to (lessDetail != null && refusal != null),
            // T157: the medium detail (ADR 0041 §5's T157 note), beside the less detail.
            "mediumDetail" to mediumDetail?.let { l ->
                linkedMapOf(
                    "zooms" to listOf(l.area.minZoom, l.area.maxZoom), "demMaxZoom" to l.area.demMaxZoom, "id" to l.area.id, "bytes" to l.bytes, "tiles" to l.tiles,
                    "line" to detailChoiceLine(AreaDetail.MEDIUM, l.bytes), "fits" to (l.refusal == null), "refusal" to l.refusal,
                )
            },
        )
    }

    /**
     * What downloading [area] (a corridor, a disc or a polygon) would take, before anything starts. T140: a drawn area
     * or a disc is planned on the phone (the server is asked once, at Download). T152: at any size (the covers are rows),
     * with [Plan.pastHeapLimit] for an area past the tile cache index's comfortable size. T249: the pinned bytes are the
     * whole index's: off the main thread it waits for the index (the planner's thread); on the main thread, or when the
     * index stalls, the plan says [Plan.pinnedPartial] (the download's own check waits again before it starts).
     */
    fun plan(area: AreaOfInterest, withLessDetail: Boolean = true): Plan {
        require(area.shape !is AreaShape.Box) { "a plan is for a corridor or a polygon; a box downloads as an offline map with routing (Download this area)" }
        val fetcher = OnlineService.get(app).fetcher ?: throw IllegalStateException(NO_SOURCE)
        val cache = OnlineService.get(app).cache
        val pinnedPartial = !cache.ready && (onMainThread() || runCatching { cache.awaitReady("AreaDownload.plan") }.isFailure)
        val pinned = cache.pinnedBytes
        val own = pinnedOwn(Areas.get(area.id))
        val speed = downloadSpeed(recent.toList(), Areas.all())
        val less = if (withLessDetail && userShape(area) && area.maxZoom > LESS_DETAIL_MAX_ZOOM && area.minZoom <= LESS_DETAIL_MAX_ZOOM) {
            plan(area.withZooms(area.minZoom, LESS_DETAIL_MAX_ZOOM), withLessDetail = false)
        } else null
        // T157: the medium detail beside it, for an area whose elevation goes past z13 and vector to z14.
        val medium = if (withLessDetail && userShape(area) && area.maxZoom > AreaDetail.MEDIUM.maxZoom && area.demMaxZoom == null && area.minZoom <= LESS_DETAIL_MAX_ZOOM) {
            plan(area.withZooms(area.minZoom, AreaDetail.MEDIUM.maxZoom, AreaDetail.MEDIUM.demMaxZoom), withLessDetail = false)
        } else null
        val t0 = System.currentTimeMillis()
        val fromServer = if (fetcher.server == DirectSources.SERVER || userShape(area)) null else serverManifest(fetcher.server, area, null)
        val m = fromServer ?: localManifest(area, fetcher.server)
        return Plan(
            area, m, if (fromServer != null) "server" else "phone", pinned, own, AppSettings.pinnedCacheMb, bytesPerMs = speed, lessDetail = less, mediumDetail = medium,
            heapTiles = heapTiles(), manifestMs = if (fromServer == null) System.currentTimeMillis() - t0 else null, pinnedPartial = pinnedPartial,
            direct = fetcher.server == DirectSources.SERVER, // T369
        )
    }

    /** T249: whether this is the UI thread (false on the JVM, where `Looper` is a stub). */
    private fun onMainThread(): Boolean = runCatching { android.os.Looper.getMainLooper()?.thread === Thread.currentThread() }.getOrDefault(false)

    /** T106: the bytes of [r] already counted as pinned (none while it is unpinned: they are not in the pinned total). */
    private fun pinnedOwn(r: Areas.Record?): Long = r?.takeIf { it.pinned }?.bytes ?: 0L

    /**
     * Starts downloading [area]. A box goes to "Download this region" (its pack); a corridor is fetched here.
     * [tripId] links the area to the trip it was made for. T95: [manifest] is the one a [plan] made (then the budget is
     * checked here, before anything is recorded, and an IllegalStateException says why it does not fit).
     */
    @Synchronized
    fun start(area: AreaOfInterest, name: String? = null, tripId: String? = null, contours: Boolean = false, manifest: AreaManifest? = null, trigger: String = TRIGGER_USER): Map<String, Any?> {
        val service = OnlineService.get(app)
        if (area.shape is AreaShape.Box) {
            require(area.demMaxZoom == null) { "a box is a region pack, which holds one zoom range: leave out demMaxZoom, or draw an area for the medium detail" } // T157
            val b = area.bounds
            return linkedMapOf("kind" to "box", "region" to service.region.start(PackBounds(b.minLon, b.minLat, b.maxLon, b.maxLat), area.minZoom, minOf(area.maxZoom, OnlineTiles.DEM_MAX_ZOOM), name))
        }
        check(job?.running != true) { "an area download is already running (${job?.name}); DELETE /areas/download first" }
        val fetcher = service.fetcher ?: throw IllegalStateException(NO_SOURCE)
        val old = Areas.get(area.id)
        check(old?.opaque == null) { "area ${old?.name}: ${io.github.jasondorjeshort.dystopo.areas.NeedsNewerApp.USER_SENTENCE}" }
        // T152 (ADR 0041 §3's T152 note): no heap check; the pinned budget is the one limit.
        // T151: an unfinished (or cancelled) download goes on from its cursor over the manifest it was made with.
        // T386: a record whose raise of detail waits or runs downloads the added tiles alone (its raise manifest), its own
        // bytes not counted against the budget twice.
        val raise = old?.raise != null && old.opaque == null
        val cursor = old?.takeIf { manifest == null && (unfinished(it) || it.state == "cancelled") }?.cursor
        val saved = cursor?.let { if (raise) Areas.raiseManifest(area.id) else Areas.manifest(area.id) }?.takeIf { it.id == area.id }
        val planned = manifest?.takeIf { it.id == area.id } ?: saved ?: if (raise) Areas.raiseManifest(area.id)?.takeIf { it.id == area.id } ?: raiseManifestOf(old!!, fetcher.server) else null
        val from = cursor?.takeIf { saved != null && planned === saved }
        val own = if (raise) 0L else pinnedOwn(old)
        planned?.let { m -> budgetRefusal(area, service.cache.pinnedBytes, m.bytes, own, AppSettings.pinnedCacheMb)?.let { throw BudgetRefused(it) } }
        // T369: no server set, the public vector source's cap, on every start (the user's, a Retry, an Update, the resume
        // check's): over it nothing starts; a record already here keeps its tiles, says why and is not resumed by itself.
        val direct = fetcher.server == DirectSources.SERVER
        val checked = if (direct) planned ?: localManifest(area, fetcher.server) else planned
        if (direct) (if (raise) raiseCapRefusalLine(checked!!.vectorTileCount.toLong()) else capRefusalLine(checked!!.vectorTileCount.toLong(), lessDetailHelps(area)))?.let { why -> // T386: a raise's own sentence
            if (old != null) Areas.update(area.id) { it.copy(error = why, autoResume = (it.autoResume ?: AutoResume()).copy(stopped = AutoResume.STOP_CAP, nextAt = 0L)) }
            throw CapRefused(why)
        }
        // end T369
        val label = name ?: area.name ?: defaultName(area, java.time.LocalDate.now())
        // T170 (T151's follow-up): a download started with contours keeps them on its resume, Retry and Download again.
        val withContours = contours || old?.contours == true
        val j = Job(area.id, label, area, fetcher.server, withContours, checked, isNew = old == null, trigger = trigger, cursor0 = from, raise = raise).also { it.stateBefore = old?.state; it.bytes0 = old?.bytes ?: 0L; it.direct = direct } // T369: the manifest checked
        job = j
        // T106 (a T95 follow-up): an unpinned area stays unpinned until the budget lets its download go on ([run]); an
        // Update the budget refused used to leave it pinned, with its tiles counted against that budget.
        val pinNow = old?.pinned ?: true
        // T151: the user's start clears the resume's failures and stop; an automatic one keeps them. [AutoResume.runFrom]
        // lets the repair at start tell a run that got somewhere from one that did not.
        val resumeState = (if (trigger == TRIGGER_USER) AutoResume() else old?.autoResume ?: AutoResume()).copy(runFrom = from?.key ?: "", trigger = trigger, nextAt = 0L)
        Areas.put(
            Areas.Record(
                id = area.id, name = label, area = area.withName(label), createdAt = old?.createdAt ?: System.currentTimeMillis(), pinned = pinNow,
                state = "downloading", source = fetcher.server, tripId = tripId ?: old?.tripId, bytes = old?.bytes ?: 0L,
                estimateBytes = old?.estimateBytes ?: 0L, tiles = old?.tiles ?: 0, versions = old?.versions.orEmpty(), // T95: held until it completes
                downloadMs = old?.downloadMs, cursor = from, cancelled = false, autoResume = resumeState, // T151
                contours = withContours, // T170
                off = old?.off ?: false, raise = old?.raise?.copy(deferred = false), // T386: started, so no longer deferred
            ),
        )
        Thread({ run(j, fetcher) }, "area-download").apply { isDaemon = true }.start()
        // T156: the chain keeps it going when the app is in the background or the process ends (a tap shows the notification).
        runCatching { DownloadWorker.handOver(app, trigger) }.onFailure { Log.w(Areas.TAG, "the download chain could not be enqueued", it) }
        return linkedMapOf("kind" to area.kind, "download" to j.json())
    }

    private class Refused(message: String) : Exception(message)

    private fun run(j: Job, fetcher: OnlineFetcher) {
        // T152: in direct mode the sources' session cap neither stops nor counts the download (the pinned budget is its limit).
        if (fetcher.server == DirectSources.SERVER) OnlineService.get(app).direct.exempt { runJob(j, fetcher) } else runJob(j, fetcher)
    }

    private fun runJob(j: Job, fetcher: OnlineFetcher) {
        val pool = Executors.newFixedThreadPool(POOL) { r -> Thread(r, "area-tiles").apply { isDaemon = true } }
        var changesAtStart = 0L
        try {
            j.state = "manifest"
            val t0 = System.currentTimeMillis()
            // T151: a start (a tap, or a resume once the network may be back) tries the network now: the fetcher's own
            // backoff after a lost connection (its source marked down, tiles failed recently) would fail every tile at once
            // and stall the run where it began.
            fetcher.retryNow()
            // T95: the versions the tiles will be fetched at, asked now; a new area records them from the start.
            fetcher.checkVersions()
            changesAtStart = fetcher.sourceList.sumOf { it.versionChanges.get() }
            Areas.update(j.id) { r -> if (r.versions.isEmpty()) r.copy(versions = fetcher.currentVersions()) else r }
            j.versionsBefore = Areas.get(j.id)?.versions
            // T151: the tiles before the cursor were fetched at the versions the record holds; when the source moved on, the
            // whole walk runs again (the tiles cached at the current version are found in the cache, the rest refreshed).
            j.resumedFrom = j.cursor0?.takeIf { !versionsMoved(j.versionsBefore.orEmpty(), fetcher.currentVersions()) }
            val fromServer = if (j.planned0 != null || fetcher.server == DirectSources.SERVER) null else serverManifest(fetcher.server, j.area, j)
            val m = j.planned0 ?: fromServer ?: localManifest(j.area, fetcher.server)
            j.manifestFrom = if (j.planned0 != null) "plan" else if (fromServer != null) "server" else "phone"
            j.manifestMs = System.currentTimeMillis() - t0
            j.manifest = m
            if (j.raise) Areas.saveRaiseManifest(m) else Areas.saveManifest(m) // T386: the area's whole manifest stays as the change wrote it
            // T91: pinned tiles have their own budget (pinnedCacheMb, Settings › Storage); refuse a download that would pass it.
            OnlineService.get(app).cache.awaitReady("AreaDownload's pinned budget check") // T242: the pinned bytes are partial until then
            budgetRefusal(j.area, OnlineService.get(app).cache.pinnedBytes, m.bytes, if (j.raise) 0L else pinnedOwn(Areas.get(j.id)), AppSettings.pinnedCacheMb)?.let { throw Refused(it) }
            // T106: it fits, so it is pinned now, before any tile is fetched (nothing fetched for it is evicted on the way).
            Areas.update(j.id) { if (it.pinned) it else it.copy(pinned = true) }
            // T152: the manifest's rows in the walk's order (zoom, route, row), never a list of every tile.
            val items = m.items.filterIsInstance<TilesItem>()
            j.planned.set(m.tileCount)
            j.rowsPlanned.set(m.rowCount)
            j.state = "tiles"
            val c = j.resumedFrom
            if (c == null) {
                fetchRows(j, fetcher, pool, items, AreaManifest.walk(items), j.failedRows, countDone = true, moveCursor = true)
            } else {
                // T151: from the cursor. The rows through it count as done (less the tiles that failed in them, walked again
                // first), then the walk goes on after it; the whole prefix and its row carry on from the cursor's.
                j.skipped.set((c.tilesThrough(AreaManifest.walk(items)) - c.failedTiles).toInt().coerceAtLeast(0))
                j.done.set(j.skipped.get())
                j.wholePrefix = c.wholeRows
                j.lastWhole = c.row
                j.rowsDone.set(c.wholeRows)
                j.savedCursor = c.key
                val again = failedItems(items, c.failed)
                j.again = c.failedTiles
                if (again.isNotEmpty()) {
                    j.againDone = false
                    fetchRows(j, fetcher, pool, again, AreaManifest.walk(again), j.failedRows, countDone = true, moveCursor = false)
                    j.againDone = !j.cancelled && j.stalled == null && !j.capStopped
                }
                if (!j.cancelled && j.stalled == null && !j.capStopped) fetchRows(j, fetcher, pool, items, c.after(AreaManifest.walk(items)), j.failedRows, countDone = true, moveCursor = true, firstIndex = c.wholeRows)
            }
            val retry = synchronized(j.failedRows) { failedItems(items, j.failedRows) }
            if (retry.isNotEmpty() && !j.cancelled && j.stalled == null && !j.capStopped) { // T369: not past the cap
                j.state = "retry"
                fetcher.retryNow()
                j.failed.set(0)
                val still = HashMap<String, HashMap<Int, TileSet>>()
                fetchRows(j, fetcher, pool, retry, AreaManifest.walk(retry), still, countDone = false, moveCursor = false)
                // A second walk cut short keeps every tile of the first (the ones it did not reach are still owed).
                if (!j.cancelled && j.stalled == null && !j.capStopped) synchronized(j.failedRows) { j.failedRows.clear(); j.failedRows.putAll(still) }
            }
            if (j.contours && !j.cancelled && j.stalled == null && !j.capStopped) { // T156: not after a stall or a pause (the run is partial anyway)
                j.state = "contours"
                makeContours(j, pool)
            }
            j.state = when {
                j.cancelled -> "cancelled"
                j.failed.get() > 0 || j.stalled != null || j.capStopped -> "partial" // T151: a walk stopped by a stall did not reach the end; T369: nor one stopped by the cap
                else -> "done"
            }
            if (j.state == "done" && !j.raise) refreshed(j, fetcher, m, changesAtStart) // T386: a raise's manifest is not the area's
        } catch (e: Refused) {
            j.error = e.message
            j.state = "refused"
        } catch (e: Exception) {
            Log.w(Areas.TAG, "area download ${j.name} failed", e)
            j.error = e.message ?: e.javaClass.simpleName
            j.state = "failed"
        } finally {
            pool.shutdownNow()
            j.finishedAt = System.currentTimeMillis()
            val resumed = j.resumedFrom != null
            if (j.state == "refused" && j.isNew) {
                // T95: a new area the budget refused leaves nothing behind (an area already on the phone keeps its tiles).
                runCatching { Areas.remove(j.id) }
            } else if (j.raise) raiseEnded(j) else Areas.update(j.id) { r ->
                val ms = j.finishedAt - j.startedAt
                r.copy(
                    state = when (j.state) { "done" -> "done"; "refused" -> j.stateBefore ?: "partial"; else -> j.state },
                    error = j.error ?: when {
                        j.capStopped && !lessDetailHelps(j.area) -> capStoppedLine(lessHelps = false) // T386 (T369's follow-up): the remedy that fits
                        j.capStopped -> CAP_STOPPED // T369
                        j.pausedBy != null && j.state == "partial" -> RESUME_PAUSED // T156
                        j.stalled != null && j.state == "partial" -> RESUME_STALLED // T151
                        j.failed.get() > 0 -> failedTilesLine(j.failed.get()) // T151: it continues by itself (the card says when)
                        else -> null
                    },
                    // T95: a whole run counts what the area holds now (fetched plus already cached; an Update's old tiles are gone).
                    // T151: a run resumed from a cursor adds what it fetched to what the earlier runs did (the tiles before the cursor were not looked at).
                    bytes = when (j.state) { "refused" -> r.bytes; "done" -> if (resumed) j.bytes0 + j.bytes.get() else j.bytes.get() + j.cachedBytes.get(); else -> j.bytes0 + j.bytes.get() },
                    estimateBytes = j.manifest?.bytes ?: r.estimateBytes,
                    tiles = if (j.state == "refused") r.tiles else j.planned.get(),
                    downloadMs = if (j.state == "refused") r.downloadMs else if (resumed) (r.downloadMs ?: 0L) + ms else ms,
                    // T151: where it got to (none once whole), and the resume's backoff or stop.
                    cursor = when {
                        j.state == "done" -> null
                        j.state == "refused" || j.manifest == null -> r.cursor
                        else -> cursorNow(j, keepEarlier = !j.againDone)
                    },
                    autoResume = afterRun(r.autoResume, j.state, progressed = j.fetched.get() > 0, stalled = j.stalled != null && j.stalledByNetwork, now = j.finishedAt, budgetMb = AppSettings.pinnedCacheMb)
                        .let { a -> if (j.capStopped) (a ?: AutoResume()).copy(stopped = AutoResume.STOP_CAP, nextAt = 0L) else a }, // T369: not resumed by itself
                )
            }
            ResumeCheck.request(ResumeCheck.TRIGGER_DOWNLOAD_OVER) // T151: the next unfinished area, or this one after its backoff
            recent.addFirst(linkedMapOf("id" to j.id, "name" to j.name, "state" to j.state, "done" to j.done.get(), "planned" to j.planned.get(), "bytes" to j.bytes.get(), "finishedAt" to j.finishedAt, "ms" to j.finishedAt - j.startedAt))
            while (recent.size > 5) recent.pollLast()
            Log.i(Areas.TAG, "area ${j.name}: ${j.state}, ${j.fetched.get()} fetched, ${j.cached.get()} cached, ${j.inPacks.get()} in packs, ${j.bytes.get()} bytes, ${j.finishedAt - j.startedAt} ms")
            j.handedOn = true // T156
        }
    }

    /**
     * T95: a download that completed whole. Unless a version changed on the way (then some tiles are at each version
     * and the area keeps the old ones until the next Update), the area records the versions it holds now; when they
     * moved on, the contour and shade tiles made in its cover at the current version go (they may have been made from
     * the older DEM the pin served) and so do the old version's tiles that no other pin holds.
     */
    private fun refreshed(j: Job, fetcher: OnlineFetcher, m: AreaManifest, changesAtStart: Long) {
        if (fetcher.sourceList.sumOf { it.versionChanges.get() } != changesAtStart) return
        val now = fetcher.currentVersions()
        val before = Areas.get(j.id)?.versions.orEmpty()
        Areas.update(j.id) { it.copy(versions = now) }
        j.versionsAfter = now
        now[fetcher.serverKey]?.filterValues { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { Areas.saveManifest(m.copy(versions = it)) }
        if (before.isEmpty() || before == now) return
        val pins = AreaPins(listOf(AreaPins.cover(j.area)))
        val derived = if (now.any { (k, v) -> before[k]?.get("dem").let { b -> b != null && b != v["dem"] } }) fetcher.dropDerived { z, x, y -> pins.contains(z, x, y) } else 0
        j.refreshDropped = derived + Areas.dropUnheld() + dropOtherSources(j, before.keys - now.keys, pins)
        Log.i(Areas.TAG, "area ${j.name} refreshed from $before to $now: ${j.refreshDropped} old tiles deleted")
    }

    /**
     * T106: an area updated from a new source (the phone's server changed) no longer needs what it held under the old
     * ones ([gone], cache keys): their tiles in its cover go, unless another pinned area fetched from that source holds
     * them. Without this they stayed pinned (pins are spatial, whatever the source), unreachable, in the pinned budget.
     */
    private fun dropOtherSources(j: Job, gone: Set<String>, cover: AreaPins): Int {
        if (gone.isEmpty()) return 0
        val others = Areas.all().filter { it.id != j.id && it.pinned && it.pack == null && it.versions.keys.any { k -> k in gone } }
        val keep = gone.associateWith { src -> AreaPins(others.filter { src in it.versions.keys }.map { AreaPins.cover(it.area) }) }
        return OnlineService.get(app).cache.clearWhere { k ->
            val src = k.substringBefore('/')
            val held = keep[src] ?: return@clearWhere false
            AreaPins.tileOf(k)?.let { (z, x, y) -> cover.contains(z, x, y) && !held.contains(z, x, y) } == true
        }
    }

    // T386
    /**
     * T386: the end of a raise's run, written over the record: done, the raise is over (its manifest file goes); cancelled,
     * refused or failed, the area stays at the detail it holds whole with the raise left to Start (deferred, the reason kept for
     * a refusal or a failure); partial (a stall, a pause, failures, the cap's 50 MB), the raise goes on from the cursor as any
     * unfinished download does. The record's bytes are counted again from the cache when its index is whole (the manifest is
     * no longer the whole area), else the earlier bytes plus what this run fetched.
     */
    private fun raiseEnded(j: Job) {
        val r0 = Areas.get(j.id) ?: return
        val done = j.state == "done"
        val partial = j.state == "partial"
        val counted = if (OnlineService.get(app).cache.ready) Areas.countBytes(r0) else null
        val whole = Areas.manifest(j.id)
        val ms = j.finishedAt - j.startedAt
        Areas.update(j.id) { r ->
            r.copy(
                state = if (partial) "partial" else "done",
                raise = if (done) null else r.raise?.copy(deferred = !partial),
                error = when {
                    done || j.state == "cancelled" -> null
                    !partial -> j.error
                    j.capStopped -> capStoppedLine(lessHelps = false)
                    j.pausedBy != null -> RESUME_PAUSED
                    j.stalled != null -> RESUME_STALLED
                    j.failed.get() > 0 -> failedTilesLine(j.failed.get())
                    else -> null
                },
                cancelled = false,
                bytes = counted ?: (j.bytes0 + j.bytes.get()),
                estimateBytes = whole?.bytes ?: r.estimateBytes, tiles = whole?.tileCount ?: r.tiles,
                downloadMs = (r.downloadMs ?: 0L) + ms,
                cursor = if (partial && j.manifest != null) cursorNow(j, keepEarlier = !j.againDone) else null,
                autoResume = if (!partial) null else afterRun(r.autoResume, j.state, progressed = j.fetched.get() > 0, stalled = j.stalled != null && j.stalledByNetwork, now = j.finishedAt, budgetMb = AppSettings.pinnedCacheMb)
                    .let { a -> if (j.capStopped) (a ?: AutoResume()).copy(stopped = AutoResume.STOP_CAP, nextAt = 0L) else a },
            )
        }
        if (done) Areas.raiseManifestFile(j.id).delete()
    }

    /** T386: the elevation's lowest zoom of [server]'s tiles (the public sources' z8, a server's z6), as [zooms] takes it. */
    fun demLo(server: String?): Int = if (server == DirectSources.SERVER) DirectSources.DEM_MIN_ZOOM else OnlineTiles.DEM_MIN_ZOOM

    /** T386: [r] changed to [to]: from the zooms it holds whole ([Areas.Record.heldArea]) and as recorded, its shape and name kept. */
    fun changeOf(r: Areas.Record, to: AreaDetail, server: String?): DetailChange.Change = DetailChange.of(r.heldArea, r.area.at(to), demLo(server), current = r.area)

    /** T386: the added tiles of [r]'s raise, made again when its file is gone. */
    private fun raiseManifestOf(r: Areas.Record, server: String?): AreaManifest =
        DetailChange.raiseManifest(DetailChange.of(r.heldArea, r.area, demLo(server)), source = "phone", generatedAt = java.time.Instant.now().toString())

    /** T386: why [r] offers no Detail, or null when it does (a drawn area or a disc kept whole, or one whose raise waits or runs). */
    fun detailRefusal(r: Areas.Record): String? = when {
        r.opaque != null -> io.github.jasondorjeshort.dystopo.areas.NeedsNewerApp.USER_SENTENCE
        r.pack != null || r.area.shape is AreaShape.Box -> DETAIL_PACK
        r.tripId != null -> TRIP_DETAIL_LINE
        job?.let { it.id == r.id && it.running } == true -> DETAIL_BUSY
        Areas.lowerings.any { it.id == r.id } -> DETAIL_BUSY
        r.state != "done" && r.raise == null -> DETAIL_NOT_SAVED
        else -> null
    }

    /**
     * T386: one choice of the Detail sheet for a record: [detail], its [label] ([detailLabel]), whether it is what the area holds
     * [now] or the detail a raise waits for ([pending]); its [bytes] after the change and [delta] against today's (the record's
     * bytes); for a lower [freedBytes] (what leaves the phone: tiles another area or a pin holds stay) and [counted] (from the cache,
     * else an estimate); for a raise the added tiles ([addedBytes], [addedTiles], [addedVectorTiles]) and [refusal] (the cap's or
     * the budget's sentence: no Start then).
     */
    class DetailOption(
        val detail: AreaDetail, val label: String, val now: Boolean, val pending: Boolean, val change: DetailChange.Change,
        val bytes: Long, val delta: Long, val freedBytes: Long, val counted: Boolean, val ownBytes: Long,
        val addedBytes: Long, val addedTiles: Int, val addedVectorTiles: Int, val refusal: String?, val overCap: Boolean, heldLabel: String,
    ) {
        val line: String = detailOptionLine(now, pending, bytes, delta, addedBytes, raise = change.raise)
        val note: String? = if (detail == AreaDetail.LESS) LESS_SEARCH_NOTE else null
        val confirm: String? = if (now) null else detailConfirmLine(change, label, heldLabel, freedBytes, addedBytes, addedTiles, ownBytes)

        fun json(): Map<String, Any?> = linkedMapOf(
            "detail" to detail.key, "label" to label, "now" to now, "pending" to pending, "line" to line, "note" to note, "confirm" to confirm,
            "bytes" to bytes, "delta" to delta, "freedBytes" to freedBytes, "ownBytes" to ownBytes, "counted" to counted,
            "added" to linkedMapOf("bytes" to addedBytes, "tiles" to addedTiles, "vectorTiles" to addedVectorTiles), "refusal" to refusal, "overCap" to overCap,
            "change" to change.json(), "id" to change.target.id,
        )
    }

    /**
     * T386: the Detail sheet's three choices for record [id] (Less, Medium, Full), off the main thread: the lower's freed bytes
     * from the cache when its index is whole (else the estimate), a raise's tiles planned (its cap and budget checked).
     */
    fun detailOptions(id: String): List<DetailOption> {
        val r = Areas.get(id) ?: throw NoSuchElementException("no area '$id'")
        detailRefusal(r)?.let { throw IllegalStateException(it) }
        val service = OnlineService.get(app)
        val fetcher = service.fetcher ?: throw IllegalStateException(NO_SOURCE)
        val direct = fetcher.server == DirectSources.SERVER
        val old = Areas.manifest(r.id) ?: localManifest(r.area, fetcher.server)
        val heldLabel = detailLabel(r.heldArea)
        return AreaDetail.entries.reversed().map { d -> // Less, Medium, Full
            val c = changeOf(r, d, fetcher.server)
            val dropped = Areas.droppedBytes(r, DetailChange.droppedItems(old, c))
            val own = dropped["own"] as Long
            val added = if (c.raise) DetailChange.raiseManifest(c) else null
            val addedBytes = added?.bytes ?: 0L
            val cap = if (direct && added != null) raiseCapRefusalLine(added.vectorTileCount.toLong()) else null
            val refusal = cap ?: added?.let { budgetRefusal(c.target, service.cache.pinnedBytes, it.bytes, 0L, AppSettings.pinnedCacheMb) }
            val now = c.target.id == r.heldArea.id
            // the area's figure is the row's: the record's bytes, counted from the cache (a part-fetched raise's tiles in it)
            val bytes = if (now) r.bytes else (r.bytes - own).coerceAtLeast(0L) + addedBytes
            DetailOption(
                d, detailLabel(c.target), now = now, pending = r.raise != null && c.target.id == r.area.id, c,
                bytes, bytes - r.bytes, dropped["freed"] as Long, dropped["counted"] == true, own,
                addedBytes, added?.tileCount ?: 0, added?.vectorTileCount ?: 0, refusal, cap != null, heldLabel,
            )
        }
    }

    /**
     * T386: changes record [id] to [to] with its shape kept (the Detail sheet's choice, `POST /areas/{id}/detail`). A lower (or the
     * held detail chosen again while a raise waits) frees what it drops ([Areas.applyDetail]) and fetches nothing. A raise is
     * refused before anything changes when the public source's cap or the budget refuses its added tiles; else the record is
     * re-keyed with its [Areas.Record.Raise], the added tiles' manifest saved, and with [start] the download of those alone begins
     * (the area usable at its old detail meanwhile); without it, "Later", the raise waits on the row for Start, through a restart.
     */
    fun changeDetail(id: String, to: AreaDetail, start: Boolean = true, chunkDelayMs: Long = 0): Map<String, Any?> {
        val r = Areas.get(id) ?: throw NoSuchElementException("no area '$id'")
        detailRefusal(r)?.let { throw IllegalStateException(it) }
        val service = OnlineService.get(app)
        val fetcher = service.fetcher ?: throw IllegalStateException(NO_SOURCE)
        val change = changeOf(r, to, fetcher.server)
        if (change.none) return linkedMapOf("id" to r.id, "unchanged" to true, "change" to change.json())
        if (!change.raise) return Areas.applyDetail(r, change, raise = null, chunkDelayMs).second + ("change" to change.json())
        val m = DetailChange.raiseManifest(change, source = if (fetcher.server == DirectSources.SERVER) "phone (public sources)" else "phone", generatedAt = java.time.Instant.now().toString())
        if (fetcher.server == DirectSources.SERVER) raiseCapRefusalLine(m.vectorTileCount.toLong())?.let { throw CapRefused(it) }
        budgetRefusal(change.target, service.cache.pinnedBytes, m.bytes, 0L, AppSettings.pinnedCacheMb)?.let { throw BudgetRefused(it) }
        if (start) check(job?.running != true) { "an area download is already running (${job?.name}); DELETE /areas/download first" }
        val (n, out) = Areas.applyDetail(r, change, Areas.Record.Raise.of(change.held, deferred = !start), chunkDelayMs)
        Areas.saveRaiseManifest(m)
        val result = LinkedHashMap(out)
        result["change"] = change.json()
        result["raise"] = linkedMapOf("tiles" to m.tileCount, "vectorTiles" to m.vectorTileCount, "bytes" to m.bytes, "perRoute" to m.items.filterIsInstance<TilesItem>().associate { it.route to it.tiles.keys.sorted() }, "deferred" to !start)
        if (start) result["download"] = start(n.area, n.name, n.tripId)["download"]
        return result
    }

    /** T386: the row's Start on a raise that waits (or Retry on one that stopped): the added tiles' download, from its cursor. */
    fun startRaise(id: String): Map<String, Any?> {
        val r = Areas.get(id) ?: throw NoSuchElementException("no area '$id'")
        check(r.raise != null) { "area ${r.name} has no raise of detail waiting" }
        return start(r.area, r.name, r.tripId)
    }
    // end T386

    /** T152: a row of the walk being fetched: its index in the walk and the tiles of it not finished yet. */
    private class RowInFlight(val index: Int, val row: TileRow) {
        val left = AtomicInteger(row.count)
    }

    /**
     * T152: fetches the tiles of [rows] (the walk of [items]) on [pool], row by row, with at most [IN_FLIGHT] tiles queued,
     * so nothing here grows with the area. The tiles that failed (network or server) go into [failed] as row sets per route
     * and zoom, for a second walk. T151: [countDone] counts the tiles in `done`; [moveCursor] (the walk itself, not the
     * failed tiles walked again) keeps the row counts and the whole prefix, starting at index [firstIndex] of the walk (the
     * cursor's), and saves the cursor every [CURSOR_SAVE_MS]. The walk stops early ([Job.stalled]) when [STALL_TILES] tiles in
     * a row could not be fetched (the network stopped answering) or, in the walk itself, [MAX_FAILED_TILES] failed.
     */
    private fun fetchRows(
        j: Job, fetcher: OnlineFetcher, pool: java.util.concurrent.ExecutorService, items: List<TilesItem>, rows: Sequence<TileRow>,
        failed: HashMap<String, HashMap<Int, TileSet>>, countDone: Boolean, moveCursor: Boolean, firstIndex: Int = 0,
    ) {
        val byRoute = items.associateBy { it.route }
        val open = ArrayDeque<RowInFlight>() // rows started and not whole yet, in the walk's order (guarded by itself)
        val permits = Semaphore(IN_FLIGHT)
        fun tileDone(r: RowInFlight) {
            if (!moveCursor || r.left.decrementAndGet() != 0) return
            j.rowsDone.incrementAndGet()
            synchronized(open) {
                while (open.isNotEmpty() && open.first().left.get() == 0) open.removeFirst().let { w -> j.wholePrefix = w.index + 1; j.lastWhole = w.row }
            }
        }
        fun stalled(): Boolean {
            if (j.unavailableRun.get() >= STALL_TILES) {
                j.stalledByNetwork = j.lastUnavailable?.startsWith("server unreachable") == true
                j.stalled = "$STALL_TILES tiles in a row could not be fetched: ${j.lastUnavailable}"
            } else if (moveCursor && j.failedNow.get() >= MAX_FAILED_TILES) {
                j.stalled = "$MAX_FAILED_TILES tiles could not be fetched in this run"
            }
            return j.stalled != null
        }
        var index = firstIndex
        walk@ for (row in rows) {
            if (j.cancelled || stalled() || j.capStopped) break // T369: the cap's bytes
            if (moveCursor && System.currentTimeMillis() - j.lastCursorSave >= CURSOR_SAVE_MS) saveCursor(j)
            val item = byRoute.getValue(row.route)
            val r = RowInFlight(index++, row)
            if (moveCursor) {
                synchronized(open) { open.addLast(r) }
                j.rowsStarted.incrementAndGet()
                j.rowNow = row
            }
            for (x in row.x0..row.x1) {
                if (j.cancelled || stalled() || j.capStopped) break@walk
                permits.acquire()
                try {
                    pool.execute {
                        try {
                            val ok = try {
                                fetchTile(j, fetcher, item, row.z, x, row.y, counted = !countDone)
                            } catch (e: Exception) {
                                Log.w(Areas.TAG, "an area tile of ${row.route} failed", e)
                                false
                            }
                            if (!ok) {
                                if (moveCursor) j.failedNow.incrementAndGet()
                                synchronized(failed) { failed.getOrPut(row.route) { HashMap() }.getOrPut(row.z) { TileSet(row.z) }.add(x, row.y) }
                            }
                        } finally {
                            tileDone(r)
                            permits.release()
                        }
                    }
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    permits.release()
                    throw e
                }
            }
        }
        permits.acquire(IN_FLIGHT) // every tile queued has finished
        permits.release(IN_FLIGHT)
    }

    /**
     * T151: the cursor now: the last row of the whole prefix, with the failed tiles of the rows through it (a failure in a
     * later row is walked again after the cursor anyway). [keepEarlier]: the earlier failed tiles were not all walked again
     * (the run was cut short first), so the cursor it resumed from keeps them too.
     */
    private fun cursorNow(j: Job, keepEarlier: Boolean = false): DownloadCursor {
        val row = j.lastWhole
        val failed = synchronized(j.failedRows) { failedThrough(j.failedRows, row) }
        val kept = if (keepEarlier) mergeFailed(failed, j.resumedFrom?.failed.orEmpty()) else failed
        return DownloadCursor(row, j.wholePrefix, kept, System.currentTimeMillis())
    }

    /** T151: saves [cursorNow] in the record when it moved (every [CURSOR_SAVE_MS] while rows complete). */
    private fun saveCursor(j: Job) {
        j.lastCursorSave = System.currentTimeMillis()
        val c = cursorNow(j, keepEarlier = !j.againDone)
        val stamp = "${c.key} ${c.failedTiles}"
        if (stamp == j.savedCursor) return
        Areas.update(j.id) { it.copy(cursor = c, bytes = j.bytes0 + j.bytes.get()) } // the bytes with it, so a kill loses only the last seconds
        j.savedCursor = stamp
        j.cursorSaves++
    }

    /** T151: [failed] (route, zoom: rows) as tile items of [items]' routes, for a walk. */
    private fun failedItems(items: List<TilesItem>, failed: Map<String, Map<Int, TileSet>>): List<TilesItem> =
        failed.mapNotNull { (route, sets) -> items.firstOrNull { it.route == route }?.let { TilesItem(it.route, it.ext, it.url, sets.filterValues { s -> s.size > 0 }, 0L) } }
            .filter { it.count > 0 }

    /** One tile of [item] at [z]/[x]/[y]: false when it could not be fetched now (network or server). */
    private fun fetchTile(j: Job, fetcher: OnlineFetcher, item: TilesItem, z: Int, x: Int, y: Int, counted: Boolean): Boolean {
        if (j.cancelled) return true
        if (j.capStopped) return false // T369: a tile queued past the cap stays owed (its row is not whole) and is not fetched
        // T356 (ADR 0066): skipped only where a map stores the tile at its own depth; a map answers deeper elevation by
        // overzoom, so a z15 area inside a map to z11 skipped its elevation before.
        val held = runCatching { app.tiles.held }.getOrNull()
        val inPack = when (item.route) {
            AreaManifest.ROUTE_DEM -> held?.mapStores(io.github.jasondorjeshort.dystopo.packs.Holdings.Role.DEM, z, x, y) == true
            AreaManifest.ROUTE_OSM -> runCatching { app.tiles.packs }.getOrNull()?.osmFor(z, x, y) != null
            else -> false
        }
        // end T356
        if (inPack) {
            j.inPacks.incrementAndGet()
            if (!counted) j.done.incrementAndGet()
            return true
        }
        val path = "$z/$x/$y.${item.ext}"
        val transform: (ByteArray) -> ByteArray = if (item.route == AreaManifest.ROUTE_OSM) OnlineFetcher::gunzipIfNeeded else { b -> b }
        val ok = when (val f = DirectSources.download { fetcher.get(item.route, path, transform, fresh = true) }) { // T95: the current version, so a download again refreshes; T355: a download
            is Fetch.Hit -> {
                if (!valid(item.route, f.bytes)) {
                    Areas.cacheKeys(item.route, path, held = false).forEach { OnlineService.get(app).cache.remove(it) } // T242: one key, also while indexing
                    j.invalid.incrementAndGet()
                } else if (f.cached) {
                    j.cached.incrementAndGet()
                    j.cachedBytes.addAndGet(f.bytes.size.toLong())
                } else {
                    j.fetched.incrementAndGet()
                    j.bytes.addAndGet(f.bytes.size.toLong())
                    // T369: the vector bytes fetched from the public source, against its 50 MB a download.
                    if (j.direct && item.route == AreaManifest.ROUTE_OSM) {
                        val n = f.bytes.size.toLong()
                        if (AreaLimit.directBytesReached(j.vectorBytes.addAndGet(n), j.largestTile.accumulateAndGet(n, ::maxOf), POOL)) j.capStopped = true
                    }
                }
                j.unavailableRun.set(0)
                true
            }
            Fetch.Missing -> { j.missing.incrementAndGet(); j.unavailableRun.set(0); true }
            is Fetch.Unavailable -> { j.failed.incrementAndGet(); j.lastUnavailable = f.reason; j.unavailableRun.incrementAndGet(); false }
        }
        if (!counted) j.done.incrementAndGet()
        return ok
    }

    private fun valid(route: String, bytes: ByteArray): Boolean = when (route) {
        AreaManifest.ROUTE_DEM -> bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        else -> true
    }

    /**
     * The corridor's contour tiles (the map's 512-px contour zooms up to one past the DEM), made and cached now. T152: row
     * by row with at most [IN_FLIGHT] queued, as the tiles are.
     */
    private fun makeContours(j: Job, pool: java.util.concurrent.ExecutorService) {
        val hi = minOf(j.area.demTop, OnlineTiles.DEM_MAX_ZOOM) + 1 // T157: one past the area's DEM (a medium area's z13)
        val lo = maxOf(j.area.minZoom, OnlineTiles.CONTOUR_MIN_ZOOM)
        val permits = Semaphore(IN_FLIGHT)
        for (z in lo..hi) {
            if (j.cancelled) break
            TileCover.of(j.area, z).forEachRow { y, x0, x1 ->
                for (x in x0..x1) {
                    if (j.cancelled) return@forEachRow
                    permits.acquire()
                    pool.execute {
                        try {
                            if (!j.cancelled && runCatching { app.tiles.contourTile(z, x, y).status == 200 }.getOrDefault(false)) j.contourTiles.incrementAndGet()
                        } finally {
                            permits.release()
                        }
                    }
                }
            }
        }
        permits.acquire(IN_FLIGHT)
        permits.release(IN_FLIGHT)
    }

    /**
     * T140: the `GET /area` answer of each server (empty for a server without one); [serverShapesOf] reads its shapes (box
     * and corridor when it names none: a server from before T140), T157 [serverZoomCapsOf] its zoom caps (none before T157).
     */
    private val areaHelpByServer = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()

    private fun serverAreaHelp(server: String): Map<String, Any?> = areaHelpByServer.getOrPut(server) {
        val conn = CleartextPolicy.open("${server.trimEnd('/')}/area")
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            if (conn.responseCode == 200) MiniJson.parseObject(conn.inputStream.use { it.readBytes().decodeToString() }) else emptyMap()
        } catch (e: IOException) {
            return emptyMap() // not kept: asked again next time
        } finally {
            conn.disconnect()
        }
    }

    /** `POST <server>/area` for [area] as tile lists (`pack=0`); null when the server has no `/area` (older) or cannot be reached. */
    private fun serverManifest(server: String, area: AreaOfInterest, j: Job?): AreaManifest? {
        if (area.kind !in AreaShape.KINDS.take(2) && area.kind !in serverShapesOf(serverAreaHelp(server))) return null // T140: it does not read this shape
        // T157: a server from before the medium detail would ignore demMaxZoom and list the DEM to maxZoom: the phone makes it.
        if (area.demMaxZoom != null && AreaManifest.ROUTE_DEM !in serverZoomCapsOf(serverAreaHelp(server))) {
            j?.serverNote = "the server does not read demMaxZoom (GET /area zoomCaps); the phone made the manifest"
            return null
        }
        val conn = CleartextPolicy.open("${server.trimEnd('/')}/area?pack=0")
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = MiniJson.encode(area.json()).toByteArray()
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            when (status) {
                // T157: a manifest of another area (a server that dropped a field it does not know) is not this one's.
                200 -> AreaManifest.parse(text).copy(source = server).takeIf { it.id == area.id }
                    ?: null.also { j?.serverNote = "the server's manifest was for another area (it did not keep every field); the phone made it" }
                404, 405 -> null // a server from before T75: make the lists here
                // T140 (ADR 0041 §3): a server from before the polygon kind ("kind must be box or corridor"): the lists are made here.
                400 -> if (serverError(text).startsWith("kind must be")) null else throw IOException("server refused the area (HTTP 400): ${serverError(text)}")
                // T152: over a server's limit (DYSTOPO_AREA_MAX_TILES, or an older server's heap check): the phone's own manifest
                // is the path past it; the tiles still come from the server's tile routes.
                413 -> null.also { j?.serverNote = "the server's manifest was refused (HTTP 413: ${serverError(text)}); the phone made it" }
                else -> throw IOException("server refused the area (HTTP $status): ${runCatching { MiniJson.parseObject(text)["error"] }.getOrNull() ?: text.take(200)}")
            }
        } catch (e: IOException) {
            if (e.message?.startsWith("server refused") == true) throw e
            Log.w(Areas.TAG, "no manifest from $server: ${e.message}; making it here")
            j?.error = null
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The manifest of a corridor made on the phone: DEM tiles from the source's lowest zoom (contours sample
     * coarser zooms than they show) to the area's deepest, vector tiles to z14, one tile of margin, plus the
     * bundled style assets and what the phone makes itself. [server] is the fetcher's target.
     */
    fun localManifest(area: AreaOfInterest, server: String?): AreaManifest {
        val direct = server == DirectSources.SERVER
        val (demZooms, osmZooms) = zooms(area, server)
        val items = ArrayList<ManifestItem>(AreaManifest.tileItems(area, demZooms, osmZooms))
        items += LocalItem(ManifestItem.KIND_CONTOURS, "dem", true, "contour, hillshade and cliff tiles are made on the phone from the DEM tiles")
        items += LocalItem(ManifestItem.KIND_GRAPH, null, false, "${if (area.shape is AreaShape.Polygon) "an area" else "a corridor"} is tiles in the cache; the trail graph is built from vector packs")
        items += AssetItem(ManifestItem.KIND_GLYPHS, "/glyphs/{fontstack}/{range}.pbf", 0, 0, bundled = true)
        items += AssetItem(ManifestItem.KIND_SPRITE, "/{file}", 4, 0, bundled = true, names = listOf("sprite.json", "sprite.png", "sprite@2x.json", "sprite@2x.png"))
        return AreaManifest(
            area.id, area.name ?: area.id, area, AreaManifest.STATUS_READY, items, source = if (direct) "phone (public sources)" else "phone",
            generatedAt = java.time.Instant.now().toString(),
        )
    }

    /**
     * T140: the DEM and vector zooms of [area]'s manifest for [server] (DEM from the source's lowest zoom, vector to z14).
     * T157: the DEM to the area's `demMaxZoom` when it has one ([AreaOfInterest.demZooms]).
     */
    fun zooms(area: AreaOfInterest, server: String?): Pair<IntRange, IntRange?> {
        val demLo = if (server == DirectSources.SERVER) DirectSources.DEM_MIN_ZOOM else OnlineTiles.DEM_MIN_ZOOM
        return area.demZooms(demLo, OnlineTiles.DEM_MAX_ZOOM) to area.osmZooms(OnlineTiles.OSM_MAX_ZOOM)
    }

    private fun serverError(text: String): String = runCatching { MiniJson.parseObject(text)["error"]?.toString() }.getOrNull() ?: text.take(200)

    /** T140 (ADR 0041 §5): the less-detail alternative's deepest zoom (z8 to z13). */
    const val LESS_DETAIL_MAX_ZOOM = 13

    private const val POOL = 6
    /** T152: tiles queued at most (the walk waits for a slot), so the work in hand never grows with the area. */
    private const val IN_FLIGHT = POOL * 4

    /** T151: the trigger of a download the user started (a tap, a route, a trip's corridor). */
    const val TRIGGER_USER = "user"

    /** T151: the cursor is saved at most this often while rows complete (and when the run ends): a kill loses a few seconds. */
    const val CURSOR_SAVE_MS = 2_000L

    /**
     * T151: tiles in a row that could not be fetched before the walk stops as stalled (two full queues: every worker failed
     * twice over, which a lost network or a down server does within seconds, while a few bad tiles never do).
     */
    const val STALL_TILES = IN_FLIGHT * 2

    /** T151: failed tiles in one walk before it stops, which bounds the failed rows the record keeps (a few KB at most). */
    const val MAX_FAILED_TILES = 512
    private const val NO_SOURCE = "no online source: set a server (Settings › Sources) or let the phone read the sources"
}

/**
 * T91: why a download of about [estimateBytes] may not start, with [pinnedBytes] already pinned ([alreadyBytes] of them
 * this area's own, from an earlier download) and a budget of [budgetMb]; null when it fits. Pure, tested.
 */
internal fun budgetError(pinnedBytes: Long, estimateBytes: Long, alreadyBytes: Long, budgetMb: Long): String? {
    val mb = 1024L * 1024L
    val after = pinnedBytes - minOf(alreadyBytes, pinnedBytes) + estimateBytes
    if (after <= budgetMb * mb) return null
    return "Offline maps in the budget would use about ${aboutMb(after)}, over its ${io.github.jasondorjeshort.dystopo.app.storage.Storage.sizeLabel(budgetMb)}: remove one or raise the budget in Settings › Storage" // T115: "128 GB", not "131072 MB"
}

/**
 * T95: a size for the user, "29 MB" (nearest whole MB from 10 MB) or "4.2 MB" (one decimal below), so the trip's
 * Offline offer and the budget message say the same number for the same bytes. Pure, tested.
 */
internal fun aboutMb(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 10) String.format(java.util.Locale.US, "%.0f MB", mb) else String.format(java.util.Locale.US, "%.1f MB", mb)
}

/**
 * T140 (ADR 0041 §4, §5): a drawn area or the area around a point (a polygon, or the one-point corridor), whose plan and
 * budget refusal are the area sheet's; a trip's corridor keeps T95's.
 */
internal fun userShape(area: AreaOfInterest): Boolean = when (val s = area.shape) {
    is AreaShape.Polygon -> true
    is AreaShape.Corridor -> s.points.size == 1
    is AreaShape.Box -> false
}

/** T140: `box`, `corridor`, `disc` (a one-point corridor) or `polygon`. */
internal fun shapeName(area: AreaOfInterest): String = if (area.shape is AreaShape.Corridor && (area.shape as AreaShape.Corridor).points.size == 1) "disc" else area.kind

private fun bufferOf(area: AreaOfInterest): Double? = when (val s = area.shape) {
    is AreaShape.Corridor -> s.bufferM
    is AreaShape.Polygon -> s.bufferM
    is AreaShape.Box -> null
}

/** T140: the budget refusal for [area]: the area sheet's sentence for a drawn area or a disc, T91's for a trip's corridor. */
internal fun budgetRefusal(area: AreaOfInterest, pinnedBytes: Long, estimateBytes: Long, alreadyBytes: Long, budgetMb: Long): String? =
    if (userShape(area)) areaBudgetError(pinnedBytes, estimateBytes, alreadyBytes, budgetMb) else budgetError(pinnedBytes, estimateBytes, alreadyBytes, budgetMb)

/**
 * T140 (ADR 0041 §5, the owner's position): over the pinned budget, a drawn area or a disc says "This area is about
 * 450 MB; 1.6 GB of your 2 GB offline budget is in use. Make the area smaller, choose less detail, or raise the budget in
 * Settings › Storage."; null when it fits (the same test as [budgetError]). Pure, tested.
 */
internal fun areaBudgetError(pinnedBytes: Long, estimateBytes: Long, alreadyBytes: Long, budgetMb: Long): String? {
    if (budgetError(pinnedBytes, estimateBytes, alreadyBytes, budgetMb) == null) return null
    val inUse = pinnedBytes - minOf(alreadyBytes, pinnedBytes)
    return "This area is about ${aboutSize(estimateBytes)}; ${aboutSize(inUse)} of your ${budgetSize(budgetMb)} offline budget is in use. " +
        "Make the area smaller, choose less detail, or raise the budget in Settings › Storage."
}

/**
 * T140: [aboutMb] below 1 GB, else "1.6 GB"; T152: from 1 TB "11.5 TB" (areas past the old heap limit reach it); T170
 * (T152's follow-up): whole gigabytes from 100 GB, "174 GB". Pure, tested.
 */
internal fun aboutSize(bytes: Long): String = when {
    bytes >= 1L shl 40 -> String.format(java.util.Locale.US, "%.1f TB", bytes / 1_099_511_627_776.0)
    bytes >= 100L shl 30 -> String.format(java.util.Locale.US, "%.0f GB", bytes / 1_073_741_824.0)
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    else -> aboutMb(bytes)
}

/**
 * T170 (T152's follow-up, "11818.4 GB of your 1024 GB"): the budget in [areaBudgetError]'s sentence in [aboutSize]'s units,
 * so both sizes read alike: the Storage screen's label up to 100 GB ("2 GB", "1.5 GB"), whole gigabytes from there
 * ("128 GB"), terabytes from 1 TB ("1 TB", "1.5 TB"). Pure, tested.
 */
internal fun budgetSize(budgetMb: Long): String = when {
    budgetMb >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f TB", budgetMb / 1_048_576.0).replace(".0 TB", " TB")
    budgetMb >= 100L shl 10 -> String.format(java.util.Locale.US, "%.0f GB", budgetMb / 1024.0)
    else -> io.github.jasondorjeshort.dystopo.app.storage.Storage.sizeLabel(budgetMb)
}

/**
 * T140 (ADR 0041 §5): the summary, "About 170 MB (1,300 tiles) at full detail"; "at less detail" for z8 to z13, the zooms
 * otherwise. T157: "at medium detail" for z8 to z14 with the elevation to z13 ([demMaxZoom]); another capped range says
 * both ("at zoom 8 to 14, elevation to 12"). T221: the medium line names its cap as the Areas card's [zoomText] does,
 * "at medium detail, elevation to z13". Pure, tested.
 */
internal fun sizeLine(bytes: Long, tiles: Int, minZoom: Int, maxZoom: Int, demMaxZoom: Int? = null): String {
    val detail = when {
        demMaxZoom != null && minZoom == AreaDetail.MIN_ZOOM && maxZoom == AreaDetail.MEDIUM.maxZoom && demMaxZoom == AreaDetail.MEDIUM.demMaxZoom -> "at ${detailWord(AreaDetail.MEDIUM)} detail, elevation to z$demMaxZoom"
        demMaxZoom != null -> "at zoom $minZoom to $maxZoom, elevation to $demMaxZoom"
        maxZoom >= io.github.jasondorjeshort.dystopo.app.online.OnlineTiles.DEM_MAX_ZOOM -> "at ${detailWord(AreaDetail.FULL)} detail"
        minZoom == 8 && maxZoom == AreaDownload.LESS_DETAIL_MAX_ZOOM -> "at ${detailWord(AreaDetail.LESS)} detail"
        else -> "at zoom $minZoom to $maxZoom"
    }
    return "About ${aboutSize(bytes)} (${String.format(java.util.Locale.US, "%,d", tiles)} tiles) $detail"
}

// T369
/**
 * T369 (ADR 0031: the coordinator's words, listed for the owner): the refusal of a download over the public vector source's
 * cap, "This area needs about 4,600 map tiles, and the public map source allows 3,000 a download. Make the area smaller,
 * choose less detail, or set a server in Settings › Sources."; without "choose less detail" where less detail would not cut
 * the vector tiles ([lessHelps] false). Null within the cap. Names no other product. Pure, tested.
 */
internal fun capRefusalLine(vectorTiles: Long, lessHelps: Boolean): String? {
    if (!AreaLimit.overDirectCap(vectorTiles)) return null
    fun n(v: Long) = String.format(java.util.Locale.US, "%,d", v)
    return "This area needs about ${n(roundTiles(vectorTiles))} map tiles, and the public map source allows ${n(AreaLimit.DIRECT_MAX_TILES)} a download. " + capRemedies(lessHelps)
}

/** T369: the remedies the cap's sentences offer: a smaller area, less detail (where it helps) or a server. Pure, tested. */
internal fun capRemedies(lessHelps: Boolean): String =
    if (lessHelps) "Make the area smaller, choose less detail, or set a server in $CAP_SERVER_PLACE." else "Make the area smaller, or set a server in $CAP_SERVER_PLACE."

/** T369: a tile count up to the next hundred from 1,000 ("about 4,600"; 3,001 reads "3,100", never the cap's own figure). Pure, tested. */
internal fun roundTiles(n: Long): Long = if (n < 1_000) n else (n + 99) / 100 * 100

/** T369: whether less detail cuts [area]'s vector tiles: its vector reaches past z13, where less detail stops. Pure, tested. */
internal fun lessDetailHelps(area: AreaOfInterest): Boolean = minOf(area.maxZoom, AreaManifest.OSM_MAX_ZOOM) > AreaDownload.LESS_DETAIL_MAX_ZOOM

/** T369: where a server is set, "Settings › Sources". */
internal val CAP_SERVER_PLACE = "${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SETTINGS} › ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SOURCES}"

/** T369 (ADR 0031): the line of a download that reached the cap's 50 MB and stopped there, its tiles kept. */
internal val CAP_STOPPED = "Stopped at 50 MB, the most the public map source allows a download; the tiles fetched so far are kept. " + capRemedies(true)
// end T369

// T386
/**
 * T386 (T369's follow-up): the line of a download that reached the cap's 50 MB, with the remedies that fit its case: less detail
 * only where it would cut the vector tiles ([lessHelps]); a raise of detail never offers it. [CAP_STOPPED] is the one with it.
 */
internal fun capStoppedLine(lessHelps: Boolean): String =
    "Stopped at 50 MB, the most the public map source allows a download; the tiles fetched so far are kept. " + capRemedies(lessHelps)

/**
 * T386 (ADR 0031): a raise of detail over the public vector source's cap, counted on the added tiles alone: "Adding this detail
 * needs about 4,600 map tiles, and the public map source allows 3,000 a download. Make the area smaller, or set a server in
 * Settings › Sources." (a server or a smaller shape, never less detail). Null within the cap. Pure, tested.
 */
internal fun raiseCapRefusalLine(vectorTiles: Long): String? {
    if (!AreaLimit.overDirectCap(vectorTiles)) return null
    fun n(v: Long) = String.format(java.util.Locale.US, "%,d", v)
    return "Adding this detail needs about ${n(roundTiles(vectorTiles))} map tiles, and the public map source allows ${n(AreaLimit.DIRECT_MAX_TILES)} a download. " + capRemedies(false)
}

/** T386: a detail's name as the app writes it everywhere (wave 48's one function, [io.github.jasondorjeshort.dystopo.packs.HeldHere.detailOf], through [DetailOutline]): "Less detail". */
internal fun detailLabel(area: AreaOfInterest): String = DetailOutline.levelOf(area.maxZoom, area.demMaxZoom ?: area.maxZoom).label

/** T386 (ADR 0031): the Detail action on an area's row, its sheet's title and its words. */
internal const val DETAIL = "Detail"
internal const val DETAIL_INTRO = "The area keeps its shape. Less detail frees space; more detail downloads only what it adds."
internal const val LESS_SEARCH_NOTE = "Search finds fewer places at this detail."
internal const val TRIP_DETAIL_LINE = "Its detail follows the trip."
internal const val DETAIL_PACK = "A map from a file or a server keeps the detail it was made with."
internal const val DETAIL_BUSY = "Wait until the download or the change running now is over."
internal const val DETAIL_NOT_SAVED = "Its download has to finish first."
internal const val DETAIL_TAKEN = "This shape is already saved at that detail."
internal const val START_NOW = "Start now"
internal const val LATER = "Later"
internal const val LOWER = "Lower"
internal const val DROP_DOWNLOAD = "Drop the download"
internal const val CANCEL = "Cancel"

/** T386: a size in the Detail sheet, as the row writes it ([OfflineMapList.sizeText]: "705 kB", "4.9 MB", "17 MB"). */
internal fun detailSize(bytes: Long): String = OfflineMapList.sizeText(bytes)

/**
 * T386: a Detail choice's size line, its figures as the row's ([detailSize]): "On this phone now: 17 MB" (the record's bytes, the
 * row's figure); a lower "About 12 MB, 4.9 MB less", or "About 12 MB, nothing to download" when none of what it drops is on the
 * phone; a raise "About 19 MB, 7.6 MB more to download"; the detail a raise waits for "Waiting: about 7.6 MB more to download".
 * Never a zero figure. Pure, tested.
 */
internal fun detailOptionLine(now: Boolean, pending: Boolean, bytes: Long, delta: Long, addedBytes: Long, raise: Boolean = addedBytes > 0): String = when {
    now -> "On this phone now: ${detailSize(bytes)}"
    pending -> "Waiting: about ${detailSize(addedBytes)} more to download"
    raise -> "About ${detailSize(bytes)}, ${detailSize(addedBytes)} more to download"
    delta < 0 -> "About ${detailSize(bytes)}, ${detailSize(-delta)} less"
    else -> "About ${detailSize(bytes)}, nothing to download"
}

/** T386: why a lower frees nothing, in place of a zero figure ([ownBytes]: the dropped tiles of the area on the phone). */
internal fun nothingFreedLine(ownBytes: Long): String =
    if (ownBytes > 0) "Nothing is freed: another saved map uses these tiles." else "Nothing is freed: none of these tiles are on this phone."

/**
 * T386: what choosing [label] asks before it runs. A lower: "Lower to Less detail? This frees about 4.9 MB. Tiles another saved
 * map uses stay on the phone.", or with nothing to free "Lower to Less detail? Nothing is freed: another saved map uses these
 * tiles." ([nothingFreedLine]). The held detail chosen again while a raise waits: "Keep Medium detail and drop the download
 * that waits? This frees about 2.3 MB." A raise: "Full detail downloads about 7.6 MB more (50 tiles). The map stays at Medium
 * detail until it is done.", with " It also frees about 2.3 MB." when it drops what an earlier raise fetched. Sizes as the
 * row's ([detailSize]); never a zero figure. Pure, tested.
 */
internal fun detailConfirmLine(change: DetailChange.Change, label: String, heldLabel: String, freedBytes: Long, addedBytes: Long, addedTiles: Int, ownBytes: Long = freedBytes): String {
    val frees = if (freedBytes > 0) "This frees about ${detailSize(freedBytes)}." else nothingFreedLine(ownBytes)
    return when {
        change.raise -> "$label downloads about ${detailSize(addedBytes)} more (${String.format(java.util.Locale.US, "%,d", addedTiles)} ${if (addedTiles == 1) "tile" else "tiles"}). " +
            "The map stays at $heldLabel until it is done." + if (change.lower && freedBytes > 0) " It also frees about ${detailSize(freedBytes)}." else ""
        change.target.id == change.held.id -> "Keep $label and drop the download that waits? $frees"
        else -> "Lower to $label? $frees" + if (freedBytes > 0) " Tiles another saved map uses stay on the phone." else ""
    }
}
// end T386

/** T221: why a download came up short, "1 tile could not be fetched." or "3 tiles could not be fetched.". Pure, tested. */
internal fun failedTilesLine(n: Int): String = if (n == 1) "1 tile could not be fetched." else "$n tiles could not be fetched."

/**
 * T192 (T189's follow-up): the pack path's size line counts the trail graph that comes with the pack (or is built on the
 * phone from it): "About 172 MB (1,300 tiles plus the trail graph) at full detail". The graph is estimated from the
 * vector bytes ([graphEstimateBytes]).
 */
internal fun packSizeLine(tileBytes: Long, graphBytes: Long, tiles: Int, minZoom: Int, maxZoom: Int): String =
    sizeLine(tileBytes + graphBytes, tiles, minZoom, maxZoom).replaceFirst(" tiles)", " tiles plus the trail graph)")

/**
 * T192: the trail graph's bytes for a pack whose vector tiles take [vectorBytes]: [GRAPH_PER_VECTOR] of them. Measured on
 * the Rainier pack: a 312,356-byte graph (`DYSG` version 5, T189) for 2,630,059 bytes of vector tiles, 0.12.
 */
internal fun graphEstimateBytes(vectorBytes: Long): Long = Math.round(vectorBytes * GRAPH_PER_VECTOR)

internal const val GRAPH_PER_VECTOR = 0.12

/** T140 (ADR 0041 §5): "Less detail (about 30 MB)". Pure, tested. */
internal fun lessDetailLine(bytes: Long): String = detailChoiceLine(AreaDetail.LESS, bytes)

/**
 * T157 (ADR 0031: the owner's words to adjust): the word of each detail, as in "at medium detail" and "Medium detail": full,
 * medium, less. Pure, tested.
 */
internal fun detailWord(d: AreaDetail): String = when (d) {
    AreaDetail.FULL -> "full"
    AreaDetail.MEDIUM -> "medium"
    AreaDetail.LESS -> "less"
}

/** T157: "Full detail", "Medium detail", "Less detail". Pure, tested. */
internal fun detailTitle(d: AreaDetail): String = detailWord(d).replaceFirstChar { it.uppercase() } + " detail"

/** T157: a detail as a choice, "Medium detail (about 60 MB)" (T140's "Less detail (about 30 MB)"); without [bytes] just the title. Pure, tested. */
internal fun detailChoiceLine(d: AreaDetail, bytes: Long?): String = if (bytes == null) detailTitle(d) else "${detailTitle(d)} (about ${aboutSize(bytes)})"

/** T157 (ADR 0031: the owner's words to adjust): what the medium detail keeps and drops, under its size line. */
internal const val MEDIUM_DETAIL_HINT = "Every path and place of full detail; the terrain shading and contours are coarser"

/**
 * T140 (ADR 0041 §3): the time a download of [bytes] takes at the last completed download's speed ([bytesPerMs]), or null
 * before the first. Pure, tested.
 */
internal fun timeLine(bytes: Long, bytesPerMs: Double?): String? {
    if (bytesPerMs == null || bytesPerMs <= 0) return null
    val s = bytes / bytesPerMs / 1000
    val t = when {
        s < 60 -> "under a minute"
        s < 90 * 60 -> "about ${Math.round(s / 60)} min"
        else -> String.format(java.util.Locale.US, "about %.1f h", s / 3600)
    }
    return "Takes $t at the speed of the last download"
}

/**
 * T140: the last completed download's speed in bytes per millisecond: the newest of [recent] (this run of the app: `state`
 * done, `bytes` fetched, `ms`) and of [records] (`downloadMs` and `bytes`, newest by `createdAt`), at least 1 MB over at
 * least a second; null when there is none. Pure, tested.
 */
internal fun downloadSpeed(recent: List<Map<String, Any?>>, records: List<Areas.Record>): Double? {
    fun ok(bytes: Long, ms: Long) = bytes >= 1_048_576 && ms >= 1000
    recent.firstOrNull { it["state"] == "done" && ok((it["bytes"] as? Number)?.toLong() ?: 0, (it["ms"] as? Number)?.toLong() ?: 0) }?.let {
        return (it["bytes"] as Number).toDouble() / (it["ms"] as Number).toDouble()
    }
    return records.filter { it.pack == null && it.opaque == null && it.state == "done" && ok(it.bytes, it.downloadMs ?: 0) }.maxByOrNull { it.createdAt }
        ?.let { it.bytes.toDouble() / it.downloadMs!! }
}

/** T140 (ADR 0041 §5): "Drawn area" or "Around here" with the date, else (a trip's) "Corridor <id>". Pure, tested. */
internal fun defaultName(area: AreaOfInterest, date: java.time.LocalDate, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val day = date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
    return when (shapeName(area)) {
        "polygon" -> "Drawn area $day"
        "disc" -> "Around here $day"
        else -> "Corridor ${area.id.take(7)}"
    }
}

/** T140 (T136's follow-up): [recent] without the entry of the job shown above it ([id] finished at [finishedAt]). Pure, tested. */
internal fun recentWithout(recent: List<Map<String, Any?>>, id: String, finishedAt: Long): List<Map<String, Any?>> =
    if (finishedAt == 0L) recent else recent.filterNot { it["id"] == id && (it["finishedAt"] as? Number)?.toLong() == finishedAt }

/** T151: the rows of [failed] (route, zoom: rows) up to and including [row] in the walk's order; none when [row] is null. Pure, tested. */
internal fun failedThrough(failed: Map<String, Map<Int, TileSet>>, row: TileRow?): Map<String, Map<Int, TileSet>> {
    if (row == null) return emptyMap()
    val out = HashMap<String, Map<Int, TileSet>>()
    for ((route, sets) in failed) {
        val zs = HashMap<Int, TileSet>()
        for ((z, set) in sets) {
            val kept = TileSet(z)
            set.forEachRow { y, x0, x1 -> if (TileRow.ORDER.compare(TileRow(z, route, y, x0, x1), row) <= 0) kept.addRow(y, x0, x1) }
            if (kept.size > 0) zs[z] = kept
        }
        if (zs.isNotEmpty()) out[route] = zs
    }
    return out
}

/** T151: the union of two failed-tile maps (route, zoom: rows). Pure, tested. */
internal fun mergeFailed(a: Map<String, Map<Int, TileSet>>, b: Map<String, Map<Int, TileSet>>): Map<String, Map<Int, TileSet>> {
    val out = HashMap<String, HashMap<Int, TileSet>>()
    for (m in listOf(a, b)) for ((route, sets) in m) for ((z, set) in sets) {
        val t = out.getOrPut(route) { HashMap() }.getOrPut(z) { TileSet(z) }
        set.forEachRow { y, x0, x1 -> t.addRow(y, x0, x1) }
    }
    return out
}

/**
 * T151: whether a source the phone reads now ([current]) serves another version than [recorded] holds for it; a version not
 * known now ("": offline at start, the check failed) is no change. Pure, tested.
 */
internal fun versionsMoved(recorded: Map<String, Map<String, String>>, current: Map<String, Map<String, String>>): Boolean =
    current.any { (source, names) -> recorded[source]?.let { old -> names.any { (n, v) -> v.isNotEmpty() && old[n] != null && old[n] != v } } == true }

/** T157: the per-kind zoom caps a server's `GET /area` answer names (`zoomCaps`: `dem`); none when it names none (from before T157). Pure, tested. */
internal fun serverZoomCapsOf(doc: Map<String, Any?>): List<String> = (doc["zoomCaps"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()

/** T140: the shapes a server's `GET /area` answer names; box and corridor when it names none (from before T140). Pure, tested. */
internal fun serverShapesOf(doc: Map<String, Any?>): List<String> =
    (doc["shapes"] as? List<*>)?.mapNotNull { it as? String }?.takeIf { it.isNotEmpty() } ?: listOf(AreaShape.KIND_BOX, AreaShape.KIND_CORRIDOR)
