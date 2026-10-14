package io.github.jasondorjeshort.dystopo.app.storage

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.ContourCache
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.online.OnlineFetcher
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.TileCache
import io.github.jasondorjeshort.dystopo.app.PackSet
import java.io.File

/**
 * T74: what the app keeps on disk, per store, with the budgets the user sets (PLANNING › Design goals, principle 3):
 * - `packs`: region packs (`files/packs/` and pushed ones in the external files dir), with their routing graphs and
 *   search indexes; not a cache (a pack is removed in the pack list), so no budget and no clear here.
 * - `online`: tiles fetched from the server (DEM, map, glyphs) and the contour and shade tiles made on the phone
 *   from them (`cache/online/tiles/`), one LRU capped by `onlineCacheMb`; T91: the bytes and the cap count only
 *   unpinned tiles.
 * - `pinned` (T91): the tiles of pinned offline areas in that same directory, with their own budget
 *   `pinnedCacheMb` (default 2 GB), checked when an area downloads; Pin, Unpin and Remove are in Offline areas.
 * - `derived`: contour, shade and cliff tiles made from packs (`cache/contours/`), one LRU capped by `contourCacheMb`.
 * - `sources`: what the phone's own readers downloaded from the public elevation sources when no server is set
 *   (`cache/sources/`, trimmed to its own cap by the readers).
 * The cache numbers are the LRU indexes' (what eviction counts); `walk=1` also walks each directory and reports
 * the bytes of its files (`apparentBytes`, as `du -b`) and the space they take (`diskBytes`, as `du -k` x 1024).
 */
object Storage {
    const val ONLINE = "online"
    const val DERIVED = "derived"
    const val SOURCES = "sources"
    const val PACKS = "packs"
    const val PINNED = "pinned"
    val CLEARABLE = listOf(ONLINE, DERIVED, SOURCES)

    /** The budgets the screen offers (any value in [AppSettings.CACHE_MB_RANGE] through `POST /storage`). */
    val BUDGET_CHOICES_MB = listOf(128L, 256L, 512L, 1024L, 2048L)

    fun packDirs(app: DystopoApp): List<File> = app.packRoots().map { File(it, PackSet.PACKS_DIR) }.filter { it.isDirectory }

    fun dirs(app: DystopoApp): Map<String, List<File>> = linkedMapOf(
        PACKS to packDirs(app),
        ONLINE to listOf(File(app.cacheDir, "online/tiles")),
        DERIVED to listOf(File(app.cacheDir, "contours")),
        SOURCES to listOf(File(app.cacheDir, "sources")),
    )

    /** Files and bytes under [dir]: count, apparent bytes, allocated bytes (st_blocks x 512). */
    data class Walk(val files: Long, val apparentBytes: Long, val diskBytes: Long)

