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
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.NeedsNewerApp
import io.github.jasondorjeshort.dystopo.areas.PolygonRings
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
 * T140 (ADR 0041 §2): records a version-1 reader (v0.1.10 and earlier) cannot parse whole, polygons and records this
 * build cannot read itself, live in the sibling `files/areas/areas2.json` ([AreaFiles]), so a downgraded build's save
 * loses nothing; this build reads both. A record it cannot read is kept opaque ([Record.opaque]): written back
 * unchanged, pinned by its record-level `bounds` and `zooms`, its card saying the phone needs a newer app.
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
    /** T140 (ADR 0041 §2): what a version-1 reader cannot parse whole. */
    private val sibling: File get() = File(dir, "areas2.json")

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
        /**
         * T140 (ADR 0041 §2): a record this build cannot read (a newer version or kind): its JSON, written back unchanged,
         * and why. [area] is then the box of its record-level `bounds` and `zooms`, which pins it; nothing downloads it.
         */
        val opaque: Opaque? = null,
        /** T151: where an unfinished download got to ([DownloadCursor]); null when it finished whole or never started. */
        val cursor: DownloadCursor? = null,
        /** T151: the user cancelled its download: never resumed by itself (Retry or Download again clears it). */
        val cancelled: Boolean = false,
        /** T151: the automatic resume's state ([AutoResume]: failures, the next try, why it stopped); null when none. */
        val autoResume: AutoResume? = null,
    ) {
        val kind: String get() = if (pack != null) "pack" else "tiles"

        /** T140: an opaque record ([opaque]); its card says the phone needs a newer app. */
        val needsNewerApp: Boolean get() = opaque != null

        /** T140: a version-1 reader (v0.1.10) parses this record whole, so it belongs in `areas.json`. */
        val readableByV1: Boolean get() = opaque == null && area.manifestVersion == 1

        fun json(): Map<String, Any?> = opaque?.raw ?: recordJson().also { if (area.manifestVersion > 1) it["version"] = area.manifestVersion }

        private fun recordJson(): LinkedHashMap<String, Any?> = linkedMapOf(
            "id" to id, "name" to name, "kind" to area.kind, "content" to kind, "pinned" to pinned, "state" to state,
            "source" to source, "pack" to pack, "tripId" to tripId, "bytes" to bytes, "estimateBytes" to estimateBytes, "tiles" to tiles,
            "error" to error, "downloadMs" to downloadMs, "createdAt" to createdAt, "bounds" to area.bounds.toList(),
            "lengthM" to area.lengthM.takeIf { it > 0 }, "zooms" to listOf(area.minZoom, area.maxZoom), "area" to area.json(),
            "versions" to versions,
        ).also { m -> // T151: only when set, so a finished record is written as before
            cursor?.let { m["cursor"] = it.json() }
            if (cancelled) m["cancelled"] = true
            autoResume?.let { m["autoResume"] = it.json() }
        }

        /** T140: an unreadable record's raw JSON and the sentence that refused it. */
        data class Opaque(val raw: Map<String, Any?>, val reason: String)

        companion object {
            /**
             * T140 (ADR 0041 §2): checks the record's `version`, then its area's `kind`, then the fields. A record that
             * fails any of them is kept opaque ([opaqueOf]) when it has an id and bounds to pin it by; else this throws.
             */
            @Suppress("UNCHECKED_CAST")
            fun parse(m: Map<String, Any?>): Record = try {
                AreaManifest.checkVersion((m["version"] as? Number)?.toInt() ?: 1)
                read(m, AreaOfInterest.parse(m["area"] as? Map<String, Any?> ?: throw IllegalArgumentException("area record without area")))
            } catch (e: IllegalArgumentException) {
                opaqueOf(m, e.message ?: "unreadable")
            } catch (e: ClassCastException) {
                opaqueOf(m, "unreadable: ${e.message}")
            }

            /** T140: [m] kept as it is, pinned by its record-level `bounds` and `zooms`; throws when it has no id or bounds. */
            fun opaqueOf(m: Map<String, Any?>, reason: String): Record {
                val id = m["id"] as? String ?: throw IllegalArgumentException("area record without id")
                val b = (m["bounds"] as? List<*>)?.map { (it as? Number)?.toDouble() }
                require(b != null && b.size == 4 && b.all { it != null }) { "area record $id without bounds" }
                val z = (m["zooms"] as? List<*>)?.map { (it as? Number)?.toInt() }
                val lo = (z?.getOrNull(0) ?: 8).coerceIn(0, AreaOfInterest.MAX_ZOOM)
                val hi = (z?.getOrNull(1) ?: 15).coerceIn(lo, AreaOfInterest.MAX_ZOOM)
                val name = m["name"] as? String ?: id
                return Record(
                    id = id, name = name, area = AreaOfInterest(AreaShape.Box(b[0]!!, b[1]!!, b[2]!!, b[3]!!), lo, hi, name),
                    createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L, pinned = m["pinned"] != false, state = m["state"] as? String ?: "done",
                    source = m["source"] as? String, pack = m["pack"] as? String, bytes = (m["bytes"] as? Number)?.toLong() ?: 0L,
                    estimateBytes = (m["estimateBytes"] as? Number)?.toLong() ?: 0L, tiles = (m["tiles"] as? Number)?.toInt() ?: 0,
                    downloadMs = (m["downloadMs"] as? Number)?.toLong(), versions = versionsOf(m["versions"]), opaque = Opaque(m, reason),
                )
            }

            private fun versionsOf(v: Any?): Map<String, Map<String, String>> = (v as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                if (k !is String || v !is Map<*, *>) null else k to v.mapNotNull { (n, ver) -> if (n is String && ver is String) n to ver else null }.toMap()
            }.toMap()

            private fun read(m: Map<String, Any?>, area: AreaOfInterest): Record = Record(
                id = m["id"] as String, name = m["name"] as? String ?: m["id"] as String,
                area = area,
                createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L, pinned = m["pinned"] != false,
                state = m["state"] as? String ?: "pending", source = m["source"] as? String, pack = m["pack"] as? String,
                tripId = m["tripId"] as? String, bytes = (m["bytes"] as? Number)?.toLong() ?: 0L,
                estimateBytes = (m["estimateBytes"] as? Number)?.toLong() ?: 0L, tiles = (m["tiles"] as? Number)?.toInt() ?: 0,
                error = m["error"] as? String, downloadMs = (m["downloadMs"] as? Number)?.toLong(),
                versions = versionsOf(m["versions"]),
                cursor = (m["cursor"] as? Map<*, *>)?.let { DownloadCursor.parse(it) }, // T151
                cancelled = m["cancelled"] == true,
                autoResume = (m["autoResume"] as? Map<*, *>)?.let { AutoResume.parse(it) },
            )
        }
    }

    private val records = LinkedHashMap<String, Record>()
    /** T140: records without even an id or bounds, kept as they were in the sibling file. */
    private val unreadable = ArrayList<Map<String, Any?>>()
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
        for ((id, r) in records.toList()) if (r.pinned && r.pack == null && r.opaque == null) take(r.versions)?.let { records[id] = r.copy(versions = it); n++ }
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
        if (r.pack != null || r.source == null || r.opaque != null) return null
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
        // T140: both files (the sibling holds what a version-1 reader cannot parse), each record by version, kind, fields.
        fun text(f: File) = runCatching { f.takeIf { it.isFile }?.readText() }.onFailure { Log.w(TAG, "${f.name} unreadable", it) }.getOrNull()
        val files = AreaFiles.decode(text(file), text(sibling)) { what, e -> Log.w(TAG, what, e) }
        for (r in files.records) records[r.id] = r
        unreadable += files.unreadable
        files.records.count { it.opaque != null }.takeIf { it > 0 }?.let { Log.i(TAG, "$it areas need a newer app; kept as they are") }
        // T151: the repair at start. A record left "downloading" by a process that ended has no live job: it becomes partial
        // (Retry shows at once, and the resume check picks it up) or cancelled when the user had cancelled it.
        val live = AreaDownload.job?.takeIf { it.running }?.id
        for (r in records.values.toList()) repairedAtStart(r, live)?.let { records[r.id] = it; repaired += r.id }
        if (repaired.isNotEmpty()) Log.i(TAG, "repaired ${repaired.size} interrupted downloads: $repaired")
        changed(save = repaired.isNotEmpty())
    }

    /** T151: the ids [load] found left "downloading" with no live job and repaired (`GET /areas/resume`). */
    val repaired: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** Installs the cache hook (called when the online service starts) and reads the records. */
    fun attach(service: OnlineService) {
        load()
        service.cache.keep = ::isPinned
        onPinsChanged = { service.cache.recountPinned() } // T91: pinned bytes have their own budget
        rowsOf(service.cache) // T151: the cache's keys as rows, for coverage
        AheadPrefetch.start()
        TripCorridors.request("start") // T130: trips saved, synced or deleted while the app was not running
        ResumeCheck.install() // T151: the foreground trigger
        ResumeCheck.request(ResumeCheck.TRIGGER_START) // T151: interrupted downloads resume from their cursor
    }

    /** T151: the online cache's keys as rows ([CachedRows]), kept by its [TileCache.onIndex] hook; one per cache. */
    @Volatile private var cacheRows: Pair<io.github.jasondorjeshort.dystopo.app.online.TileCache, CachedRows>? = null

    private val rowsLock = Any() // not the object's lock: the cache calls [isPinned] under its own lock

    private fun rowsOf(cache: io.github.jasondorjeshort.dystopo.app.online.TileCache): CachedRows = synchronized(rowsLock) {
        cacheRows?.takeIf { it.first === cache }?.second ?: CachedRows().also { rows ->
            // The hook first, then every key already there (under the cache's lock: a key added meanwhile is added twice, which is harmless).
            cache.onIndex = { key, present -> if (present) rows.add(key) else rows.remove(key) }
            cache.forEachKey { rows.add(it) }
            cacheRows = cache to rows
        }
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
        check(r.opaque == null) { "area ${r.name}: ${NeedsNewerApp.USER_SENTENCE}" } // T140: kept as it is
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
        check(r.opaque == null) { "area ${r.name}: ${NeedsNewerApp.USER_SENTENCE}" } // T140
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
        val t0 = System.currentTimeMillis()
        val m = manifest(r.id) ?: manifestOf(r) // T151: read before the file goes (it was deleted first, so the manifest was always remade)
        manifestFile(r.id).delete()
        synchronized(manifestMemo) { manifestMemo.remove(r.id) }
        var tilesDeleted = 0
        var pack: Map<String, Any?>? = null
        if (r.pack != null && PackManager.get(app).find(r.pack) != null) pack = runCatching { PackManager.get(app).delete(r.pack) }.getOrElse { mapOf("error" to it.message) }
        val cache = OnlineService.get(app).cache
        if (m != null) {
            // T151: one pass over the cache's index, each key looked up in the manifest's rows; before, every tile of the
            // manifest was a prefix scan of the whole index (123 s for a cancelled France download of 934 tiles).
            val items = m.items.filterIsInstance<TilesItem>()
            val byPrefix = HashMap<String, TilesItem>()
            for (item in items) for (p in keyPrefixes(item.route, r.source)) byPrefix[p] = item
            tilesDeleted = cache.clearWhere { k ->
                val t = parseTileKey(k)
                val item = t?.let { byPrefix[it.prefix] }
                item != null && t.ext == item.ext && item.tiles[t.z]?.contains(t.x, t.y) == true && !isPinned(k)
            }
        }
        val unheld = dropUnheld() // T95: and its tiles of an older version that no other area holds
        return linkedMapOf("removed" to r.id, "tilesDeleted" to tilesDeleted + unheld, "pack" to pack, "ms" to System.currentTimeMillis() - t0)
    }

    /** T91: told (on its own thread, so no lock is held) when the pins change, so the cache recounts its pinned bytes. */
    @Volatile private var onPinsChanged: (() -> Unit)? = null
    private var pinCovers: List<Map<Int, TileSet>> = emptyList()

    private fun changed(save: Boolean = true) {
        val all = records.values.toList()
        val pinned = all.filter { it.pinned && it.pack == null }
        // T140: an opaque record pins its box at the zooms the heap allows ([opaquePinArea]): a newer kind may be far larger.
        val covers = pinned.map { r -> coverMemo.getOrPut(r.id) { AreaPins.cover(if (r.opaque != null) opaquePinArea(r.area, AreaDownload.heapTiles()) else r.area) } } +
            transient.values.map { it.first }
        val same = covers == pinCovers
        pinCovers = covers
        current = AreaPins(covers, pinned.map { it.versions } + transient.values.map { it.second }) // T95: with the versions
        if (!same) onPinsChanged?.let { hook -> Thread(hook, "areas-recount").apply { isDaemon = true }.start() }
        list = all
        if (save) runCatching {
            // T140 (ADR 0041 §2): the sibling first, so a record moving into it is never in neither file; each written whole.
            val files = AreaFiles(all, unreadable.toList())
            files.siblingText()?.let { writeAtomically(sibling, it) } ?: sibling.delete()
            writeAtomically(file, files.mainText())
        }.onFailure { Log.w(TAG, "saving areas failed", it) }
    }

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
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
    fun cacheKeys(route: String, path: String, source: String? = null, held: Boolean = true): List<String> =
        keyPrefixes(route, source, held).map { "$it/$path" }

    /** T151: the key prefixes (`<source>/<route>[@<version>]`) of [cacheKeys], without the tile's path. */
    fun keyPrefixes(route: String, source: String? = null, held: Boolean = true): List<String> {
        val f = OnlineService.get(app).fetcher
        val keys = f?.sourceList?.map { it.prefix(route) }.orEmpty()
        val older = if (!held || f == null) emptyList() else OnlineFetcher.VERSION_OF[route]?.let { name ->
            val p = pins()
            f.sourceList.flatMap { s -> p.versions(s.key, name).filter { it != (s.versions[name] ?: "") }.map { s.prefixAt(route, it) } }
        }.orEmpty()
        return (keys + older).ifEmpty { listOfNotNull(source?.let { "${OnlineFetcher.keyFor(it)}/$route" }) }
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
        runCatching {
            val f = manifestFile(m.id)
            f.writeText(MiniJson.encode(m.json()))
            synchronized(manifestMemo) { manifestMemo[m.id] = Triple(f.lastModified(), f.length(), m) }
        }.onFailure { Log.w(TAG, "saving manifest ${m.id} failed", it) }
    }

    /** T151: the manifests read, by id, with the file's time and length they were read at (the sheet asked for each every 1.5 s, parsing it). */
    private val manifestMemo = HashMap<String, Triple<Long, Long, AreaManifest>>()

    fun manifest(id: String): AreaManifest? {
        val f = manifestFile(id).takeIf { it.isFile } ?: return null
        val time = f.lastModified()
        val length = f.length()
        synchronized(manifestMemo) { manifestMemo[id]?.takeIf { it.first == time && it.second == length }?.let { return it.third } }
        val m = runCatching { AreaManifest.parse(f.readText()) }.getOrNull() ?: return null
        synchronized(manifestMemo) { manifestMemo[id] = Triple(time, length, m) }
        return m
    }

    /** A tile manifest computed on the phone for a record whose file is gone. */
    private fun manifestOf(r: Record): AreaManifest? = if (r.pack != null || r.opaque != null) null else AreaDownload.localManifest(r.area, r.source)

    /**
     * What of [r] is on the device now: tiles present (in a pack, or in the cache under the area's source) out of
     * the manifest's tiles; a pack-backed area is complete while its pack is installed.
     */
    fun coverage(r: Record): Map<String, Any?> {
        if (r.pack != null) {
            val p = PackManager.get(app).find(r.pack)
            return linkedMapOf("present" to (p != null), "share" to if (p != null) 1.0 else 0.0, "packBytes" to p?.files?.files?.sumOf { it.length() })
        }
        val t0 = System.nanoTime()
        val m = manifest(r.id) ?: return linkedMapOf("share" to null, "why" to "no manifest")
        val packs = runCatching { app.tiles.packs }.getOrNull()
        val rows = rowsOf(OnlineService.get(app).cache)
        var have = 0L
        var total = 0L
        var inPacks = 0L
        var olderOnly = 0L
        var rowCount = 0
        val perRoute = LinkedHashMap<String, Map<String, Long>>()
        val iv = SpanList()
        // T151: row by row. Per manifest row, the x spans the packs overlap, the spans cached at the current version and
        // those cached only at an older version a pin holds; their unions give what the per-tile walk counted (a tile in a
        // pack first, then cached now, then cached at the version it was fetched at), at the cost of rows and spans.
        for (item in m.items.filterIsInstance<TilesItem>()) {
            var h = 0L
            var t = 0L
            val current = keyPrefixes(item.route, r.source, held = false)
            val older = keyPrefixes(item.route, r.source, held = true) - current.toSet()
            val (role, covers) = when (item.route) {
                AreaManifest.ROUTE_DEM -> io.github.jasondorjeshort.dystopo.packs.PackRoleZooms.DEM to packs?.regions?.mapNotNull { it.demCoverage }.orEmpty()
                AreaManifest.ROUTE_OSM -> io.github.jasondorjeshort.dystopo.packs.PackRoleZooms.VECTOR to packs?.regions?.mapNotNull { it.osmCoverage }.orEmpty()
                else -> io.github.jasondorjeshort.dystopo.packs.PackRoleZooms.DEM to emptyList()
            }
            for ((z, set) in item.tiles) set.forEachRow { y, x0, x1 ->
                rowCount++
                t += x1 - x0 + 1
                iv.clear()
                for (c in covers) packSpan(c, role, z, y, x0, x1)?.let { iv.add(it.first, it.last) }
                val p = iv.unionLength()
                for (prefix in current) rows.spans(prefix, item.ext, z, y, x0, x1, iv)
                val pc = iv.unionLength()
                for (prefix in older) rows.spans(prefix, item.ext, z, y, x0, x1, iv)
                val all = iv.unionLength()
                h += all
                inPacks += p
                olderOnly += all - pc // T95: kept at the version it was fetched at
            }
            perRoute[item.route] = mapOf("have" to h, "tiles" to t)
            have += h
            total += t
        }
        return linkedMapOf(
            "share" to if (total == 0L) null else Math.round(have * 1000.0 / total) / 1000.0, "have" to have, "tiles" to total, "inPacks" to inPacks,
            "atOlderVersion" to olderOnly, "routes" to perRoute, "rows" to rowCount, "ms" to Math.round((System.nanoTime() - t0) / 1e5) / 10.0,
        )
    }

    fun json(withCoverage: Boolean = true): Map<String, Any?> {
        val all = all()
        val service = OnlineService.get(app)
        val cache = service.cache
        val current = runCatching { service.fetcher?.sourceList?.map { it.key } }.getOrNull().orEmpty()
        return linkedMapOf(
            "areas" to all.map { r ->
                r.json() + mapOf("olderThanSource" to older(r), "fromOtherSource" to fromOtherSource(r)) + shapeJson(r) +
                    if (withCoverage) mapOf("coverage" to coverage(r)) else emptyMap()
            },
            "pinned" to all.count { it.pinned }, "transientPins" to transientCount,
            // T140 (ADR 0041 §2): the records in each file, and the entries kept as they were (no id or bounds).
            "files" to mapOf("areas" to all.count { it.readableByV1 }, "areas2" to all.count { !it.readableByV1 } + unreadable.size), "unreadable" to unreadable.size,
            "pinnedBySource" to pinnedBySource(cache.byPrefix(1, pinned = true), current), // T106 (ADR 0030's deferred item)
            "cache" to cache.json(), "download" to AreaDownload.json(), "prefetch" to AheadPrefetch.json(),
            "cacheRows" to cacheRows?.second?.let { mapOf("tiles" to it.size, "spans" to it.spanCount) }, // T151: the cache's keys as rows (coverage)
            "outline" to AreaOutlines.json(all), // T106: the corridor polygons; T136: of every kept corridor, drawn or not
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

/**
 * T140: the box an opaque record is pinned by ([area], its record-level bounds and zooms), its deepest zoom lowered
 * until the pin's cover (zooms 0 to the deepest, a tile of margin) fits half of [maxTiles]: the cover is filled in
 * memory at start-up, and a record of a kind this build does not know may span a country. Pure, tested.
 */
internal fun opaquePinArea(area: AreaOfInterest, maxTiles: Long): AreaOfInterest {
    var z = area.maxZoom
    while (z > area.minZoom && io.github.jasondorjeshort.dystopo.areas.AreaLimit.upperBound(area, 0..z, null, 1) > maxTiles / 2) z--
    return if (z == area.maxZoom) area else area.copy(maxZoom = z)
}

/**
 * T140 (ADR 0041 §2): the per-area extras of `GET /areas`: `needsNewerApp` (and `needsNewerAppReason`, the raw
 * sentence) for an opaque record; for a polygon its rings, vertices and area; for a disc (a one-point corridor) its radius.
 */
internal fun shapeJson(r: Areas.Record): Map<String, Any?> {
    val m = linkedMapOf<String, Any?>("needsNewerApp" to r.needsNewerApp)
    r.opaque?.let { m["needsNewerAppReason"] = it.reason; return m }
    when (val s = r.area.shape) {
        is AreaShape.Polygon -> {
            m["rings"] = s.rings.size
            m["vertices"] = s.rings.sumOf { it.size }
            m["areaKm2"] = Math.round(PolygonRings.areaM2(s.rings) / 1e4) / 100.0
        }
        is AreaShape.Corridor -> if (s.points.size == 1) m["radiusM"] = s.bufferM
        else -> Unit
    }
    return m
}

/**
 * T140 (ADR 0041 §2): the two record files. `areas.json` keeps what a version-1 reader (v0.1.10 and earlier) parses
 * whole, written as that reader writes it, so its own save loses nothing; `areas2.json` (the sibling) holds the rest:
 * polygon records, records this build keeps opaque, and entries without even an id or bounds ([unreadable]). The
 * sibling is retired (folded back) one release after the first polygon build is the oldest alpha supported. Pure, tested.
 */
internal class AreaFiles(val records: List<Areas.Record>, val unreadable: List<Map<String, Any?>> = emptyList()) {
    fun mainText(): String = MiniJson.encode(mapOf("version" to 1, "areas" to records.filter { it.readableByV1 }.map { it.json() }))

    /** The sibling file's text, or null when every record is in `areas.json` (then the sibling is deleted). */
    fun siblingText(): String? {
        val rest = records.filterNot { it.readableByV1 }.map { it.json() } + unreadable
        return if (rest.isEmpty()) null else MiniJson.encode(mapOf("version" to AreaManifest.VERSION, "areas" to rest))
    }

    companion object {
        /** Both files' records ([main] first; an id in both is taken from [sibling]); [log] hears what could not be read. */
        @Suppress("UNCHECKED_CAST")
        fun decode(main: String?, sibling: String?, log: (String, Throwable) -> Unit = { _, _ -> }): AreaFiles {
            val records = LinkedHashMap<String, Areas.Record>()
            val unreadable = ArrayList<Map<String, Any?>>()
            for ((name, text) in listOf("areas.json" to main, "areas2.json" to sibling)) {
                if (text == null) continue
                val doc = runCatching { MiniJson.parseObject(text) }.onFailure { log("$name unreadable", it) }.getOrNull() ?: continue
                for (m in (doc["areas"] as? List<*>).orEmpty()) {
                    val map = m as? Map<String, Any?> ?: continue
                    runCatching { Areas.Record.parse(map) }.onSuccess { records[it.id] = it }.onFailure { log("an area in $name kept as it is", it); unreadable += map }
                }
            }
            return AreaFiles(records.values.toList(), unreadable)
        }
    }
}

/** T151: a tile cache key `<prefix>/<z>/<x>/<y>.<ext>` taken apart; null for any other key (a halo variant, a non-tile). */
internal class TileKey(val prefix: String, val z: Int, val x: Int, val y: Int, val ext: String)

/** T151: [TileKey] of [k], or null. Pure, tested. */
internal fun parseTileKey(k: String): TileKey? {
    val s3 = k.lastIndexOf('/')
    if (s3 <= 0) return null
    val s2 = k.lastIndexOf('/', s3 - 1)
    if (s2 <= 0) return null
    val s1 = k.lastIndexOf('/', s2 - 1)
    if (s1 <= 0) return null
    val dot = k.indexOf('.', s3 + 1)
    if (dot < 0 || k.indexOf('.', dot + 1) >= 0) return null
    val y = digits(k, s3 + 1, dot) ?: return null
    val x = digits(k, s2 + 1, s3) ?: return null
    val z = digits(k, s1 + 1, s2) ?: return null
    return TileKey(k.substring(0, s1), z, x, y, k.substring(dot + 1))
}

private fun digits(s: String, from: Int, to: Int): Int? {
    if (to <= from || to - from > 9) return null
    var n = 0
    for (i in from until to) {
        val c = s[i]
        if (c !in '0'..'9') return null
        n = n * 10 + (c - '0')
    }
    return n
}

/**
 * T151: a list of x spans (inclusive), filled per row and measured as a union. Reused across rows (no allocation per
 * row once it has grown). Not thread-safe.
 */
internal class SpanList {
    private var a = LongArray(16)
    private var n = 0

    fun clear() { n = 0 }

    fun add(lo: Int, hi: Int) {
        if (hi < lo) return
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = (lo.toLong() shl 32) or (hi.toLong() and 0xffffffffL) // x is never negative, so the order is by lo, then hi
    }

    /** The tiles in the union of the spans held. */
    fun unionLength(): Long {
        if (n == 0) return 0
        java.util.Arrays.sort(a, 0, n)
        var total = 0L
        var lo = (a[0] ushr 32).toInt()
        var hi = a[0].toInt()
        for (i in 1 until n) {
            val l = (a[i] ushr 32).toInt()
            val h = a[i].toInt()
            if (l > hi + 1) { total += hi - lo + 1L; lo = l; hi = h } else if (h > hi) hi = h
        }
        return total + (hi - lo + 1L)
    }
}

/**
 * T151: the online tile cache's keys as rows: per key prefix and extension, per zoom and tile row, the sorted, disjoint
 * spans of x cached. Kept by the cache's [io.github.jasondorjeshort.dystopo.app.online.TileCache.onIndex] hook (every key
 * added or removed), so [Areas.coverage] asks it per manifest row and span instead of per tile. It costs the rows of what
 * is cached: a downloaded area's tiles are a few spans per row; a browsed cache's scattered tiles a span each.
 * Thread-safe.
 */
internal class CachedRows {
    /** One tile row's spans as pairs in [a] (`a[2i]` first x, `a[2i + 1]` last), sorted, disjoint and not adjacent. */
    private class Row {
        var a = IntArray(4)
        var n = 0
        fun lo(i: Int) = a[2 * i]
        fun hi(i: Int) = a[2 * i + 1]

        /** The first span whose end is at least [x]; [n] when none. */
        fun firstEndingAtOrAfter(x: Int): Int {
            var l = 0
            var h = n
            while (l < h) {
                val m = (l + h) ushr 1
                if (hi(m) < x) l = m + 1 else h = m
            }
            return l
        }

        /** Adds [x], which is not held (the caller checks), merging with a span ending at x - 1 or starting at x + 1. */
        fun add(x: Int) {
            val i = firstEndingAtOrAfter(x - 1)
            val joinsLeft = i < n && hi(i) == x - 1
            val joinsRight = if (joinsLeft) i + 1 < n && lo(i + 1) == x + 1 else i < n && lo(i) == x + 1
            when {
                joinsLeft && joinsRight -> { a[2 * i + 1] = hi(i + 1); a.copyInto(a, 2 * (i + 1), 2 * (i + 2), 2 * n); n-- }
                joinsLeft -> a[2 * i + 1] = x
                joinsRight -> a[2 * i] = x
                else -> {
                    if (2 * (n + 1) > a.size) a = a.copyOf(a.size * 2)
                    a.copyInto(a, 2 * (i + 1), 2 * i, 2 * n)
                    a[2 * i] = x
                    a[2 * i + 1] = x
                    n++
                }
            }
        }

        fun remove(x: Int) {
            val i = firstEndingAtOrAfter(x)
            if (i >= n || lo(i) > x) return // not held
            val l = lo(i)
            val h = hi(i)
            when {
                l == h -> { a.copyInto(a, 2 * i, 2 * (i + 1), 2 * n); n-- }
                x == l -> a[2 * i] = x + 1
                x == h -> a[2 * i + 1] = x - 1
                else -> { // split in two
                    if (2 * (n + 1) > a.size) a = a.copyOf(a.size * 2)
                    a.copyInto(a, 2 * (i + 1), 2 * i, 2 * n)
                    a[2 * i + 1] = x - 1
                    a[2 * (i + 1)] = x + 1
                    n++
                }
            }
        }
    }

    private val byKey = HashMap<String, HashMap<Long, Row>>() // "<prefix>|<ext>" -> (z << 32 | y) -> row
    private var tiles = 0L

    private fun group(prefix: String, ext: String) = "$prefix|$ext"

    @Synchronized
    fun add(key: String) {
        val t = parseTileKey(key) ?: return
        val row = byKey.getOrPut(group(t.prefix, t.ext)) { HashMap() }.getOrPut((t.z.toLong() shl 32) or t.y.toLong()) { Row() }
        if (contains(row, t.x)) return
        row.add(t.x)
        tiles++
    }

    @Synchronized
    fun remove(key: String) {
        val t = parseTileKey(key) ?: return
        val rows = byKey[group(t.prefix, t.ext)] ?: return
        val k = (t.z.toLong() shl 32) or t.y.toLong()
        val row = rows[k] ?: return
        if (!contains(row, t.x)) return
        row.remove(t.x)
        tiles--
        if (row.n == 0) rows.remove(k)
        if (rows.isEmpty()) byKey.remove(group(t.prefix, t.ext))
    }

    private fun contains(row: Row, x: Int): Boolean {
        val i = row.firstEndingAtOrAfter(x)
        return i < row.n && row.lo(i) <= x
    }

    @Synchronized
    fun contains(key: String): Boolean {
        val t = parseTileKey(key) ?: return false
        return byKey[group(t.prefix, t.ext)]?.get((t.z.toLong() shl 32) or t.y.toLong())?.let { contains(it, t.x) } == true
    }

    /** Adds to [out] the spans of [prefix] ([ext]) cached on row [y] of zoom [z], clipped to [x0]..[x1]. */
    @Synchronized
    fun spans(prefix: String, ext: String, z: Int, y: Int, x0: Int, x1: Int, out: SpanList) {
        val row = byKey[group(prefix, ext)]?.get((z.toLong() shl 32) or y.toLong()) ?: return
        var i = row.firstEndingAtOrAfter(x0)
        while (i < row.n && row.lo(i) <= x1) {
            out.add(maxOf(row.lo(i), x0), minOf(row.hi(i), x1))
            i++
        }
    }

    /** Tiles and spans held (`GET /areas` reports them with the cache). */
    @get:Synchronized val size: Long get() = tiles
    @get:Synchronized val spanCount: Int get() = byKey.values.sumOf { rows -> rows.values.sumOf { it.n } }
}

/**
 * T151: the x span of tile row [y] at zoom [z], within [x0]..[x1], whose tiles pack coverage [c] overlaps for [role]
 * (what `PackSet.demFor` and `osmFor` answer per tile: a tile is in a pack when one overlaps it); null when none. The
 * overlap in x is one interval (the tile's longitudes grow with x), found from the bounds and checked with
 * [io.github.jasondorjeshort.dystopo.packs.PackPrecedence.overlaps] at its ends, so it agrees with the per-tile test
 * exactly. Pure, tested.
 */
internal fun packSpan(c: io.github.jasondorjeshort.dystopo.packs.PackCoverage, role: io.github.jasondorjeshort.dystopo.packs.PackRoleZooms, z: Int, y: Int, x0: Int, x1: Int): IntRange? {
    fun ov(x: Int) = io.github.jasondorjeshort.dystopo.packs.PackPrecedence.overlaps(c, z, x, y, role)
    val a = kotlin.math.floor(io.github.jasondorjeshort.dystopo.geo.WebMercator.lonToX(c.bounds.minLon, z)).toInt()
    val b = kotlin.math.floor(io.github.jasondorjeshort.dystopo.geo.WebMercator.lonToX(c.bounds.maxLon, z)).toInt()
    var lo = maxOf(x0, a - 1)
    var hi = minOf(x1, b + 1)
    if (lo > hi) return null
    var n = 0
    while (lo <= hi && !ov(lo) && n++ < 3) lo++
    n = 0
    while (hi >= lo && !ov(hi) && n++ < 3) hi--
    return if (lo <= hi && ov(lo) && ov(hi)) lo..hi else null
}
