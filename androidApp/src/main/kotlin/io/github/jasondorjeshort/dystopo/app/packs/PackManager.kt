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
    /** T53: the server's pack catalog, installed-pack hashes, downloads and updates from it. */
    val catalogClient = PackCatalogClient(this, packsDir)

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
     *
     * T144: never waits for the UI thread: [DystopoApp.reload] opens and closes off it and posts the style, and [jumpTo]
     * posts the camera move; the returned `camera` is the one asked for. The lock here orders pack changes among
     * workers; the UI thread never takes it.
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

    /**
     * Moves the camera to show [b], if the map is up. Returns the camera asked for, or null. T144: the move is posted to
     * the UI thread (after a style swap posted before it), not awaited; the view's size is read from its fields.
     */
    fun jumpTo(b: PackBounds): Map<String, Any?>? {
        val view = app.map.mapView ?: return null
        return try {
            val w = view.width
            val h = view.height
            val density = view.resources.displayMetrics.density
            val zoom = (if (w > 0 && h > 0) b.fitZoom(w / density.toDouble(), h / density.toDouble(), 512.0, 24.0) else 10.0).coerceIn(3.0, 16.0)
            main.post { runCatching { app.map.setCamera(b.centerLat, b.centerLon, zoom, 0.0, 0.0) }.onFailure { Log.w(TAG, "camera jump failed", it) } }
            linkedMapOf("lat" to b.centerLat, "lon" to b.centerLon, "zoom" to zoom, "bearing" to 0.0, "tilt" to 0.0, "posted" to true)
        } catch (e: Exception) {
            Log.w(TAG, "camera jump failed", e)
            null
        }
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

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
        val graphs = pack?.vector?.let(io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner::graphFiles).orEmpty() // T49: its routing graph too
        val deleted = (files + listOfNotNull(index?.takeIf { it.isFile }) + graphs).filter { it.delete() }.map { it.name }
        PlaceSearch.forget(name)
        catalogClient.forget(name) // T53: its recorded content hashes
        io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner.forget(name) // T49
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
            // T65: a vector-only pack cannot be primary (it has no terrain); it joins the map like any other pack.
            if (activeName() == null && find(name)?.info?.usable == true) {
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

    /**
     * T53: swaps verified update files in for pack [name]: closes the pack (the map keeps the others), renames
     * each `(part, target)` in [swaps], deletes the old copies they replace (another root, or a `-osm` name)
     * and the archives of [removed] roles, which the server's pack no longer has. [onUpdated] reopens it.
     */
    @Synchronized
    internal fun installUpdate(name: String, swaps: List<Pair<File, File>>, removed: List<io.github.jasondorjeshort.dystopo.packs.PackRole>) {
        val old = catalog().firstOrNull { it.name == name }
        if (name in inUse()) app.reload(skip = setOf(name))
        for ((part, target) in swaps) {
            target.delete()
            if (!part.renameTo(target)) throw java.io.IOException("rename ${part.name} -> ${target.name} failed")
            archiveCache.remove(target.path)
        }
        val targets = swaps.map { it.second.absolutePath }.toSet()
        val replaced = swaps.mapNotNull { (_, t) -> io.github.jasondorjeshort.dystopo.packs.PackNames.parse(t.name)?.role }.toSet() + removed
        for (role in replaced) {
            val f = (if (role == io.github.jasondorjeshort.dystopo.packs.PackRole.DEM) old?.dem else old?.vector) ?: continue
            if (f.absolutePath !in targets && f.delete()) {
                archiveCache.remove(f.path)
                Log.i(TAG, "update of $name replaced ${f.path}")
            }
        }
    }

    /** T53: an update of [name] is installed: reopen the packs (search and contours follow the new files). */
    internal fun onUpdated(name: String) {
        try {
            PlaceSearch.forget(name)
            app.reload()
            app.collectContourGarbage()
            Log.i(TAG, "updated pack $name is on the map")
        } catch (e: Exception) {
            Log.w(TAG, "reopening updated pack $name failed", e)
        }
    }

    // ---- T79: importing pack files picked on the phone ----

    /** One file to import: its display name (the role and pack name come from it when it follows [PackNames]), size if known, and its bytes. */
    class ImportSource(val displayName: String, val size: Long?, val open: () -> java.io.InputStream)

    /** The import running or last finished (`GET /packs/import`, the first-run card and the pack list show it). */
    @Volatile var importState: Map<String, Any?> = linkedMapOf("state" to "idle")
        private set
    private val importing = java.util.concurrent.atomic.AtomicBoolean(false)
    val importRunning: Boolean get() = importing.get()

    /**
     * T79: imports a pack from files on the phone (the system file picker, or paths in debug builds): a
     * `<name>-dem.pmtiles` and optionally a `<name>-outdoors.pmtiles`. Each file is copied into the packs
     * directory as a part file and validated like a download (a PMTiles v3 archive whose header's sections
     * all fit in the file; T29's check), its role taken from the name and checked against the tile type
     * (raster = terrain, MVT = map); a file named otherwise gets its role from the tile type. Then the pack
     * joins the map like a finished download ([onDownloaded]: primary when there is none, camera moved to
     * it). A pack of the same name is replaced. Nothing is installed when any file fails. Blocks; returns
     * the result, also kept in [importState].
     */
    fun importFiles(sources: List<ImportSource>, name: String? = null): Map<String, Any?> {
        if (!importing.compareAndSet(false, true)) throw IllegalStateException("an import is already running")
        val started = System.currentTimeMillis()
        val parts = ArrayList<File>()
        val progress = java.util.concurrent.atomic.AtomicLong()
        val total = sources.sumOf { it.size ?: 0L }
        fun state(s: String, extra: Map<String, Any?> = emptyMap()) {
            importState = linkedMapOf<String, Any?>("state" to s, "files" to sources.map { it.displayName }, "bytesDone" to progress.get(),
                "bytesTotal" to total.takeIf { it > 0 }, "elapsedMs" to System.currentTimeMillis() - started) + extra
        }
        try {
            require(sources.isNotEmpty()) { "no files chosen" }
            require(sources.size <= 2) { "choose one <name>-dem.pmtiles and at most one <name>-outdoors.pmtiles (got ${sources.size} files)" }
            state("copying")
            val picked = sources.mapIndexed { i, src ->
                val part = File(packsDir, ".import-$i-${System.nanoTime()}.part")
                parts += part
                src.open().use { input ->
                    java.io.FileOutputStream(part).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            progress.addAndGet(n.toLong())
                            if (progress.get() % (4L shl 20) < n) state("copying")
                        }
                    }
                }
                state("verifying")
                val info = try {
                    FileRangeSource(part).use { s -> PackArchiveInfo.read(src.displayName, part.length(), PmtilesReader.open(s, mapOf(Compression.GZIP to gunzip))) }
                } catch (e: Exception) {
                    throw IllegalArgumentException("${src.displayName}: not a PMTiles archive (${e.message ?: e.javaClass.simpleName})")
                }
                require(info.complete) { "${src.displayName}: ${part.length()} bytes, but its header needs ${info.expectedBytes} (an incomplete copy?)" }
                val role = importRole(src.displayName, info.tileType)
                Picked(src.displayName, part, role, PackNames.parse(src.displayName)?.pack, info)
            }
            val roles = picked.groupBy { it.role }
            roles.forEach { (role, files) -> require(files.size == 1) { "two ${role.label} archives: ${files.joinToString { it.displayName }}" } }
            val dem = roles[io.github.jasondorjeshort.dystopo.packs.PackRole.DEM]?.single()
                ?: throw IllegalArgumentException("no terrain file: choose the <name>-dem.pmtiles file (and optionally <name>-outdoors.pmtiles)")
            val vector = roles[io.github.jasondorjeshort.dystopo.packs.PackRole.VECTOR]?.single()
            val names = picked.mapNotNull { it.packName }.distinct()
            require(names.size <= 1) { "the files belong to different packs: ${names.joinToString()}" }
            val packName = name?.trim()?.ifEmpty { null } ?: names.firstOrNull() ?: importName(dem.displayName)
            require(PackNames.isValidName(packName)) { "invalid pack name '$packName'" }
            // Same name: close it, then the new files replace its archives (a vector archive it had stays unless replaced).
            synchronized(this) {
                if (packName in inUse()) app.reload(skip = setOf(packName))
                val old = catalog().firstOrNull { it.name == packName }
                for (p in picked) {
                    val target = File(packsDir, PackNames.fileName(packName, p.role))
                    val previous = if (p.role == io.github.jasondorjeshort.dystopo.packs.PackRole.DEM) old?.dem else old?.vector
                    if (previous != null && previous.absolutePath != target.absolutePath) previous.delete()
                    target.delete()
                    if (!p.part.renameTo(target)) throw java.io.IOException("rename ${p.part.name} -> ${target.name} failed")
                    archiveCache.remove(target.path)
                }
                PlaceSearch.forget(packName)
                catalogClient.forget(packName) // hashed again in the background like any pack that arrived another way
            }
            onDownloaded(packName, show = true)
            val result = linkedMapOf<String, Any?>(
                "name" to packName, "dem" to dem.json(), "vector" to vector?.json(),
                "active" to activeName(), "inUse" to inUse(), "bytes" to progress.get(),
                "labels" to (app.tiles.packs.glyphDir != null || io.github.jasondorjeshort.dystopo.app.AppSettings.server.isNotEmpty()), // glyphs on the device or from the server
            )
            state("done", result)
            Log.i(TAG, "imported pack $packName (${picked.size} files, ${progress.get()} bytes)")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "pack import failed", e)
            state("failed", mapOf("error" to (e.message ?: e.javaClass.simpleName)))
            throw e
        } finally {
            parts.forEach { if (it.isFile) it.delete() }
            importing.set(false)
        }
    }

    private class Picked(val displayName: String, val part: File, val role: io.github.jasondorjeshort.dystopo.packs.PackRole, val packName: String?, val info: PackArchiveInfo) {
        fun json() = linkedMapOf("file" to displayName, "tileType" to info.tileType, "minZoom" to info.minZoom, "maxZoom" to info.maxZoom, "bytes" to info.bytes)
    }

    companion object {
        const val TAG = "DystopoPacks"

        /**
         * T79: an imported file's role: the name's (`-dem`, `-outdoors`, `-osm`) when it has one, which must agree
         * with the tile type; else the tile type's (raster = terrain, `mvt` = map).
         */
        fun importRole(displayName: String, tileType: String): io.github.jasondorjeshort.dystopo.packs.PackRole {
            val byType = when (tileType) {
                "mvt" -> io.github.jasondorjeshort.dystopo.packs.PackRole.VECTOR
                "png", "webp", "jpeg" -> io.github.jasondorjeshort.dystopo.packs.PackRole.DEM
                else -> null
            }
            val byName = PackNames.parse(displayName)?.role
            if (byName != null && byType != null && byName != byType) {
                throw IllegalArgumentException("$displayName: the name says ${byName.label} but it holds $tileType tiles")
            }
            return byName ?: byType ?: throw IllegalArgumentException("$displayName: unknown tile type '$tileType' (want terrain PNG tiles or map MVT tiles)")
        }

        /** T79: a pack name for a file whose name does not follow [PackNames]: its stem made file-name safe. */
        fun importName(displayName: String): String {
            val stem = displayName.substringAfterLast('/').removeSuffix(".pmtiles").removeSuffix("-dem").removeSuffix("-outdoors").removeSuffix("-osm")
            val safe = stem.replace(Regex("[^A-Za-z0-9._-]+"), "-").replace(Regex("\\.{2,}"), ".").trim('-', '.', '_').take(64)
            return safe.takeIf { PackNames.isValidName(it) } ?: "imported"
        }

        @Volatile private var instance: PackManager? = null

        fun get(app: DystopoApp = DystopoApp.instance): PackManager =
            instance ?: synchronized(this) { instance ?: PackManager(app).also { instance = it } }
    }
}
