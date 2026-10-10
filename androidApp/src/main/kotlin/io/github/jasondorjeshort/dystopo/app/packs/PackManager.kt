package io.github.jasondorjeshort.dystopo.app.packs

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.FileRangeSource
import io.github.jasondorjeshort.dystopo.app.PackFiles
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.app.gunzip
import io.github.jasondorjeshort.dystopo.packs.PackArchiveInfo
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.PackInfo
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.app.search.PlaceSearch
import io.github.jasondorjeshort.dystopo.search.SearchIndex
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The region packs on the device: lists them (name, bounds, zooms, size, attribution from the PMTiles
 * headers and metadata), chooses the primary one (moves the camera to it), deletes them, and owns the
 * [PackDownloader] that adds new ones.
 *
 * T41: every usable pack is on the map at once ([PackSet]); "active" in `/packs` is the primary pack
 * (where the map starts, a tie-breaker in tile precedence), `inUse` lists every pack the map draws from.
 * A finished download joins them without displacing the primary pack.
 */
class PackManager private constructor(private val app: DystopoApp) {
    /** Where downloads land (the internal files dir; pushed packs may also sit in the external one). */
    val packsDir: File = File(app.filesDir, PackSet.PACKS_DIR).apply { mkdirs() }
    val downloads = PackDownloader(this, packsDir)

    private class Cached(val size: Long, val mtime: Long, val info: PackArchiveInfo?, val error: String?)
    private val archiveCache = ConcurrentHashMap<String, Cached>()

    private fun roots() = app.packRoots()

    fun catalog(): List<PackFiles> = PackSet.catalog(roots())

    /** The name of the primary pack, or null. */
    fun activeName(): String? = app.tiles.packs.name

    /** T41: every pack the map draws from now. */
    fun inUse(): List<String> = app.tiles.packs.regions.map { it.name }

    /** Header and metadata of one archive, cached by path, size and mtime. Errors are reported, not thrown. */
    private fun archive(file: File?): Cached? {
        file ?: return null
        val size = file.length()
        val mtime = file.lastModified()
        archiveCache[file.path]?.takeIf { it.size == size && it.mtime == mtime }?.let { return it }
        val c = try {
            FileRangeSource(file).use { src ->
                Cached(size, mtime, PackArchiveInfo.read(file.name, size, PmtilesReader.open(src, mapOf(Compression.GZIP to gunzip))), null)
            }
        } catch (e: Exception) {
            Cached(size, mtime, null, "${file.name}: ${e.message ?: e.javaClass.simpleName}")
        }
        archiveCache[file.path] = c
        return c
    }

    /** One pack with any read errors. */
    class Listed(val info: PackInfo, val files: PackFiles, val errors: List<String>) {
        fun json(active: Boolean): Map<String, Any?> =
            info.json(active) + mapOf("files" to files.files.map { it.path }, "errors" to errors.ifEmpty { null })
    }

    fun list(): List<Listed> = catalog().map { files ->
        val d = archive(files.dem)
        val v = archive(files.vector)
        Listed(PackInfo(files.name, d?.info, v?.info), files, listOfNotNull(d?.error, v?.error))
    }

    fun find(name: String): Listed? = list().firstOrNull { it.info.name == name }

    fun json(): Map<String, Any?> {
        val active = activeName()
        val used = inUse().toSet()
        val packs = list()
        return linkedMapOf(
            "active" to active,
            "inUse" to app.tiles.packs.json(), // T41: in precedence order, with profile and ranks
            "dir" to packsDir.path,
            "roots" to roots().map { File(it, PackSet.PACKS_DIR).path },
            "count" to packs.size,
            "packs" to packs.map { it.json(it.info.name == active) + mapOf("inUse" to (it.info.name in used)) },
            "downloads" to downloads.activeCount(),
            "search" to io.github.jasondorjeshort.dystopo.app.search.PlaceSearch.json(app), // T39, T41: every pack's search index (build progress)
            "routing" to io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner.json(app), // T45: active pack trail graph (build progress)
        )
    }

