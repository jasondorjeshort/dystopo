package io.github.jasondorjeshort.dystopo.app.areas

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy
import io.github.jasondorjeshort.dystopo.app.online.DirectSources
import io.github.jasondorjeshort.dystopo.app.online.Fetch
import io.github.jasondorjeshort.dystopo.app.online.OnlineFetcher
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.AssetItem
import io.github.jasondorjeshort.dystopo.areas.LocalItem
import io.github.jasondorjeshort.dystopo.areas.ManifestItem
import io.github.jasondorjeshort.dystopo.areas.TileCover
import io.github.jasondorjeshort.dystopo.areas.TilesItem
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * T75: downloads an area of interest. A box is "Download this region" ([OnlineService.region], a pack built on the
 * server or on the phone, then recorded in [Areas]); a corridor is fetched tile by tile into the online tile cache:
 *
 * 1. The manifest: `POST <server>/area` (the server's tile lists for the corridor) when a server is set, else (no
 *    server, or a server without `/area`) the same lists made here ([localManifest]).
 * 2. Every DEM and vector tile of the manifest goes through the online fetcher (the same path the map uses, so the
 *    map later finds them in the cache with the network off), six at a time, low zooms first. Tiles a pack already
 *    holds are skipped; tiles already cached are not fetched again, so running the download again resumes it.
 *    Each fetched tile is checked (a DEM tile must be a PNG, a vector tile must not fail to unzip); a bad one is
 *    dropped from the cache and counted under `invalid`. Failed tiles are tried once more at the end.
 * 3. With `contours`, the contour tiles of the corridor are made and cached too (they are otherwise made on the
 *    phone when first shown, from the cached DEM, also offline).
 *
 * The area is pinned from the start, so nothing fetched for it is evicted while it downloads. One job at a time;
 * [json] is the progress (`GET /areas/download`).
 *
 * T95: [plan] is the manifest, its size and the pinned budget check before anything starts (the trip's Offline
 * button shows it and refuses a download over the budget). A download fetches the current version of every tile
 * (`fresh`), so running it again on an area older than its source ("Update") refreshes it: when it completes with
 * nothing failed and no version change on the way, the area records the versions it now holds, the tiles of its old
 * version that no other pin holds are deleted, and so are the contour and shade tiles made in it at the current
 * version (they may have been made from the old DEM the pin served).
 */
object AreaDownload {
    private val app: DystopoApp get() = DystopoApp.instance

    class Job(val id: String, val name: String, val area: AreaOfInterest, val source: String, val contours: Boolean, val planned0: AreaManifest? = null, val isNew: Boolean = true) {
        @Volatile var state = "starting" // starting, manifest, tiles, retry, contours, done, partial, failed, refused, cancelled
        /** T95: what the area held before and after (versions per source), and the tiles deleted after a refresh. */
        @Volatile var versionsBefore: Map<String, Map<String, String>>? = null
        @Volatile var versionsAfter: Map<String, Map<String, String>>? = null
        @Volatile var refreshDropped: Int? = null
        /** T95: the record's state before this run (kept when the budget refuses it). */
        @Volatile var stateBefore: String? = null
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        @Volatile var manifest: AreaManifest? = null
        @Volatile var manifestFrom: String? = null
        val planned = AtomicInteger()
        val done = AtomicInteger()
        val fetched = AtomicInteger()
        val cached = AtomicInteger()
        val inPacks = AtomicInteger()
        val missing = AtomicInteger()
        val failed = AtomicInteger()
        val invalid = AtomicInteger()
        val bytes = AtomicLong()
        /** T95: bytes of the tiles found already cached (with [bytes], the area's tiles on the phone after a whole run). */
        val cachedBytes = AtomicLong()
        val contourTiles = AtomicInteger()
        val startedAt = System.currentTimeMillis()
        @Volatile var manifestMs: Long? = null
        @Volatile var finishedAt = 0L
        val running get() = state !in setOf("done", "partial", "failed", "refused", "cancelled")

        fun json(): Map<String, Any?> {
            val now = if (finishedAt != 0L) finishedAt else System.currentTimeMillis()
            val p = planned.get()
            return linkedMapOf(
                "id" to id, "name" to name, "state" to state, "error" to error, "source" to source, "manifestFrom" to manifestFrom,
                "planned" to p, "done" to done.get(), "percent" to if (p == 0) null else Math.round(done.get() * 1000.0 / p) / 10.0,
                "fetched" to fetched.get(), "alreadyCached" to cached.get(), "inPacks" to inPacks.get(), "missing" to missing.get(),
                "failed" to failed.get(), "invalid" to invalid.get(), "bytes" to bytes.get(), "contourTiles" to contourTiles.get(),
                "estimateBytes" to manifest?.bytes, "manifestMs" to manifestMs, "elapsedMs" to now - startedAt,
                "versionsBefore" to versionsBefore, "versionsAfter" to versionsAfter, "refreshDropped" to refreshDropped, // T95
                "manifest" to manifest?.summary(),
            )
        }
    }

