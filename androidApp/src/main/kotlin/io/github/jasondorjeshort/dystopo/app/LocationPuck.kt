package io.github.jasondorjeshort.dystopo.app

import android.location.Location
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos

/**
 * The "you are here" marker: our own GeoJSON source plus three circle layers on top of the style
 * (accuracy halo in ground metres, white ring, blue dot). It replaces MapLibre's LocationComponent,
 * whose puck is built from symbol layers and did not draw on the emulator (its layers were present,
 * on top and visible, the source moved, yet no icon appeared). Circle layers need no sprites or
 * animators, and they show in map snapshots. Main thread only.
 *
 * T203, three looks ([Mode]): the GPS dot; the terrain estimate (the survey of 2026-11-01: a hollow dot, white inside the
 * existing blue outline, so it is never taken for a fix, and a dashed ring at the estimate's spread in ground metres in
 * place of the accuracy disc, its own line source and layer since a circle layer draws no dashes); and no position
 * after the give-up (a grey dot on the last estimate, no ring). The puck has no heading cone, so none is shown or hidden.
 */
class LocationPuck {
    enum class Mode(val code: String) { GPS("gps"), ESTIMATE("estimate"), LOST("lost") }

    private var source: GeoJsonSource? = null
    private var accuracy: CircleLayer? = null
    private var ringSource: GeoJsonSource? = null
    private var dot: CircleLayer? = null

    /** T203: the look shown now. */
    var mode = Mode.GPS
        private set

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
        // T203: the estimate's dashed ring (empty unless estimating).
        val ring = GeoJsonSource(ESTIMATE_SOURCE)
        style.addSource(ring)
        style.addLayer(
            LineLayer(ESTIMATE_RING, ESTIMATE_SOURCE).withProperties(
                PropertyFactory.lineColor("#1a66d9"),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            ),
        )
        style.addLayer(
            CircleLayer(RING, SOURCE).withProperties(
                PropertyFactory.circleRadius(11f),
                PropertyFactory.circleColor("#ffffff"),
                PropertyFactory.circleStrokeColor("rgba(0,0,0,0.35)"),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
        val d = CircleLayer(DOT, SOURCE).withProperties(
            PropertyFactory.circleRadius(7.5f),
            PropertyFactory.circleColor("#1a66d9"),
        )
        style.addLayer(d)
        source = src
        accuracy = acc
        ringSource = ring
        dot = d
        val m = mode
        mode = Mode.GPS
        last?.let { show(it, m) }
    }

    fun uninstall() {
        source = null
        accuracy = null
        ringSource = null
        dot = null
    }

    val installed: Boolean get() = source != null

    fun show(loc: Location, look: Mode = Mode.GPS) {
        source?.setGeoJson(Point.fromLngLat(loc.longitude, loc.latitude))
        setMode(look)
        // T203: the estimate's ring at its spread; nothing for the other looks.
        ringSource?.setGeoJson(
            if (look == Mode.ESTIMATE) org.maplibre.geojson.FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(ring(loc.latitude, loc.longitude, loc.accuracy.toDouble())))
            else org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList<org.maplibre.geojson.Feature>()),
        )
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

    /** T203: the look's paint, set only when it changes. */
    private fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        accuracy?.setProperties(
            PropertyFactory.circleOpacity(if (m == Mode.GPS) 0.18f else 0f),
            PropertyFactory.circleStrokeOpacity(if (m == Mode.GPS) 0.5f else 0f),
        )
        dot?.setProperties(
            *when (m) {
                Mode.GPS -> arrayOf(PropertyFactory.circleRadius(7.5f), PropertyFactory.circleColor("#1a66d9"), PropertyFactory.circleStrokeWidth(0f))
                Mode.ESTIMATE -> arrayOf(
                    PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#ffffff"),
                    PropertyFactory.circleStrokeColor("#1a66d9"), PropertyFactory.circleStrokeWidth(2.5f),
                )
                Mode.LOST -> arrayOf(PropertyFactory.circleRadius(7.5f), PropertyFactory.circleColor("#8f959e"), PropertyFactory.circleStrokeWidth(0f))
            },
        )
    }

    companion object {
        const val SOURCE = "dystopo-location"
        const val ACCURACY = "dystopo-location-accuracy"
        const val RING = "dystopo-location-ring"
        const val DOT = "dystopo-location-dot"
        /** T203: the estimate's dashed ring and its source. */
        const val ESTIMATE_SOURCE = "dystopo-location-estimate"
        const val ESTIMATE_RING = "dystopo-location-estimate-ring"
        val LAYERS = listOf(ACCURACY, ESTIMATE_RING, RING, DOT)

        /** T203: a ring of [radiusM] ground metres around a point, as a closed line of 64 segments. */
        fun ring(lat: Double, lon: Double, radiusM: Double): LineString {
            val dLat = radiusM / 111_195.0
            val dLon = radiusM / (111_195.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
            return LineString.fromLngLats((0..64).map { i ->
                val a = 2 * Math.PI * i / 64
                Point.fromLngLat(lon + dLon * Math.sin(a), lat + dLat * Math.cos(a))
            })
        }
    }
}
