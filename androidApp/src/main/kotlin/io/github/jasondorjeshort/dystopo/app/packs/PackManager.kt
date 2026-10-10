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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The region packs on the device: lists them (name, bounds, zooms, size, attribution from the PMTiles
 * headers and metadata), switches the active one (reopens the tile service, reloads the style, moves
 * the camera to the pack), deletes them, and owns the [PackDownloader] that adds new ones.
 */
class PackManager private constructor(private val app: DystopoApp) {
    /** Where downloads land (the internal files dir; pushed packs may also sit in the external one). */
    val packsDir: File = File(app.filesDir, PackSet.PACKS_DIR).apply { mkdirs() }
    val downloads = PackDownloader(this, packsDir)

    private class Cached(val size: Long, val mtime: Long, val info: PackArchiveInfo?, val error: String?)
    private val archiveCache = ConcurrentHashMap<String, Cached>()

    private fun roots() = app.packRoots()

    fun catalog(): List<PackFiles> = PackSet.catalog(roots())

    /** The name of the pack the map shows now, or null. */
    fun activeName(): String? = app.tiles.packs.name

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
        val packs = list()
        return linkedMapOf(
            "active" to active,
            "dir" to packsDir.path,
            "roots" to roots().map { File(it, PackSet.PACKS_DIR).path },
            "count" to packs.size,
            "packs" to packs.map { it.json(it.info.name == active) },
            "downloads" to downloads.activeCount(),
            "search" to io.github.jasondorjeshort.dystopo.app.search.PlaceSearch.json(app), // T39: active pack search index (build progress)
        )
    }

    /**
     * Makes [name] the active pack: stores the choice, reopens the packs and the style, and (when the
     * map is up and [moveCamera]) fits the camera to the pack's bounds. Returns what happened.
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

    /** Deletes a pack's files (and cancels its download). The active pack switches to another one. */
    @Synchronized
    fun delete(name: String): Map<String, Any?> {
        require(PackNames.isValidName(name)) { "invalid pack name '$name'" }
        val cancelled = downloads.cancel(name)
        val pack = catalog().firstOrNull { it.name == name }
        if (pack == null && !cancelled) throw NoSuchElementException("no pack '$name'")
        val wasActive = activeName() == name
        val files = pack?.files.orEmpty()
        if (wasActive) {
            // Point the selection elsewhere first, so the reload below doesn't reopen the files being deleted.
            val next = list().map { it.info }.firstOrNull { it.name != name && it.usable }?.name
            if (next != null) PackSet.storeActive(roots(), next) else File(packsDir, PackSet.ACTIVE_FILE).delete()
        }
        val deleted = files.filter { it.delete() }.map { it.name }
        if (wasActive) {
            // Nothing left with this name, so the reload picks the stored pack, the legacy one, or none.
            app.reload()
            app.tiles.packs.dem?.header?.let { h -> jumpTo(PackBounds.of(h)) }
        }
        files.forEach { archiveCache.remove(it.path) }
        return linkedMapOf("deleted" to deleted, "downloadCancelled" to cancelled, "wasActive" to wasActive, "active" to activeName())
    }

    /** Called by the downloader when a pack's files are complete. */
    internal fun onDownloaded(name: String, activate: Boolean) {
        if (activate || activeName() == null) {
            try {
                activate(name)
            } catch (e: Exception) {
                Log.w(TAG, "activating downloaded pack $name failed", e)
            }
        }
    }

    companion object {
        const val TAG = "DystopoPacks"

        @Volatile private var instance: PackManager? = null

        fun get(app: DystopoApp = DystopoApp.instance): PackManager =
            instance ?: synchronized(this) { instance ?: PackManager(app).also { instance = it } }
    }
}
