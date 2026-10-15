package io.github.jasondorjeshort.dystopo.app.areas

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.online.OnlineFetcher
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.VersionPins
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
 *
 * T95: each record keeps the content versions its tiles were fetched at ([Record.versions], per source); the pins
 * carry them ([VersionPins], read by [OnlineFetcher]), so a source's version change keeps the area's tiles and
 * lookups serve them at that version until the area is downloaded again ("Update" when [older] says the source
 * has moved on, or the prefetch ahead on the route).
 */
object Areas : VersionPins {
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
        /** T95: per source (cache key) the content versions (`dem`, `osm`; "" for none) the tiles were fetched at. */
        val versions: Map<String, Map<String, String>> = emptyMap(),
    ) {
        val kind: String get() = if (pack != null) "pack" else "tiles"

        fun json(): Map<String, Any?> = linkedMapOf(
            "id" to id, "name" to name, "kind" to area.kind, "content" to kind, "pinned" to pinned, "state" to state,
            "source" to source, "pack" to pack, "tripId" to tripId, "bytes" to bytes, "estimateBytes" to estimateBytes, "tiles" to tiles,
            "error" to error, "downloadMs" to downloadMs, "createdAt" to createdAt, "bounds" to area.bounds.toList(),
            "lengthM" to area.lengthM.takeIf { it > 0 }, "zooms" to listOf(area.minZoom, area.maxZoom), "area" to area.json(),
            "versions" to versions,
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
                versions = (m["versions"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                    if (k !is String || v !is Map<*, *>) null else k to v.mapNotNull { (n, ver) -> if (n is String && ver is String) n to ver else null }.toMap()
                }.toMap(),
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

    @Volatile private var current: AreaPins = AreaPins.NONE
    /** Covers pinned for a while by [AheadPrefetch] (navigation), by key, with the versions they were fetched at (T95). */
    private val transient = LinkedHashMap<String, Pair<Map<Int, TileSet>, Map<String, Map<String, String>>>>()

    /**
     * The tile cache hook (the default of [TileCache.keep], so it holds from the cache's first trim at start-up):
     * true for tiles of a pinned area (and of the segments ahead while navigating).
     */
    fun isPinned(key: String): Boolean {
        if (!loaded) runCatching { load() } // before the app is up (unit tests) nothing is pinned
        return current.isPinned(key)
    }

    /** T95 ([VersionPins]): the pins with their versions, for the fetcher's lookups and version changes. */
    override fun pins(): AreaPins {
        if (!loaded) runCatching { load() }
        return current
    }

    /**
     * T95 ([VersionPins]): [source]'s version of [name] moves on from [old] (a known one). A pinned record (or transient pin) that
     * never recorded one for that source (made before T95) takes [old], the version its tiles were fetched at.
     */
    @Synchronized
    override fun adopt(source: String, name: String, old: String) {
        if (!loaded) runCatching { load() }.onFailure { return }
        var n = 0
        fun take(v: Map<String, Map<String, String>>): Map<String, Map<String, String>>? =
            if (v[source]?.containsKey(name) == true) null else v + (source to (v[source].orEmpty() + (name to old)))
        for ((id, r) in records.toList()) if (r.pinned && r.pack == null) take(r.versions)?.let { records[id] = r.copy(versions = it); n++ }
        for ((k, t) in transient.toList()) take(t.second)?.let { transient[k] = t.first to it; n++ }
        if (n > 0) {
            Log.i(TAG, "$n areas take $name version '$old' of $source")
            changed()
        }
    }

    /**
     * T95: the content names (`dem`, `osm`) whose version [r] was fetched at differs from what its source serves
     * now ("older than the source"); empty when they agree, null when not known (no online source, or none recorded).
     * T106 (ADR 0030's deferred item): an area fetched from another source than the one the phone uses now
     * ([fromOtherSource]) is older too, in every name it holds: its tiles sit under that source's key, which no lookup
     * reads any more, so the map finds none of them and Update fetches them from the source set now.
     */
    fun older(r: Record): List<String>? {
        if (r.pack != null || r.source == null) return null
        val f = runCatching { OnlineService.get(app).fetcher }.getOrNull() ?: return null
        val key = OnlineFetcher.keyFor(r.source)
        val s = f.sourceList.firstOrNull { it.key == key } ?: return otherSourceNames(r.versions[key])
        val recorded = r.versions[key] ?: return null
        return AreaPins.older(recorded, HashMap(s.versions))
    }

    /** T106: [r] was fetched from a source the phone no longer uses (its server changed, or no server became one). */
    fun fromOtherSource(r: Record): Boolean {
        if (r.pack != null || r.source == null) return false
        val f = runCatching { OnlineService.get(app).fetcher }.getOrNull() ?: return false
        val key = OnlineFetcher.keyFor(r.source)
        return f.sourceList.none { it.key == key }
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
        onPinsChanged = { service.cache.recountPinned() } // T91: pinned bytes have their own budget
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
        check(r.pack == null) { "area ${r.name} is the pack ${r.pack}: $PACK_NOTE" } // T91: pinning one did nothing either
        val n = update(r.id) { it.copy(pinned = on) }!!
        if (!on) dropUnheld() // T95: its tiles of an older version are reachable no more
        return n
    }

    /**
     * T106 (a T91 follow-up): Dismiss on an area whose download failed, was cut short or was refused: the message goes. An
     * area with nothing on the phone (no tile of its manifest) is removed; any other keeps what it has, `done` when every
     * tile is there, else `partial` (Retry finishes it). Returns the record, or null when it was removed.
     */
    fun dismiss(id: String): Record? {
        val r = get(id) ?: throw NoSuchElementException("no area '$id'")
        check(r.state != "downloading" || AreaDownload.job?.let { it.id == r.id && it.running } != true) { "area ${r.name} is downloading; DELETE /areas/download first" }
        val share = (coverage(r)["share"] as? Number)?.toDouble()
        return when (val next = dismissedState(r.pack != null, share, r.bytes, r.state)) {
            null -> { remove(r.id); null }
            else -> update(r.id) { it.copy(state = next, error = null) }
        }
    }

    /** T95: the fetcher's [OnlineFetcher.dropUnheld] after the pins changed (an area refreshed, unpinned or removed). */
    fun dropUnheld(): Int = runCatching { OnlineService.get(app).fetcher?.dropUnheld() ?: 0 }.getOrDefault(0)

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
        val unheld = dropUnheld() // T95: and its tiles of an older version that no other area holds
        return linkedMapOf("removed" to r.id, "tilesDeleted" to tilesDeleted + unheld, "pack" to pack)
    }

    /** T91: told (on its own thread, so no lock is held) when the pins change, so the cache recounts its pinned bytes. */
    @Volatile private var onPinsChanged: (() -> Unit)? = null
    private var pinCovers: List<Map<Int, TileSet>> = emptyList()

    private fun changed(save: Boolean = true) {
        val all = records.values.toList()
        val pinned = all.filter { it.pinned && it.pack == null }
        val covers = pinned.map { r -> coverMemo.getOrPut(r.id) { AreaPins.cover(r.area) } } + transient.values.map { it.first }
        val same = covers == pinCovers
        pinCovers = covers
        current = AreaPins(covers, pinned.map { it.versions } + transient.values.map { it.second }) // T95: with the versions
        if (!same) onPinsChanged?.let { hook -> Thread(hook, "areas-recount").apply { isDaemon = true }.start() }
        list = all
        if (save) runCatching {
            val tmp = File(dir, "areas.json.tmp")
            tmp.writeText(MiniJson.encode(mapOf("version" to 1, "areas" to all.map { it.json() })))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.w(TAG, "saving areas failed", it) }
    }

    /** [AheadPrefetch]: pins [cover] (fetched at [versions], T95) under [key] until [unpinTransient]. */
    @Synchronized
    fun pinTransient(key: String, cover: Map<Int, TileSet>, versions: Map<String, Map<String, String>> = emptyMap()) {
        load()
        transient[key] = cover to versions
        changed(save = false)
    }

    @Synchronized
    fun unpinTransient(prefix: String = "") {
        if (transient.keys.removeAll { it.startsWith(prefix) }) changed(save = false)
    }

    val transientCount: Int @Synchronized get() = transient.size

    /**
     * The cache keys tile [path] of [route] may sit under: every source of the current fetcher with its content
     * version (T74: `<source>/<route>@<version>/...`), else the recorded [source] unversioned; T95: with [held], also
     * under every older version a pin holds for that source (the keys a lookup tries after a miss).
     */
    fun cacheKeys(route: String, path: String, source: String? = null, held: Boolean = true): List<String> {
        val f = OnlineService.get(app).fetcher
        val keys = f?.sourceList?.map { "${it.prefix(route)}/$path" }.orEmpty()
        val older = if (!held || f == null) emptyList() else OnlineFetcher.VERSION_OF[route]?.let { name ->
            val p = pins()
            f.sourceList.flatMap { s -> p.versions(s.key, name).filter { it != (s.versions[name] ?: "") }.map { "${s.prefixAt(route, it)}/$path" } }
        }.orEmpty()
        return (keys + older).ifEmpty { listOfNotNull(source?.let { "${OnlineFetcher.keyFor(it)}/$route/$path" }) }
    }

    fun cached(route: String, path: String, source: String? = null): Boolean {
        val cache = OnlineService.get(app).cache
        return cacheKeys(route, path, source).any { cache.contains(it) }
    }

    /** T95: whether tile [path] of [route] is cached at the current version of some source (not only at an older one a pin holds). */
    fun cachedCurrent(route: String, path: String, source: String? = null): Boolean {
        val cache = OnlineService.get(app).cache
        return cacheKeys(route, path, source, held = false).any { cache.contains(it) }
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
        var olderOnly = 0
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
                    cachedCurrent(item.route, "$z/$x/$y.${item.ext}", r.source) -> h++
                    cached(item.route, "$z/$x/$y.${item.ext}", r.source) -> { h++; olderOnly++ } // T95: kept at the version it was fetched at
                }
            }
            perRoute[item.route] = mapOf("have" to h, "tiles" to t)
            have += h
            total += t
        }
        return linkedMapOf(
            "share" to if (total == 0) null else Math.round(have * 1000.0 / total) / 1000.0, "have" to have, "tiles" to total, "inPacks" to inPacks,
            "atOlderVersion" to olderOnly, "routes" to perRoute,
        )
    }

    fun json(withCoverage: Boolean = true): Map<String, Any?> {
        val all = all()
        val service = OnlineService.get(app)
        val cache = service.cache
        val current = runCatching { service.fetcher?.sourceList?.map { it.key } }.getOrNull().orEmpty()
        return linkedMapOf(
            "areas" to all.map { r ->
                r.json() + mapOf("olderThanSource" to older(r), "fromOtherSource" to fromOtherSource(r)) + if (withCoverage) mapOf("coverage" to coverage(r)) else emptyMap()
            },
            "pinned" to all.count { it.pinned }, "transientPins" to transientCount,
            "pinnedBySource" to pinnedBySource(cache.byPrefix(1, pinned = true), current), // T106 (ADR 0030's deferred item)
            "cache" to cache.json(), "download" to AreaDownload.json(), "prefetch" to AheadPrefetch.json(),
            "outline" to AreaOutlines.json(), // T106: the sheet's corridor polygons
        )
    }
}