    /**
     * Makes [name] the primary pack: stores the choice, reopens the packs and the style (the other packs stay
     * on the map), and (when the map is up and [moveCamera]) fits the camera to the pack's bounds.
     */
    @Synchronized
    fun activate(name: String, moveCamera: Boolean = true): Map<String, Any?> {
        val pack = find(name) ?: throw NoSuchElementException("no pack '$name'")
        require(pack.info.usable) { "pack '$name' is not usable (no DEM, or an archive is incomplete): ${pack.errors}" }
        val previous = activeName()
        PackSet.storeActive(roots(), name)
        app.reload()
        // No contour cache clear (T33): the cache directory is keyed on the DEM pack identity (T26), so another
        // pack never sees these tiles, and clearing here deleted the tiles the new service's warm-up had just made.
        val camera = if (moveCamera) pack.info.bounds?.let { jumpTo(it) } else null
        Log.i(TAG, "activated pack $name (was $previous)")
        return linkedMapOf("active" to activeName(), "previous" to previous, "camera" to camera, "pack" to pack.json(true))
    }

    /** Moves the camera to show [b], if the map is up. Returns the camera, or null. */
    fun jumpTo(b: PackBounds): Map<String, Any?>? {
        val view = app.map.mapView ?: return null
        return try {
            val (w, h, density) = app.map.onMain { Triple(view.width, view.height, view.resources.displayMetrics.density) }
            val zoom = if (w > 0 && h > 0) b.fitZoom(w / density.toDouble(), h / density.toDouble(), 512.0, 24.0) else 10.0
            app.map.setCamera(b.centerLat, b.centerLon, zoom.coerceIn(3.0, 16.0), 0.0, 0.0)
        } catch (e: Exception) {
            Log.w(TAG, "camera jump failed", e)
            null
        }
    }

    /**
     * Deletes a pack's files (and cancels its download), its search index and its contour tiles. When it was
     * the primary pack, another one becomes primary. The map closes the pack before its files go.
     */
    @Synchronized
    fun delete(name: String): Map<String, Any?> {
        require(PackNames.isValidName(name)) { "invalid pack name '$name'" }
        val cancelled = downloads.cancel(name)
        val pack = catalog().firstOrNull { it.name == name }
        if (pack == null && !cancelled) throw NoSuchElementException("no pack '$name'")
        val wasActive = activeName() == name
        val wasInUse = name in inUse()
        val files = pack?.files.orEmpty()
        if (wasActive) {
            // Point the selection elsewhere first, so the reload below picks another primary pack.
            val next = list().map { it.info }.firstOrNull { it.name != name && it.usable }?.name
            if (next != null) PackSet.storeActive(roots(), next) else File(packsDir, PackSet.ACTIVE_FILE).delete()
        }
        // T41: close it first (the map keeps every other pack), then delete.
        if (wasInUse) app.reload(skip = setOf(name))
        // T41: the search index next to the vector archive goes too (T39 left it behind).
        val index = pack?.vector?.let { v -> File(v.absoluteFile.parentFile, SearchIndex.fileNameFor(v.name)) }
        val deleted = (files + listOfNotNull(index?.takeIf { it.isFile })).filter { it.delete() }.map { it.name }
        PlaceSearch.forget(name)
        // T41: and its contour tiles (no DEM file on the device has that identity any more).
        app.collectContourGarbage()
        if (wasActive) app.tiles.packs.dem?.header?.let { h -> jumpTo(PackBounds.of(h)) }
        files.forEach { archiveCache.remove(it.path) }
        return linkedMapOf(
            "deleted" to deleted, "downloadCancelled" to cancelled, "wasActive" to wasActive, "wasInUse" to wasInUse,
            "active" to activeName(), "inUse" to inUse(),
        )
    }

    /**
     * Called by the downloader when a pack's files are complete: the pack joins the map (T41: the primary
     * pack stays; the new one becomes primary only when there is none), and with [show] the camera moves to it.
     */
    internal fun onDownloaded(name: String, show: Boolean) {
        // T41: a downloaded pack comes with the label glyphs and marker sprites (from the server, when one is set).
        io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).fetcher?.server?.let { s ->
            runCatching { io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).installAssets(s) }
        }
        try {
            if (activeName() == null) {
                activate(name, moveCamera = show)
                return
            }
            app.reload()
            if (show) find(name)?.info?.bounds?.let { jumpTo(it) }
            Log.i(TAG, "downloaded pack $name is on the map (primary stays ${activeName()})")
        } catch (e: Exception) {
            Log.w(TAG, "opening downloaded pack $name failed", e)
        }
    }

    companion object {
        const val TAG = "DystopoPacks"

        @Volatile private var instance: PackManager? = null

        fun get(app: DystopoApp = DystopoApp.instance): PackManager =
            instance ?: synchronized(this) { instance ?: PackManager(app).also { instance = it } }
    }
}
