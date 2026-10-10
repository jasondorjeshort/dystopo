package io.github.jasondorjeshort.dystopo.app.search

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.FileRangeSource
import io.github.jasondorjeshort.dystopo.app.gunzip
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.search.PlaceKind
import io.github.jasondorjeshort.dystopo.search.SearchHit
import io.github.jasondorjeshort.dystopo.search.SearchIndex
import io.github.jasondorjeshort.dystopo.search.SearchIndexBuilder
import java.io.File
import java.util.concurrent.Executors

/**
 * Place search for the active pack (T39). The index lives next to the pack's vector archive as
 * `<name>-search.idx`; [ensure] loads it in the background, or builds it from the archive when it is
 * missing, unreadable or stale (the archive's fingerprint changed), with progress in [json] (shown in
 * `GET /packs` under `search` and in `GET /search/status`). Packs without a vector archive have no index.
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

    @Volatile var current: State? = null
        private set

    /**
     * Makes sure the active pack's index is loaded or being built; cheap when it already is. Returns
     * its state, or null when the active pack has no vector archive.
     */
    @Synchronized
    fun ensure(app: DystopoApp = DystopoApp.instance, rebuild: Boolean = false): State? {
        val packs = app.tiles.packs
        val vector = packs.osm?.file ?: return null.also { current = null }
        val name = packs.name ?: "default"
        val cur = current
        // A failed build is not retried on its own (the overlay polls this); POST /search/build retries it.
        if (cur != null && cur.matches(name, vector) && (!rebuild || cur.status in listOf("queued", "loading", "building"))) return cur
        val state = State(name, vector, File(vector.absoluteFile.parentFile, SearchIndex.fileNameFor(vector.name)), vector.length(), vector.lastModified())
        current = state
        worker.execute { run(state, rebuild) }
        return state
    }

    private fun run(s: State, rebuild: Boolean) {
        try {
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

    /** Hits for [query] in the active pack, or null while its index is not ready. */
    fun search(query: String, lat: Double?, lon: Double?, limit: Int = 20, kinds: Set<PlaceKind>? = null): List<SearchHit>? {
        val idx = ensure()?.index ?: return null
        return idx.search(query, lat, lon, limit, kinds)
    }

    /** Status for `/packs` and `/search/status`. */
    fun json(app: DystopoApp = DystopoApp.instance): Map<String, Any?>? = ensure(app)?.json()

    const val TAG = "DystopoSearch"
}
