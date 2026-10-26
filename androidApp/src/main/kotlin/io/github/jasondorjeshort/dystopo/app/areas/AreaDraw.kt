package io.github.jasondorjeshort.dystopo.app.areas

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.PolygonRings
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.hypot

/**
 * T142 (ADR 0041 §5, "Draw an area"): the drawing editor. It starts from the current view as a four-vertex polygon
 * ([fromView]); a tap on the map adds a vertex (into the edge it lengthens least), a vertex drags, a midpoint handle adds
 * one (tapped or dragged), a long press on a vertex removes it (three stay), and [undo] steps back. The ring is drawn by
 * [PickerLayer] with a handle on every vertex and midpoint, and every change asks [AreaPicker] for the size line again
 * (the phone's own manifest; the server is asked once, at Download).
 *
 * Touches reach [touch] through the map view's one touch listener (`TripOverlay`'s, which asks here first): a touch that
 * starts on a handle is ours (the map does not pan); any other touch goes on to the map, and when it was a single tap
 * (not the first half of a double tap, which zooms) it adds a vertex. Main thread, except [json].
 */
object AreaDraw {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** The ring being drawn (open: the first point not repeated); main thread writes. */
    var ring by mutableStateOf<List<AreaPoint>>(emptyList())
        private set
    var canUndo by mutableStateOf(false)
        private set
    private val history = ArrayList<List<AreaPoint>>()

    /** True while the editor takes touches (the picker is in its Draw mode). */
    @Volatile var active = false
        private set

    @Volatile var taps = 0
        private set
    @Volatile var drags = 0
        private set
    @Volatile var inserts = 0
        private set
    @Volatile var removes = 0
        private set
    @Volatile var undos = 0
        private set
    /** What the last gesture did: `tap`, `drag`, `midpoint`, `remove`, `removeRefused` (three vertices stay), `undo`. */
    @Volatile var lastGesture: String? = null
        private set

    /** Starts drawing [initial] (main thread). */
    fun begin(initial: List<AreaPoint>) {
        require(initial.size >= 3) { "a drawing needs at least three vertices" }
        history.clear()
        canUndo = false
        ring = initial
        active = true
        taps = 0; drags = 0; inserts = 0; removes = 0; undos = 0
        lastGesture = null
        changed()
    }

    /** Ends drawing (main thread). */
    fun end() {
        active = false
        main.removeCallbacks(hold)
        pendingTap?.let { main.removeCallbacks(it) }
        pendingTap = null
        grab = null
        history.clear()
        canUndo = false
        ring = emptyList()
    }

    /** Replaces the ring as one step Undo takes back (main thread). */
    fun set(next: List<AreaPoint>) {
        require(next.size >= 3) { "a drawing needs at least three vertices" }
        remember()
        ring = next
        changed()
    }

    fun undo(): Boolean {
        if (history.isEmpty()) return false
        ring = history.removeAt(history.size - 1)
        canUndo = history.isNotEmpty()
        undos++
        lastGesture = "undo"
        changed()
        return true
    }

    private fun remember() {
        history += ring
        if (history.size > MAX_UNDO) history.removeAt(0)
        canUndo = true
    }

    private fun changed() = AreaPicker.drawingChanged()

    /** The drawn area: the ring counter-clockwise (RFC 7946), no buffer, z8 to z15 ([AreaPicker]'s full detail). */
    fun area(ring: List<AreaPoint> = this.ring, name: String? = null): AreaOfInterest =
        AreaOfInterest(AreaShape.Polygon(listOf(counterClockwise(ring))), AreaPicker.MIN_ZOOM, AreaPicker.FULL_MAX_ZOOM, name)

    /**
     * The starting polygon: the corners of the band of the view above the picker card, inset (a fifth of the width each
     * side, from 15% to 50% of the height); null when the map is not laid out. Main thread.
     */
    fun fromView(): List<AreaPoint>? {
        val v = DystopoApp.instance.map.mapView ?: return null
        val m = DystopoApp.instance.map.map ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        return listOf(0.2f to 0.15f, 0.2f to 0.5f, 0.8f to 0.5f, 0.8f to 0.15f).map { (fx, fy) ->
            m.projection.fromScreenLocation(PointF(w * fx, h * fy)).let { AreaPoint(it.latitude, it.longitude) }
        }
    }

    // ---- touches ----

    private sealed class Grab {
        data class Vertex(val i: Int) : Grab()
        data class Mid(val i: Int) : Grab()
    }

    private var grab: Grab? = null
    private var dragging = false
    private var held = false
    private var downX = 0f
    private var downY = 0f
    private var tapCandidate = false
    private var pendingTap: Runnable? = null
    private val hold = Runnable { onHold() }

