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
import io.github.jasondorjeshort.dystopo.app.cachebudget.BudgetPlan
import io.github.jasondorjeshort.dystopo.app.cachebudget.CacheBudget
import io.github.jasondorjeshort.dystopo.app.cachebudget.CacheKind
import java.io.File

/**
 * T74: what the app keeps on disk, per store, with the budgets the user sets (PLANNING › Design goals, principle 3):
 * - `packs`: region packs (`files/packs/` and pushed ones in the external files dir), with their routing graphs and
 *   search indexes; not a cache (a pack is removed in the pack list), so no budget and no clear here.
 * - `online`: tiles fetched from the server (DEM, map, glyphs) (`cache/online/tiles/`, one LRU capped by
 *   `onlineCacheMb`); T91: the bytes and the cap count only unpinned tiles. T112: the contour and shade tiles made on
 *   the phone from them sit in the same directory but count with `derived` (bytes, holds, Clear).
 * - `pinned` (T91): the tiles of pinned offline areas in that same directory, with their own budget
 *   `pinnedCacheMb` (default 2 GB, T115: up to 1 TB), checked when an area downloads; Pin, Unpin and Remove are in Offline areas.
 *   T95: each row's `holds` and `entries` count its own tiles (the online row's held pinned tiles before), and
 *   `olderThanSource` names the pinned areas whose source has moved on to a newer version (Update in Offline areas).
 *   Clear on the online row deletes its own tiles only ([TileCache.clearUnkept]); pinned tiles go with their area.
 * - `derived`: contour, shade and cliff tiles made from packs (`cache/contours/`, one LRU capped by `contourCacheMb`);
 *   T112: plus those made from downloaded DEM tiles (`fromOnlineBytes`).
 * - `sources`: what the phone's own readers downloaded from the public elevation sources when no server is set
 *   (`cache/sources/`, trimmed to its own cap by the readers).
 * The cache numbers are the LRU indexes' (what eviction counts); `walk=1` also walks each directory and reports
 * the bytes of its files (`apparentBytes`, as `du -b`) and the space they take (`diskBytes`, as `du -k` x 1024).
 *
 * T112: `online`, `derived` and `sources` share one total budget ([CacheBudget], `budget` in the JSON: a preset or a
 * custom size, set with [setBudget]); each such row says its `floorBytes` and `share` of the total, and its own cap
 * (`capMb`, the `onlineCacheMb` and `contourCacheMb` settings) is a ceiling under it. Pinned areas and packs stay outside.
 */
object Storage {
    const val ONLINE = "online"
    const val DERIVED = "derived"
    const val SOURCES = "sources"
    const val PACKS = "packs"
    const val PINNED = "pinned"
    val CLEARABLE = listOf(ONLINE, DERIVED, SOURCES)

    /** A budget in MB as the screen's chips and the settings list say it: whole GB where it is one, else MB (pure). */
    fun sizeLabel(mb: Long): String = when {
        mb >= 1024 && mb % 1024 == 0L -> "${mb / 1024} GB"
        mb >= 1024 -> String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        else -> "$mb MB"
    }

    /**
     * The pinned budgets the screen offers (any value in [AppSettings.PINNED_CACHE_MB_RANGE] through `POST /storage`);
     * T115: up to the Large preset's 128 GB, as the range now allows.
     */
    val BUDGET_CHOICES_MB = listOf(128L, 256L, 512L, 1024L, 2048L, 8192L, 32768L, 131072L)

    /** T112: a budgeted row's floor and share of the total. */
    private fun budgetFields(kind: CacheKind, bytes: Long): Map<String, Any?> {
        val total = AppSettings.cacheBudgetMb * BudgetPlan.MB
        return linkedMapOf(
            "inBudget" to true, "kind" to kind.key,
            "floorBytes" to BudgetPlan.floorBytes(kind, total),
            "share" to if (total > 0) bytes.toDouble() / total else null,
        )
    }

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