    @Volatile var job: Job? = null
        private set

    fun json(): Map<String, Any?>? = job?.json()

    fun cancel(id: String? = null): Boolean {
        val j = job ?: return false
        if (id != null && j.id != id) return false
        if (!j.running) return false
        j.cancelled = true
        return true
    }

    /**
     * T95: what downloading corridor [area] would take, before anything starts: the manifest (from the server, else
     * made here), its estimated bytes and tiles, and [Plan.refusal] when the pinned budget would be passed.
     */
    class Plan(val area: AreaOfInterest, val manifest: AreaManifest, val manifestFrom: String, val pinnedBytes: Long, val alreadyBytes: Long, val budgetMb: Long) {
        val bytes: Long get() = manifest.bytes
        val tiles: Int get() = manifest.tileCount
        val refusal: String? get() = budgetError(pinnedBytes, bytes, alreadyBytes, budgetMb)

        fun json(): Map<String, Any?> = linkedMapOf(
            "id" to area.id, "name" to area.name, "bytes" to bytes, "mb" to Math.round(bytes / 104_857.6) / 10.0, "tiles" to tiles,
            "perRoute" to manifest.items.filterIsInstance<TilesItem>().associate { it.route to mapOf("tiles" to it.count, "bytes" to it.bytes, "zooms" to it.zooms?.let { z -> listOf(z.first, z.last) }) },
            "zooms" to listOf(area.minZoom, area.maxZoom), "bufferM" to (area.shape as? AreaShape.Corridor)?.bufferM, "lengthM" to area.lengthM,
            "manifestFrom" to manifestFrom, "pinnedBytes" to pinnedBytes, "alreadyBytes" to alreadyBytes, "budgetMb" to budgetMb,
            "fits" to (refusal == null), "refusal" to refusal,
        )
    }

    fun plan(area: AreaOfInterest): Plan {
        require(area.shape is AreaShape.Corridor) { "a plan is for a corridor; a box is a region pack (Download this region)" }
        val fetcher = OnlineService.get(app).fetcher ?: throw IllegalStateException(NO_SOURCE)
        val fromServer = if (fetcher.server == DirectSources.SERVER) null else serverManifest(fetcher.server, area, null)
        val m = fromServer ?: localManifest(area, fetcher.server)
        return Plan(area, m, if (fromServer != null) "server" else "phone", OnlineService.get(app).cache.pinnedBytes, Areas.get(area.id)?.bytes ?: 0L, AppSettings.pinnedCacheMb)
    }

    /**
     * Starts downloading [area]. A box goes to "Download this region" (its pack); a corridor is fetched here.
     * [tripId] links the area to the trip it was made for. T95: [manifest] is the one a [plan] made (then the budget is
     * checked here, before anything is recorded, and an IllegalStateException says why it does not fit).
     */
    @Synchronized
    fun start(area: AreaOfInterest, name: String? = null, tripId: String? = null, contours: Boolean = false, manifest: AreaManifest? = null): Map<String, Any?> {
        val service = OnlineService.get(app)
        if (area.shape is AreaShape.Box) {
            val b = area.bounds
            return linkedMapOf("kind" to "box", "region" to service.region.start(PackBounds(b.minLon, b.minLat, b.maxLon, b.maxLat), area.minZoom, minOf(area.maxZoom, OnlineTiles.DEM_MAX_ZOOM), name))
        }
        check(job?.running != true) { "an area download is already running (${job?.name}); DELETE /areas/download first" }
        val fetcher = service.fetcher ?: throw IllegalStateException(NO_SOURCE)
        val old = Areas.get(area.id)
        val planned = manifest?.takeIf { it.id == area.id }
        planned?.let { m -> budgetError(service.cache.pinnedBytes, m.bytes, old?.bytes ?: 0L, AppSettings.pinnedCacheMb)?.let { throw IllegalStateException(it) } }
        val label = name ?: area.name ?: "Corridor ${area.id.take(7)}"
        val j = Job(area.id, label, area, fetcher.server, contours, planned, isNew = old == null).also { it.stateBefore = old?.state }
        job = j
        Areas.put(
            Areas.Record(
                id = area.id, name = label, area = area.withName(label), createdAt = old?.createdAt ?: System.currentTimeMillis(), pinned = true,
                state = "downloading", source = fetcher.server, tripId = tripId ?: old?.tripId, bytes = old?.bytes ?: 0L,
                estimateBytes = old?.estimateBytes ?: 0L, tiles = old?.tiles ?: 0, versions = old?.versions.orEmpty(), // T95: held until it completes
            ),
        )
        Thread({ run(j, fetcher) }, "area-download").apply { isDaemon = true }.start()
        return linkedMapOf("kind" to "corridor", "download" to j.json())
    }

