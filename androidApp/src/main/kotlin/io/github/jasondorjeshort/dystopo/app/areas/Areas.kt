package io.github.jasondorjeshort.dystopo.app.areas

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.online.OnlineFetcher
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.TileSet
import io.github.jasondorjeshort.dystopo.areas.TilesItem
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.File

/**
 * T75: the phone's areas of interest (design principle 4). An area is a box or a route corridor with a zoom range
 * whose data the user asked to have offline; it is recorded here with what its download fetched and is **pinned**:
 * the online tile cache's eviction skips its tiles ([TileCache.keep] is [isPinned], one hook line in
 * `online/TileCache.kt`) until the user unpins it, after which they are ordinary least-recently-used entries.
 *
 * Two kinds of content (see [AreaManifest]):
 * - a box downloads a region pack ("Download this region", [io.github.jasondorjeshort.dystopo.app.online.RegionDownload]):
 *   archives are never evicted, so a pack-backed area stays until it is removed (which deletes the pack);
 * - a corridor (a trip's "Download for offline", `POST /areas/download`) fetches the DEM and vector tiles within its
 *   buffer into the tile cache ([AreaDownload]), where the pins keep them.
 *
 * Records live in `files/areas/areas.json`; each area's manifest (with its tile lists) in `files/areas/<id>.json`.
 * [AheadPrefetch] adds transient pins for the segments ahead during navigation. [coverage] counts what is on the
 * device now: a tile counts when a pack holds it or the cache has it under the area's source.
 */
object Areas {
    const val TAG = "DystopoAreas"
    private val app: DystopoApp get() = DystopoApp.instance
    private val dir: File by lazy { File(app.filesDir, "areas").apply { mkdirs() } }
    private val file: File get() = File(dir, "areas.json")

    /** One area. [pack] is the region pack of a box; [source] the tile source a corridor's tiles were fetched from. */
    data class Record(
        val id: String,
        val name: String,
        val area: AreaOfInterest,
        val createdAt: Long,
        val pinned: Boolean = true,
        val state: String = "pending",
        val source: String? = null,
        val pack: String? = null,
        val tripId: String? = null,
        val bytes: Long = 0,
        val estimateBytes: Long = 0,
        val tiles: Int = 0,
        val error: String? = null,
        val downloadMs: Long? = null,
    ) {
        val kind: String get() = if (pack != null) "pack" else "tiles"

        fun json(): Map<String, Any?> = linkedMapOf(
            "id" to id, "name" to name, "kind" to area.kind, "content" to kind, "pinned" to pinned, "state" to state,
            "source" to source, "pack" to pack, "tripId" to tripId, "bytes" to bytes, "estimateBytes" to estimateBytes, "tiles" to tiles,
            "error" to error, "downloadMs" to downloadMs, "createdAt" to createdAt, "bounds" to area.bounds.toList(),
            "lengthM" to area.lengthM.takeIf { it > 0 }, "zooms" to listOf(area.minZoom, area.maxZoom), "area" to area.json(),
        )

        companion object {
            @Suppress("UNCHECKED_CAST")
            fun parse(m: Map<String, Any?>): Record = Record(
                id = m["id"] as String, name = m["name"] as? String ?: m["id"] as String,
                area = AreaOfInterest.parse(m["area"] as Map<String, Any?>),
                createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L, pinned = m["pinned"] != false,
                state = m["state"] as? String ?: "pending", source = m["source"] as? String, pack = m["pack"] as? String,
                tripId = m["tripId"] as? String, bytes = (m["bytes"] as? Number)?.toLong() ?: 0L,
                estimateBytes = (m["estimateBytes"] as? Number)?.toLong() ?: 0L, tiles = (m["tiles"] as? Number)?.toInt() ?: 0,
                error = m["error"] as? String, downloadMs = (m["downloadMs"] as? Number)?.toLong(),
            )
        }
    }

    private val records = LinkedHashMap<String, Record>()
    private val coverMemo = HashMap<String, Map<Int, TileSet>>()
    private var loaded = false

    /**
     * T87: loads this object, and so creates its Compose state ([list], [screenOpen]), on the main thread from
     * `Application.onCreate` before any composition, as `NavSession.prepare()` does (T86): [AheadPrefetch]'s
     * thread could otherwise be the first to touch it while a composition snapshot is open.
     */
    fun prepare() = Unit

