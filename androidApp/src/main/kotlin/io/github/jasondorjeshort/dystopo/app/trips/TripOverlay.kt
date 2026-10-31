package io.github.jasondorjeshort.dystopo.app.trips

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.ViaPoint
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
import org.maplibre.geojson.Point

/**
 * The route being edited on the map (T60): trail legs as a solid violet line, straight legs (no trail)
 * dashed, imported fixed legs thinner; via points as numbered dots (green start, red end); waypoints as
 * yellow flags with their names. While a route is open, a touch that starts on a via point drags it
 * (the map does not pan) and [onDragEnd] reroutes on release; a tap elsewhere goes to [onTap] (add a via
 * point or a waypoint, depending on the tool). Survives style reloads like the track overlay. [show]
 * can be called from any thread; the touch handling runs on the main thread. T187: the water along the route
 * ([io.github.jasondorjeshort.dystopo.app.routing.RouteWater]) as markers, one symbol per kind, once computed (not while
 * a via point is dragged; none with the `waterAlongRoute` setting off).
 */
class TripOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: MapView? = null
    private var clickMap: MapLibreMap? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }
    private val click = MapLibreMap.OnMapClickListener { p -> onTap?.invoke(p.latitude, p.longitude) ?: false }

    /** A tap on the map while a route is open; true when it was used. Main thread. */
    @Volatile var onTap: ((Double, Double) -> Boolean)? = null

    /** A via point was dragged to a new place (index, lat, lon). Main thread. */
    @Volatile var onDragEnd: ((Int, Double, Double) -> Unit)? = null

    @Volatile private var plan: RoutePlan? = null
    @Volatile private var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile var features = 0
        private set
    @Volatile var installedOnStyle = false
        private set

    /** T187: the water markers of the route shown, and that route's points (a late result for an older line is dropped). */
    val water = io.github.jasondorjeshort.dystopo.app.routing.WaterMarkers("dystopo-trip")
    @Volatile private var shownPoints: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>? = null

    /** The drag in progress: via index and where it is now. */
    @Volatile private var drag: Pair<Int, LatLng>? = null
    @Volatile var drags = 0
        private set
    @Volatile var lastDrag: Map<String, Any?>? = null
        private set

    @SuppressLint("ClickableViewAccessibility")
    fun bind(mapView: MapView) {
        if (view === mapView) return
        unbind()
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        mapView.getMapAsync { map ->
            if (view !== mapView) return@getMapAsync
            map.addOnMapClickListener(click)
            clickMap = map
        }
        mapView.setOnTouchListener { _, ev -> io.github.jasondorjeshort.dystopo.app.areas.AreaDraw.touch(ev) || touch(ev) } // T142: the area drawing first (only while drawing)
        install()
    }

    fun unbind() {
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        view?.setOnTouchListener(null)
        clickMap?.removeOnMapClickListener(click)
        clickMap = null
        view = null
        installedOnStyle = false
    }

    /** Shows [p] (null hides everything). */
    fun show(p: RoutePlan?) {
        plan = p
        rebuild()
        val pts = p?.path?.points?.takeIf { it.size >= 2 }
        if (pts != shownPoints) { shownPoints = pts; refreshWater() } // T187: a new line (a reroute, another trip)
    }

    /** T187: the markers for the route shown, from [io.github.jasondorjeshort.dystopo.app.routing.RouteWater]; any thread. */
    fun refreshWater() {
        val pts = shownPoints
        water.set(null)
        main.post { apply() }
        if (pts == null) return
        io.github.jasondorjeshort.dystopo.app.routing.RouteWater.async(pts) { r ->
            if (shownPoints !== pts || !AppSettings.waterAlongRoute) return@async
            water.set(r.items)
            main.post { apply() }
        }
    }

    private fun rebuild() {
        val p = plan
        val fs = ArrayList<Feature>()
        if (p != null) {
            val d = drag
            val via = p.path.via.mapIndexed { i, v -> if (d != null && d.first == i) ViaPoint(d.second.latitude, d.second.longitude) else v }
            for ((i, leg) in p.path.legs.withIndex()) {
                val touched = d != null && (d.first == i || d.first == i + 1)
                // While dragging, the two legs at the dragged point are drawn straight to it (rerouted on release).
                val pts = if (touched) listOf(via[i], via[i + 1]).map { Point.fromLngLat(it.lon, it.lat) } else leg.points.map { Point.fromLngLat(it.lon, it.lat) }
                fs += Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                    addStringProperty("kind", if (touched) "preview" else leg.kind.label)
                    addNumberProperty("leg", i)
                }
            }
            for ((i, v) in via.withIndex()) {
                fs += Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat)).apply {
                    addStringProperty("kind", if (i == 0) "start" else if (i == via.size - 1) "end" else "via")
                    addStringProperty("label", (i + 1).toString())
                    addNumberProperty("index", i)
                }
            }
            for (w in p.waypoints) {
                fs += Feature.fromGeometry(Point.fromLngLat(w.lon, w.lat)).apply {
                    addStringProperty("kind", "wpt")
                    addStringProperty("name", w.name)
                    addStringProperty("id", w.id)
                }
            }
        }
        data = FeatureCollection.fromFeatures(fs)
        features = fs.size
        main.post { apply() }
    }

    // ---- dragging ----

    private fun nearestVia(x: Float, y: Float): Int? {
        val m = clickMap ?: return null
        val p = plan ?: return null
        val r = DRAG_RADIUS_DP * (view?.resources?.displayMetrics?.density ?: 1f)
        var best: Int? = null
        var bestD = r * r
        for ((i, v) in p.path.via.withIndex()) {
            val s = m.projection.toScreenLocation(LatLng(v.lat, v.lon))
            val d = (s.x - x) * (s.x - x) + (s.y - y) * (s.y - y)
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Returns true while a via point is being dragged, so MapLibre does not pan. */
    private fun touch(ev: MotionEvent): Boolean {
        val m = clickMap ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (onDragEnd == null || plan == null) return false
                val i = nearestVia(ev.x, ev.y) ?: return false
                drag = i to m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                rebuild()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val d = drag ?: return false
                drag = d.first to m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                rebuild()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val d = drag ?: return false
                val at = m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                drag = null
                drags++
                lastDrag = linkedMapOf("index" to d.first, "lat" to at.latitude, "lon" to at.longitude, "atMs" to System.currentTimeMillis())
                onDragEnd?.invoke(d.first, at.latitude, at.longitude)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                if (drag == null) return false
                drag = null
                rebuild()
                return true
            }
        }
        return drag != null
    }

    /**
     * Drags via point [index] to ([lat], [lon]) through the map view's own touch dispatch, as a finger
     * would (down on the point, [steps] moves, up). Main thread. False when the point is not on screen.
     */
    fun simulateDrag(index: Int, lat: Double, lon: Double, steps: Int = 8): Boolean {
        val v = view ?: return false
        val m = clickMap ?: return false
        val p = plan ?: return false
        val via = p.path.via.getOrNull(index) ?: return false
        val a = m.projection.toScreenLocation(LatLng(via.lat, via.lon))
        val b = m.projection.toScreenLocation(LatLng(lat, lon))
        if (a.x < 0 || a.y < 0 || a.x > v.width || a.y > v.height) return false
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float, t: Long) {
            val e = MotionEvent.obtain(t0, t, action, x, y, 0)
            v.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, a.x, a.y, t0)
        for (s in 1..steps) {
            val f = s.toFloat() / steps
            send(MotionEvent.ACTION_MOVE, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, t0 + s * 16L)
        }
        send(MotionEvent.ACTION_UP, b.x, b.y, t0 + (steps + 1) * 16L)
        return true
    }

    // ---- style ----

    private fun install() {
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> addTo(style) } }
    }

    private fun addTo(style: Style) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, data))
            val kind = Expression.get("kind")
            fun isKind(vararg k: String) = Expression.any(*k.map { Expression.eq(kind, Expression.literal(it)) }.toTypedArray())
            val lines = isKind(LegKind.TRAIL.label, LegKind.STRAIGHT.label, LegKind.FIXED.label, "preview")
            style.addLayer(
                LineLayer(CASING, SOURCE).withFilter(lines).withProperties(
                    PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(7f), PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(LINE, SOURCE).withFilter(isKind(LegKind.TRAIL.label, LegKind.FIXED.label)).withProperties(
                    PropertyFactory.lineColor(COLOR),
                    PropertyFactory.lineWidth(Expression.match(kind, Expression.literal(4f), Expression.stop(LegKind.FIXED.label, 3f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(DASHED, SOURCE).withFilter(isKind(LegKind.STRAIGHT.label, "preview")).withProperties(
                    PropertyFactory.lineColor(Expression.match(kind, Expression.color(android.graphics.Color.parseColor(COLOR)), Expression.stop("preview", Expression.color(0xFF555555.toInt())))),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineDasharray(arrayOf(1.6f, 1.4f)),
                ),
            )
            style.addLayer(
                CircleLayer(POINTS, SOURCE).withFilter(isKind("start", "end", "via", "wpt")).withProperties(
                    PropertyFactory.circleRadius(Expression.match(kind, Expression.literal(9f), Expression.stop("wpt", 6f))),
                    PropertyFactory.circleColor(
                        Expression.match(
                            kind, Expression.color(android.graphics.Color.WHITE),
                            Expression.stop("start", Expression.color(0xFF1A9850.toInt())),
                            Expression.stop("end", Expression.color(0xFFD73027.toInt())),
                            Expression.stop("wpt", Expression.color(0xFFFFD400.toInt())),
                        ),
                    ),
                    PropertyFactory.circleStrokeColor(Expression.match(kind, Expression.color(android.graphics.Color.parseColor(COLOR)), Expression.stop("wpt", Expression.color(0xFF2B2420.toInt())), Expression.stop("start", Expression.color(android.graphics.Color.WHITE)), Expression.stop("end", Expression.color(android.graphics.Color.WHITE)))),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
            )
            val size = (12.0 * MapLibreStyle.BASE_TEXT_SCALE * AppSettings.textScale).toFloat()
            style.addLayer(
                SymbolLayer(NUMBERS, SOURCE).withFilter(isKind("start", "end", "via")).withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf(MapLibreStyle.FONT_MEDIUM)),
                    PropertyFactory.textSize(size * 0.85f),
                    PropertyFactory.textColor(Expression.match(kind, Expression.color(0xFF2B2420.toInt()), Expression.stop("start", Expression.color(android.graphics.Color.WHITE)), Expression.stop("end", Expression.color(android.graphics.Color.WHITE)))),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
            style.addLayer(
                SymbolLayer(LABELS, SOURCE).withFilter(isKind("wpt")).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textFont(arrayOf(MapLibreStyle.FONT_MEDIUM)),
                    PropertyFactory.textSize(size),
                    PropertyFactory.textColor("#2b2420"),
                    PropertyFactory.textHaloColor("#ffffff"),
                    PropertyFactory.textHaloWidth(1.8f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                    PropertyFactory.textOffset(arrayOf(0.9f, 0f)),
                    PropertyFactory.textOptional(true),
                ),
            )
        }
        water.addTo(style, view?.resources?.displayMetrics?.density ?: 2f) // T187
        installedOnStyle = true
        apply()
    }

    private fun apply() {
        val v = view ?: return
        v.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            if (!style.isFullyLoaded) return@getMapAsync
            (style.getSource(SOURCE) as? GeoJsonSource)?.setGeoJson(data)
            water.apply(style) // T187
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "features" to features, "installedOnStyle" to installedOnStyle, "dragging" to drag?.first, "drags" to drags, "lastDrag" to lastDrag,
        "waterMarkers" to water.count, // T187
    )

    companion object {
        const val SOURCE = "dystopo-trip"
        const val CASING = "dystopo-trip-casing"
        const val LINE = "dystopo-trip-line"
        const val DASHED = "dystopo-trip-dashed"
        const val POINTS = "dystopo-trip-points"
        const val NUMBERS = "dystopo-trip-numbers"
        const val LABELS = "dystopo-trip-labels"
        const val COLOR = "#7b3294"

        /** How close (dp) a touch must start to a via point to drag it. */
        const val DRAG_RADIUS_DP = 28f
    }
}
