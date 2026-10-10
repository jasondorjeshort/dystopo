package io.github.jasondorjeshort.dystopo.app.tracking

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.Waypoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/**
 * Draws one track on the map: a GeoJSON source with the track line (white casing under a magenta
 * line, one line per segment), start and end markers (green and red dots) and waypoints (yellow
 * dots with a dark ring, unlike the style's POI dots, labelled with their names in dark text on a
 * white halo, T40), on top of every style layer. Labels use the style's medium font, which the
 * glyph route serves; they never hide behind or hide the style's own labels' collisions
 * (`text-allow-overlap` off, `text-optional`), so a crowded view drops a waypoint name before a dot.
 *
 * Tapping a waypoint dot (or its label) calls [onWaypointTap] with its id (T40's rename/delete sheet).
 *
 * The overlay survives style reloads: [bind] listens for "style loaded" on the map view and re-adds
 * the source and layers with the current data. [show] can be called from any thread.
 */
class TrackOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: MapView? = null
    private var clickMap: MapLibreMap? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }
    private val clickListener = MapLibreMap.OnMapClickListener { latLng -> onClick(latLng) }

    /** Called on the main thread with a tapped waypoint's id. */
    @Volatile var onWaypointTap: ((String) -> Unit)? = null

    @Volatile private var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile var features = 0
        private set
    @Volatile var labels = 0
        private set
    @Volatile var installedOnStyle = false
        private set

    /** Attaches to [mapView] (main thread). A new view (activity recreated) replaces the old one. */
    fun bind(mapView: MapView) {
        if (view === mapView) return
        unbind()
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        mapView.getMapAsync { map ->
            if (view !== mapView) return@getMapAsync
            map.addOnMapClickListener(clickListener)
            clickMap = map
        }
        install()
    }

    fun unbind() {
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        clickMap?.removeOnMapClickListener(clickListener)
        clickMap = null
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
        for (w in waypoints) {
            fs += marker(w.lon, w.lat, "waypoint").apply {
                addStringProperty("name", w.name)
                addStringProperty("id", w.id)
            }
        }
        data = FeatureCollection.fromFeatures(fs)
        features = fs.size
        labels = waypoints.count { it.name.isNotBlank() }
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
            val isWaypoint = Expression.eq(Expression.get("kind"), Expression.literal("waypoint"))
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
            val size = (13.0 * MapLibreStyle.BASE_TEXT_SCALE * AppSettings.textScale).toFloat()
            style.addLayer(
                SymbolLayer(LABELS, SOURCE).withFilter(isWaypoint).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textFont(arrayOf(MapLibreStyle.FONT_MEDIUM)),
                    PropertyFactory.textSize(size),
                    PropertyFactory.textColor("#2b2420"),
                    PropertyFactory.textHaloColor("#ffffff"),
                    PropertyFactory.textHaloWidth(1.8f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                    PropertyFactory.textOffset(arrayOf(0.9f, 0f)),
                    PropertyFactory.textMaxWidth(10f),
                    PropertyFactory.textOptional(true),
                    PropertyFactory.textPadding(2f),
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

    /** A tap near a waypoint dot or label selects it; other taps fall through to the map. */
    private fun onClick(latLng: LatLng): Boolean {
        val map = clickMap ?: return false
        val cb = onWaypointTap ?: return false
        val p = map.projection.toScreenLocation(latLng)
        val r = TAP_RADIUS_PX
        val box = android.graphics.RectF(p.x - r, p.y - r, p.x + r, p.y + r)
        val hit = map.queryRenderedFeatures(box, POINTS, LABELS)
            .filter { it.getStringProperty("kind") == "waypoint" }
            .minByOrNull { f ->
                val g = f.geometry() as? Point ?: return@minByOrNull Float.MAX_VALUE
                val q: PointF = map.projection.toScreenLocation(LatLng(g.latitude(), g.longitude()))
                (q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)
            } ?: return false
        val id = hit.getStringProperty("id") ?: return false
        cb(id)
        return true
    }

    companion object {
        const val SOURCE = "dystopo-track"
        const val CASING = "dystopo-track-casing"
        const val LINE = "dystopo-track-line"
        const val POINTS = "dystopo-track-points"
        const val LABELS = "dystopo-track-labels"
        private const val TAP_RADIUS_PX = 36f
    }
}
