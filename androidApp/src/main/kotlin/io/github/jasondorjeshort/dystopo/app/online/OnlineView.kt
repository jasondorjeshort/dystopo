package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.areas.Areas
import io.github.jasondorjeshort.dystopo.packs.PackBounds

/**
 * The map view as online mode sees it: camera, visible box, and where its terrain comes from. T236 (ADR 0056 B3): [kept], the
 * tile the source was read from lies in an offline map on the list ([Areas.isRecorded]), so a cache hit there reads "on this
 * phone" and not "viewed earlier".
 */
class OnlineView(val lat: Double, val lon: Double, val zoom: Double, val bounds: PackBounds?, val source: String, val kept: Boolean = false) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "lat" to lat, "lon" to lon, "zoom" to zoom, "source" to source, "kept" to kept,
        "bbox" to bounds?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
    )

    fun withSource(s: String, kept: Boolean = false) = OnlineView(lat, lon, zoom, bounds, s, kept)

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

        /**
         * T144: [view] with where its terrain comes from (pack, online, cached, none; T254: wider, cut from an ancestor, whose key
         * is looked up), published for [currentOnMain]. Not on the UI thread. T236: and whether the tile matched lies in an
         * offline map on the list, asked with the fetcher's versioned key. T255: the search box reads the published source
         * ([io.github.jasondorjeshort.dystopo.app.search.PlaceSearch.coverageOnMain]) to say why a search finds nothing here.
         */
        fun resolve(app: DystopoApp, view: OnlineView): OnlineView {
            val tiles = app.tiles
            val online = tiles.online?.viewSource(view.lat, view.lon, view.zoom)
            val keys = online?.second?.let { (z, x, y) -> keys(app, z, x, y) }.orEmpty()
            // T236: a memo of a tile no longer in the cache under any current source (the server changed, the cache was
            // cleared) is not "viewed earlier": nothing on the phone holds it.
            val gone = online?.second != null && keys.isNotEmpty() && keys.none { k -> runCatching { OnlineService.get(app).cache.contains(k) }.getOrDefault(true) }
            val source = sourceOf(online?.first, gone, tiles.packs.dem != null) { tiles.online?.worldDraws(view.lat, view.lon, view.zoom) == true }
            val kept = !gone && keys.any { k -> runCatching { Areas.isRecorded(k) }.getOrDefault(false) }
            lastSource = source
            lastKept = kept
            return view.withSource(source, kept)
        }

        /**
         * The source word from [online] ([OnlineTiles.viewSource]'s; null without online mode), whether the DEM tile it
         * matched is [gone] from the cache, and whether a pack is loaded ([packDem]). T275: a gone tile falls back to the
         * world overview where that draws the view ([OnlineTiles.worldDraws]). The DEM records are looked at first, and in
         * server mode MapLibre prefetches DEM tiles four zooms out (z6 under a z9 view at Mont Blanc after the device tour's
         * direct step); the offline step then cleared the cache, and that z6 record, also the europe-z5 view's tile, read
         * "no data here" over the overview the map drew. Pure, tested.
         */
        fun sourceOf(online: String?, gone: Boolean, packDem: Boolean, worldDraws: () -> Boolean): String = when {
            gone -> if (worldDraws()) OnlineTiles.WORLD else OnlineTiles.NONE
            online != null -> online
            packDem -> OnlineTiles.PACK
            else -> OnlineTiles.NONE
        }

        /** T144: where [view]'s terrain comes from (pack, online, cached, none), published for [currentOnMain]. Not on the UI thread. */
        fun sourceFor(app: DystopoApp, view: OnlineView): String = resolve(app, view).source

        /** T236: the cache keys of DEM tile z/x/y under each current source's versioned prefix, as the fetcher looks it up. */
        private fun keys(app: DystopoApp, z: Int, x: Int, y: Int): List<String> = runCatching {
            OnlineService.get(app).fetcher?.sourceList?.map { "${it.prefix("dem")}/$z/$x/$y.png" }.orEmpty()
        }.getOrDefault(emptyList())

        /** The current view with its source worked out on the calling thread; from any thread but the UI one. */
        fun current(app: DystopoApp): OnlineView? =
            runCatching { app.map.onMain { currentOnMain(app) } }.getOrNull()?.let { resolve(app, it) }
    }
}
