package io.github.jasondorjeshort.dystopo.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * T49: the map's visible box, updated whenever the camera comes to rest ([MapController] calls [update]);
 * Compose state, so the credit line recomposes for the packs in view. Null before the first camera idle.
 */
object MapViewport {
    var bounds by mutableStateOf<PackBounds?>(null)
        private set

    fun update(m: MapLibreMap) {
        val b = m.projection.visibleRegion.latLngBounds
        val next = PackBounds(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
        if (next != bounds) bounds = next
    }

    /** Whether boxes [a] and [b] overlap (degrees; touching edges do not count). */
    fun overlaps(a: PackBounds, b: PackBounds): Boolean =
        a.maxLon > b.minLon && a.minLon < b.maxLon && a.maxLat > b.minLat && a.minLat < b.maxLat
}
