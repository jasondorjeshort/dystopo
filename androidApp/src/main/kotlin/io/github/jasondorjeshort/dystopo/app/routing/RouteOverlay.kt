package io.github.jasondorjeshort.dystopo.app.routing

import android.os.Handler
import android.os.Looper
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Draws the planned route in T31's track overlay style (a white casing under a 4 px line, round caps
 * and joins, green start and red end dots with white rings), in orange so a route and a recorded track
 * shown together stay apart; a start point picked before the end shows as the green dot alone.
 * Survives style reloads like [io.github.jasondorjeshort.dystopo.app.tracking.TrackOverlay], and
 * forwards long-presses on the map to [onLongPress]. [show] can be called from any thread.
 */
class RouteOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: MapView? = null
    private var pressMap: MapLibreMap? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }
    private val longPress = MapLibreMap.OnMapLongClickListener { p -> onLongPress?.invoke(p.latitude, p.longitude); true }

    /** Called on the main thread with a long-pressed point. */
    @Volatile var onLongPress: ((Double, Double) -> Unit)? = null

    @Volatile private var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile var features = 0
        private set
    @Volatile var installedOnStyle = false
        private set

    fun bind(mapView: MapView) {
        if (view === mapView) return
        unbind()
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        mapView.getMapAsync { map ->
            if (view !== mapView) return@getMapAsync
            map.addOnMapLongClickListener(longPress)
            pressMap = map
        }
        install()
    }

    fun unbind() {
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        pressMap?.removeOnMapLongClickListener(longPress)
        pressMap = null
        view = null
        installedOnStyle = false
    }

    /** Shows [points] (the route) with start and end dots, or only a start dot at [start] while the end is not chosen. */
    fun show(points: List<RoutePoint>?, start: LatLng?, end: LatLng?) {
        val fs = ArrayList<Feature>()
        if (points != null && points.size >= 2) {
            fs += Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })).apply { addStringProperty("kind", "route") }
        }
        val s = points?.firstOrNull()?.let { LatLng(it.lat, it.lon) } ?: start
        val e = points?.lastOrNull()?.let { LatLng(it.lat, it.lon) } ?: end
        s?.let { fs += marker(it, "start") }
        if (points != null || end != null) e?.let { fs += marker(it, "end") }
        data = FeatureCollection.fromFeatures(fs)
        features = fs.size
        main.post { apply() }
    }

    private fun marker(p: LatLng, kind: String) =
        Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply { addStringProperty("kind", kind) }

    private fun install() {
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> addTo(style) } }
    }

    private fun addTo(style: Style) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, data))
            val isLine = Expression.eq(Expression.get("kind"), Expression.literal("route"))
            val isPoint = Expression.neq(Expression.get("kind"), Expression.literal("route"))
            style.addLayer(
                LineLayer(CASING, SOURCE).withFilter(isLine).withProperties(
                    PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(7f), PropertyFactory.lineOpacity(0.9f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(LINE, SOURCE).withFilter(isLine).withProperties(
                    PropertyFactory.lineColor(COLOR), PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                CircleLayer(POINTS, SOURCE).withFilter(isPoint).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor(
                        Expression.match(
                            Expression.get("kind"), Expression.color(android.graphics.Color.GRAY),
                            Expression.stop("start", Expression.color(0xFF1A9850.toInt())),
                            Expression.stop("end", Expression.color(0xFFD73027.toInt())),
                        ),
                    ),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
        }
        installedOnStyle = true
        apply()
    }

    private fun apply() {
        val v = view ?: return
        v.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            if (!style.isFullyLoaded) return@getMapAsync
            (style.getSource(SOURCE) as? GeoJsonSource)?.setGeoJson(data)
        }
    }

    companion object {
        const val SOURCE = "dystopo-route"
        const val CASING = "dystopo-route-casing"
        const val LINE = "dystopo-route-line"
        const val POINTS = "dystopo-route-points"
        const val COLOR = "#e66101"
    }
}