    /** The map view's touch, asked before the trip editor's; true when the editor took it (the map does not see it). */
    fun touch(ev: MotionEvent): Boolean {
        if (!active) return false
        val app = DystopoApp.instance
        val m = app.map.map ?: return false
        val v = app.map.mapView ?: return false
        val density = v.resources.displayMetrics.density
        val slop = ViewConfiguration.get(v.context).scaledTouchSlop.toFloat()
        val moved = hypot(ev.x - downX, ev.y - downY)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                held = false
                pendingTap?.let { // the second tap of a double tap: the map zooms, no vertex
                    main.removeCallbacks(it)
                    pendingTap = null
                    tapCandidate = false
                    return false
                }
                grab = hit(m, ev.x, ev.y, density)
                if (grab != null) {
                    if (grab is Grab.Vertex) main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
                tapCandidate = true
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val g = grab ?: run { if (moved > slop) tapCandidate = false; return false }
                if (held) return true
                if (!dragging && moved > slop) {
                    dragging = true
                    main.removeCallbacks(hold)
                    remember()
                    if (g is Grab.Mid) { // a midpoint dragged is a new vertex there
                        ring = insert(ring, g.i + 1, midpoint(ring[g.i], ring[(g.i + 1) % ring.size]))
                        grab = Grab.Vertex(g.i + 1)
                        inserts++
                    }
                }
                if (dragging) {
                    val at = m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                    val i = (grab as Grab.Vertex).i
                    ring = ring.toMutableList().also { it[i] = AreaPoint(at.latitude, at.longitude) }
                    changed()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(hold)
                val g = grab
                grab = null
                if (g != null) {
                    when {
                        held -> Unit
                        dragging -> { drags++; lastGesture = "drag"; changed() }
                        g is Grab.Mid -> { // a tap on a midpoint adds the vertex there
                            set(insert(ring, g.i + 1, midpoint(ring[g.i], ring[(g.i + 1) % ring.size])))
                            inserts++
                            lastGesture = "midpoint"
                        }
                    }
                    dragging = false
                    return true
                }
                if (tapCandidate && moved <= slop && ev.eventTime - ev.downTime < ViewConfiguration.getLongPressTimeout()) {
                    val at = m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                    val r = Runnable { pendingTap = null; addVertex(AreaPoint(at.latitude, at.longitude)) }
                    pendingTap = r
                    main.postDelayed(r, ViewConfiguration.getDoubleTapTimeout().toLong())
                }
                tapCandidate = false
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(hold)
                tapCandidate = false
                val g = grab
                grab = null
                if (g != null && dragging) { dragging = false; changed() }
                return g != null
            }
        }
        return grab != null
    }

    /** A long press on a vertex: removed, unless three are left. */
    private fun onHold() {
        val g = grab as? Grab.Vertex ?: return
        if (dragging) return
        held = true
        if (ring.size <= 3) { lastGesture = "removeRefused"; return }
        set(ring.filterIndexed { k, _ -> k != g.i })
        removes++
        lastGesture = "remove"
    }

    /** A tap on the map away from the handles: a vertex there, in the edge it lengthens least (in screen pixels). */
    private fun addVertex(p: AreaPoint) {
        if (!active || ring.size < 3) return
        val m = DystopoApp.instance.map.map ?: return
        val s = ring.map { m.projection.toScreenLocation(LatLng(it.lat, it.lon)).let { q -> q.x.toDouble() to q.y.toDouble() } }
        val q = m.projection.toScreenLocation(LatLng(p.lat, p.lon)).let { it.x.toDouble() to it.y.toDouble() }
        set(insert(ring, bestEdge(s, q) + 1, p))
        taps++
        lastGesture = "tap"
    }