/**
 * T106 (ADR 0030's deferred item): the pinned tiles per source cache key ([TileCache.byPrefix] of the pinned entries:
 * key to tiles and bytes), with whether the phone reads that source now ([current]): after a server switch the old
 * source's pinned tiles stay on the phone, counted against the offline areas' budget, until their area is updated
 * (then they go) or unpinned. Biggest first. Pure, tested.
 */
internal fun pinnedBySource(byKey: Map<String, Pair<Int, Long>>, current: Collection<String>): List<Map<String, Any?>> =
    byKey.entries.sortedByDescending { it.value.second }.map { (key, v) ->
        linkedMapOf("source" to key, "tiles" to v.first, "bytes" to v.second, "inUse" to (key in current))
    }

/**
 * T106: the state an area takes when its failure is dismissed, from whether it is a pack, the share of its manifest's
 * tiles the map finds now ([share], null when unknown), the bytes it recorded and its [state]: `done` for a pack or a
 * whole corridor, `partial` for some tiles; null (remove it) when nothing of it was ever downloaded. An area whose tiles
 * the map does not find although it has bytes (fetched from another source, its Update refused) keeps them and its state.
 * Pure, tested.
 */
internal fun dismissedState(isPack: Boolean, share: Double?, bytes: Long, state: String): String? = when {
    isPack -> "done"
    share != null && share >= 1.0 -> "done"
    share != null && share > 0.0 -> "partial"
    bytes <= 0 -> null
    state == "done" || state == "partial" -> state
    else -> "partial"
}

/**
 * T106: the names an area fetched from a source the phone no longer reads is older in: the ones it recorded for that
 * source ([recorded]), else `dem` and `osm` (an area from before T95 recorded none). Pure, tested.
 */
internal fun otherSourceNames(recorded: Map<String, String>?): List<String> =
    recorded?.keys?.sorted()?.takeIf { it.isNotEmpty() } ?: listOf("dem", "osm")