    /** For the Areas screen (Compose state, replaced on every change). */
    var list by mutableStateOf<List<Record>>(emptyList())
        private set

    /** The Areas screen is open. */
    var screenOpen by mutableStateOf(false)

    @Volatile private var pins: AreaPins = AreaPins.NONE
    /** Covers pinned for a while by [AheadPrefetch] (navigation), by key. */
    private val transient = LinkedHashMap<String, Map<Int, TileSet>>()

    /**
     * The tile cache hook (the default of [TileCache.keep], so it holds from the cache's first trim at start-up):
     * true for tiles of a pinned area (and of the segments ahead while navigating).
     */
    fun isPinned(key: String): Boolean {
        if (!loaded) runCatching { load() } // before the app is up (unit tests) nothing is pinned
        return pins.isPinned(key)
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        dir // the app's files dir; throws before the app exists, leaving [loaded] false
        loaded = true
        runCatching {
            if (file.isFile) {
                val doc = MiniJson.parseObject(file.readText())
                for (m in (doc["areas"] as? List<*>).orEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    runCatching { Record.parse(m as Map<String, Any?>) }.onSuccess { records[it.id] = it }.onFailure { Log.w(TAG, "unreadable area dropped", it) }
                }
            }
        }.onFailure { Log.w(TAG, "areas.json unreadable", it) }
        changed(save = false)
    }

    /** Installs the cache hook (called when the online service starts) and reads the records. */
    fun attach(service: OnlineService) {
        load()
        service.cache.keep = ::isPinned
        AheadPrefetch.start()
    }

    fun all(): List<Record> {
        load()
        return synchronized(this) { records.values.toList() }
    }

    fun get(id: String): Record? = all().firstOrNull { it.id == id || (id.length >= 4 && it.id.startsWith(id)) }

    @Synchronized
    fun put(r: Record) {
        load()
        records[r.id] = r
        changed()
    }

    @Synchronized
    fun update(id: String, f: (Record) -> Record): Record? {
        load()
        val r = records[id] ?: return null
        val n = f(r)
        records[id] = n
        changed()
        return n
    }

    /** Pins or unpins [id]; unpinned tiles rejoin the cache's LRU order (and go first when it is over its cap). */
    fun pin(id: String, on: Boolean): Record {
        val r = get(id) ?: throw NoSuchElementException("no area '$id'")
        check(on || r.pack == null) { "area ${r.name} is the pack ${r.pack}: packs are never evicted; remove the area to delete it" }
        return update(r.id) { it.copy(pinned = on) }!!
    }

    /**
     * Removes [id]: a pack-backed area deletes its pack; a corridor's tiles that no other pinned area holds are
     * dropped from the cache. Returns what went.
     */
    fun remove(id: String): Map<String, Any?> {
        val r = get(id) ?: throw NoSuchElementException("no area '$id'")
        AreaDownload.cancel(r.id)
        synchronized(this) {
            records.remove(r.id)
            changed()
        }
        manifestFile(r.id).delete()
        var tilesDeleted = 0
        var pack: Map<String, Any?>? = null
        if (r.pack != null && PackManager.get(app).find(r.pack) != null) pack = runCatching { PackManager.get(app).delete(r.pack) }.getOrElse { mapOf("error" to it.message) }
        val m = manifest(r.id) ?: manifestOf(r)
        val cache = OnlineService.get(app).cache
        if (m != null) {
            for (item in m.items.filterIsInstance<TilesItem>()) for ((z, set) in item.tiles) for ((x, y) in set.tiles()) {
                for (key in cacheKeys(item.route, "$z/$x/$y.${item.ext}", r.source)) if (!isPinned(key)) tilesDeleted += cache.clear(key)
            }
        }
        return linkedMapOf("removed" to r.id, "tilesDeleted" to tilesDeleted, "pack" to pack)
    }

    private fun changed(save: Boolean = true) {
        val all = records.values.toList()
        val covers = all.filter { it.pinned && it.pack == null }.map { r -> coverMemo.getOrPut(r.id) { AreaPins.cover(r.area) } } + transient.values
        pins = AreaPins(covers)
        list = all
        if (save) runCatching {
            val tmp = File(dir, "areas.json.tmp")
            tmp.writeText(MiniJson.encode(mapOf("version" to 1, "areas" to all.map { it.json() })))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.w(TAG, "saving areas failed", it) }
    }

