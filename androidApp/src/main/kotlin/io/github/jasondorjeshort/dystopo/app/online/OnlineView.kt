package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.PacksRoutes
import io.github.jasondorjeshort.dystopo.app.areas.Areas
import io.github.jasondorjeshort.dystopo.packs.Holdings
import io.github.jasondorjeshort.dystopo.packs.PackBounds

/**
 * The map view as online mode sees it: camera, visible box, and where its terrain comes from. T236 (ADR 0056 B3): [kept], the
 * tile the source was read from lies in an offline map on the list ([Areas.isRecorded]), so a cache hit there reads "on this
 * phone" and not "viewed earlier". T359 (ADR 0066 build task 5): [served], what serves the view's vector and elevation tiles by
 * the lookup ([Served]); [offAhead], a map that is off (T342) would serve one of them ahead of what serves now.
 */
class OnlineView(
    val lat: Double, val lon: Double, val zoom: Double, val bounds: PackBounds?, val source: String, val kept: Boolean = false,
    val served: Served? = null, val offAhead: Boolean = false,
    /** T360: the lookup's answer [resolve] read the source from, which the coverage panel turns into words (not in [json]). */
    val here: PacksRoutes.HoldingsHere? = null,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "lat" to lat, "lon" to lon, "zoom" to zoom, "source" to source, "kept" to kept,
        "bbox" to bounds?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
        "served" to served?.json(), "offAhead" to offAhead, // T359
    )

    fun withSource(s: String, kept: Boolean = false) = OnlineView(lat, lon, zoom, bounds, s, kept, served, offAhead, here)

    /**
     * T359: what serves the view's tiles now, by ADR 0066's lookup (`GET /holdings`' `tilePathServes`, the tile paths' own
     * answer): [vector] the vector tile at the map's zoom, [dem] the elevation tile one zoom deeper, each the holding's kind
     * (null: nothing on the phone serves it, so the network does where a fetch is allowed); [vectorName] and [demName] the
     * holdings' names; [network] a fetch is allowed now (the online member's `allowed`, which reads [OnlineUi.hasNetwork]);
     * [demSameDepth], the cache serves the elevation tile at a zoom a map stores it at ([OnlineTiles.viewSource]'s rule, T368:
     * a z14 tile over a z14 map is not finer), so the chip names the map ([chipDem]) where the lookup names the cache (it
     * ranks a cached tile by the source's stored depth, not the tile's zoom: T368's follow-up, `GET /holdings` unchanged).
     */
    class Served(
        val vector: Holdings.Kind?, val dem: Holdings.Kind?, val vectorName: String?, val demName: String?, val network: Boolean,
        val demSameDepth: Boolean = false,
    ) {
        /** The elevation's holding as the chip names it: a map where the cache serves at a zoom the map stores ([demSameDepth]). */
        val chipDem: Holdings.Kind? get() = if (demSameDepth && dem == Holdings.Kind.CACHE) Holdings.Kind.MAP else dem

        fun json(): Map<String, Any?> = linkedMapOf(
            "vector" to vectorName, "vectorKind" to vector?.word, "dem" to demName, "demKind" to dem?.word, "networkAllowed" to network,
            "demSameDepth" to demSameDepth,
        )
    }

    companion object {
        /** T144: the source [sourceFor] worked out last (off the UI thread); what [currentOnMain] reports, without a lock. */
        @Volatile private var lastSource: String? = null
        @Volatile private var lastKept = false

        /**
         * The current view, or null when the map is not up. Main thread. T144: takes no lock: the camera and box come from
         * the map, the source is the last one published by [sourceFor] (a worker's; it reads the online tiles' records,
         * which take locks the tile threads hold).
         */
        fun currentOnMain(app: DystopoApp): OnlineView? {
            val m = app.map.map ?: return null
            val c = m.cameraPosition
            val target = c.target ?: return null
            val b = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull()
            val box = b?.let { PackBounds(it.longitudeWest, it.latitudeSouth, it.longitudeEast, it.latitudeNorth) }
            return OnlineView(target.latitude, target.longitude, c.zoom, box, lastSource ?: OnlineTiles.NONE, lastKept)
        }

        /** T359: the word [sourceOf] gives a downloaded area's tiles (kept on the phone as tiles: the chip's "on this phone"). */
        const val AREA = "area"

        /**
         * T144: [view] with where its map comes from, published for [currentOnMain]. Not on the UI thread. T359 (ADR 0066 build
         * task 5): from the lookup ([PacksRoutes.holdingsHere], what `GET /holdings` reports): the holding that serves the view's
         * vector tile and the one that serves its elevation tile ([Served]), named in the chip's words by [sourceOf]; the online
         * tiles' records ([OnlineTiles.viewSource] for the elevation, [OnlineTiles.vectorSource] for the vector tile) only say,
         * where the network or the cache serves, whether the tile came this session, from the cache, or cut from a wider zoom.
         * T236: whether the tile matched lies in an offline map on the list, asked with the fetcher's versioned key. T255: the
         * search box reads the published source ([io.github.jasondorjeshort.dystopo.app.search.PlaceSearch.coverageOnMain]).
         */
        fun resolve(app: DystopoApp, view: OnlineView, publish: Boolean = true): OnlineView {
            val tiles = app.tiles
            val o = tiles.online
            val here = runCatching { PacksRoutes.holdingsHere(app, view.zoom, view.lat, view.lon) }.getOrNull()
            val vName = here?.let { it.vectorPath ?: it.vectorNow.serving?.holding?.name }
            val dName = here?.let { it.demPath ?: it.demNow.serving?.holding?.name }
            fun kind(n: String?) = n?.let { name -> if (name == "cache") Holdings.Kind.CACHE else here?.holdings?.firstOrNull { it.name == name }?.kind }
            val online = runCatching { o?.viewSource(view.lat, view.lon, view.zoom) }.getOrNull()
            // T368's rule (viewSource): inside a map, a cached tile names the network only past the map's own depth.
            val sameDepth = kind(dName) == Holdings.Kind.CACHE && online?.first == OnlineTiles.PACK
            val served = here?.let { Served(kind(vName), kind(dName), vName, dName, it.online?.allowed == true, sameDepth) }
            // The elevation tile whose record and cache keys are read: the cached tile that serves (T368: fetched behind a map),
            // else the record viewSource matched.
            val demTile = if (served?.chipDem == Holdings.Kind.CACHE && here != null) Triple(here.dem.z, here.dem.x, here.dem.y) else online?.second
            val demRecord = if (served?.chipDem == Holdings.Kind.CACHE && here != null) {
                runCatching { OnlineService.get(app).demSource(here.dem.z, here.dem.x, here.dem.y) }.getOrNull() ?: OnlineService.CACHED
            } else online?.first?.takeIf { it != OnlineTiles.PACK }
            val keys = demTile?.let { (z, x, y) -> keys(app, z, x, y) }.orEmpty()
            // T236: a memo of a tile no longer in the cache under any current source (the server changed, the cache was
            // cleared) is not "viewed earlier": nothing on the phone holds it.
            val gone = demTile != null && keys.isNotEmpty() && keys.none { k -> runCatching { OnlineService.get(app).cache.contains(k) }.getOrDefault(true) }
            val vectorRecord = runCatching {
                o?.overviewOnline(view.lat, view.lon, view.zoom) ?: here?.vector?.let { v -> o?.vectorSource("${v.z}/${v.x}/${v.y}.pbf") }
            }.getOrNull()
            val source = if (served == null) {
                // No lookup (it failed): the records alone, as before T359.
                legacySourceOf(online?.first, gone, tiles.packs.dem != null) { o?.worldDraws(view.lat, view.lon, view.zoom) == true }
            } else {
                sourceOf(served, vectorRecord, demRecord.takeUnless { gone }) { o?.worldDraws(view.lat, view.lon, view.zoom) == true }
            }
            val kept = source == AREA || (!gone && keys.any { k -> runCatching { Areas.isRecorded(k) }.getOrDefault(false) })
            val word = if (source == AREA) OnlineService.CACHED else source
            val offAhead = here != null && (offAhead(here.vector) || offAhead(here.dem))
            if (publish) {
                lastSource = word
                lastKept = kept
            }
            return OnlineView(view.lat, view.lon, view.zoom, view.bounds, word, kept, served, offAhead, here) // T360: here, the panel's
        }

        /**
         * T359: the source word for the chip from what serves the view ([served]) and the online tiles' records: for each of the
         * vector and the elevation tile, a map is [OnlineTiles.PACK], an area [AREA] (tiles kept on the phone), the cache its
         * record ([vectorRecord], [demRecord]: `online` this session, else `cached`), the world overview [OnlineTiles.WORLD];
         * nothing on the phone: the record (`online`, `cached`, `wider`), else for the vector tile the network where a fetch is
         * allowed (`online`: the online map draws there; the elevation's network is named only once a tile came, since below
         * its first zoom no elevation is drawn), else nothing. The chip names the one furthest from the phone (the network,
         * then the cache, a wider zoom, an area, the overview, a map), so "on this phone" is said only where both come from
         * the phone; with neither, the world overview where it draws ([worldDraws]), else [OnlineTiles.NONE]. While a deeper
         * tile is fetched behind a map, the map serves, so the chip says the map's word until the tile lands. Pure, tested.
         */
        fun sourceOf(served: Served, vectorRecord: String?, demRecord: String?, worldDraws: () -> Boolean): String {
            val recorded = setOf(OnlineService.ONLINE, OnlineService.CACHED, OnlineTiles.WIDER)
            fun role(kind: Holdings.Kind?, record: String?, network: Boolean): String? = when (kind) {
                Holdings.Kind.MAP -> OnlineTiles.PACK
                Holdings.Kind.AREA -> AREA
                Holdings.Kind.CACHE -> record?.takeIf { it == OnlineService.ONLINE || it == OnlineService.CACHED } ?: OnlineService.CACHED
                Holdings.Kind.OVERVIEW -> OnlineTiles.WORLD
                null -> record?.takeIf { it in recorded } ?: if (network) OnlineService.ONLINE else null
            }
            val words = listOfNotNull(role(served.vector, vectorRecord, served.network), role(served.chipDem, demRecord, false))
            return words.minByOrNull { FURTHEST.indexOf(it) } ?: if (worldDraws()) OnlineTiles.WORLD else OnlineTiles.NONE
        }

        /** T359: [sourceOf]'s order, the source furthest from the phone first. */
        private val FURTHEST = listOf(OnlineService.ONLINE, OnlineService.CACHED, OnlineTiles.WIDER, AREA, OnlineTiles.WORLD, OnlineTiles.PACK)

        /**
         * T359: a map that is off (T342) would serve [here]'s tile ahead of what serves it now: it stores deeper than the one
         * that serves (or nothing on the phone serves the tile). Where it is coarser it would not serve, so the chip does not
         * blame it. Pure, tested.
         */
        fun offAhead(here: Holdings.Here): Boolean {
            val depth = here.serving?.depth ?: -1
            return here.off.any { (it.stored(here.role)?.maxZoom ?: -1) > depth }
        }

        /**
         * The source word from the records alone (before T359; kept for a view whose lookup failed): [online]
         * ([OnlineTiles.viewSource]'s; null without online mode), whether the DEM tile it matched is [gone] from the cache,
         * and whether a pack is loaded ([packDem]). T275: a gone tile falls back to the world overview where that draws the
         * view ([OnlineTiles.worldDraws]). Pure, tested.
         */
        fun legacySourceOf(online: String?, gone: Boolean, packDem: Boolean, worldDraws: () -> Boolean): String = when {
            gone -> if (worldDraws()) OnlineTiles.WORLD else OnlineTiles.NONE
            online != null -> online
            packDem -> OnlineTiles.PACK
            else -> OnlineTiles.NONE
        }

        /** T144: where [view]'s map comes from, published for [currentOnMain]. Not on the UI thread. */
        fun sourceFor(app: DystopoApp, view: OnlineView): String = resolve(app, view).source

        /** T359: where the map at ([lat], [lon]) and [zoom] comes from ([resolve]), not published (search's and routes' ends). Not on the UI thread. */
        fun sourceAt(app: DystopoApp, lat: Double, lon: Double, zoom: Double): String =
            resolve(app, OnlineView(lat, lon, zoom, null, OnlineTiles.NONE), publish = false).source

        /** T236: the cache keys of DEM tile z/x/y under each current source's versioned prefix, as the fetcher looks it up. */
        private fun keys(app: DystopoApp, z: Int, x: Int, y: Int): List<String> = runCatching {
            OnlineService.get(app).fetcher?.sourceList?.map { "${it.prefix("dem")}/$z/$x/$y.png" }.orEmpty()
        }.getOrDefault(emptyList())

        /** The current view with its source worked out on the calling thread; from any thread but the UI one. */
        fun current(app: DystopoApp): OnlineView? =
            runCatching { app.map.onMain { currentOnMain(app) } }.getOrNull()?.let { resolve(app, it) }
    }
}