    fun walk(dir: File): Walk {
        var n = 0L
        var bytes = 0L
        var disk = 0L
        if (dir.isDirectory) dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                n++
                bytes += f.length()
                disk += runCatching { android.system.Os.stat(f.path).st_blocks * 512 }.getOrDefault(f.length())
            }
        }
        return Walk(n, bytes, disk)
    }

    private fun walkAll(dirs: List<File>): Walk = dirs.map(::walk).fold(Walk(0, 0, 0)) { a, b -> Walk(a.files + b.files, a.apparentBytes + b.apparentBytes, a.diskBytes + b.diskBytes) }

    /** The online cache split by what it holds: fetched tiles per route, and tiles made on the phone from them. */
    private fun onlineBreakdown(cache: TileCache): Map<String, Map<String, Long>> {
        val out = LinkedHashMap<String, LongArray>()
        for ((prefix, v) in cache.byPrefix(2)) {
            val route = prefix.substringAfter('/').substringBefore('@')
            val kind = when {
                OnlineFetcher.DERIVED_FROM_DEM.any { route.startsWith(it) } -> route.substringBefore('-') // contours, shade
                else -> route
            }
            val a = out.getOrPut(kind) { LongArray(2) }
            a[0] += v.first
            a[1] += v.second
        }
        return out.mapValues { mapOf("tiles" to it.value[0], "bytes" to it.value[1]) }
    }

    /** The derived cache split into contours, shade and cliffs (by directory name). */
    private fun derivedBreakdown(cache: TileCache): Map<String, Map<String, Long>> {
        val out = LinkedHashMap<String, LongArray>()
        for ((dir, v) in cache.byPrefix()) {
            val kind = when (dir.substringBefore('-')) {
                ContourCache.SHADE_VERSION -> "shade"
                ContourCache.CLIFF_VERSION -> "cliffs"
                else -> "contours"
            }
            val a = out.getOrPut(kind) { LongArray(2) }
            a[0] += v.first
            a[1] += v.second
        }
        return out.mapValues { mapOf("tiles" to it.value[0], "bytes" to it.value[1]) }
    }

    /** `GET /storage` and the screen: every store with its bytes, budget and what it holds. */
    fun json(app: DystopoApp, walk: Boolean = false): Map<String, Any?> {
        val online = OnlineService.get(app).cache
        val pinned = online.pinnedBytes // T91
        val derived = ContourCache.get(app)
        val dirs = dirs(app)
        val packsWalk = walkAll(dirs.getValue(PACKS))
        val sourcesWalk = walkAll(dirs.getValue(SOURCES))
        fun dirsJson(k: String) = dirs.getValue(k).map { d ->
            val m = linkedMapOf<String, Any?>("path" to d.path)
            if (walk || k == PACKS || k == SOURCES) {
                val w = walk(d)
                m["files"] = w.files; m["apparentBytes"] = w.apparentBytes; m["diskBytes"] = w.diskBytes
            }
            m
        }
        val packList = runCatching { io.github.jasondorjeshort.dystopo.app.packs.PackManager.get(app).list() }.getOrDefault(emptyList())
        val stores = linkedMapOf<String, Any?>(
            PACKS to linkedMapOf(
                "label" to "Packs", "bytes" to packsWalk.apparentBytes, "budgetMb" to null, "clearable" to false,
                "count" to packList.size,
                "packs" to packList.map { p -> mapOf("name" to p.info.name, "bytes" to p.files.files.sumOf { it.length() }) },
                "dirs" to dirsJson(PACKS),
            ),
            ONLINE to linkedMapOf(
                "label" to "Online tiles", "bytes" to online.bytes - pinned, "entries" to online.size, "budgetMb" to AppSettings.onlineCacheMb,
                "setting" to "onlineCacheMb", "clearable" to true, "holds" to onlineBreakdown(online), "evictions" to online.evictions.get(),
                "allBytes" to online.bytes, "dirs" to dirsJson(ONLINE),
            ),
            PINNED to linkedMapOf(
                "label" to "Offline areas (pinned)", "bytes" to pinned, "budgetMb" to AppSettings.pinnedCacheMb,
                "setting" to "pinnedCacheMb", "clearable" to false, "areas" to runCatching { io.github.jasondorjeshort.dystopo.app.areas.Areas.all().count { it.pinned && it.pack == null } }.getOrDefault(0),
            ),
            DERIVED to linkedMapOf(
                "label" to "Contours and shading", "bytes" to derived.bytes, "entries" to derived.size, "budgetMb" to AppSettings.contourCacheMb,
                "setting" to "contourCacheMb", "clearable" to true, "holds" to derivedBreakdown(derived), "evictions" to derived.evictions.get(),
                "dirs" to dirsJson(DERIVED),
            ),
            SOURCES to linkedMapOf(
                "label" to "Source data (no server)", "bytes" to sourcesWalk.apparentBytes, "budgetMb" to null, "clearable" to true,
                "dirs" to dirsJson(SOURCES),
            ),
        )
        val total = packsWalk.apparentBytes + online.bytes + derived.bytes + sourcesWalk.apparentBytes
        return linkedMapOf(
            "totalBytes" to total,
            "budgetChoicesMb" to BUDGET_CHOICES_MB,
            "budgetRangeMb" to listOf(AppSettings.CACHE_MB_RANGE.first, AppSettings.CACHE_MB_RANGE.last),
            "freeBytes" to app.cacheDir.usableSpace,
            "stores" to stores,
            "lastClear" to lastClear,
        )
    }

    @Volatile var lastClear: Map<String, Any?>? = null
        private set

    /** Empties [store] (`online`, `derived`, `sources`); returns what went. Packs are removed in the pack list. */
    fun clear(app: DystopoApp, store: String): Map<String, Any?> {
        val before = System.currentTimeMillis()
        val result: Map<String, Any?> = when (store) {
            ONLINE -> {
                val c = OnlineService.get(app).cache
                val bytes = c.bytes
                mapOf("store" to store, "tiles" to c.clear(), "bytes" to bytes)
            }
            DERIVED -> {
                val c = ContourCache.get(app)
                val bytes = c.bytes
                mapOf("store" to store, "tiles" to ContourCache.clear(), "bytes" to bytes)
            }
            SOURCES -> {
                val dir = File(app.cacheDir, "sources")
                val w = walk(dir)
                dir.walkBottomUp().forEach { if (it != dir) it.delete() }
                mapOf("store" to store, "files" to w.files, "bytes" to w.apparentBytes)
            }
            else -> throw IllegalArgumentException("store must be one of $CLEARABLE (got '$store'); packs are removed in the pack list")
        }
        lastClear = result + mapOf("ms" to System.currentTimeMillis() - before, "at" to before)
        if (store != SOURCES) app.reload() // the map asks again for what it showed from the cleared store
        return lastClear!!
    }

    /** Sets budgets (`onlineCacheMb`, `contourCacheMb`) through the settings path (validated, persisted, applied, synced). */
    fun setBudgets(app: DystopoApp, params: Map<String, Any?>): String? {
        val budgets = params.filterKeys { it in SettingsRoutes.CACHE_KEYS }
        if (budgets.isEmpty()) return null
        return when (val p = SettingsRoutes.parse(budgets, AppSettings.units)) {
            is SettingsRoutes.Parsed.Error -> p.message
            is SettingsRoutes.Parsed.Ok -> { SettingsRoutes(app).apply(p); null }
        }
    }
}

