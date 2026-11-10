package io.github.jasondorjeshort.dystopo.app.tracking

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.gpx.TrackDrawing
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
 * Draws the shown tracks and routes on the map (T262: every one shown on this device, [Tracking.refreshOverlay]; one track
 * before). Each track: a GeoJSON line (white casing under a magenta
 * line, one line per segment), start and end markers (green and red dots) and waypoints (yellow
 * dots with a dark ring, unlike the style's POI dots, labelled with their names in dark text on a
 * white halo, T40), on top of every style layer. Labels use the style's medium font, which the
 * glyph route serves; they never hide behind or hide the style's own labels' collisions
 * (`text-allow-overlap` off, `text-optional`), so a crowded view drops a waypoint name before a dot.
 *
 * T260: a gap between two segments longer than the recorder's interval ([TrackDrawing.gaps]) is bridged by a dashed
 * straight line in the track's colour, under the track: the colour stays the track's, the dash says the path there is
 * unknown (the owner: colours could come to mean older tracks, so a gap is not told by colour).
 *
 * Tapping a waypoint dot (or its label) calls [onWaypointTap] with its id (T40's rename/delete sheet).
 *
 * T262: two sources. [SOURCE] holds the stored shown tracks, the shown routes (a violet line under the tracks, without
 * the trip editor's marks; the route open in the editor is the editor's to draw) and the waypoints; it is sent to the
 * map only when what it holds changed ([show]'s `storedKey`), so a recording's fixes do not resend every shown track.
 * [LIVE_SOURCE] holds the recording track alone, its layers above every other, so the recording is on top. The scene
 * itself is built by [TrackScene], pure and tested.
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
    @Volatile private var liveData: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile private var storedKey: Any? = null
    @Volatile private var storedDirty = true
    @Volatile var features = 0
        private set
    @Volatile var labels = 0
        private set
    @Volatile var installedOnStyle = false
        private set
    /** T260: the dashed bridges drawn now, and their total time (`GET /tracks` `overlay`). */
    @Volatile var bridges = 0
        private set
    @Volatile var bridgedMs = 0L
        private set
    /** T262: the tracks (the recording among them) and routes drawn now, and how often the stored source was sent to the map. */
    @Volatile var tracksDrawn: List<String> = emptyList()
        private set
    @Volatile var routesDrawn: List<String> = emptyList()
        private set
    @Volatile var liveDrawn: String? = null
        private set
    @Volatile var storedSends = 0L
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

    /**
     * T262: draws [stored] (the shown tracks but the recording), [routes] (each shown route's id and its line as lat, lon
     * pairs), [waypoints] (those of the shown tracks and the free-standing ones) and the [live] recording, on top. The
     * stored source is sent again only when [storedKey] differs from the last one. Any thread.
     */
    fun show(stored: List<Track>, routes: List<Pair<String, List<Pair<Double, Double>>>>, waypoints: List<Waypoint>, live: Track?, storedKey: Any) {
        val s = TrackScene.of(stored, routes, waypoints)
        val l = TrackScene.of(listOfNotNull(live), emptyList(), emptyList(), live = true)
        if (storedKey != this.storedKey) {
            this.storedKey = storedKey
            data = FeatureCollection.fromFeatures(features(s))
            storedDirty = true
        }
        liveData = FeatureCollection.fromFeatures(features(l))
        features = s.size + l.size
        labels = waypoints.count { it.name.isNotBlank() }
        bridges = s.bridges + l.bridges
        bridgedMs = s.bridgedMs + l.bridgedMs
        tracksDrawn = stored.map { it.id } + listOfNotNull(live?.id)
        routesDrawn = routes.map { it.first }
        liveDrawn = live?.id
        main.post { apply() }
    }

    private fun features(scene: TrackScene.Scene): List<Feature> {
        val fs = ArrayList<Feature>()
        for (line in scene.lines) {
            val parts = line.parts.map { part -> part.map { Point.fromLngLat(it.second, it.first) } }
            val geometry = if (parts.size == 1) LineString.fromLngLats(parts[0]) else MultiLineString.fromLngLats(parts)
            fs += Feature.fromGeometry(geometry).apply {
                addStringProperty("kind", line.kind)
                addStringProperty("id", line.id)
            }
        }
        for (m in scene.marks) {
            fs += marker(m.lon, m.lat, m.kind).apply {
                m.name?.let { addStringProperty("name", it) }
                m.id?.let { addStringProperty("id", it) }
            }
        }
        return fs
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
            style.addSource(GeoJsonSource(LIVE_SOURCE, liveData))
            val isRoute = Expression.eq(Expression.get("kind"), Expression.literal("route"))
            // T262: the shown routes under every track, in the trip editor's violet, casing and line.
            style.addLayer(
                LineLayer(ROUTE_CASING, SOURCE).withFilter(isRoute).withProperties(
                    PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(6f), PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(ROUTE_LINE, SOURCE).withFilter(isRoute).withProperties(
                    PropertyFactory.lineColor(io.github.jasondorjeshort.dystopo.app.trips.TripOverlay.COLOR), PropertyFactory.lineWidth(3.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            addTrackLayers(style, SOURCE, GAP, CASING, LINE, POINTS)
            // T262: the recording above the stored tracks; the waypoints' names above both.
            addTrackLayers(style, LIVE_SOURCE, LIVE_GAP, LIVE_CASING, LIVE_LINE, LIVE_POINTS)
            val isWaypoint = Expression.eq(Expression.get("kind"), Expression.literal("waypoint"))
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
        storedDirty = true
        apply()
    }

    /** The gap's dash, the casing, the line and the marks of one source's tracks (T260's dash under the casing). */
    private fun addTrackLayers(style: Style, source: String, gap: String, casing: String, line: String, points: String) {
        val isLine = Expression.eq(Expression.get("kind"), Expression.literal("track"))
        val isGap = Expression.eq(Expression.get("kind"), Expression.literal("gap"))
        val isPoint = Expression.all(
            Expression.neq(Expression.get("kind"), Expression.literal("track")),
            Expression.neq(Expression.get("kind"), Expression.literal("gap")),
            Expression.neq(Expression.get("kind"), Expression.literal("route")),
        )
        style.addLayer(
            LineLayer(gap, source).withFilter(isGap).withProperties(
                PropertyFactory.lineColor("#d01c8b"), PropertyFactory.lineWidth(3f), PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)), PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            ),
        )
        style.addLayer(
            LineLayer(casing, source).withFilter(isLine).withProperties(
                PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(7f), PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(line, source).withFilter(isLine).withProperties(
                PropertyFactory.lineColor("#d01c8b"), PropertyFactory.lineWidth(4f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer(points, source).withFilter(isPoint).withProperties(
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

    private fun apply() {
        val v = view ?: return
        v.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            if (!style.isFullyLoaded) return@getMapAsync
            if (storedDirty) {
                storedDirty = false
                storedSends++
                (style.getSource(SOURCE) as? GeoJsonSource)?.setGeoJson(data)
            }
            (style.getSource(LIVE_SOURCE) as? GeoJsonSource)?.setGeoJson(liveData)
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
        const val GAP = "dystopo-track-gap"
        const val LINE = "dystopo-track-line"
        const val POINTS = "dystopo-track-points"
        const val LABELS = "dystopo-track-labels"
        /** T262: the shown routes (in [SOURCE]) and the recording's own source and layers, above the stored tracks. */
        const val ROUTE_CASING = "dystopo-track-route-casing"
        const val ROUTE_LINE = "dystopo-track-route-line"
        const val LIVE_SOURCE = "dystopo-track-live"
        const val LIVE_GAP = "dystopo-track-live-gap"
        const val LIVE_CASING = "dystopo-track-live-casing"
        const val LIVE_LINE = "dystopo-track-live-line"
        const val LIVE_POINTS = "dystopo-track-live-points"
        private const val TAP_RADIUS_PX = 36f
    }
}

/**
 * T262: what [TrackOverlay] draws, as plain data (pure, `TrackSceneTest`): per track its line (one part per segment of two
 * or more points), T260's gap bridges ([TrackDrawing.gaps], per track), its start and end marks ("current" for the end of
 * the live recording); per route its line; the waypoints with their names. Points are (lat, lon).
 */
internal object TrackScene {
    class Line(val kind: String, val id: String, val parts: List<List<Pair<Double, Double>>>)
    class Mark(val kind: String, val lat: Double, val lon: Double, val id: String? = null, val name: String? = null)
    class Scene(val lines: List<Line>, val marks: List<Mark>, val bridges: Int, val bridgedMs: Long) {
        val size: Int get() = lines.size + marks.size
    }

    fun of(tracks: List<Track>, routes: List<Pair<String, List<Pair<Double, Double>>>>, waypoints: List<Waypoint>, live: Boolean = false): Scene {
        val lines = ArrayList<Line>()
        val marks = ArrayList<Mark>()
        var bridges = 0
        var bridgedMs = 0L
        for ((id, pts) in routes) if (pts.size >= 2) lines += Line("route", id, listOf(pts))
        for (t in tracks) {
            val parts = t.segments.map { s -> s.points.map { it.lat to it.lon } }.filter { it.size >= 2 }
            if (parts.isNotEmpty()) lines += Line("track", t.id, parts)
            val gaps = TrackDrawing.gaps(t)
            for (g in gaps) lines += Line("gap", t.id, listOf(listOf(g.from.lat to g.from.lon, g.to.lat to g.to.lon)))
            bridges += gaps.size
            bridgedMs += gaps.sumOf { it.durationMs }
            val pts = t.points
            pts.firstOrNull()?.let { marks += Mark("start", it.lat, it.lon, t.id) }
            // While recording the "end" is the current position.
            if (pts.size >= 2) pts.last().let { marks += Mark(if (live) "current" else "end", it.lat, it.lon, t.id) }
        }
        for (w in waypoints) marks += Mark("waypoint", w.lat, w.lon, w.id, w.name)
        return Scene(lines, marks, bridges, bridgedMs)
    }
}