    /** [AheadPrefetch]: pins [cover] under [key] until [unpinTransient]. */
    @Synchronized
    fun pinTransient(key: String, cover: Map<Int, TileSet>) {
        load()
        transient[key] = cover
        changed(save = false)
    }

    @Synchronized
    fun unpinTransient(prefix: String = "") {
        if (transient.keys.removeAll { it.startsWith(prefix) }) changed(save = false)
    }

    val transientCount: Int @Synchronized get() = transient.size

    /**
     * The cache keys tile [path] of [route] may sit under: every source of the current fetcher with its content
     * version (T74: `<source>/<route>@<version>/...`), else the recorded [source] unversioned.
     */
    fun cacheKeys(route: String, path: String, source: String? = null): List<String> {
        val f = OnlineService.get(app).fetcher
        val keys = f?.sourceList?.map { "${it.prefix(route)}/$path" }.orEmpty()
        return keys.ifEmpty { listOfNotNull(source?.let { "${OnlineFetcher.keyFor(it)}/$route/$path" }) }
    }

    fun cached(route: String, path: String, source: String? = null): Boolean {
        val cache = OnlineService.get(app).cache
        return cacheKeys(route, path, source).any { cache.contains(it) }
    }

    fun manifestFile(id: String) = File(dir, "$id.json")

    fun saveManifest(m: AreaManifest) {
        runCatching { manifestFile(m.id).writeText(MiniJson.encode(m.json())) }.onFailure { Log.w(TAG, "saving manifest ${m.id} failed", it) }
    }

    fun manifest(id: String): AreaManifest? = manifestFile(id).takeIf { it.isFile }?.let { f -> runCatching { AreaManifest.parse(f.readText()) }.getOrNull() }

    /** A tile manifest computed on the phone for a record whose file is gone. */
    private fun manifestOf(r: Record): AreaManifest? = if (r.pack != null) null else AreaDownload.localManifest(r.area, r.source)

    /**
     * What of [r] is on the device now: tiles present (in a pack, or in the cache under the area's source) out of
     * the manifest's tiles; a pack-backed area is complete while its pack is installed.
     */
    fun coverage(r: Record): Map<String, Any?> {
        if (r.pack != null) {
            val p = PackManager.get(app).find(r.pack)
            return linkedMapOf("present" to (p != null), "share" to if (p != null) 1.0 else 0.0, "packBytes" to p?.files?.files?.sumOf { it.length() })
        }
        val m = manifest(r.id) ?: return linkedMapOf("share" to null, "why" to "no manifest")
        val packs = runCatching { app.tiles.packs }.getOrNull()
        var have = 0
        var total = 0
        var inPacks = 0
        val perRoute = LinkedHashMap<String, Map<String, Int>>()
        for (item in m.items.filterIsInstance<TilesItem>()) {
            var h = 0
            var t = 0
            for ((z, set) in item.tiles) for ((x, y) in set.tiles()) {
                t++
                val pack = when (item.route) {
                    AreaManifest.ROUTE_DEM -> packs?.demFor(z, x, y)
                    AreaManifest.ROUTE_OSM -> packs?.osmFor(z, x, y)
                    else -> null
                }
                when {
                    pack != null -> { h++; inPacks++ }
                    cached(item.route, "$z/$x/$y.${item.ext}", r.source) -> h++
                }
            }
            perRoute[item.route] = mapOf("have" to h, "tiles" to t)
            have += h
            total += t
        }
        return linkedMapOf("share" to if (total == 0) null else Math.round(have * 1000.0 / total) / 1000.0, "have" to have, "tiles" to total, "inPacks" to inPacks, "routes" to perRoute)
    }

    fun json(withCoverage: Boolean = true): Map<String, Any?> {
        val all = all()
        val cache = OnlineService.get(app).cache
        return linkedMapOf(
            "areas" to all.map { r -> r.json() + if (withCoverage) mapOf("coverage" to coverage(r)) else emptyMap() },
            "pinned" to all.count { it.pinned }, "transientPins" to transientCount,
            "cache" to cache.json(), "download" to AreaDownload.json(), "prefetch" to AheadPrefetch.json(),
        )
    }
}
