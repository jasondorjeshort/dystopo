package io.github.jasondorjeshort.dystopo.app.search

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

/**
 * Place search over every pack on the map (T39, T41). Each pack's index lives next to its vector
 * archive as `<name>-search.idx`; [ensure] loads the indexes in the background, or builds one from its
 * archive when it is missing, unreadable or stale (the archive's fingerprint changed), one at a time,
 * with progress in [json] (shown in `GET /packs` under `search` and in `GET /search/status`). Packs
 * without a vector archive have no index. [search] queries every ready index ([MultiPackSearch]), so
 * results come in while other packs are still being indexed.
 */
object PlaceSearch {
    /** The index state for one pack's vector archive. Fields are written by the worker thread. */
    class State(val pack: String, val vector: File, val file: File, private val vectorBytes: Long, private val vectorMtime: Long) {
        @Volatile var status = "queued" // queued, loading, building, ready, error
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var buildMs: Long? = null
        @Volatile var loadMs: Long? = null
        @Volatile var error: String? = null
        @Volatile var rebuildReason: String? = null
        @Volatile var index: SearchIndex? = null

        fun matches(name: String?, f: File?) = name == pack && f != null && f.path == vector.path && f.length() == vectorBytes && f.lastModified() == vectorMtime

        val busy: Boolean get() = status in listOf("queued", "loading", "building")

        fun json(): Map<String, Any?> = linkedMapOf(
            "pack" to pack, "status" to status, "file" to file.path, "bytes" to file.takeIf { it.isFile }?.length(),
            "progress" to if (total > 0) linkedMapOf("tiles" to done, "of" to total, "percent" to done * 100 / total) else null,
            "buildMs" to buildMs, "loadMs" to loadMs, "rebuildReason" to rebuildReason, "error" to error,
            "entries" to index?.entries?.size, "tokens" to index?.tokenCount,
        )
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
            if (cur != null && cur.matches(r.name, vector) && (!rebuild || cur.busy)) return@mapNotNull cur
            val state = State(r.name, vector, File(vector.absoluteFile.parentFile, SearchIndex.fileNameFor(vector.name)), vector.length(), vector.lastModified())
            worker.execute { run(state, rebuild) }
            state
        }
        states = next
        return next
    }

    /** Drops a deleted pack's state (its index file is deleted by the pack manager). */
    @Synchronized
    fun forget(pack: String) {
        states = states.filter { it.pack != pack }
    }

    private fun run(s: State, rebuild: Boolean) {
        try {
            if (!s.vector.isFile) throw IllegalStateException("${s.vector.name} is gone")
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
                s.status = "building"
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

    /** Overall state: `none` (no pack to search), `ready` (all), `partial` (some ready, others coming), or the first busy/error state. */
    fun status(states: List<State> = this.states): String = when {
        states.isEmpty() -> "none"
        states.all { it.status == "ready" } -> "ready"
        states.any { it.status == "ready" } -> "partial"
        states.any { it.busy } -> states.first { it.busy }.status
        else -> "error"
    }

    /** Status for `/packs` and `/search/status`: overall, plus one entry per pack. */
    fun json(app: DystopoApp = DystopoApp.instance): Map<String, Any?> {
        val s = ensure(app)
        return linkedMapOf("status" to status(s), "ready" to s.count { it.index != null }, "packs" to s.map { it.json() })
    }

    const val TAG = "DystopoSearch"
}