/**
 * T74 debug routes (registered with one line in [io.github.jasondorjeshort.dystopo.app.Routes]):
 * - `GET /storage[?walk=1]`: every store ([Storage]) with bytes, budget and contents; `walk=1` adds each
 *   directory's file count, apparent and allocated bytes (to compare with `adb shell du`).
 * - `POST /storage?onlineCacheMb=&contourCacheMb=`: sets the budgets (as `POST /settings`; 400 for a bad value).
 * - `POST /storage/clear?store=online|derived|sources`: empties one store.
 * - `POST /storage/ui?open=1|0`: opens or closes the Storage screen.
 */
object StorageRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/storage" && !r.path.startsWith("/storage/")) return null
        return try {
            when (r.method to r.path) {
                "GET" to "/storage" -> Response.json(Storage.json(app, walk = r.query["walk"] == "1"))
                "POST" to "/storage" -> {
                    val unknown = r.query.keys - SettingsRoutes.CACHE_KEYS
                    if (unknown.isNotEmpty()) return Response.json(mapOf("error" to "unknown parameters $unknown (onlineCacheMb, contourCacheMb)"), 400)
                    Storage.setBudgets(app, r.query)?.let { return Response.json(mapOf("error" to it), 400) }
                    Response.json(Storage.json(app))
                }
                "POST" to "/storage/clear" -> Response.json(Storage.clear(app, r.query["store"] ?: ""))
                "POST" to "/storage/ui" -> {
                    val open = r.query["open"] != "0"
                    app.map.onMain { StorageUi.visible = open }
                    Response.json(mapOf("open" to open))
                }
                else -> Response.notFound()
            }
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to e.message), 400)
        }
    }
}