    /**
     * The online cache split by what it holds: fetched tiles per route, and tiles made on the phone from them. T95:
     * only the unpinned entries ([pinned] false, the online row, whose bytes count them alone) or only the pinned ones.
     */
    internal fun onlineBreakdown(cache: TileCache, pinned: Boolean): Map<String, Map<String, Long>> {
        val out = LinkedHashMap<String, LongArray>()
        for ((prefix, v) in cache.byPrefix(2, pinned)) {
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

    /**
     * T112: the rows by what they hold, as the budget counts kinds: the online row the downloaded tiles (DEM, map,
     * glyphs), the derived row every contour, shade and cliff tile the phone made, from packs (the contour cache) or
     * from downloaded DEM tiles (the online cache's contour and shade entries, which the online row listed before).
     */
    internal fun splitHolds(online: Map<String, Map<String, Long>>, derived: Map<String, Map<String, Long>>): Pair<Map<String, Map<String, Long>>, Map<String, Map<String, Long>>> {
        val derivedNames = OnlineFetcher.DERIVED_FROM_DEM.map { it.substringBefore('-') }.toSet()
        val fetched = online.filterKeys { it !in derivedNames }
        val made = LinkedHashMap<String, LongArray>()
        for ((k, v) in derived.entries + online.filterKeys { it in derivedNames }.entries) {
            val a = made.getOrPut(k) { LongArray(2) }
            a[0] += v["tiles"] ?: 0L
            a[1] += v["bytes"] ?: 0L
        }
        return fetched to made.mapValues { mapOf("tiles" to it.value[0], "bytes" to it.value[1]) }
    }

    /** `GET /storage` and the screen: every store with its bytes, budget and what it holds. */
    fun json(app: DystopoApp, walk: Boolean = false): Map<String, Any?> {
        val online = OnlineService.get(app).cache
        val pinned = online.pinnedBytes // T91
        val derived = ContourCache.get(app)
        // T112: the online cache's contour and shade tiles count with the derived row.
        val (onlineHolds, derivedHolds) = splitHolds(onlineBreakdown(online, pinned = false), derivedBreakdown(derived))
        val onlineMadeBytes = online.bytesWhere(pinned = false, matches = CacheBudget::onlineDerived)
        val onlineFetchedBytes = online.bytes - pinned - onlineMadeBytes
        val onlineFetchedTiles = onlineHolds.values.sumOf { it["tiles"] ?: 0L }
        val derivedBytes = derived.bytes - derived.pinnedBytes + onlineMadeBytes
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
        val areas = runCatching { io.github.jasondorjeshort.dystopo.app.areas.Areas.all() }.getOrDefault(emptyList())
        val stores = linkedMapOf<String, Any?>(
            PACKS to linkedMapOf(
                "label" to "Packs", "bytes" to packsWalk.apparentBytes, "budgetMb" to null, "clearable" to false,
                "count" to packList.size,
                "packs" to packList.map { p -> mapOf("name" to p.info.name, "bytes" to p.files.files.sumOf { it.length() }) },
                "dirs" to dirsJson(PACKS),
            ),
            ONLINE to linkedMapOf<String, Any?>(
                // T95: the entries and what they hold count the unpinned tiles only, like the bytes (T91). T112: downloaded tiles only.
                "label" to "Online tiles", "bytes" to onlineFetchedBytes, "entries" to onlineFetchedTiles, "capMb" to AppSettings.onlineCacheMb,
                "setting" to "onlineCacheMb", "clearable" to true, "holds" to onlineHolds, "evictions" to online.evictions.get(),
                "budgetEvictions" to online.budgetEvictions.get(), // T112
                "allBytes" to online.bytes, "dirs" to dirsJson(ONLINE),
            ) + budgetFields(CacheKind.ONLINE, onlineFetchedBytes),
            PINNED to linkedMapOf(
                "label" to "Offline areas (pinned)", "bytes" to pinned, "entries" to online.pinnedSize, "budgetMb" to AppSettings.pinnedCacheMb,
                "setting" to "pinnedCacheMb", "clearable" to false, "areas" to areas.count { it.pinned && it.pack == null },
                "holds" to onlineBreakdown(online, pinned = true), // T95
                "olderThanSource" to areas.filter { it.pinned && !io.github.jasondorjeshort.dystopo.app.areas.Areas.older(it).isNullOrEmpty() }.map { it.name }, // T95
            ),
            DERIVED to linkedMapOf<String, Any?>(
                // T112: from packs (the contour cache) and from downloaded DEM tiles (in the online cache).
                "label" to "Contours and shading", "bytes" to derivedBytes, "entries" to derivedHolds.values.sumOf { it["tiles"] ?: 0L },
                "fromPacksBytes" to derived.bytes, "fromOnlineBytes" to onlineMadeBytes, "capMb" to AppSettings.contourCacheMb,
                "setting" to "contourCacheMb", "clearable" to true, "holds" to derivedHolds, "evictions" to derived.evictions.get(),
                "budgetEvictions" to derived.budgetEvictions.get(), // T112
                "dirs" to dirsJson(DERIVED),
            ) + budgetFields(CacheKind.DERIVED, derivedBytes),
            SOURCES to linkedMapOf<String, Any?>(
                "label" to "Source data (no server)", "bytes" to sourcesWalk.apparentBytes, "clearable" to true,
                "dirs" to dirsJson(SOURCES),
            ) + budgetFields(CacheKind.SOURCES, sourcesWalk.apparentBytes),
        )
        val total = packsWalk.apparentBytes + online.bytes + derived.bytes + sourcesWalk.apparentBytes // pinned included
        return linkedMapOf(
            "totalBytes" to total,
            "budget" to CacheBudget.json(), // T112
            "budgetChoicesMb" to BUDGET_CHOICES_MB,
            "budgetRangeMb" to listOf(AppSettings.CACHE_MB_RANGE.first, AppSettings.CACHE_MB_RANGE.last),
            "pinnedRangeMb" to listOf(AppSettings.PINNED_CACHE_MB_RANGE.first, AppSettings.PINNED_CACHE_MB_RANGE.last), // T115
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
                // T95: the row's own tiles only; the pinned tiles of offline areas stay (they went too before).
                // T112: and the contour and shade tiles made from them stay too (the derived row's).
                val c = OnlineService.get(app).cache
                val bytes = c.bytesWhere(pinned = false) { !CacheBudget.onlineDerived(it) }
                mapOf("store" to store, "tiles" to c.clearWhere { k -> !CacheBudget.onlineDerived(k) && !c.keep(k) }, "bytes" to bytes, "pinnedKept" to c.pinnedSize)
            }
            DERIVED -> {
                // T112: every tile the phone made: the contour cache, and the online cache's unpinned contour and shade tiles.
                val c = ContourCache.get(app)
                val online = OnlineService.get(app).cache
                val bytes = c.bytes + online.bytesWhere(pinned = false, matches = CacheBudget::onlineDerived)
                val tiles = ContourCache.clear() + online.clearWhere { k -> CacheBudget.onlineDerived(k) && !online.keep(k) }
                mapOf("store" to store, "tiles" to tiles, "bytes" to bytes)
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

    /**
     * T112: sets the one cache budget from [params]: `budgetMb` (a size), `preset` (small, medium, large) or
     * `percentFree` (that share of the free space plus what the budgeted caches hold, picked once). Returns an error
     * message, or null with the run in [lastBudgetRun]; null too when none of the three is given.
     */
    fun setBudget(app: DystopoApp, params: Map<String, Any?>): String? {
        val given = params.keys.filter { it in BUDGET_KEYS }
        if (given.isEmpty()) return null
        if (given.size > 1) return "give one of budgetMb, preset, percentFree (got ${given.sorted()})"
        val raw = params.getValue(given.single()).toString().trim()
        val mb = when (given.single()) {
            "budgetMb" -> raw.toLongOrNull()?.takeIf { it in BudgetPlan.RANGE_MB }
                ?: return "budgetMb must be a whole number of MB from ${BudgetPlan.RANGE_MB.first} to ${BudgetPlan.RANGE_MB.last} (got '$raw')"
            "preset" -> BudgetPlan.Preset.of(raw)?.mb ?: return "preset must be one of ${BudgetPlan.Preset.entries.map { it.key }} (got '$raw')"
            else -> {
                val pct = raw.toIntOrNull()?.takeIf { it in 1..90 } ?: return "percentFree must be a whole number from 1 to 90 (got '$raw')"
                BudgetPlan.percentOfFreeMb(pct, app.cacheDir.usableSpace, CacheBudget.engine?.used()?.values?.sum() ?: 0L)
            }
        }
        lastBudgetRun = linkedMapOf<String, Any?>("budgetMb" to mb) + (CacheBudget.set(app, mb)?.json() ?: emptyMap())
        return null
    }

    val BUDGET_KEYS = setOf("budgetMb", "preset", "percentFree")

    @Volatile var lastBudgetRun: Map<String, Any?>? = null
        private set

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
 * - `POST /storage?onlineCacheMb=&contourCacheMb=`: sets the per-kind caps (as `POST /settings`; 400 for a bad value).
 * - `POST /storage?budgetMb=<MB>|preset=small|medium|large|percentFree=<1-90>` (T112): sets the one cache budget and
 *   evicts down to it at once, derived first, never the view or the route ahead ([CacheBudget]); the answer is
 *   `GET /storage` plus `budgetRun` (bytes freed per kind, whether the kinds fit).
 * - `POST /storage/clear?store=online|derived|sources`: empties one store.
 * - `POST /storage/ui?open=1|0[&custom=1]`: opens or closes the Storage screen (T112: `custom=1` with the custom size row open).
 */
object StorageRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/storage" && !r.path.startsWith("/storage/")) return null
        return try {
            when (r.method to r.path) {
                "GET" to "/storage" -> Response.json(Storage.json(app, walk = r.query["walk"] == "1"))
                "POST" to "/storage" -> {
                    val unknown = r.query.keys - SettingsRoutes.CACHE_KEYS - Storage.BUDGET_KEYS
                    if (unknown.isNotEmpty()) return Response.json(mapOf("error" to "unknown parameters $unknown (onlineCacheMb, contourCacheMb, pinnedCacheMb, budgetMb, preset, percentFree)"), 400)
                    Storage.setBudget(app, r.query)?.let { return Response.json(mapOf("error" to it), 400) } // T112
                    Storage.setBudgets(app, r.query)?.let { return Response.json(mapOf("error" to it), 400) }
                    app.map.onMain { StorageUi.revision++ }
                    val run = if (r.query.keys.any { it in Storage.BUDGET_KEYS }) mapOf("budgetRun" to Storage.lastBudgetRun) else emptyMap()
                    Response.json(Storage.json(app) + run)
                }
                "POST" to "/storage/clear" -> Response.json(Storage.clear(app, r.query["store"] ?: ""))
                "POST" to "/storage/ui" -> {
                    val open = r.query["open"] != "0"
                    val custom = r.query["custom"] == "1"
                    app.map.onMain { StorageUi.visible = open; StorageUi.custom = custom }
                    Response.json(mapOf("open" to open, "custom" to custom))
                }
                else -> Response.notFound()
            }
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to e.message), 400)
        }
    }
}
