package io.github.jasondorjeshort.dystopo.app.search

import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.FileRangeSource
import io.github.jasondorjeshort.dystopo.app.gunzip
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.app.online.OnlineView
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.search.MultiPackSearch
import io.github.jasondorjeshort.dystopo.search.PackHit
import io.github.jasondorjeshort.dystopo.search.PlaceKind
import io.github.jasondorjeshort.dystopo.search.SearchIndex
import io.github.jasondorjeshort.dystopo.search.SearchIndexBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Place search over every pack on the map (T39, T41). Each pack's index lives next to its vector
 * archive as `<name>-search.idx`; [ensure] loads the indexes in the background, or builds one from its
 * archive when it is missing, unreadable or stale (the archive's fingerprint changed), one at a time,
 * with progress in [json] (shown in `GET /packs` under `search` and in `GET /search/status`). Packs
 * without a vector archive have no index. [search] queries every ready index ([MultiPackSearch]), so
 * results come in while other packs are still being indexed.
 *
 * T149: nothing is loaded or built until a use asks: [ensure] is called by [search] (a query), by the search box while
 * it is open or holds text ([SearchUi.refresh] while [SearchUi.wanted]), and by `POST /search/build`; [peek] and [json] only read
 * the states, a pack not asked for yet showing `idle`. A build asks the heap guard first
 * ([io.github.jasondorjeshort.dystopo.app.online.HeapGuard.admitBuild] for [estimateBytes] of the archive, taking turns
 * with the routing graph's build); a refused one is state `refused` and is asked again after [REFUSED_RETRY_MS].
 *
 * T170 (T150's follow-up: 240 StrictMode disk reads per device tour from the search box's 2 s poll): the UI thread never
 * reads a file here. A vector archive's size and time ([Stamp], how a changed archive is told) come from [stamps], a
 * snapshot refreshed on [statter] after each read on the UI thread; other threads read the file and update it.
 *
 * T377 (the idea of 2026-11-07): an area kept as tiles ([AreaTiles.areas]: a finished download with no pack) has an index
 * too, built from its vector tiles in the online cache ([AreaTiles.source], a [io.github.jasondorjeshort.dystopo.search.TileListSource])
 * and kept beside the area's manifest as `files/areas/<id>-search.idx`. Its key is the area's id and versions, and its
 * fingerprint counts the tiles present at the zooms it scans, so a tile that arrives (a download over an open pack skipped
 * it; the pack was turned off and the area downloaded again) makes it stale: a use checks it again after [RECHECK_MS]. It is
 * built only on use, like a pack's (ADR 0071: never at a start or after a background download), and its tile reads wait
 * while the app is off the screen. An index file whose area is gone is deleted ([sweepOrphans], on the worker, when the set
 * of areas changes). Search's coverage counts such an area ([searchCoverage]); the route's ([coverage]) does not, so the
 * route card keeps saying that routes need an offline map there.
 */
object PlaceSearch {
    /**
     * T377: the area an index state is for: its id, its versions ([AreaTiles.versionsKey]) and its record (the shape for
     * [searchCoverage]).
     */
    class AreaRef(val record: io.github.jasondorjeshort.dystopo.app.areas.Areas.Record) {
        val id: String get() = record.id
        val versions: String = AreaTiles.versionsKey(record)
        /** The area's cells at [COVER_ZOOM], made on first use (any thread). */
        val cover: io.github.jasondorjeshort.dystopo.areas.TileSet by lazy { io.github.jasondorjeshort.dystopo.areas.TileCover.of(record.area, COVER_ZOOM) }

        /** Whether [lat], [lon] lies in the area's cells at [COVER_ZOOM]. */
        fun covers(lat: Double, lon: Double): Boolean {
            val b = record.area.bounds
            if (lon < b.minLon || lon > b.maxLon || lat < b.minLat || lat > b.maxLat) return false
            val n = 1 shl COVER_ZOOM
            val x = ((lon + 180) / 360 * n).toInt().coerceIn(0, n - 1)
            val r = Math.toRadians(lat)
            val y = ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n).toInt().coerceIn(0, n - 1)
            return cover.contains(x, y)
        }

        /** What a new download of the area changes (its tiles, bytes and time): a fresh state reads its tiles again at once. */
        val stamp: String = stampOf(record)

        fun matches(r: io.github.jasondorjeshort.dystopo.app.areas.Areas.Record): Boolean = r.id == id && AreaTiles.versionsKey(r) == versions && stampOf(r) == stamp

        private fun stampOf(r: io.github.jasondorjeshort.dystopo.app.areas.Areas.Record) = "${r.tiles}:${r.bytes}:${r.downloadMs}"
    }

    /**
     * The index state for one pack's vector archive ([vector]), or T377 for one area kept as tiles ([area]; [pack] is then
     * its name, as a hit names it). Fields are written by the worker thread.
     */
    class State(val pack: String, val vector: File?, val file: File, stamp: Stamp?, val area: AreaRef? = null) {
        /** T170: the archive's size and time this state was made for; null until a thread other than the UI's read them. */
        @Volatile var stamp: Stamp? = stamp
        @Volatile var status = "queued" // queued, loading, waiting (T149: for the heap guard), building, ready, refused (T149), error; idle in [peek]
        /** T149: the build's admission by the heap guard, and the heap at its start and its peak. */
        @Volatile var guard: io.github.jasondorjeshort.dystopo.app.online.HeapGuard.Admission? = null
        @Volatile var heapStartBytes: Long? = null
        @Volatile var peakHeapBytes: Long? = null
        @Volatile var refusedAt = 0L
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var buildMs: Long? = null
        @Volatile var loadMs: Long? = null
        @Volatile var error: String? = null
        @Volatile var rebuildReason: String? = null
        @Volatile var index: SearchIndex? = null
        /** T377: an area's last count of its tiles ([AreaTiles.Scan]) and when it was taken (elapsed ms); a recheck under way. */
        @Volatile var scan: AreaTiles.Scan? = null
        @Volatile var checkedAt = 0L
        @Volatile var checking = false
        @Volatile var checks = 0

        /** The key [ensure] keeps states by: `pack:<name>` or `area:<id>`. */
        val key: String get() = area?.let { "area:${it.id}" } ?: "pack:$pack"

        /** T170: the stamps compared only when both are known (on the UI thread from [stamps]; never a file read there). */
        fun matches(name: String?, f: File?): Boolean {
            if (name != pack || f == null || vector == null || f.path != vector.path) return false
            val mine = stamp ?: return true
            return PlaceSearch.stampOf(f)?.let { it == mine } ?: true
        }

        val busy: Boolean get() = status in listOf("queued", "loading", "waiting", "building")

        fun json(): Map<String, Any?> = linkedMapOf(
            "pack" to pack, "kind" to if (area != null) "area" else "pack", "status" to status, "file" to file.path, "bytes" to file.takeIf { it.isFile }?.length(),
            "progress" to if (total > 0) linkedMapOf("tiles" to done, "of" to total, "percent" to done * 100 / total) else null,
            "buildMs" to buildMs, "loadMs" to loadMs, "rebuildReason" to rebuildReason, "error" to error,
            "entries" to index?.entries?.size, "tokens" to index?.tokenCount,
            "guard" to guard?.json(), "heapStartBytes" to heapStartBytes, "peakHeapBytes" to peakHeapBytes, // T149
        ).also { m ->
            val a = area ?: return@also
            // T377: the area's tiles at the scanned zooms: planned by its shape, present, missing (skipped under an open map).
            val sc = scan
            m["areaId"] = a.id
            m["versions"] = a.versions
            m["zooms"] = sc?.zooms
            m["deepestZoom"] = sc?.deepestZoom
            m["lessDetail"] = sc?.deepestZoom?.let { it < 14 } // no z14: fewer places (an area at Less detail)
            m["tilesPlanned"] = sc?.tilesPlanned
            m["tilesPresent"] = sc?.source?.tileCount
            m["tilesMissing"] = sc?.tilesMissing
            m["missingByZoom"] = sc?.missing?.mapKeys { it.key.toString() }
            m["plannedByZoom"] = sc?.planned?.mapKeys { it.key.toString() }
            m["scanMs"] = sc?.ms
            m["fingerprint"] = sc?.source?.fingerprint
            m["checks"] = checks
            m["checkedAgoMs"] = if (checkedAt > 0) SystemClock.elapsedRealtime() - checkedAt else null
        }
    }

    /** T170: a vector archive's size and modification time. */
    data class Stamp(val bytes: Long, val mtime: Long)

    /** T170: the last stamps read, by path; replaced whole, read by the UI thread. */
    @Volatile private var stamps: Map<String, Stamp> = emptyMap()
    private val stampLock = Any()
    private val statPending = AtomicBoolean(false)
    private val statter = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "dystopo-search-stat").apply { isDaemon = true }
    }

    /** T170: refreshes of [stamps] made on [statter] (for `GET /search/status` `stampRefreshes`). */
    @Volatile var stampRefreshes = 0
        private set

    /**
     * T170: [f]'s stamp. On the UI thread the last one read (null before the first refresh) and a refresh queued on
     * [statter]; on any other thread the file is read now and the snapshot updated.
     */
    fun stampOf(f: File): Stamp? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshStamps()
            return stamps[f.path]
        }
        val s = Stamp(f.length(), f.lastModified())
        if (stamps[f.path] != s) synchronized(stampLock) { stamps = stamps + (f.path to s) }
        return s
    }

    /** T170: re-reads the stamps of every pack's vector archive on [statter]; one refresh at a time, extra calls dropped. */
    private fun refreshStamps(app: DystopoApp = DystopoApp.instance) {
        if (!statPending.compareAndSet(false, true)) return
        statter.execute {
            try {
                val files = app.tiles.packs.regions.mapNotNull { it.osm?.file }
                val next = files.associate { it.path to Stamp(it.length(), it.lastModified()) }
                synchronized(stampLock) { stamps = next }
                stampRefreshes++
            } catch (e: Exception) {
                Log.w(TAG, "reading the vector archives' stamps failed", e)
            } finally {
                statPending.set(false)
            }
        }
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "dystopo-search").apply { isDaemon = true }
    }

    /** Index states by pack name, for the packs on the map with a vector archive. */
    @Volatile var states: List<State> = emptyList()
        private set

    /**
     * Makes sure every pack's index is loaded or being built; cheap when they already are. Returns their
     * states (empty when no pack on the map has a vector archive). [rebuild] rebuilds them all.
     */
    @Synchronized
    fun ensure(app: DystopoApp = DystopoApp.instance, rebuild: Boolean = false): List<State> {
        val old = states.associateBy { it.key }
        val packs = app.tiles.packs.regions.mapNotNull { r ->
            val vector = r.osm?.file ?: return@mapNotNull null
            val cur = old["pack:${r.name}"]
            // A failed build is not retried on its own (the overlay polls this); POST /search/build retries it.
            // T149: a refused one is, by a use after REFUSED_RETRY_MS (the heap may have room by then).
            val retry = cur?.status == "refused" && SystemClock.elapsedRealtime() - cur.refusedAt >= REFUSED_RETRY_MS
            if (cur != null && cur.matches(r.name, vector) && !retry && (!rebuild || cur.busy)) return@mapNotNull cur
            val state = State(r.name, vector, File(vector.absoluteFile.parentFile, SearchIndex.fileNameFor(vector.name)), stampOf(vector))
            worker.execute { run(state, rebuild) }
            state
        }
        // T377: the areas kept as tiles, each by its id and versions; a ready one is checked again after RECHECK_MS of use.
        val records = areaRecords()
        val now = SystemClock.elapsedRealtime()
        val areas = records.map { r ->
            val cur = old["area:${r.id}"]
            val retry = cur?.status == "refused" && now - cur.refusedAt >= REFUSED_RETRY_MS
            if (cur?.area != null && cur.area.matches(r) && !retry && (!rebuild || cur.busy)) {
                if (cur.status == "ready" && !cur.checking && now - cur.checkedAt >= RECHECK_MS) {
                    cur.checking = true
                    worker.execute { recheck(cur) }
                }
                return@map cur
            }
            val state = areaState(app, r)
            worker.execute { run(state, rebuild) }
            state
        }
        val ids = records.map { it.id }.toSet()
        if (ids != sweptFor) {
            sweptFor = ids
            worker.execute { sweepOrphans(app) }
        }
        val next = packs + areas
        states = next
        return next
    }

    /** T377: the area records kept as tiles, or none when the records cannot be read (before the app is up). */
    private fun areaRecords(): List<io.github.jasondorjeshort.dystopo.app.areas.Areas.Record> = runCatching { AreaTiles.areas() }.getOrDefault(emptyList())

    /** T377: a fresh state for area [r]: its index file beside its manifest. */
    private fun areaState(app: DystopoApp, r: io.github.jasondorjeshort.dystopo.app.areas.Areas.Record): State =
        State(r.name, null, File(File(app.filesDir, io.github.jasondorjeshort.dystopo.app.areas.Areas.DIR), SearchIndex.fileNameForArea(r.id)), null, refOf(r))

    /** T377: one [AreaRef] per area id and versions, so its cover is made once (the search box peeks every 2 s). */
    private val refs = java.util.concurrent.ConcurrentHashMap<String, AreaRef>()

    private fun refOf(r: io.github.jasondorjeshort.dystopo.app.areas.Areas.Record): AreaRef {
        val k = r.id + "|" + AreaTiles.versionsKey(r) + "|" + r.tiles + ":" + r.bytes + ":" + r.downloadMs
        refs[k]?.let { return it }
        if (refs.size > 64) refs.clear()
        return AreaRef(r).also { refs[k] = it }
    }

    /** T377: a use checks a ready area's tiles again after this long (a tile that arrived makes its index stale). */
    const val RECHECK_MS = 60_000L

    /** T377: the cells an area's cover is read at for [searchCoverage]. */
    const val COVER_ZOOM = 13

    /** T377: the area ids the last [sweepOrphans] was queued for. */
    @Volatile private var sweptFor: Set<String>? = null

    /** T377: index files deleted because their area was gone (`GET /search/status` `orphansSwept`). */
    @Volatile var orphansSwept = 0
        private set

    /**
     * T377: deletes each `<area id>-search.idx` (and a `.tmp` left by a kill) beside the areas' manifests whose area is no
     * longer on the phone (removed, or T386: re-keyed by a change of detail). On the worker.
     */
    private fun sweepOrphans(app: DystopoApp) {
        try {
            val dir = File(app.filesDir, io.github.jasondorjeshort.dystopo.app.areas.Areas.DIR)
            val keep = io.github.jasondorjeshort.dystopo.app.areas.Areas.all().map { it.id }.toSet()
            for (f in dir.listFiles().orEmpty()) {
                val id = AREA_INDEX.matchEntire(f.name)?.groupValues?.get(1) ?: continue
                if (id !in keep && f.delete()) {
                    orphansSwept++
                    Log.i(TAG, "deleted ${f.name}: its area is gone")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sweeping the areas' index files failed", e)
        }
    }

    /** T377: an area's index file name ([SearchIndex.fileNameForArea]), or its temporary one. */
    private val AREA_INDEX = Regex("^(a[0-9a-f]{16})" + Regex.escape(SearchIndex.SUFFIX) + "(\\.tmp)?$")

    /**
     * T149: the states of the packs on the map without starting anything: a pack whose index was not asked for yet (or
     * whose archive changed since) is a fresh state with status `idle`, not kept. For [json] and the search box's line.
     */
    fun peek(app: DystopoApp = DystopoApp.instance): List<State> {
        val old = states.associateBy { it.key }
        val packs = app.tiles.packs.regions.mapNotNull { r ->
            val vector = r.osm?.file ?: return@mapNotNull null
            old["pack:${r.name}"]?.takeIf { it.matches(r.name, vector) }
                ?: State(r.name, vector, File(vector.absoluteFile.parentFile, SearchIndex.fileNameFor(vector.name)), stampOf(vector)).apply { status = "idle" }
        }
        return packs + peekAreas(app)
    }

    /** T377: the areas' states without starting anything: a known state for the same id and versions, else an idle one. */
    private fun peekAreas(app: DystopoApp): List<State> {
        val old = states.associateBy { it.key }
        return areaRecords().map { r -> old["area:${r.id}"]?.takeIf { it.area?.matches(r) == true } ?: areaState(app, r).apply { status = "idle" } }
    }

    /** T149: a refused build is asked again by the next use after this long. */
    const val REFUSED_RETRY_MS = 30_000L

    /**
     * T149: the heap an index build of a vector archive of [archiveBytes] may take at its peak, with the margin the
     * graph's estimate has ([io.github.jasondorjeshort.dystopo.routing.RoutingGraphBuilder.ESTIMATE_MARGIN]): the builder
     * decodes every tile at the top zoom and two lower ones and keeps every named occurrence, which grows with the
     * archive. Constants measured in T149 (`PlaceSearchEstimateTest`). Pure.
     */
    fun estimateBytes(archiveBytes: Long): Long =
        ((SEARCH_BASE_BYTES + archiveBytes * SEARCH_BYTES_PER_ARCHIVE_BYTE) * io.github.jasondorjeshort.dystopo.routing.RoutingGraphBuilder.ESTIMATE_MARGIN).toLong()

    /**
     * T149's measurement (the heap's growth through an index build, used bytes from the start to the peak, on an API 35
     * emulator): the Rainier outdoors pack (2.6 MB) 5.1 MB, a 1 x 0.7 degree box around Rainier (8.9 MB) 20.4 MB, the
     * Washington 3 degree box (124 MB) 132.6 MB in 55 s.
     */
    const val SEARCH_BASE_BYTES = 12L shl 20
    const val SEARCH_BYTES_PER_ARCHIVE_BYTE = 1.0

    /**
     * T377: the same for an area's index from [tiles] vector tiles in the cache, which has no archive to weigh: each tile
     * counted as [AREA_BYTES_PER_TILE] of archive. Pure.
     */
    fun estimateAreaBytes(tiles: Int): Long = estimateBytes(tiles.toLong() * AREA_BYTES_PER_TILE)

    /**
     * T377: an online vector tile's weight in [estimateAreaBytes]: the cache keeps them unpacked, and T377's measure on an API 35
     * emulator (the Greenwich area of `areas.json` at medium detail: 122 tiles at z10, z12 and z14, 23.1 MB, about 190 KB a
     * tile) grew the heap by 49 MB in a 5.1 s build, which this weight with the margin covers (52 MB); a rural tile is smaller.
     */
    const val AREA_BYTES_PER_TILE = 192L shl 10

    /** Drops a deleted pack's state (its index file is deleted by the pack manager). */
    @Synchronized
    fun forget(pack: String) {
        states = states.filter { it.area != null || it.pack != pack }
    }

    private fun run(s: State, rebuild: Boolean) {
        if (s.area != null) return runArea(s, s.area, rebuild)
        val vector = s.vector!!
        try {
            if (!vector.isFile) throw IllegalStateException("${vector.name} is gone")
            if (s.stamp == null) s.stamp = stampOf(vector) // T170: made on the UI thread before any stamp was read
            FileRangeSource(vector).use { src ->
                val reader = PmtilesReader.open(src, mapOf(Compression.GZIP to gunzip))
                val fingerprint = SearchIndexBuilder.fingerprint(reader.header)
                if (!rebuild && s.file.isFile) {
                    s.status = "loading"
                    val t0 = SystemClock.elapsedRealtime()
                    val loaded = runCatching { SearchIndex.decode(s.file.readBytes()) }
                    s.loadMs = SystemClock.elapsedRealtime() - t0
                    val idx = loaded.getOrNull()
                    if (idx != null && idx.source.fingerprint == fingerprint) {
                        s.index = idx
                        s.status = "ready"
                        return
                    }
                    s.rebuildReason = loaded.exceptionOrNull()?.message ?: "stale (pack changed)"
                } else {
                    s.rebuildReason = if (rebuild) "requested" else "missing"
                }
                build(s, estimateBytes(vector.length())) { SearchIndexBuilder(reader, vector.name) { d, n -> s.done = d; s.total = n } }
            }
        } catch (e: Exception) {
            s.error = "${e.javaClass.simpleName}: ${e.message}"
            s.status = "error"
            Log.w(TAG, "search index for ${s.pack} failed", e)
        }
    }

    /**
     * T149: the heap guard's turn and admission for [s]'s build of [estimate] bytes, then the build ([builder]), written to
     * [s]'s file and made ready; a refused one is state `refused`. T377: shared by a pack's and an area's index; an area's
     * old index stays searchable until the new one is ready.
     */
    private fun build(s: State, estimate: Long, builder: () -> SearchIndexBuilder) {
        s.status = if (s.index != null) "building" else "waiting"
        val guard = io.github.jasondorjeshort.dystopo.app.online.HeapGuard
        guard.buildTurn {
            val admission = guard.admitBuild("search ${s.pack}", estimate)
            s.guard = admission
            if (admission.refused) {
                s.refusedAt = SystemClock.elapsedRealtime()
                s.status = "refused"
                return@buildTurn
            }
            s.status = "building"
            val w = guard.watch("search")
            s.heapStartBytes = w.startBytes
            try {
                val t0 = SystemClock.elapsedRealtime()
                val idx = builder().build(System.currentTimeMillis())
                val bytes = idx.encode()
                s.file.parentFile?.mkdirs()
                val tmp = File(s.file.path + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(s.file)) { s.file.delete(); tmp.renameTo(s.file) }
                s.buildMs = SystemClock.elapsedRealtime() - t0
                s.index = idx
                s.status = "ready"
                Log.i(TAG, "built ${s.file.name}: ${idx.entries.size} entries, ${bytes.size} bytes in ${s.buildMs} ms")
            } finally {
                w.close()
                s.peakHeapBytes = w.peakBytes
            }
        }
    }

    /**
     * T377: an area's index: its tiles counted ([AreaTiles.source]); the file kept beside its manifest used when its
     * fingerprint (the area's id, versions and the tiles present) matches, else built from the tiles in the cache.
     */
    private fun runArea(s: State, a: AreaRef, rebuild: Boolean) {
        try {
            s.status = "loading"
            val scan = AreaTiles.source(a.record) ?: throw IllegalStateException("${a.record.name} has no map tiles in its manifest")
            s.scan = scan
            s.checkedAt = SystemClock.elapsedRealtime()
            val fingerprint = scan.source.fingerprint
            if (!rebuild && s.file.isFile) {
                val t0 = SystemClock.elapsedRealtime()
                val loaded = runCatching { SearchIndex.decode(s.file.readBytes()) }
                s.loadMs = SystemClock.elapsedRealtime() - t0
                val idx = loaded.getOrNull()
                if (idx != null && idx.source.fingerprint == fingerprint) {
                    s.index = idx
                    s.status = "ready"
                    return
                }
                s.rebuildReason = loaded.exceptionOrNull()?.message ?: "stale (the area's tiles changed)"
            } else {
                s.rebuildReason = if (rebuild) "requested" else "missing"
            }
            build(s, estimateAreaBytes(scan.source.tileCount)) { SearchIndexBuilder(scan.source, s.file.name) { d, n -> s.done = d; s.total = n } }
        } catch (e: Exception) {
            s.error = "${e.javaClass.simpleName}: ${e.message}"
            s.status = "error"
            Log.w(TAG, "search index for the area ${s.pack} failed", e)
        }
    }

    /** T377: a ready area's tiles counted again; a changed fingerprint rebuilds its index (the old one searchable meanwhile). */
    private fun recheck(s: State) {
        val a = s.area ?: return
        try {
            val scan = AreaTiles.source(a.record) ?: return
            s.checks++
            s.checkedAt = SystemClock.elapsedRealtime()
            val stale = s.index?.source?.fingerprint != scan.source.fingerprint
            s.scan = scan
            if (stale) {
                s.rebuildReason = "stale (the area's tiles changed)"
                build(s, estimateAreaBytes(scan.source.tileCount)) { SearchIndexBuilder(scan.source, s.file.name) { d, n -> s.done = d; s.total = n } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checking the area ${s.pack}'s index failed", e)
        } finally {
            s.checking = false
        }
    }

    /** The ready indexes, by pack. */
    fun ready(): List<Pair<String, SearchIndex>> = states.mapNotNull { s -> s.index?.let { s.pack to it } }

    /** Hits for [query] across every ready index, or null while none is ready (but some pack has one coming). */
    fun search(query: String, lat: Double?, lon: Double?, limit: Int = 20, kinds: Set<PlaceKind>? = null): List<PackHit>? {
        val all = ensure()
        val ready = ready()
        if (ready.isEmpty()) return if (all.isEmpty()) emptyList() else null
        return MultiPackSearch.search(ready, query, lat, lon, limit, kinds)
    }

    /**
     * Overall state: `none` (no pack to search), `ready` (all), `partial` (some ready, others coming), the first busy
     * state, T149 `idle` (none asked for yet), `refused` (the heap guard refused every build left), or `error`.
     */
    fun status(states: List<State> = this.states): String = when {
        states.isEmpty() -> "none"
        states.all { it.status == "ready" } -> "ready"
        states.any { it.status == "ready" } -> "partial"
        states.any { it.busy } -> states.first { it.busy }.status
        states.any { it.status == "idle" } -> "idle"
        states.any { it.status == "refused" } -> "refused"
        else -> "error"
    }

    /** Status for `/packs` and `/search/status`: overall, plus one entry per pack. T149: reads only ([peek]), starts nothing. */
    fun json(app: DystopoApp = DystopoApp.instance): Map<String, Any?> {
        val s = peek(app)
        return linkedMapOf(
            "status" to status(s), "ready" to s.count { it.index != null }, "packs" to s.filter { it.area == null }.map { it.json() },
            "areas" to s.filter { it.area != null }.map { it.json() }, "orphansSwept" to orphansSwept, // T377
            "stampRefreshes" to stampRefreshes,
        )
    }

    /**
     * T255: what the offline maps hold at [lat], [lon] for search and routes ([MapCoverage]): an offline map's vector archive
     * covers it, or only map tiles draw there, or nothing on the phone does. [source] is the view's source as
     * [OnlineTiles.viewSource] names it (the online chip's, [OnlineView.currentOnMain], on the UI thread); null reads it now
     * at [zoom], which takes the online tiles' locks, so never on the UI thread then. T359 (ADR 0066 build task 5): the maps are
     * the lookup's holdings ([io.github.jasondorjeshort.dystopo.app.PackSet.holdings], in memory: the maps that are on, each
     * archive's box), and the source read here is the chip's, from the lookup ([OnlineView.resolve]).
     */
    fun coverage(app: DystopoApp, lat: Double, lon: Double, zoom: Double, source: String? = null): MapCoverage {
        val maps = app.tiles.packs.holdings().filter { it.kind == io.github.jasondorjeshort.dystopo.packs.Holdings.Kind.MAP && !it.off }
        if (MapCoverage.inside(maps.mapNotNull { it.vector?.bounds }, lat, lon)) return MapCoverage.VECTOR
        // T264: the world overview draws but holds nothing to search or route on: read as if it were not there.
        val s = (source ?: runCatching { OnlineView.sourceAt(app, lat, lon, zoom) }.getOrNull())?.takeUnless { it == OnlineTiles.WORLD }
        // With no online tiles (or before the chip has read the view) the source says only that some map exists: ask its box.
        val pack = MapCoverage.inside(maps.mapNotNull { it.dem?.bounds }, lat, lon)
        return MapCoverage.of(false, if (s == null || s == OnlineTiles.PACK) (if (pack) OnlineTiles.PACK else OnlineTiles.NONE) else s)
    }

    /**
     * T377: search's own coverage at [lat], [lon]: [coverage], except that an area kept as tiles covering the place makes it
     * searchable ([MapCoverage.forSearch]); routes keep [coverage] (no trail network is built from an area's tiles).
     */
    fun searchCoverage(app: DystopoApp, lat: Double, lon: Double, zoom: Double, source: String? = null): MapCoverage =
        MapCoverage.forSearch(coverage(app, lat, lon, zoom, source), areaAt(app, lat, lon) != null)

    /** T377: the state of the area kept as tiles that covers [lat], [lon] (a known state, else an idle one), or null. */
    fun areaAt(app: DystopoApp, lat: Double, lon: Double): State? = peekAreas(app).firstOrNull { it.area?.covers(lat, lon) == true }

    /** T255: [coverage] at the camera's centre on the UI thread, with the chip's last source; null while the map is not up. */
    fun coverageOnMain(app: DystopoApp = DystopoApp.instance): MapCoverage? {
        val v = OnlineView.currentOnMain(app) ?: return null
        return coverage(app, v.lat, v.lon, v.zoom, v.source)
    }

    /** T377: [searchCoverage] at the camera's centre on the UI thread, and the area there; null while the map is not up. */
    fun searchCoverageOnMain(app: DystopoApp = DystopoApp.instance): Pair<MapCoverage, State?>? {
        val v = OnlineView.currentOnMain(app) ?: return null
        val area = areaAt(app, v.lat, v.lon)
        return MapCoverage.forSearch(coverage(app, v.lat, v.lon, v.zoom, v.source), area != null) to area
    }

    const val TAG = "DystopoSearch"
}

/**
 * T255 (the idea of 2026-11-07, search and routes finding nothing outside a pack): whether search and routes can answer at a
 * place. T377: search has a coverage of its own ([forSearch], [PlaceSearch.searchCoverage]): an area kept as tiles is
 * searchable, never routable. Both read an offline map's vector archive (the search index and the trail network are built from it); a map the
 * phone draws from tiles alone (viewed earlier, a map-only download, cut from a wider zoom, a terrain-only map) has neither,
 * so a search or a route there says so instead of "No places match" or a route not found. [code] is the reason code of
 * `GET /search` and of a failed route's reply.
 */
enum class MapCoverage(val code: String) {
    /** An offline map with a vector archive covers the place: search and routes read it. */
    VECTOR("vector"),
    /** Only map tiles draw there: nothing to search or to route on. */
    TILES_ONLY("tiles-only"),
    /** Nothing on the phone draws there. */
    NO_MAP("no-map");

    companion object {
        /** Pure, tested: a vector archive's cover wins; else the view's source ([OnlineTiles.viewSource]) says whether tiles draw there. */
        fun of(vectorCovers: Boolean, source: String): MapCoverage = when {
            vectorCovers -> VECTOR
            source == OnlineTiles.NONE -> NO_MAP
            else -> TILES_ONLY
        }

        /** Pure, tested: [lat], [lon] lies in one of [boxes] (edges included). */
        fun inside(boxes: List<PackBounds>, lat: Double, lon: Double): Boolean =
            boxes.any { b -> lon >= b.minLon && lon <= b.maxLon && lat >= b.minLat && lat <= b.maxLat }

        /** Pure, tested: the coverage of a route between two places: the first end without a vector archive, tiles before nothing. */
        fun ofRoute(a: MapCoverage, b: MapCoverage): MapCoverage = when {
            a == TILES_ONLY || b == TILES_ONLY -> TILES_ONLY
            a == NO_MAP || b == NO_MAP -> NO_MAP
            else -> VECTOR
        }

        /**
         * Pure, tested: `GET /search`'s `reason`: `found` or `no-match` where a vector archive covers the place, else the coverage's
         * code; T377: `indexing` for no hits yet where the area there is still being indexed ([indexing]).
         */
        fun searchReason(c: MapCoverage, hits: Int, indexing: Boolean = false): String =
            if (c != VECTOR) c.code else if (hits > 0) "found" else if (indexing) "indexing" else "no-match"

        /**
         * T377, pure, tested: search's coverage from the route's [c]: where an area kept as tiles covers the place ([areaCovers])
         * search reads its index, so it is [VECTOR] for search; the route keeps [c] ([ofRoute] of the route's two ends).
         */
        fun forSearch(c: MapCoverage, areaCovers: Boolean): MapCoverage = if (areaCovers) VECTOR else c

        /** Pure, tested: the search box's sentence for [c] with [hits] results (null: the results speak for themselves). */
        fun searchSentence(c: MapCoverage, hits: Int): String? = when (c) {
            VECTOR -> if (hits > 0) null else SEARCH_NO_MATCH
            TILES_ONLY -> SEARCH_TILES_ONLY
            NO_MAP -> SEARCH_NO_MAP
        }
    }
}