    private class Want(val item: TilesItem, val z: Int, val x: Int, val y: Int) {
        val path get() = "$z/$x/$y.${item.ext}"
    }

    private class Refused(message: String) : Exception(message)

    private fun run(j: Job, fetcher: OnlineFetcher) {
        val pool = Executors.newFixedThreadPool(POOL) { r -> Thread(r, "area-tiles").apply { isDaemon = true } }
        var changesAtStart = 0L
        try {
            j.state = "manifest"
            val t0 = System.currentTimeMillis()
            // T95: the versions the tiles will be fetched at, asked now; a new area records them from the start.
            fetcher.checkVersions()
            changesAtStart = fetcher.sourceList.sumOf { it.versionChanges.get() }
            Areas.update(j.id) { r -> if (r.versions.isEmpty()) r.copy(versions = fetcher.currentVersions()) else r }
            j.versionsBefore = Areas.get(j.id)?.versions
            val fromServer = if (j.planned0 != null || fetcher.server == DirectSources.SERVER) null else serverManifest(fetcher.server, j.area, j)
            val m = j.planned0 ?: fromServer ?: localManifest(j.area, fetcher.server)
            j.manifestFrom = if (j.planned0 != null) "plan" else if (fromServer != null) "server" else "phone"
            j.manifestMs = System.currentTimeMillis() - t0
            j.manifest = m
            Areas.saveManifest(m)
            // T91: pinned tiles have their own budget (pinnedCacheMb, Settings › Storage); refuse a download that would pass it.
            budgetError(OnlineService.get(app).cache.pinnedBytes, m.bytes, Areas.get(j.id)?.bytes ?: 0L, AppSettings.pinnedCacheMb)?.let { throw Refused(it) }
            val wants = m.items.filterIsInstance<TilesItem>().flatMap { item -> item.tiles.flatMap { (z, set) -> set.tiles().map { (x, y) -> Want(item, z, x, y) } } }
                .sortedWith(compareBy({ it.z }, { it.item.route }))
            j.planned.set(wants.size)
            j.state = "tiles"
            val retry = fetchAll(j, fetcher, pool, wants)
            if (retry.isNotEmpty() && !j.cancelled) {
                j.state = "retry"
                fetcher.retryNow()
                j.failed.set(0)
                fetchAll(j, fetcher, pool, retry, counted = true)
            }
            if (j.contours && !j.cancelled) {
                j.state = "contours"
                makeContours(j, pool)
            }
            j.state = when {
                j.cancelled -> "cancelled"
                j.failed.get() > 0 -> "partial"
                else -> "done"
            }
            if (j.state == "done") refreshed(j, fetcher, m, changesAtStart)
        } catch (e: Refused) {
            j.error = e.message
            j.state = "refused"
        } catch (e: Exception) {
            Log.w(Areas.TAG, "area download ${j.name} failed", e)
            j.error = e.message ?: e.javaClass.simpleName
            j.state = "failed"
        } finally {
            pool.shutdownNow()
            j.finishedAt = System.currentTimeMillis()
            if (j.state == "refused" && j.isNew) {
                // T95: a new area the budget refused leaves nothing behind (an area already on the phone keeps its tiles).
                runCatching { Areas.remove(j.id) }
            } else Areas.update(j.id) { r ->
                r.copy(
                    state = when (j.state) { "done" -> "done"; "refused" -> j.stateBefore ?: "partial"; else -> j.state },
                    error = j.error ?: if (j.failed.get() > 0) "${j.failed.get()} tiles could not be fetched; download again to finish" else null,
                    // T95: a whole run counts what the area holds now (fetched plus already cached; an Update's old tiles are gone).
                    bytes = when (j.state) { "refused" -> r.bytes; "done" -> j.bytes.get() + j.cachedBytes.get(); else -> r.bytes + j.bytes.get() },
                    estimateBytes = j.manifest?.bytes ?: r.estimateBytes,
                    tiles = if (j.state == "refused") r.tiles else j.planned.get(),
                    downloadMs = if (j.state == "refused") r.downloadMs else j.finishedAt - j.startedAt,
                )
            }
            Log.i(Areas.TAG, "area ${j.name}: ${j.state}, ${j.fetched.get()} fetched, ${j.cached.get()} cached, ${j.inPacks.get()} in packs, ${j.bytes.get()} bytes, ${j.finishedAt - j.startedAt} ms")
        }
    }

