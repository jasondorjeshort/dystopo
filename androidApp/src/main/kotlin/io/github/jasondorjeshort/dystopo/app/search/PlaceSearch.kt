package io.github.jasondorjeshort.dystopo.app.search

import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.FileRangeSource
import io.github.jasondorjeshort.dystopo.app.gunzip
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
 */
object PlaceSearch {
    /** The index state for one pack's vector archive. Fields are written by the worker thread. */
    class State(val pack: String, val vector: File, val file: File, stamp: Stamp?) {
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

        /** T170: the stamps compared only when both are known (on the UI thread from [stamps]; never a file read there). */
        fun matches(name: String?, f: File?): Boolean {
            if (name != pack || f == null || f.path != vector.path) return false
            val mine = stamp ?: return true
            return PlaceSearch.stampOf(f)?.let { it == mine } ?: true
        }

        val busy: Boolean get() = status in listOf("queued", "loading", "waiting", "building")

        fun json(): Map<String, Any?> = linkedMapOf(
            "pack" to pack, "status" to status, "file" to file.path, "bytes" to file.takeIf { it.isFile }?.length(),
            "progress" to if (total > 0) linkedMapOf("tiles" to done, "of" to total, "percent" to done * 100 / total) else null,
            "buildMs" to buildMs, "loadMs" to loadMs, "rebuildReason" to rebuildReason, "error" to error,
            "entries" to index?.entries?.size, "tokens" to index?.tokenCount,
            "guard" to guard?.json(), "heapStartBytes" to heapStartBytes, "peakHeapBytes" to peakHeapBytes, // T149
        )
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
        val old = states.associateBy { it.pack }
        val next = app.tiles.packs.regions.mapNotNull { r ->
            val vector = r.osm?.file ?: return@mapNotNull null
            val cur = old[r.name]
            // A failed build is not retried on its own (the overlay polls this); POST /search/build retries it.
            // T149: a refused one is, by a use after REFUSED_RETRY_MS (the heap may have room by then).
            val retry = cur?.status == "refused" && SystemClock.elapsedRealtime() - cur.refusedAt >= REFUSED_RETRY_MS
            if (cur != null && cur.matches(r.name, vector) && !retry && (!rebuild || cur.busy)) return@mapNotNull cur
            val state = State(r.name, vector, File(vector.absoluteFile.parentFile, SearchIndex.fileNameFor(vector.name)), stampOf(vector))
            worker.execute { run(state, rebuild) }
            state
        }
        states = next
        return next
    }

    /**
     * T149: the states of the packs on the map without starting anything: a pack whose index was not asked for yet (or
     * whose archive changed since) is a fresh state with status `idle`, not kept. For [json] and the search box's line.
     */
    fun peek(app: DystopoApp = DystopoApp.instance): List<State> {
        val old = states.associateBy { it.pack }
        return app.tiles.packs.regions.mapNotNull { r ->
            val vector = r.osm?.file ?: return@mapNotNull null
            old[r.name]?.takeIf { it.matches(r.name, vector) }
                ?: State(r.name, vector, File(vector.absoluteFile.parentFile, SearchIndex.fileNameFor(vector.name)), stampOf(vector)).apply { status = "idle" }
        }
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

    /** Drops a deleted pack's state (its index file is deleted by the pack manager). */
    @Synchronized
    fun forget(pack: String) {
        states = states.filter { it.pack != pack }
    }

    private fun run(s: State, rebuild: Boolean) {
        try {
            if (!s.vector.isFile) throw IllegalStateException("${s.vector.name} is gone")
            if (s.stamp == null) s.stamp = stampOf(s.vector) // T170: made on the UI thread before any stamp was read
            FileRangeSource(s.vector).use { src ->
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
                // T149: the heap guard, taking turns with the routing graph's build.
                s.status = "waiting"
                val guard = io.github.jasondorjeshort.dystopo.app.online.HeapGuard
                guard.buildTurn {
                    val admission = guard.admitBuild("search ${s.pack}", estimateBytes(s.vector.length()))
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
                        val builder = SearchIndexBuilder(reader, s.vector.name) { d, n -> s.done = d; s.total = n }
                        val idx = builder.build(System.currentTimeMillis())
                        val bytes = idx.encode()
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
        } catch (e: Exception) {
            s.error = "${e.javaClass.simpleName}: ${e.message}"
            s.status = "error"
            Log.w(TAG, "search index for ${s.pack} failed", e)
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
        return linkedMapOf("status" to status(s), "ready" to s.count { it.index != null }, "packs" to s.map { it.json() }, "stampRefreshes" to stampRefreshes)
    }

    const val TAG = "DystopoSearch"
}
