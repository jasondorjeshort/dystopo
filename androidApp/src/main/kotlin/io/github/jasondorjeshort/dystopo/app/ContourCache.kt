package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The on-device contour tile cache (T41): one directory per DEM pack and contour version under
 * `cache/contours/` (`v2-<unit>-<tileSize>[-i<interval>]-<pack identity>/z/x/y.pbf`), all kept across pack
 * switches and restarts, with one least-recently-used cap over all of them ([AppSettings.contourCacheMb]).
 * Before T41 a new tile service deleted every directory but its own pack's, so switching back regenerated
 * everything.
 *
 * Directories are deleted only when nothing can use them again: an older version prefix, or a pack
 * identity no DEM file on the device has (a deleted or replaced pack). Built lazily on the first contour
 * request, off the main thread (the index walks the directory).
 */
object ContourCache {
    /** Bump when contour generation changes what it writes; part of every directory name. */
    const val VERSION = "v2"

    @Volatile private var cache: TileCache? = null
    val staleFilesDeleted = AtomicLong()

    private fun root(app: DystopoApp) = File(app.cacheDir, "contours")

    fun get(app: DystopoApp = DystopoApp.instance): TileCache = cache ?: synchronized(this) {
        cache ?: TileCache(root(app), AppSettings.contourCacheMb * MB).also { cache = it }
    }

    /**
     * The directory (cache key prefix) of [identity]'s contour tiles in [unit] at [tileSize]; [tag] is the
     * fixed contour interval (`-i100`, T47) or empty.
     */
    fun dirName(unit: String, tileSize: Int, identity: String, tag: String = "") = "$VERSION-$unit-$tileSize$tag-$identity"

    fun key(dir: String, z: Int, x: Int, y: Int) = "$dir/$z/$x/$y.pbf"

    /** Whether a directory named [name] can still be used: this version, and a pack identity in [identities]. */
    fun keeps(name: String, identities: Set<String>): Boolean {
        val parts = name.split('-')
        return parts.size >= 4 && parts[0] == VERSION && parts.last() in identities
    }

    /**
     * Deletes the directories under [root] no pack can use ([keeps]), through [cache] so its index and byte
     * count follow, plus any files the index never saw. Returns the number of files deleted.
     */
    fun collectGarbage(root: File, cache: TileCache, identities: Set<String>): Int {
        var n = 0
        for (d in root.listFiles()?.filter { it.isDirectory }.orEmpty()) {
            if (keeps(d.name, identities)) continue
            n += cache.clear("${d.name}/")
            d.walkBottomUp().forEach { if (it.isFile && it.delete()) n++ else it.delete() } // a kill mid-write
        }
        return n
    }

    /** [collectGarbage] for the app's cache on a background thread; [identities] are the DEM files on the device. */
    fun collectGarbage(app: DystopoApp, identities: Set<String>) {
        Thread({
            staleFilesDeleted.addAndGet(collectGarbage(root(app), get(app), identities).toLong())
        }, "contour-cache-gc").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    fun resize(mb: Long) = cache?.resize(mb * MB)

    /** Deletes every cached contour tile (all packs); returns how many. */
    fun clear(): Int = cache?.clear() ?: 0

    fun json(): Map<String, Any?> {
        val c = cache ?: return mapOf("loaded" to false, "maxBytes" to AppSettings.contourCacheMb * MB)
        return c.json() + mapOf(
            "dirs" to c.byPrefix().map { (k, v) -> mapOf("dir" to k, "tiles" to v.first, "bytes" to v.second) },
            "staleFilesDeleted" to staleFilesDeleted.get(),
        )
    }

    const val MB = 1024L * 1024
}
