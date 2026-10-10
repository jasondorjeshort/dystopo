package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.packs.PackBounds

/** The map view as online mode sees it: camera, visible box, and where its terrain comes from. */
class OnlineView(val lat: Double, val lon: Double, val zoom: Double, val bounds: PackBounds?, val source: String) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "lat" to lat, "lon" to lon, "zoom" to zoom, "source" to source,
        "bbox" to bounds?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
    )

    companion object {
        /** The current view, or null when the map is not up. Main thread only. */
        fun currentOnMain(app: DystopoApp): OnlineView? {
            val m = app.map.map ?: return null
            val c = m.cameraPosition
            val target = c.target ?: return null
            val b = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull()
            val box = b?.let { PackBounds(it.longitudeWest, it.latitudeSouth, it.longitudeEast, it.latitudeNorth) }
            val tiles = app.tiles
            val source = tiles.online?.viewSource(target.latitude, target.longitude, c.zoom)
                ?: if (tiles.packs.dem != null) OnlineTiles.PACK else OnlineTiles.NONE
            return OnlineView(target.latitude, target.longitude, c.zoom, box, source)
        }

        /** [currentOnMain] from any thread. */
        fun current(app: DystopoApp): OnlineView? = runCatching { app.map.onMain { currentOnMain(app) } }.getOrNull()
    }
}