    /**
     * T95: a download that completed whole. Unless a version changed on the way (then some tiles are at each version
     * and the area keeps the old ones until the next Update), the area records the versions it holds now; when they
     * moved on, the contour and shade tiles made in its cover at the current version go (they may have been made from
     * the older DEM the pin served) and so do the old version's tiles that no other pin holds.
     */
    private fun refreshed(j: Job, fetcher: OnlineFetcher, m: AreaManifest, changesAtStart: Long) {
        if (fetcher.sourceList.sumOf { it.versionChanges.get() } != changesAtStart) return
        val now = fetcher.currentVersions()
        val before = Areas.get(j.id)?.versions.orEmpty()
        Areas.update(j.id) { it.copy(versions = now) }
        j.versionsAfter = now
        now[fetcher.serverKey]?.filterValues { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { Areas.saveManifest(m.copy(versions = it)) }
        if (before.isEmpty() || before == now) return
        val pins = AreaPins(listOf(AreaPins.cover(j.area)))
        val derived = if (now.any { (k, v) -> before[k]?.get("dem").let { b -> b != null && b != v["dem"] } }) fetcher.dropDerived { z, x, y -> pins.contains(z, x, y) } else 0
        j.refreshDropped = derived + Areas.dropUnheld()
        Log.i(Areas.TAG, "area ${j.name} refreshed from $before to $now: ${j.refreshDropped} old tiles deleted")
    }

    /** Fetches [wants] on [pool]; returns the ones that failed (network or server). */
    private fun fetchAll(j: Job, fetcher: OnlineFetcher, pool: java.util.concurrent.ExecutorService, wants: List<Want>, counted: Boolean = false): List<Want> {
        val cache = OnlineService.get(app).cache
        val futures: List<Future<Boolean>> = wants.map { w ->
            pool.submit(Callable {
                if (j.cancelled) return@Callable true
                val packs = runCatching { app.tiles.packs }.getOrNull()
                val inPack = when (w.item.route) {
                    AreaManifest.ROUTE_DEM -> packs?.demFor(w.z, w.x, w.y) != null
                    AreaManifest.ROUTE_OSM -> packs?.osmFor(w.z, w.x, w.y) != null
                    else -> false
                }
                if (inPack) {
                    j.inPacks.incrementAndGet()
                    if (!counted) j.done.incrementAndGet()
                    return@Callable true
                }
                val transform: (ByteArray) -> ByteArray = if (w.item.route == AreaManifest.ROUTE_OSM) OnlineFetcher::gunzipIfNeeded else { b -> b }
                val ok = when (val f = fetcher.get(w.item.route, w.path, transform, fresh = true)) { // T95: the current version, so a download again refreshes
                    is Fetch.Hit -> {
                        if (!valid(w.item.route, f.bytes)) {
                            Areas.cacheKeys(w.item.route, w.path, held = false).forEach { cache.clear(it) }
                            j.invalid.incrementAndGet()
                        } else if (f.cached) {
                            j.cached.incrementAndGet()
                            j.cachedBytes.addAndGet(f.bytes.size.toLong())
                        } else {
                            j.fetched.incrementAndGet()
                            j.bytes.addAndGet(f.bytes.size.toLong())
                        }
                        true
                    }
                    Fetch.Missing -> { j.missing.incrementAndGet(); true }
                    is Fetch.Unavailable -> { j.failed.incrementAndGet(); false }
                }
                if (!counted) j.done.incrementAndGet()
                ok
            })
        }
        return wants.filterIndexed { i, _ -> runCatching { !futures[i].get() }.getOrDefault(true) }
    }

    private fun valid(route: String, bytes: ByteArray): Boolean = when (route) {
        AreaManifest.ROUTE_DEM -> bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        else -> true
    }

    /** The corridor's contour tiles (the map's 512-px contour zooms up to one past the DEM), made and cached now. */
    private fun makeContours(j: Job, pool: java.util.concurrent.ExecutorService) {
        val hi = minOf(j.area.maxZoom, OnlineTiles.DEM_MAX_ZOOM) + 1
        val lo = maxOf(j.area.minZoom, OnlineTiles.CONTOUR_MIN_ZOOM)
        val tiles = (lo..hi).flatMap { z -> TileCover.of(j.area, z).tiles().map { (x, y) -> Triple(z, x, y) } }
        val futures = tiles.map { (z, x, y) -> pool.submit { if (!j.cancelled && runCatching { app.tiles.contourTile(z, x, y).status == 200 }.getOrDefault(false)) j.contourTiles.incrementAndGet() } }
        futures.forEach { runCatching { it.get() } }
    }

    /** `POST <server>/area` for [area] as tile lists (`pack=0`); null when the server has no `/area` (older) or cannot be reached. */
    private fun serverManifest(server: String, area: AreaOfInterest, j: Job?): AreaManifest? {
        val conn = CleartextPolicy.open("${server.trimEnd('/')}/area?pack=0")
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = MiniJson.encode(area.json()).toByteArray()
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            when (status) {
                200 -> AreaManifest.parse(text).copy(source = server)
                404, 405 -> null // a server from before T75: make the lists here
                else -> throw IOException("server refused the area (HTTP $status): ${runCatching { MiniJson.parseObject(text)["error"] }.getOrNull() ?: text.take(200)}")
            }
        } catch (e: IOException) {
            if (e.message?.startsWith("server refused") == true) throw e
            Log.w(Areas.TAG, "no manifest from $server: ${e.message}; making it here")
            j?.error = null
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The manifest of a corridor made on the phone: DEM tiles from the source's lowest zoom (contours sample
     * coarser zooms than they show) to the area's deepest, vector tiles to z14, one tile of margin, plus the
     * bundled style assets and what the phone makes itself. [server] is the fetcher's target.
     */
    fun localManifest(area: AreaOfInterest, server: String?): AreaManifest {
        val direct = server == DirectSources.SERVER
        val demLo = if (direct) DirectSources.DEM_MIN_ZOOM else OnlineTiles.DEM_MIN_ZOOM
        val demZooms = demLo..minOf(maxOf(area.maxZoom, demLo), OnlineTiles.DEM_MAX_ZOOM)
        val osmZooms = (area.minZoom..minOf(area.maxZoom, OnlineTiles.OSM_MAX_ZOOM)).takeIf { !it.isEmpty() }
        val items = ArrayList<ManifestItem>(AreaManifest.tileItems(area, demZooms, osmZooms))
        items += LocalItem(ManifestItem.KIND_CONTOURS, "dem", true, "contour, hillshade and cliff tiles are made on the phone from the DEM tiles")
        items += LocalItem(ManifestItem.KIND_GRAPH, null, false, "a corridor is tiles in the cache; the trail graph is built from vector packs")
        items += AssetItem(ManifestItem.KIND_GLYPHS, "/glyphs/{fontstack}/{range}.pbf", 0, 0, bundled = true)
        items += AssetItem(ManifestItem.KIND_SPRITE, "/{file}", 4, 0, bundled = true, names = listOf("sprite.json", "sprite.png", "sprite@2x.json", "sprite@2x.png"))
        return AreaManifest(
            area.id, area.name ?: area.id, area, AreaManifest.STATUS_READY, items, source = if (direct) "phone (public sources)" else "phone",
            generatedAt = java.time.Instant.now().toString(),
        )
    }

    private const val POOL = 6
    private const val NO_SOURCE = "no online source: set a server (Settings › Sources) or let the phone read the sources"
}

/**
 * T91: why a download of about [estimateBytes] may not start, with [pinnedBytes] already pinned ([alreadyBytes] of them
 * this area's own, from an earlier download) and a budget of [budgetMb]; null when it fits. Pure, tested.
 */
internal fun budgetError(pinnedBytes: Long, estimateBytes: Long, alreadyBytes: Long, budgetMb: Long): String? {
    val mb = 1024L * 1024L
    val after = pinnedBytes - minOf(alreadyBytes, pinnedBytes) + estimateBytes
    if (after <= budgetMb * mb) return null
    return "Offline areas would use about ${aboutMb(after)}, over their ${budgetMb} MB budget: remove an area or raise the budget in Settings › Storage"
}

/**
 * T95: a size for the user, "29 MB" (nearest whole MB from 10 MB) or "4.2 MB" (one decimal below), so the trip's
 * Offline offer and the budget message say the same number for the same bytes. Pure, tested.
 */
internal fun aboutMb(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 10) String.format(java.util.Locale.US, "%.0f MB", mb) else String.format(java.util.Locale.US, "%.1f MB", mb)
}
