package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

/** Something that can fill a Web Mercator window with elevations (meters, NAVD88/EGM-ish orthometric heights). */
interface ElevationSource {
    val id: String

    /** Data source registry ids (`sources.json`) this source can draw on, including geoids used for datum conversion. */
    val registryIds: List<String>

    /** Credit for everything this source can draw on, composed from the registry. */
    val attribution: String get() = SourceRegistry.builtIn.attribution(registryIds)

    /**
     * [counters] carries source-specific numbers for the JSON report (tiles fetched, nodata cells...).
     * [provenance] lists the registry ids that actually contributed data to [grid], in registry order.
     * It is required, and validated: every id must be in the registry, and a grid with any data must
     * name at least one id (an empty list would silently fall back to crediting everything). Empty is
     * only valid for an all-NaN grid; [ElevationSource.provenanceIfData] builds the usual value.
     */
    data class Result(val grid: ElevationGrid, val counters: Map<String, Long>, val provenance: List<String>) {
        init {
            for (id in provenance) require(id in SourceRegistry.builtIn) { "provenance id '$id' is not in sources.json" }
            require(provenance.isNotEmpty() || grid.values.all { it.isNaN() }) {
                "result has elevation data but empty provenance; set it to the registry ids that contributed"
            }
        }

        /** Credit for what contributed; falls back to [source]'s full credit when nothing did. */
        fun attribution(source: ElevationSource): String =
            if (provenance.isEmpty()) source.attribution else SourceRegistry.builtIn.attribution(provenance)
    }

    fun fetch(window: MercatorWindow): Result

    companion object {
        val ids = listOf("best", "copernicus", "terrarium", "3dep", "3dep-1m", "3dep-best", "swissalti3d")

        fun byId(id: String): ElevationSource = when (id) {
            "best" -> BestSource()
            "copernicus" -> CopernicusSource()
            "terrarium" -> TerrariumSource()
            "3dep" -> ThreeDepSource()
            "3dep-1m" -> LidarSource()
            "3dep-best" -> ThreeDepBestSource()
            "swissalti3d" -> SwissAltiSource()
            else -> throw IllegalArgumentException("unknown source '$id'; known: ${ids.joinToString()}")
        }

        /** [ids] if [grid] has any data, else nothing: provenance for a single-dataset source. */
        fun provenanceIfData(grid: ElevationGrid, ids: List<String>): List<String> =
            if (grid.values.any { !it.isNaN() }) ids else emptyList()

        /** Union of provenance lists, deduplicated in registry order. */
        fun mergeProvenance(vararg lists: List<String>): List<String> = SourceRegistry.builtIn.ordered(lists.flatMap { it })
    }
}

/**
 * A source that blends layers at coverage edges. The blend band is sized in cells from a ground
 * cell size; [ElevationSource.fetch] uses the window's own. Tile exports pass one reference cell
 * size for a whole zoom level (see `TileWindows.referenceCellSizeMeters`) so adjacent tiles, whose
 * windows sit at slightly different latitudes, weight the same cell identically.
 */
interface FeatheredSource : ElevationSource {
    fun fetch(window: MercatorWindow, featherCellSizeMeters: Double): ElevationSource.Result

    override fun fetch(window: MercatorWindow): ElevationSource.Result = fetch(window, window.cellSizeMeters)
}

object Http {
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /**
     * Called with the URL before every network request ([download], [range], [get], Overpass); `tour
     * --offline-ok` throws there to stay cache-only, and tests use it to count or forbid requests.
     */
    @Volatile var beforeNetwork: ((String) -> Unit)? = null

    private fun request(url: String) = HttpRequest.newBuilder(URI(url))
        .header("User-Agent", "dystopo-devtool/0.1")
        .timeout(Duration.ofSeconds(120))

    /**
     * Runs [block] up to [attempts] times, sleeping [initialDelayMs] and doubling between tries, as
     * long as each failure is [transient]. A non-transient failure, or the last one, is rethrown.
     */
    fun <T> retry(attempts: Int, initialDelayMs: Long, transient: (Exception) -> Boolean, block: () -> T): T {
        require(attempts >= 1)
        var delay = initialDelayMs
        for (attempt in 1..attempts) {
            try {
                return block()
            } catch (e: Exception) {
                if (attempt == attempts || !transient(e)) throw e
                Thread.sleep(delay)
                delay *= 2
            }
        }
        error("unreachable")
    }

