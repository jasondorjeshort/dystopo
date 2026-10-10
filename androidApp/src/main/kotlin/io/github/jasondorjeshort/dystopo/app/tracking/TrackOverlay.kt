package io.github.jasondorjeshort.dystopo.app.tracking

import android.os.Handler
import android.os.Looper
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.Waypoint
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
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/**
 * Draws one track on the map: a GeoJSON source with the track line (white casing under a magenta
 * line, one line per segment), start and end markers (green and red dots) and waypoints
 * (yellow dots with a dark ring, unlike the style's POI dots), on top of every style layer. Circles rather than symbols, so it needs no sprite or glyphs.
 *
 * The overlay survives style reloads: [bind] listens for "style loaded" on the map view and re-adds
 * the source and layers with the current data. [show] can be called from any thread.
 */
class TrackOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: MapView? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }

    @Volatile private var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile var features = 0
        private set
    @Volatile var installedOnStyle = false
        private set

    /** Attaches to [mapView] (main thread). A new view (activity recreated) replaces the old one. */
    fun bind(mapView: MapView) {
        if (view === mapView) return
        unbind()
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        install()
    }

    fun unbind() {
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        view = null
        installedOnStyle = false
    }

    fun show(track: Track?, waypoints: List<Waypoint>, recording: Boolean) {
        val fs = ArrayList<Feature>()
        val segments = track?.segments?.map { s -> s.points.map { Point.fromLngLat(it.lon, it.lat) } }?.filter { it.size >= 2 }.orEmpty()
        if (segments.isNotEmpty()) {
            val geometry = if (segments.size == 1) LineString.fromLngLats(segments[0]) else MultiLineString.fromLngLats(segments)
            fs += Feature.fromGeometry(geometry).apply { addStringProperty("kind", "track") }
        }
        val pts = track?.points.orEmpty()
        pts.firstOrNull()?.let { fs += marker(it.lon, it.lat, "start") }
        // While recording the "end" is the current position.
        if (pts.size >= 2) pts.last().let { fs += marker(it.lon, it.lat, if (recording) "current" else "end") }
        for (w in waypoints) fs += marker(w.lon, w.lat, "waypoint").apply { addStringProperty("name", w.name) }
        data = FeatureCollection.fromFeatures(fs)
        features = fs.size
        main.post { apply() }
    }

    private fun marker(lon: Double, lat: Double, kind: String) =
        Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply { addStringProperty("kind", kind) }

    private fun install() {
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> addTo(style) } }
    }

    private fun addTo(style: Style) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, data))
            val isLine = Expression.eq(Expression.get("kind"), Expression.literal("track"))
            val isPoint = Expression.neq(Expression.get("kind"), Expression.literal("track"))
            style.addLayer(
                LineLayer(CASING, SOURCE).withFilter(isLine).withProperties(
                    PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(7f), PropertyFactory.lineOpacity(0.9f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(LINE, SOURCE).withFilter(isLine).withProperties(
                    PropertyFactory.lineColor("#d01c8b"), PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                CircleLayer(POINTS, SOURCE).withFilter(isPoint).withProperties(
                    PropertyFactory.circleRadius(
                        Expression.match(Expression.get("kind"), Expression.literal(6f), Expression.stop("waypoint", 7f), Expression.stop("current", 7f)),
                    ),
                    PropertyFactory.circleColor(
                        Expression.match(
                            Expression.get("kind"), Expression.color(android.graphics.Color.GRAY),
                            Expression.stop("start", Expression.color(0xFF1A9850.toInt())),
                            Expression.stop("end", Expression.color(0xFFD73027.toInt())),
                            Expression.stop("current", Expression.color(0xFFD01C8B.toInt())),
                            Expression.stop("waypoint", Expression.color(0xFFFFD400.toInt())),
                        ),
                    ),
                    PropertyFactory.circleStrokeColor(
                        Expression.match(Expression.get("kind"), Expression.color(android.graphics.Color.WHITE), Expression.stop("waypoint", Expression.color(0xFF2B2420.toInt()))),
                    ),
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
        const val SOURCE = "dystopo-track"
        const val CASING = "dystopo-track-casing"
        const val LINE = "dystopo-track-line"
        const val POINTS = "dystopo-track-points"
    }
}
