package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import io.github.jasondorjeshort.dystopo.app.storage.FileTrees
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
 * request, off the main thread (the index walks the directory). T242: the index is built on a thread of its own, as the
 * online cache's is ([TileCache.state]): [collectGarbage] waits for it on its thread, [json] answers without the directories
 * meanwhile, [resize] applies once it is whole.
 *
 * T379 (ADR 0071): this cache keeps no snapshot, so it walks its directory at every start (the cache budget's start and
 * the contour collection after the packs open make it). Its walk takes the online cache's rule ([TileCache]'s gate): none without a screen or a
 * notified job, a pause at Home. [collectGarbage] waits for the whole index as an action queued on it
 * ([TileCache.whenReady]), not as a thread parked in a wait holding its lock while the walk is held.
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

    /** T49: first part of the shade tile directories ([io.github.jasondorjeshort.dystopo.app.options.ShadeService]). */
    const val SHADE_VERSION = "shade1"

    /** T49: the directory of shade tiles for option set [optionsKey] over the DEM archive [demIdentity]. */
    fun shadeDir(optionsKey: String, demIdentity: String) = "$SHADE_VERSION-${optionsKey.replace(Regex("[^A-Za-z0-9]"), "_")}-$demIdentity"

    fun shadeKey(dir: String, z: Int, x: Int, y: Int) = "$dir/$z/$x/$y.png"

    /** T49: bump when [CliffTiles] changes what it writes; first part of the cliff directories. */
    const val CLIFF_VERSION = "cliffs1"

    /**
     * T49: the directory of vector tiles with their cliffs turned downhill ([CliffTiles]), cached in the same
     * LRU as the contours: per vector archive and the DEM archive the sides were decided from.
     */
    fun cliffDir(vectorIdentity: String, demIdentity: String) = "$CLIFF_VERSION-$vectorIdentity-$demIdentity"

    /**
     * Whether a directory named [name] can still be used: this version, and a pack identity in [identities];
     * a cliff directory ([cliffDir]) needs both its vector and its DEM archive in [identities].
     */
    fun keeps(name: String, identities: Set<String>): Boolean {
        val parts = name.split('-')
        // T357: an edge tile's identity names every map it is made from ([io.github.jasondorjeshort.dystopo.dem.MergedTile.key]): kept while all are on the phone.
        fun known(id: String) = id.split(io.github.jasondorjeshort.dystopo.dem.MergedTile.KEY_JOIN).all { it in identities }
        if (parts[0] == CLIFF_VERSION) return parts.size == 3 && parts[1] in identities && known(parts[2])
        if (parts[0] == SHADE_VERSION) return parts.size == 3 && known(parts[2])
        return parts.size >= 4 && parts[0] == VERSION && known(parts.last())
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
            n += FileTrees.deleteTree(d) // a kill mid-write; T328: another collection deleting the same directory is no error
        }
        return n
    }

    private val gcLock = Any()

    /**
     * [collectGarbage] for the app's cache on a background thread; [identities] are the DEM and vector files on the device.
     * T328: one collection at a time. A launch after an install and a pack push ran two (the `packs` stage's and the
     * reload the bundled assets' install asks for) over the same stale directories, and one's delete removed a directory
     * the other's stdlib walk had just found: the launch crash of the sweeps' `a11y` part after the guard's reboot.
     */
    fun collectGarbage(app: DystopoApp, identities: Set<String>) {
        // T379: once the index is whole (a held walk parks no thread here); then on a low-priority thread of its own.
        get(app).whenReady("contour-cache-gc-wait") {
            Thread({
                val n = synchronized(gcLock) { collectGarbage(root(app), get(app), identities) }
                staleFilesDeleted.addAndGet(n.toLong())
            }, "contour-cache-gc").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }
    }

    fun resize(mb: Long) = cache?.resize(mb * MB)

    /** Deletes every cached contour tile (all packs); returns how many. */
    fun clear(): Int = cache?.clear() ?: 0

    fun json(): Map<String, Any?> {
        val c = cache ?: return mapOf("loaded" to false, "maxBytes" to AppSettings.contourCacheMb * MB)
        return c.json() + mapOf(
            // T242: null while the index is built (`state`), as the cache's own figures are partial then
            "dirs" to if (!c.ready) null else c.byPrefix().map { (k, v) -> mapOf("dir" to k, "tiles" to v.first, "bytes" to v.second) },
            "staleFilesDeleted" to staleFilesDeleted.get(),
        )
    }

    const val MB = 1024L * 1024
}