    /**
     * Retries dropped connections (EOF, reset), which happen under many parallel requests to one
     * host. HTTP error statuses are not retried.
     */
    private fun <T> withRetry(attempts: Int = 4, block: () -> T): T =
        retry(attempts, 300, { it is java.io.IOException }, block)

    /** Downloads [url] to [file] atomically. */
    fun download(url: String, file: File) {
        beforeNetwork?.invoke(url)
        file.parentFile.mkdirs()
        val tmp = partFile(file)
        val response = withRetry { client.send(request(url).build(), HttpResponse.BodyHandlers.ofFile(tmp.toPath())) }
        if (response.statusCode() != 200) {
            tmp.delete()
            throw HttpStatusException(response.statusCode(), url)
        }
        publish(tmp, file)
    }

    /** A temp name next to [file], unique per process and thread (worktrees and parallel renders share one cache). */
    fun partFile(file: File): File =
        File(file.parentFile, "${file.name}.${ProcessHandle.current().pid()}-${Thread.currentThread().threadId()}.part")

    /**
     * Moves a finished [tmp] onto [file]. If another thread or process got there first, its file is
     * kept and [tmp] is dropped: both downloaded the same bytes, and replacing a cache file that may
     * be open for reading fails on Windows (and, with REPLACE_EXISTING, two racing movers make each
     * other fail with FileAlreadyExistsException). Returns false when the existing file was kept.
     */
    fun publish(tmp: File, file: File): Boolean {
        // A hard link is created atomically or not at all. Files.move without REPLACE_EXISTING checks and
        // renames in two steps on Linux, so two racing movers could both "win" there (found by CI, T48).
        try {
            Files.createLink(file.toPath(), tmp.toPath())
            tmp.delete()
            return true
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            tmp.delete()
            return false
        } catch (e: Exception) {
            if (e !is UnsupportedOperationException && e !is java.nio.file.FileSystemException) throw e
            // No hard links on this file system: move, as before.
        }
        try {
            Files.move(tmp.toPath(), file.toPath())
            return true
        } catch (e: java.nio.file.FileSystemException) {
            // FileAlreadyExistsException, or AccessDeniedException while the winner's file is being read.
            if (!file.exists()) throw e
            tmp.delete()
            return false
        }
    }

    /**
     * GET [url] as bytes with [headers], retrying dropped connections up to [attempts] times; the
     * response is returned whatever its status (callers decide what 204/404 mean).
     */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Duration = Duration.ofSeconds(60), attempts: Int = 3): HttpResponse<ByteArray> {
        beforeNetwork?.invoke(url)
        val b = HttpRequest.newBuilder(URI(url)).timeout(timeout)
        for ((k, v) in headers) b.header(k, v)
        val req = b.build()
        return retry(attempts, 500, { it is java.io.IOException }) { client.send(req, HttpResponse.BodyHandlers.ofByteArray()) }
    }

    fun range(url: String, offset: Long, length: Int): ByteArray {
        beforeNetwork?.invoke(url)
        val req = request(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        val response = withRetry { client.send(req, HttpResponse.BodyHandlers.ofByteArray()) }
        if (response.statusCode() != 206 && response.statusCode() != 200) throw HttpStatusException(response.statusCode(), url)
        return response.body()
    }
}

/** An HTTP error status. An [IllegalStateException], as `check` threw before, so existing handlers still match. */
class HttpStatusException(val status: Int, url: String) : IllegalStateException("HTTP $status for $url")

/**
 * HTTP range reads with an on-disk cache per (offset, length), so re-running a render
 * doesn't re-download. [fetches] counts network requests. [fetchRange] is the network layer
 * (url, offset, length); tests swap in a fake.
 */
class CachedHttpRangeSource(
    private val url: String,
    private val cacheDir: File,
    private val fetchRange: (String, Long, Int) -> ByteArray = Http::range,
) : ByteRangeSource {
    @Volatile var fetches = 0L
        private set

    override fun read(offset: Long, length: Int): ByteArray {
        val file = File(cacheDir, "$offset-$length.bin")
        if (file.exists()) return file.readBytes()
        val bytes = fetchRange(url, offset, length)
        synchronized(this) { fetches++ }
        file.parentFile.mkdirs()
        // Parallel tile renders can fetch the same cold range at once: each writes its own temp file,
        // the first to land wins, and the others return the winner's bytes so every caller agrees.
        val tmp = Http.partFile(file)
        tmp.writeBytes(bytes)
        return if (Http.publish(tmp, file)) bytes else file.readBytes()
    }
}