    /** The vertex handle (first) or the midpoint handle under ([x], [y]) in screen pixels, within the handles' touch radius. */
    private fun hit(m: MapLibreMap, x: Float, y: Float, density: Float): Grab? {
        val r = ring
        if (r.size < 3) return null
        fun d(p: AreaPoint): Double = m.projection.toScreenLocation(LatLng(p.lat, p.lon)).let { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
        val vertex = r.indices.minByOrNull { d(r[it]) }!!
        if (d(r[vertex]) <= VERTEX_TOUCH_DP * density) return Grab.Vertex(vertex)
        val mids = r.indices.map { midpoint(r[it], r[(it + 1) % r.size]) }
        val mid = mids.indices.minByOrNull { d(mids[it]) }!!
        if (d(mids[mid]) <= MID_TOUCH_DP * density) return Grab.Mid(mid)
        return null
    }

    // ---- scripted gestures (debug routes), through the map view's own touch dispatch ----

    private fun send(v: MapView, t0: Long, action: Int, x: Float, y: Float, t: Long) {
        val e = MotionEvent.obtain(t0, t, action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun screenOf(p: AreaPoint): PointF? {
        val v = DystopoApp.instance.map.mapView ?: return null
        val m = DystopoApp.instance.map.map ?: return null
        val s = m.projection.toScreenLocation(LatLng(p.lat, p.lon))
        return s.takeIf { it.x >= 0 && it.y >= 0 && it.x <= v.width && it.y <= v.height }
    }

    /**
     * Drags vertex [index] (or, with [mid], the midpoint after it) to ([lat], [lon]) as a finger would: down on the
     * handle, [steps] moves, up. False when the handle or the target is off screen. Main thread.
     */
    fun simulateDrag(index: Int, mid: Boolean, lat: Double, lon: Double, steps: Int = 8): Boolean {
        val v = DystopoApp.instance.map.mapView ?: return false
        val r = ring
        if (index !in r.indices) return false
        val a = screenOf(if (mid) midpoint(r[index], r[(index + 1) % r.size]) else r[index]) ?: return false
        val b = screenOf(AreaPoint(lat, lon)) ?: return false
        val t0 = SystemClock.uptimeMillis()
        send(v, t0, MotionEvent.ACTION_DOWN, a.x, a.y, t0)
        for (s in 1..steps) {
            val f = s.toFloat() / steps
            send(v, t0, MotionEvent.ACTION_MOVE, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, t0 + s * 16L)
        }
        send(v, t0, MotionEvent.ACTION_UP, b.x, b.y, t0 + (steps + 1) * 16L)
        return true
    }

    /** A quick tap at ([lat], [lon]) (the vertex lands after the double-tap timeout). False when off screen. Main thread. */
    fun simulateTap(lat: Double, lon: Double): Boolean {
        val v = DystopoApp.instance.map.mapView ?: return false
        val a = screenOf(AreaPoint(lat, lon)) ?: return false
        val t0 = SystemClock.uptimeMillis()
        send(v, t0, MotionEvent.ACTION_DOWN, a.x, a.y, t0)
        send(v, t0, MotionEvent.ACTION_UP, a.x, a.y, t0 + 40)
        return true
    }

    /** The first half of a long press on vertex [index] (the route waits, then calls [simulateRelease]). Main thread. */
    fun simulatePress(index: Int): Long? {
        val v = DystopoApp.instance.map.mapView ?: return null
        val p = ring.getOrNull(index)?.let { screenOf(it) } ?: return null
        val t0 = SystemClock.uptimeMillis()
        send(v, t0, MotionEvent.ACTION_DOWN, p.x, p.y, t0)
        pressAt = p
        return t0
    }

    private var pressAt: PointF? = null

    fun simulateRelease(t0: Long) {
        val v = DystopoApp.instance.map.mapView ?: return
        val p = pressAt ?: return
        send(v, t0, MotionEvent.ACTION_UP, p.x, p.y, SystemClock.uptimeMillis())
        pressAt = null
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "active" to active, "vertices" to ring.size, "ring" to ring.map { listOf(it.lat, it.lon) }, "canUndo" to canUndo,
        "areaKm2" to ring.takeIf { it.size >= 3 }?.let { Math.round(PolygonRings.areaM2(listOf(it)) / 1e4) / 100.0 },
        "taps" to taps, "drags" to drags, "inserts" to inserts, "removes" to removes, "undos" to undos, "lastGesture" to lastGesture,
    )

    /** Touch radius of a vertex handle and of a midpoint handle, in dp. */
    const val VERTEX_TOUCH_DP = 28f
    const val MID_TOUCH_DP = 22f
    private const val MAX_UNDO = 100
}

/** T142: [ring] with [p] inserted at [index]. Pure, tested. */
internal fun insert(ring: List<AreaPoint>, index: Int, p: AreaPoint): List<AreaPoint> = ring.toMutableList().also { it.add(index.coerceIn(0, ring.size), p) }

/**
 * T142: the edge (from vertex i to i + 1, the last closing the ring) that a new point [q] lengthens least, by
 * |a q| + |q b| - |a b| over the ring's screen positions [s]. Pure, tested.
 */
internal fun bestEdge(s: List<Pair<Double, Double>>, q: Pair<Double, Double>): Int {
    fun d(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(a.first - b.first, a.second - b.second)
    return s.indices.minByOrNull { i -> val a = s[i]; val b = s[(i + 1) % s.size]; d(a, q) + d(q, b) - d(a, b) } ?: 0
}

/** T142: the middle of the edge [a]-[b] as the map draws it (straight in Web Mercator). Pure, tested. */
internal fun midpoint(a: AreaPoint, b: AreaPoint): AreaPoint {
    val y = (WebMercator.latToY(a.lat, 0) + WebMercator.latToY(b.lat, 0)) / 2
    return AreaPoint(WebMercator.yToLat(y, 0), (a.lon + b.lon) / 2)
}

/** T142: [ring] counter-clockwise with longitude as x (RFC 7946's outer ring), reversed when it is not. Pure, tested. */
internal fun counterClockwise(ring: List<AreaPoint>): List<AreaPoint> {
    var s = 0.0
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        s += a.lon * b.lat - b.lon * a.lat
    }
    return if (s < 0) ring.reversed() else ring
}

/**
 * T142: what the picker shows on the map: the shape being chosen (the drawing with its vertex and midpoint handles, the
 * circle around a point, a country's parts), filled and outlined, over the areas' own outlines. Survives style reloads.
 */
object PickerLayer {
    private const val SOURCE = "dystopo-picker"
    private const val FILL = "dystopo-picker-fill"
    private const val LINE = "dystopo-picker-line"
    private const val MID = "dystopo-picker-mid"
    private const val VERTEX = "dystopo-picker-vertex"
    private const val COLOR = "#00796b"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var view: MapView? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }
    @Volatile private var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile var features = 0
        private set

    fun bind(mapView: MapView) {
        if (view === mapView) return
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        install()
    }

    fun clear() = set(emptyList())

    /** The drawing: its ring filled and outlined, a handle on every vertex and a smaller one on every edge's middle. */
    fun drawing(ring: List<AreaPoint>) {
        if (ring.size < 3) return clear()
        val closed = (ring + ring.first()).map { Point.fromLngLat(it.lon, it.lat) }
        val fs = arrayListOf(
            Feature.fromGeometry(Polygon.fromLngLats(listOf(closed))).apply { addStringProperty("kind", "shape") },
            Feature.fromGeometry(LineString.fromLngLats(closed)).apply { addStringProperty("kind", "edge") },
        )
        for (i in ring.indices) {
            val m = midpoint(ring[i], ring[(i + 1) % ring.size])
            fs += Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply { addStringProperty("kind", "mid"); addNumberProperty("index", i) }
        }
        for ((i, p) in ring.withIndex()) fs += Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("kind", "vertex"); addNumberProperty("index", i) }
        set(fs)
    }

    /** An area's shape: a polygon's parts with their holes, a disc as a circle with its centre. */
    fun area(area: AreaOfInterest) {
        val fs = ArrayList<Feature>()
        val rings: List<List<AreaPoint>> = when (val s = area.shape) {
            is AreaShape.Polygon -> s.rings
            is AreaShape.Corridor -> if (s.points.size == 1) listOf(PolygonRings.circle(s.points[0], s.bufferM, 72)) else emptyList()
            is AreaShape.Box -> listOf(listOf(AreaPoint(s.south, s.west), AreaPoint(s.south, s.east), AreaPoint(s.north, s.east), AreaPoint(s.north, s.west)))
        }
        for ((outer, holes) in polygonParts(rings)) {
            val pts = (listOf(outer) + holes).map { r -> (r + r.first()).map { Point.fromLngLat(it.lon, it.lat) } }
            fs += Feature.fromGeometry(Polygon.fromLngLats(pts)).apply { addStringProperty("kind", "shape") }
            for (p in pts) fs += Feature.fromGeometry(LineString.fromLngLats(p)).apply { addStringProperty("kind", "edge") }
        }
        (area.shape as? AreaShape.Corridor)?.points?.singleOrNull()?.let { c ->
            fs += Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply { addStringProperty("kind", "mid") }
        }
        set(fs)
    }

    private fun set(fs: List<Feature>) {
        data = FeatureCollection.fromFeatures(fs)
        features = fs.size
        main.post { apply() }
    }

    private fun install() {
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> addTo(style) } }
    }

    private fun addTo(style: Style) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, data))
            val kind = Expression.get("kind")
            fun isKind(k: String) = Expression.eq(kind, Expression.literal(k))
            style.addLayer(FillLayer(FILL, SOURCE).withFilter(isKind("shape")).withProperties(PropertyFactory.fillColor(COLOR), PropertyFactory.fillOpacity(0.16f)))
            style.addLayer(LineLayer(LINE, SOURCE).withFilter(isKind("edge")).withProperties(PropertyFactory.lineColor(COLOR), PropertyFactory.lineWidth(2.5f)))
            style.addLayer(
                CircleLayer(MID, SOURCE).withFilter(isKind("mid")).withProperties(
                    PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(COLOR), PropertyFactory.circleOpacity(0.7f),
                    PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(1.5f),
                ),
            )
            style.addLayer(
                CircleLayer(VERTEX, SOURCE).withFilter(isKind("vertex")).withProperties(
                    PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#ffffff"),
                    PropertyFactory.circleStrokeColor(COLOR), PropertyFactory.circleStrokeWidth(3f),
                ),
            )
        }
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
}
