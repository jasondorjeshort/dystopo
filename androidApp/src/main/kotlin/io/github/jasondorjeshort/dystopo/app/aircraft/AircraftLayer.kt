package io.github.jasondorjeshort.dystopo.app.aircraft

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.location.Location
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.aircraft.AdsbLolSource
import io.github.jasondorjeshort.dystopo.aircraft.AircraftFetch
import io.github.jasondorjeshort.dystopo.aircraft.AircraftGeo
import io.github.jasondorjeshort.dystopo.aircraft.AircraftKind
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttp
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttpReply
import io.github.jasondorjeshort.dystopo.aircraft.AircraftJson
import io.github.jasondorjeshort.dystopo.aircraft.AircraftNoConnection
import io.github.jasondorjeshort.dystopo.aircraft.AircraftNow
import io.github.jasondorjeshort.dystopo.aircraft.AircraftQuery
import io.github.jasondorjeshort.dystopo.aircraft.AircraftQuota
import io.github.jasondorjeshort.dystopo.aircraft.AircraftRules
import io.github.jasondorjeshort.dystopo.aircraft.AircraftSource
import io.github.jasondorjeshort.dystopo.aircraft.AircraftText
import io.github.jasondorjeshort.dystopo.aircraft.AircraftTracker
import io.github.jasondorjeshort.dystopo.aircraft.FeedState
import io.github.jasondorjeshort.dystopo.aircraft.Observer
import io.github.jasondorjeshort.dystopo.aircraft.ReplaySource
import io.github.jasondorjeshort.dystopo.aircraft.RouteLines
import io.github.jasondorjeshort.dystopo.aircraft.RouteTable
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.MapViewport
import io.github.jasondorjeshort.dystopo.app.online.ConnectFailed
import io.github.jasondorjeshort.dystopo.app.online.HttpGet
import io.github.jasondorjeshort.dystopo.app.online.HttpReply
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
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
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * T311: the Flights layer on the map (Settings › Map options › Flight tracking, off by default). While it is on and the
 * app is in the foreground with the map showing, it asks [AircraftPoller] (owner `map`) for its view's centre and a
 * radius reaching the view's corners; the poller reads the live feed (adsb.lol, [AdsbLolSource]) every refresh interval
 * for that centre rounded to 0.1°, or plays a recorded file ([ReplaySource], `POST /aircraft/replay`, debug builds), and
 * once a second hands over the aircraft moved up along their tracks ([AircraftTracker], at most 30 s), stale ones hollow
 * with their age, ones past 60 s gone.
 *
 * Drawn on the map in a GeoJSON source and four layers of its own, added on every style load (as
 * [io.github.jasondorjeshort.dystopo.app.divergence.DivergenceLayer]): each aircraft a silhouette turned to its track
 * (drawn here with `style.addImage`; filled, hollow when stale, larger and orange when tapped: shape and size carry the
 * state, not colour alone), a short trail coloured by altitude ([AircraftText.ALTITUDE_COLOURS], one fixed scale), labels
 * from zoom [AircraftRules.LABEL_MIN_ZOOM] or under [AircraftRules.LABEL_MAX_COUNT] in view; a tap on one selects it: its
 * track since the layer saw it solid, the remaining great circle to its destination dashed when its route is plausible,
 * and the card ([AircraftCard]).
 *
 * T314: which flights get which route lines is [RouteLines.plan]'s (pure, in shared); this layer only draws its output.
 * The selected flight also gets the path before the app saw it, dotted and lighter (round dots `[0.5, 2]`, 0.6 opacity):
 * the great circle from its origin airport to the first point of its kept track, where the solid track starts; the
 * card names it ("Dotted: estimated path from SEA"). Under "Flight tracking: Route lines" All, every other flight with a
 * plausible route gets one faint thin line origin > aircraft > destination (dark, 0.35 opacity, under the trails), the
 * [RouteLines.CAP] nearest the view's centre, in their own source ([FAINT_SOURCE]) set only when a poll answers, the
 * selection changes or the setting does, not every second. Nothing is fetched for any of it.
 *
 * Threads (T314, after a verifier's SIGABRT "Scudo: corrupted chunk header" in `Layer::setProperty`): MapLibre's map,
 * style, layer and source objects are touched on the main thread only. [push] builds the features on whichever thread
 * calls it (the poller's tick, a selection) and posts [pushNow]; every function that calls MapLibre passes [mainOnly]
 * first, which in a debug build counts and logs a call from another thread (`GET /aircraft` › `offMainCalls`) and
 * reposts it to the main thread. And the layer lets go of a MapView when the activity is destroyed
 * (`MapController.detach` calls [unbind]): before, a tick after a rotation or a font-scale change still pushed into the
 * destroyed view's style until the new view bound.
 *
 * Feed data stays in memory (ODbL): nothing of it is written to disk, cached or exported; turning the layer off forgets
 * it. Stored on the phone: the day's request count ([AircraftQuota], a number and a day). No call sign leaves the phone:
 * routes come from the shipped table (`assets/aircraft/routes.gzip`).
 */
object AircraftLayer {
    const val SOURCE = "dystopo-aircraft"
    const val TRAIL = "dystopo-aircraft-trail"
    const val TRACK = "dystopo-aircraft-track"
    const val ROUTE = "dystopo-aircraft-route"
    const val PLANES = "dystopo-aircraft-planes"
    const val RING = "dystopo-aircraft-emergency"
    const val RING_OUTER = "dystopo-aircraft-emergency-outer"
    const val ESTIMATED = "dystopo-aircraft-estimated" // T314
    const val FAINT_SOURCE = "dystopo-aircraft-faint" // T314
    const val FAINT = "dystopo-aircraft-faint-lines" // T314
    @Volatile var hiddenOnGround = 0
        private set
    private const val TAP_RADIUS_DP = 22f

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var view: MapView? = null
    private var clickMap: MapLibreMap? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }
    private val clickListener = MapLibreMap.OnMapClickListener { onClick(it) }
    private var started = false

    /** Whether the map asks the poller now: Flight tracking on, the app in the foreground, the map not covered. */
    @Volatile var foregroundReason: String? = null
        private set
    @Volatile var drawnCount = 0
        private set
    @Volatile var labelled = false
        private set
    @Volatile var installedOnStyle = false
        private set
    @Volatile var drawn: List<AircraftNow> = emptyList()
        private set

    /** Compose state (main thread, [AircraftUiState]): bumped each tick so the card and the status line follow. */
    val revision: Int get() = AircraftUiState.revision
    /** The tapped aircraft's hex code, or null. Main thread for writes. */
    var selected: String?
        get() = AircraftUiState.selected
        set(v) { AircraftUiState.selected = v }

    val on: Boolean get() = AircraftPoller.on

    /** Whether the layer draws (for the credit line): on, and the feed has answered or aircraft are drawn. */
    val drawing: Boolean get() = on && (drawnCount > 0 || AircraftPoller.state == FeedState.LIVE)

    /** The status line over the map, or null. */
    val message: String? get() = AircraftPoller.message

    val replay: ReplaySource? get() = AircraftPoller.replay

    // --- binding ---

    /** Draws on [mapView] from now on (main thread); asks the poller for the view's aircraft while it may. */
    fun bind(mapView: MapView) {
        if (!mainOnly("bind") { bind(mapView) }) return
        if (!started) {
            started = true
            AircraftPoller.onBeforeTick { askForTheView() }
            AircraftPoller.addListener { v -> drawn = v.aircraft; push(v.aircraft) }
        }
        if (view === mapView) return
        unbind()
        view = mapView
        installedOnStyle = false
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        mapView.getMapAsync { m ->
            if (view !== mapView) return@getMapAsync
            m.addOnMapClickListener(clickListener)
            clickMap = m
        }
        install()
    }

    fun unbind() {
        if (!mainOnly("unbind") { unbind() }) return
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        clickMap?.removeOnMapClickListener(clickListener)
        clickMap = null
        view = null
        installedOnStyle = false
    }

    // --- the main-thread rule (T314) ---

    /** Calls to MapLibre that arrived off the main thread (each reposted there); 0 is the rule. */
    @Volatile var offMainCalls = 0L
        private set
    @Volatile var lastOffMain: String? = null
        private set
    /** Pushes skipped because the layer's MapView is no longer the one the map controller holds (a destroyed activity's). */
    @Volatile var skippedDetached = 0L
        private set

    /**
     * True on the main thread. Elsewhere: counts it (with the caller's [what] and, in a debug build, the stack), logs it,
     * posts [again] to the main thread and returns false, so the caller returns without touching MapLibre.
     */
    private inline fun mainOnly(what: String, crossinline again: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        offMainCalls++
        lastOffMain = if (BuildConfig.DEBUG) "$what on ${Thread.currentThread().name}: " +
            Throwable().stackTrace.drop(1).take(6).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" } else what
        android.util.Log.e("Dystopo", "AircraftLayer: MapLibre call off the main thread: $lastOffMain")
        main.post { again() }
        return false
    }

    /** Why the map does not ask now, or null when it does: off, in the background, no view yet, or the Horizon view over it. */
    private fun pauseReason(): String? {
        if (!on) return "off"
        DystopoApp.instance.map.backgroundReason()?.let { return it }
        if (io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi.open) return "horizonViewOpen"
        if (MapViewport.current == null) return "noView"
        return null
    }

    /** The map's request: its view's centre and the radius to the view's corners (the poller rounds the centre). */
    private fun askForTheView() {
        foregroundReason = pauseReason()
        val v = MapViewport.current
        if (foregroundReason != null || v == null) { AircraftPoller.release(AircraftPoller.MAP); return }
        val q = AircraftQuery.forView((v.minLat + v.maxLat) / 2, (v.minLon + v.maxLon) / 2, v.minLon, v.minLat, v.maxLon, v.maxLat)
        AircraftPoller.request(AircraftPoller.MAP, q.lat, q.lon, q.radiusNm)
    }

    // --- drawing ---

    private fun install() {
        if (!mainOnly("install") { install() }) return
        val v = view ?: return
        v.getMapAsync { m -> m.getStyle { style -> addTo(style) } }
    }

    private fun addTo(style: Style) {
        if (!mainOnly("addTo") { addTo(style) }) return
        if (view == null || !style.isFullyLoaded) return
        if (style.getImage(icon(AircraftKind.GENERIC, "")) == null) {
            val d = DystopoApp.instance.resources.displayMetrics.density
            for (k in AircraftKind.entries) {
                style.addImage(icon(k, ""), silhouette(k, d, 26f, 0xFF1D2A3A.toInt(), 0xFFFFFFFF.toInt(), filled = true))
                style.addImage(icon(k, "-hollow"), silhouette(k, d, 26f, 0xFF1D2A3A.toInt(), 0xCCFFFFFF.toInt(), filled = false))
                style.addImage(icon(k, "-selected"), silhouette(k, d, 34f, 0xFFD9480F.toInt(), 0xFFFFFFFF.toInt(), filled = true))
            }
        }
        if (style.getSource(FAINT_SOURCE) == null) {
            // T314: the faint route lines of the other flights, under everything else of the layer.
            style.addSource(GeoJsonSource(FAINT_SOURCE, FeatureCollection.fromFeatures(emptyList())))
            style.addLayer(LineLayer(FAINT, FAINT_SOURCE).withProperties(
                PropertyFactory.lineColor("#1d2a3a"), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(0.35f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ))
        }
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, FeatureCollection.fromFeatures(emptyList())))
            fun kind(k: String) = Expression.eq(Expression.get("kind"), Expression.literal(k))
            val stops = AircraftText.ALTITUDE_COLOURS.map { (m, c) -> Expression.stop(m, Expression.color(android.graphics.Color.parseColor(c))) }.toTypedArray()
            val altColour = Expression.switchCase(
                Expression.lt(Expression.get("alt"), Expression.literal(-1000)), Expression.color(android.graphics.Color.parseColor(AircraftText.UNKNOWN_COLOUR)),
                Expression.interpolate(Expression.linear(), Expression.get("alt"), *stops),
            )
            style.addLayer(LineLayer(TRAIL, SOURCE).withFilter(kind("trail")).withProperties(
                PropertyFactory.lineColor(altColour), PropertyFactory.lineWidth(3f), PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ))
            style.addLayer(LineLayer(ROUTE, SOURCE).withFilter(kind("route")).withProperties(
                PropertyFactory.lineColor("#d9480f"), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            ))
            // T314: the estimated path before the app saw it: round dots, lighter than the solid track, so it reads as a guess.
            style.addLayer(LineLayer(ESTIMATED, SOURCE).withFilter(kind("estimated")).withProperties(
                PropertyFactory.lineColor("#d9480f"), PropertyFactory.lineWidth(3f), PropertyFactory.lineOpacity(0.6f),
                PropertyFactory.lineDasharray(arrayOf(0.5f, 2f)), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ))
            style.addLayer(LineLayer(TRACK, SOURCE).withFilter(kind("track")).withProperties(
                PropertyFactory.lineColor("#d9480f"), PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ))
            // The emergency ring (squawk 7700, 7600, 7500): a double ring around the silhouette, a shape and not only a colour.
            style.addLayer(CircleLayer(RING_OUTER, SOURCE).withFilter(kind("emergency")).withProperties(
                PropertyFactory.circleRadius(25f), PropertyFactory.circleOpacity(0.0f),
                PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f),
            ))
            style.addLayer(CircleLayer(RING, SOURCE).withFilter(kind("emergency")).withProperties(
                PropertyFactory.circleRadius(20f), PropertyFactory.circleOpacity(0.0f),
                PropertyFactory.circleStrokeColor("#c92a2a"), PropertyFactory.circleStrokeWidth(3.5f),
            ))
            style.addLayer(SymbolLayer(PLANES, SOURCE).withFilter(kind("plane")).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconRotate(Expression.get("rot")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.symbolSortKey(Expression.get("sort")),
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf(MapLibreStyle.FONT_MEDIUM)),
                PropertyFactory.textSize(12f), PropertyFactory.textColor("#1d2a3a"),
                PropertyFactory.textHaloColor("#ffffff"), PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
                PropertyFactory.textOptional(true),
            ))
        }
        installedOnStyle = true
        faintDirty = true // T314: a new style has an empty faint source
        pushNow()
    }

    /** The image name of [kind]'s silhouette: [variant] "" (filled), "-hollow" (stale) or "-selected". */
    fun icon(kind: AircraftKind, variant: String) = "dystopo-aircraft-${kind.json}$variant"

    /**
     * [kind]'s silhouette, nose up (north; the layer turns it to the track), [sizeDp] across, [ink] with an [edge] outline;
     * hollow ([filled] false) is the outline over a pale fill. Shapes: an airliner (swept wings, tailplane; also the
     * generic one), a heavy (longer, wider wings with four engines), a light aircraft (straight wings), a rotorcraft (a
     * rotor disc over a body), a glider (long thin wings), a balloon (an envelope over a basket), a drone (four rotors).
     */
    private fun silhouette(kind: AircraftKind, density: Float, sizeDp: Float, ink: Int, edge: Int, filled: Boolean): Bitmap {
        val s = (sizeDp * density).toInt().coerceAtLeast(16)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val u = s / 32f
        val p = when (kind) {
            AircraftKind.HEAVY -> Path().apply {
                moveTo(16 * u, 0.5f * u); cubicTo(17.9f * u, 2 * u, 18.2f * u, 6 * u, 18.2f * u, 10 * u)
                lineTo(31.5f * u, 18.5f * u); lineTo(31.5f * u, 21 * u); lineTo(18.2f * u, 17 * u)
                lineTo(17.6f * u, 26 * u); lineTo(23 * u, 29.5f * u); lineTo(23 * u, 31.5f * u); lineTo(16 * u, 30 * u)
                lineTo(9 * u, 31.5f * u); lineTo(9 * u, 29.5f * u); lineTo(14.4f * u, 26 * u)
                lineTo(13.8f * u, 17 * u); lineTo(0.5f * u, 21 * u); lineTo(0.5f * u, 18.5f * u); lineTo(13.8f * u, 10 * u)
                cubicTo(13.8f * u, 6 * u, 14.1f * u, 2 * u, 16 * u, 0.5f * u); close()
                for ((x, y) in listOf(6f to 18.5f, 10.5f to 15.8f, 21.5f to 15.8f, 26f to 18.5f)) addCircle(x * u, y * u, 1.4f * u, Path.Direction.CW)
            }
            AircraftKind.LIGHT -> Path().apply {
                moveTo(16 * u, 3 * u); cubicTo(17.5f * u, 4 * u, 17.5f * u, 7 * u, 17.3f * u, 10 * u)
                lineTo(30 * u, 10 * u); lineTo(30 * u, 14 * u); lineTo(17.2f * u, 14 * u)
                lineTo(16.8f * u, 25 * u); lineTo(22 * u, 25 * u); lineTo(22 * u, 28 * u); lineTo(16 * u, 28.5f * u)
                lineTo(10 * u, 28 * u); lineTo(10 * u, 25 * u); lineTo(15.2f * u, 25 * u)
                lineTo(14.8f * u, 14 * u); lineTo(2 * u, 14 * u); lineTo(2 * u, 10 * u); lineTo(14.7f * u, 10 * u)
                cubicTo(14.5f * u, 7 * u, 14.5f * u, 4 * u, 16 * u, 3 * u); close()
            }
            AircraftKind.GLIDER -> Path().apply {
                moveTo(16 * u, 4 * u); cubicTo(17 * u, 5 * u, 17 * u, 8 * u, 16.8f * u, 11 * u)
                lineTo(31.5f * u, 12 * u); lineTo(31.5f * u, 13.6f * u); lineTo(16.7f * u, 13.6f * u)
                lineTo(16.4f * u, 26 * u); lineTo(20 * u, 26.5f * u); lineTo(20 * u, 28 * u); lineTo(16 * u, 28 * u)
                lineTo(12 * u, 28 * u); lineTo(12 * u, 26.5f * u); lineTo(15.6f * u, 26 * u)
                lineTo(15.3f * u, 13.6f * u); lineTo(0.5f * u, 13.6f * u); lineTo(0.5f * u, 12 * u); lineTo(15.2f * u, 11 * u)
                cubicTo(15 * u, 8 * u, 15 * u, 5 * u, 16 * u, 4 * u); close()
            }
            AircraftKind.ROTORCRAFT -> Path().apply {
                fillType = Path.FillType.WINDING
                addCircle(16 * u, 13 * u, 11.5f * u, Path.Direction.CW)
                addCircle(16 * u, 13 * u, 9.5f * u, Path.Direction.CCW)
                addOval(RectF(12.5f * u, 7 * u, 19.5f * u, 20 * u), Path.Direction.CW)
                addRect(15.2f * u, 19 * u, 16.8f * u, 30 * u, Path.Direction.CW)
                addRect(12.5f * u, 28 * u, 19.5f * u, 30 * u, Path.Direction.CW)
            }
            AircraftKind.BALLOON -> Path().apply {
                addOval(RectF(6 * u, 2 * u, 26 * u, 23 * u), Path.Direction.CW)
                moveTo(10 * u, 20 * u); lineTo(14 * u, 26 * u); lineTo(18 * u, 26 * u); lineTo(22 * u, 20 * u); close()
                addRect(13 * u, 26 * u, 19 * u, 30.5f * u, Path.Direction.CW)
            }
            AircraftKind.UAV -> Path().apply {
                for ((x, y) in listOf(7f to 7f, 25f to 7f, 7f to 25f, 25f to 25f)) addCircle(x * u, y * u, 5.5f * u, Path.Direction.CW)
                addRect(12 * u, 12 * u, 20 * u, 20 * u, Path.Direction.CW)
                moveTo(16 * u, 3 * u); lineTo(19 * u, 10 * u); lineTo(13 * u, 10 * u); close()
            }
            AircraftKind.AIRLINER, AircraftKind.GENERIC -> Path().apply {
            moveTo(16 * u, 1 * u) // nose
            cubicTo(17.6f * u, 2.5f * u, 17.8f * u, 6 * u, 17.8f * u, 10 * u)
            lineTo(30 * u, 17 * u); lineTo(30 * u, 19.5f * u); lineTo(17.8f * u, 16 * u) // right wing
            lineTo(17.4f * u, 25 * u); lineTo(22 * u, 28.5f * u); lineTo(22 * u, 30.5f * u); lineTo(16 * u, 29 * u) // right tail
            lineTo(10 * u, 30.5f * u); lineTo(10 * u, 28.5f * u); lineTo(14.6f * u, 25 * u) // left tail
            lineTo(14.2f * u, 16 * u); lineTo(2 * u, 19.5f * u); lineTo(2 * u, 17 * u); lineTo(14.2f * u, 10 * u) // left wing
            cubicTo(14.2f * u, 6 * u, 14.4f * u, 2.5f * u, 16 * u, 1 * u)
            close()
            }
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = if (filled) ink else edge }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = if (filled) 1.6f * density else 2f * density
            color = if (filled) edge else ink; strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(p, line.apply { if (filled) strokeWidth = 3f * density })
        c.drawPath(p, fill)
        if (!filled) c.drawPath(p, line.apply { strokeWidth = 1.8f * density })
        return b
    }

    @Volatile private var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())

    /** Builds the features from [list] and sends them to the map (any thread). */
    private val pushLock = Any()

    private fun push(all: List<AircraftNow>) = synchronized(pushLock) { pushLocked(all) }

    /** Builds the features (no MapLibre call here: any thread), then posts [pushNow] to the main thread. */
    private fun pushLocked(all: List<AircraftNow>) {
        val sel = selected
        val box = MapViewport.current
        val zoom = zoomNow
        // On the ground: hidden below the airport zoom, never labelled, not counted for the label cap (the owner, 2026-11-20).
        val groundShown = zoom >= AircraftRules.GROUND_MIN_ZOOM
        hiddenOnGround = if (groundShown) 0 else all.count { it.report.onGround }
        val list = if (groundShown) all else all.filter { !it.report.onGround }
        val airborne = list.filter { !it.report.onGround }
        val inView = if (box == null) airborne.size else airborne.count { it.lon in box.minLon..box.maxLon && it.lat in box.minLat..box.maxLat }
        val labels = zoom >= AircraftRules.LABEL_MIN_ZOOM || inView <= AircraftRules.LABEL_MAX_COUNT
        labelled = labels
        val fs = ArrayList<Feature>()
        for (a in list) {
            val pts = a.trail
            for (i in 1 until pts.size) {
                val p = pts[i - 1]
                val q = pts[i]
                fs += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(p.lon, p.lat), Point.fromLngLat(q.lon, q.lat)))).apply {
                    addStringProperty("kind", "trail"); addNumberProperty("alt", q.altitudeM ?: -9999.0)
                }
            }
        }
        val selectedNow = list.firstOrNull { it.hex == sel }
        val hist = selectedNow?.let { AircraftPoller.history(it.hex) }.orEmpty()
        val mode = AppSettings.AIRCRAFT_ROUTE_LINES.value
        val plan = RouteLines.plan(list, mode, sel, hist, box?.let { (it.minLat + it.maxLat) / 2 }, box?.let { (it.minLon + it.maxLon) / 2 })
        fun line(pts: List<Pair<Double, Double>>, kind: String) =
            Feature.fromGeometry(LineString.fromLngLats(pts.map { (la, lo) -> Point.fromLngLat(lo, la) })).apply { addStringProperty("kind", kind) }
        if (selectedNow != null) {
            val track = hist.map { Point.fromLngLat(it.lon, it.lat) } + Point.fromLngLat(selectedNow.lon, selectedNow.lat)
            if (track.size >= 2) fs += Feature.fromGeometry(LineString.fromLngLats(track)).apply { addStringProperty("kind", "track") }
            plan.estimatedPast?.let { fs += line(it, "estimated") }
            plan.remaining?.let { fs += line(it, "route") }
        }
        lastPlan = plan
        estimatedFrom = plan.estimatedFrom
        // T314: the faint lines' own source, rebuilt only on a poll, a selection or a setting change.
        val key = "${AircraftPoller.polls}|$sel|${mode.key}|${list.isEmpty()}"
        if (key != faintKey) {
            faintKey = key
            faintData = FeatureCollection.fromFeatures(plan.faint.map { f -> line(f.points, "faint").apply { addStringProperty("hex", f.hex) } })
            faintCount = plan.faint.size
            faintPointsMax = plan.faint.maxOfOrNull { it.points.size } ?: 0
            routeLinesCapped = plan.capped
            faintUpdatedAtMs = System.currentTimeMillis()
            faintUpdates++
            faintDirty = true
        }
        routeLinesDrawn = (if (selectedNow != null && plan.drawn > plan.faint.size) 1 else 0) + faintCount
        for (a in list) {
            val isSel = a.hex == sel
            fs += Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).apply {
                addStringProperty("kind", "plane")
                addStringProperty("hex", a.hex)
                addStringProperty("icon", icon(a.report.kind, if (isSel) "-selected" else if (a.stale) "-hollow" else ""))
                addNumberProperty("rot", if (a.report.kind == AircraftKind.BALLOON) 0.0 else a.report.trackDeg ?: 0.0)
                addNumberProperty("sort", if (isSel) 3 else if (a.report.emergency != null) 2 else if (a.stale) 0 else 1)
                addStringProperty("label", if ((labels && !a.report.onGround) || isSel) a.label else "")
            }
            if (a.report.emergency != null) fs += Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).apply {
                addStringProperty("kind", "emergency"); addStringProperty("hex", a.hex)
            }
        }
        data = FeatureCollection.fromFeatures(fs)
        drawnCount = list.size
        main.post {
            if (selected != null && list.none { it.hex == selected }) selected = null
            AircraftUiState.revision++
            pushNow()
        }
    }

    @Volatile private var zoomNow = 0.0
    @Volatile var pushes = 0L
        private set

    // T314: the route lines' plan and the faint source's state (for GET /aircraft).
    @Volatile private var lastPlan: RouteLines.Plan = RouteLines.Plan.NONE
    /** The origin airport of the selected flight's dotted estimate while one is drawn (the card's line), else null. */
    @Volatile var estimatedFrom: io.github.jasondorjeshort.dystopo.aircraft.Airport? = null
        private set
    @Volatile private var faintKey: String? = null
    @Volatile private var faintData: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile private var faintDirty = true
    @Volatile private var faintCount = 0
    @Volatile private var faintPointsMax = 0
    @Volatile var routeLinesDrawn = 0
        private set
    @Volatile var routeLinesCapped = 0
        private set
    @Volatile private var faintUpdatedAtMs = 0L
    @Volatile private var faintPushedAtMs = 0L
    @Volatile private var faintUpdates = 0L
    @Volatile private var faintPushes = 0L

    private fun pushNow() {
        if (!mainOnly("pushNow") { pushNow() }) return
        val v = view ?: return
        if (v !== DystopoApp.instance.map.mapView) { skippedDetached++; return } // a destroyed activity's view
        v.getMapAsync { m ->
            if (view !== v) return@getMapAsync
            zoomNow = m.cameraPosition.zoom
            val style = m.style ?: return@getMapAsync
            if (!style.isFullyLoaded) return@getMapAsync
            (style.getSource(SOURCE) as? GeoJsonSource)?.setGeoJson(data)
            pushes++
            if (faintDirty) {
                (style.getSource(FAINT_SOURCE) as? GeoJsonSource)?.let { s ->
                    s.setGeoJson(faintData)
                    faintDirty = false
                    faintPushedAtMs = System.currentTimeMillis()
                    faintPushes++
                }
            }
        }
    }

    /** A tap on an aircraft selects it (main thread); other taps fall through. */
    private fun onClick(latLng: LatLng): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) { offMainCalls++; return false }
        if (!on) return false
        val m = clickMap ?: return false
        val p = m.projection.toScreenLocation(latLng)
        val r = TAP_RADIUS_DP * DystopoApp.instance.resources.displayMetrics.density
        val hit = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), PLANES).minByOrNull { f ->
            val g = f.geometry() as? Point ?: return@minByOrNull Float.MAX_VALUE
            val q: PointF = m.projection.toScreenLocation(LatLng(g.latitude(), g.longitude()))
            (q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)
        } ?: return false
        select(hit.getStringProperty("hex"))
        return true
    }

    /** Selects [hex] (null: none) and redraws at once. Main thread. */
    fun select(hex: String?) {
        selected = hex
        Thread { runCatching { push(drawn) } }.start()
    }

    /** The screen point of [hex] now (px in the map view), for the debug route's tap; null when not drawn. Main thread. */
    fun screenPoint(hex: String): PointF? {
        if (Looper.myLooper() != Looper.getMainLooper()) { offMainCalls++; return null }
        val a = drawn.firstOrNull { it.hex == hex } ?: return null
        val m = clickMap ?: DystopoApp.instance.map.map ?: return null
        return m.projection.toScreenLocation(LatLng(a.lat, a.lon))
    }

    fun json(): Map<String, Any?> {
        val list = drawn
        return LinkedHashMap(AircraftPoller.json()).apply {
            put("mapAsksBecause", foregroundReason)
            put("count", list.size); put("stale", list.count { it.stale }); put("labelled", labelled)
            put("hiddenOnGround", hiddenOnGround); put("groundMinZoom", AircraftRules.GROUND_MIN_ZOOM)
            put("emergencies", list.count { it.report.emergency != null })
            put("selected", selected); put("drawing", drawing); put("installedOnStyle", installedOnStyle); put("pushes", pushes)
            // T314: the route lines (the setting, the selected flight's dotted estimate, the faint lines and their source).
            val p = lastPlan
            put("routeLines", AppSettings.AIRCRAFT_ROUTE_LINES.value.key)
            put("estimatedPastPoints", p.estimatedPast?.size ?: 0)
            put("estimatedFrom", p.estimatedFrom?.code)
            put("estimatedPastWhyNot", p.estimatedPastWhyNot)
            put("remainingPoints", p.remaining?.size ?: 0)
            put("routeLinesDrawn", routeLinesDrawn); put("routeLinesCapped", routeLinesCapped); put("routeLinesCap", RouteLines.CAP)
            put("faintLines", faintCount); put("faintPointsMax", faintPointsMax)
            put("faintUpdatedAtMs", faintUpdatedAtMs.takeIf { it > 0 }); put("faintPushedAtMs", faintPushedAtMs.takeIf { it > 0 })
            put("faintUpdates", faintUpdates); put("faintPushes", faintPushes)
            put("offMainCalls", offMainCalls); put("lastOffMain", lastOffMain); put("skippedDetached", skippedDetached) // T314
            put("aircraft", list.sortedBy { it.distanceM ?: 0.0 }.map { AircraftJson.now(it) })
        }
    }
}

/** T311: the Flights layer's Compose state, made on the main thread at its first read in composition (a `*UiState`, T226). */
object AircraftUiState {
    var revision by mutableStateOf(0)
    var selected by mutableStateOf<String?>(null)

    /** Touches the state on the main thread before the first composition (`MainActivity.onCreate`). */
    fun prepare() {
        revision
        selected
    }
}
