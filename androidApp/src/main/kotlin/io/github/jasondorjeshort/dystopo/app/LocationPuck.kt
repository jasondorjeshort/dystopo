package io.github.jasondorjeshort.dystopo.app

import android.location.Location
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import kotlin.math.cos

/**
 * The "you are here" marker: our own GeoJSON source plus three circle layers on top of the style
 * (accuracy halo in ground metres, white ring, blue dot). It replaces MapLibre's LocationComponent,
 * whose puck is built from symbol layers and did not draw on the emulator (its layers were present,
 * on top and visible, the source moved, yet no icon appeared). Circle layers need no sprites or
 * animators, and they show in map snapshots. Main thread only.
 */
class LocationPuck {
    private var source: GeoJsonSource? = null
    private var accuracy: CircleLayer? = null

    /** Adds the source and layers to a freshly loaded style, showing [last] if there is one. */
    fun install(style: org.maplibre.android.maps.Style, last: Location?) {
        val src = GeoJsonSource(SOURCE)
        style.addSource(src)
        val acc = CircleLayer(ACCURACY, SOURCE).withProperties(
            PropertyFactory.circleColor("#2f7be5"),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleStrokeColor("#2f7be5"),
            PropertyFactory.circleStrokeOpacity(0.5f),
            PropertyFactory.circleStrokeWidth(1f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        )
        style.addLayer(acc)
        style.addLayer(
            CircleLayer(RING, SOURCE).withProperties(
                PropertyFactory.circleRadius(11f),
                PropertyFactory.circleColor("#ffffff"),
                PropertyFactory.circleStrokeColor("rgba(0,0,0,0.35)"),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
        style.addLayer(
            CircleLayer(DOT, SOURCE).withProperties(
                PropertyFactory.circleRadius(7.5f),
                PropertyFactory.circleColor("#1a66d9"),
            ),
        )
        source = src
        accuracy = acc
        last?.let { show(it) }
    }

    fun uninstall() {
        source = null
        accuracy = null
    }

    val installed: Boolean get() = source != null

    fun show(loc: Location) {
        source?.setGeoJson(Point.fromLngLat(loc.longitude, loc.latitude))
        // MapLibre's world is 512 px wide at zoom 0, so one pixel there spans 78271.517 m * cos(lat).
        val r0 = loc.accuracy / (78271.517 * cos(Math.toRadians(loc.latitude)))
        accuracy?.setProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.exponential(2), Expression.zoom(),
                    Expression.stop(0, r0), Expression.stop(24, r0 * (1 shl 24)),
                ),
            ),
        )
    }

    companion object {
        const val SOURCE = "dystopo-location"
        const val ACCURACY = "dystopo-location-accuracy"
        const val RING = "dystopo-location-ring"
        const val DOT = "dystopo-location-dot"
        val LAYERS = listOf(ACCURACY, RING, DOT)
    }
}
