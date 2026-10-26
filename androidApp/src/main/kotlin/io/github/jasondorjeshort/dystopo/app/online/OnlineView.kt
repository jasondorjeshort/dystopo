package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.packs.PackBounds

/** The map view as online mode sees it: camera, visible box, and where its terrain comes from. */
class OnlineView(val lat: Double, val lon: Double, val zoom: Double, val bounds: PackBounds?, val source: String) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "lat" to lat, "lon" to lon, "zoom" to zoom, "source" to source,
        "bbox" to bounds?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
    )

    fun withSource(s: String) = OnlineView(lat, lon, zoom, bounds, s)

    companion object {
        /** T144: the source [sourceFor] worked out last (off the UI thread); what [currentOnMain] reports, without a lock. */
        @Volatile private var lastSource: String? = null

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
            return OnlineView(target.latitude, target.longitude, c.zoom, box, lastSource ?: OnlineTiles.NONE)
        }

        /** T144: where [view]'s terrain comes from (pack, online, cached, none), published for [currentOnMain]. Not on the UI thread. */
        fun sourceFor(app: DystopoApp, view: OnlineView): String {
            val tiles = app.tiles
            val source = tiles.online?.viewSource(view.lat, view.lon, view.zoom)
                ?: if (tiles.packs.dem != null) OnlineTiles.PACK else OnlineTiles.NONE
            lastSource = source
            return source
        }

        /** The current view with its source worked out on the calling thread; from any thread but the UI one. */
        fun current(app: DystopoApp): OnlineView? =
            runCatching { app.map.onMain { currentOnMain(app) } }.getOrNull()?.let { it.withSource(sourceFor(app, it)) }
    }
}
