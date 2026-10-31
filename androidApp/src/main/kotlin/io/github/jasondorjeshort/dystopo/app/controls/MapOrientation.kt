package io.github.jasondorjeshort.dystopo.app.controls

import android.graphics.PointF
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * T100: the map's one orientation state and the camera that follows the fix, for the whole app: the recentre
 * button ([Recenter], [RecenterButton]), navigation (T61's camera modes are a view of it,
 * [OrientationRules.cameraMode]; `NavSession.setCamera` and the navigation card's camera actions set it) and the
 * debug routes ([ControlRoutes]). Everything here runs on the main thread; the fields are volatile for readers.
 *
 * - [orientation]: north-up (the default), free rotate (entered only by a rotate gesture) or facing (the map turned
 *   to [Heading]). Kept across restarts as north-up or facing ([AppSettings.mapOrientation]); a free-rotated map
 *   comes back north-up, because the camera's bearing is not restored at launch.
 * - [following]: the camera stays on each new fix. A recentre turns it on; a pan or fling by hand, or any camera
 *   move that is not ours (a route fit, a search result, a deep link, `POST /camera`), turns it off. While
 *   following, pinch zoom, double-tap zoom and rotation act about the map's centre (`UiSettings.focalPoint`), so
 *   they keep the fix centred.
 * - In facing, a ticker turns the map to the heading between fixes ([TURN_MS] eases whenever it is
 *   [OrientationRules.TURN_DEG] off), about the fix when following, about the map's centre otherwise.
 * - T176: [locked] ("Keep north up", [AppSettings.orientationLock]): MapLibre's rotate gesture is off, a bearing found
 *   off north at rest in north-up turns the camera back to north ([lockReturns]) instead of entering free rotate, free
 *   rotate is never entered ([set] makes it north-up), and a tap on the position button no longer advances; the
 *   orientation button and the navigation card still change the mode. A pan never changes the mode, locked or not.
 */
object MapOrientation {
    /** One follow move per fix, as T61's navigation did. */
    const val FOLLOW_MS = 400
    const val TURN_MS = 180
    const val TICK_MS = 150L
    /** After our own move ends, MapLibre's rest event may come a little later; until then `animating` is still ours. */
    const val OWN_GRACE_MS = 300L
    const val GESTURE_STALE_MS = 5_000L
    /** T176: a tap that zooms eases over at least this long (a jump of four levels in 300 ms reads as a cut). */
    const val ZOOM_MS = 600

    private val main = Handler(Looper.getMainLooper())
    private val app get() = DystopoApp.instance

    @Volatile var orientation = Orientation.NORTH_UP
        private set
    @Volatile var following = false
        private set
    @Volatile private var loaded = false

    /** The camera's bearing as last moved or seen at rest (navigation's rejoin arrow is drawn relative to it). */
    @Volatile var bearing = 0.0
        private set

    // Our own camera moves: a foreign move cancels ours (its callback ends the flight) and is then seen at rest.
    private var ownSeq = 0L
    /** T176: the sequence number of the last tap's move; a fix's follow move or a facing turn would cancel its zoom. */
    private var tapSeq = -1L
    @Volatile private var ownInFlight = false
    @Volatile private var ownEndedAt = 0L
    /** Where our last move put (or, cut short by a gesture, left) the camera's centre; at rest, a centre elsewhere was moved by someone else. */
    private var ownTarget: LatLng? = null
    @Volatile private var gestures = 0 // two-finger gestures in progress: turn, pinch, two-finger move
    private var multiMove = false
    @Volatile private var gestureAt = 0L

    @Volatile var followMoves = 0L
        private set
    /** Fixes caught up with at rest after a two-finger gesture held the camera ([OrientationRules.atRest]). */
    @Volatile var catchUps = 0L
        private set
    @Volatile var turns = 0L
        private set
    @Volatile var recenters = 0L
        private set
    @Volatile var changes = 0L
        private set
    @Volatile var lastChange: Map<String, Any?>? = null
        private set
    @Volatile var lastFollowEnd: Map<String, Any?>? = null
        private set
    @Volatile var lastFollowStart: Map<String, Any?>? = null
        private set
    /** T176: bearings turned back to north at rest under the lock. */
    @Volatile var lockReturns = 0L
        private set

    /** T176: "Keep north up" ([AppSettings.orientationLock]). */
    val locked: Boolean get() = AppSettings.orientationLock

    /** The persisted mode, once (main thread, first map). */
    private fun load() {
        if (loaded) return
        loaded = true
        orientation = Orientation.parse(AppSettings.mapOrientation)?.takeIf { it != Orientation.FREE } ?: Orientation.NORTH_UP
        onOrientation()
    }

    /** Hooks the gesture listeners on a new map (main thread, from `MapController.attach`). */
    fun install(m: MapLibreMap) {
        load()
        bearing = m.cameraPosition.bearing
        ownInFlight = false
        ownTarget = null
        gestures = 0
        multiMove = false
        m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            // One finger dragging is a pan. Two fingers start a move too when they only turn or pinch the map (each
            // finger moves while their centre stays), so for those the map at rest decides ([onCameraIdle]): following
            // stops when the centre was dragged away, and catches up with the fix when only the fix moved on.
            override fun onMoveBegin(detector: MoveGestureDetector) {
                if (detector.pointersCount <= 1) stopFollowing("pan")
                else if (!multiMove) { multiMove = true; gestures++; gestureAt = SystemClock.elapsedRealtime() }
            }
            override fun onMove(detector: MoveGestureDetector) { if (multiMove) gestureAt = SystemClock.elapsedRealtime() }
            override fun onMoveEnd(detector: MoveGestureDetector) {
                if (multiMove) { multiMove = false; gestures = (gestures - 1).coerceAtLeast(0) }
            }
        })
        m.addOnFlingListener { stopFollowing("fling") }
        m.uiSettings.isRotateGesturesEnabled = !locked // T176: the lock ignores two-finger turns outright
        m.addOnRotateListener(object : MapLibreMap.OnRotateListener {
            override fun onRotateBegin(detector: RotateGestureDetector) {
                gestures++
                gestureAt = SystemClock.elapsedRealtime()
                if (!locked) set(Orientation.FREE, "rotateGesture", move = false) // T176: the gesture is off then; never free
            }
            override fun onRotate(detector: RotateGestureDetector) {
                gestureAt = SystemClock.elapsedRealtime()
                bearing = m.cameraPosition.bearing
                publish() // the free-rotate icon's north pointer turns with the map
            }
            override fun onRotateEnd(detector: RotateGestureDetector) { gestures = (gestures - 1).coerceAtLeast(0) }
        })
        m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) { gestures++; gestureAt = SystemClock.elapsedRealtime() }
            override fun onScale(detector: StandardScaleGestureDetector) { gestureAt = SystemClock.elapsedRealtime() }
            override fun onScaleEnd(detector: StandardScaleGestureDetector) { gestures = (gestures - 1).coerceAtLeast(0) }
        })
        focal()
        publish()
    }

    /**
     * The camera came to rest (main thread): a move that was not ours ends following, and fixes skipped while two
     * fingers held the map are caught up with ([OrientationRules.atRest]); a turned north-up map is free.
     */
    fun onCameraIdle(m: MapLibreMap) {
        bearing = m.cameraPosition.bearing
        if (orientation == Orientation.FREE) publish()
        if (ownInFlight) return
        if (following && !gesturing()) {
            val off = app.map.fixOffsetPx()
            val drift = driftPx(m)
            when (OrientationRules.atRest(drift, off, tolerancePx())) {
                OrientationRules.AtRest.STOP -> stopFollowing("cameraMoved", drift ?: off)
                OrientationRules.AtRest.CATCH_UP -> app.map.lastLocation?.let { fix ->
                    move(LatLng(fix.latitude, fix.longitude), targetBearing(), null, FOLLOW_MS)
                    followMoves++
                    catchUps++
                }
                OrientationRules.AtRest.KEEP -> {}
            }
        }
        // The icon never lies: a north-up map turned by something else (a deep link, `POST /camera`) is free rotate,
        // and a free-rotated map turned back to north (MapLibre's own compass, top right, snaps it there) is north-up.
        // T176: under the lock a north-up map turned by something else is turned back to north instead.
        val offNorth = kotlin.math.abs(OrientationRules.rel(bearing))
        when (OrientationRules.atRestBearing(orientation, offNorth, locked)) {
            OrientationRules.AtRestBearing.TO_FREE -> set(Orientation.FREE, "bearingChanged", move = false)
            OrientationRules.AtRestBearing.TO_NORTH_UP -> set(Orientation.NORTH_UP, if (offNorth < 0.05) "turnedNorth" else "lock", move = offNorth >= 0.05)
            OrientationRules.AtRestBearing.TURN_NORTH -> {
                val fix = app.map.lastLocation
                move(if (following && fix != null) LatLng(fix.latitude, fix.longitude) else null, 0.0, null, FOLLOW_MS)
                lockReturns++
            }
            OrientationRules.AtRestBearing.KEEP -> {}
        }
        focal()
    }

    /** Each fix the puck shows (main thread, `MapController.onFix`). */
    fun onFix(loc: Location) {
        Heading.onFix(loc)
        if (!following) {
            if (orientation == Orientation.FACING) turn()
            return
        }
        // A pinch or a rotation in progress, or someone else moving the camera (a route fit, a search result): do
        // not fight it; the next fix catches up, or the move ends away from the fix and following stops at rest.
        if (busy() || tapMoving()) return
        move(LatLng(loc.latitude, loc.longitude), targetBearing(), null, FOLLOW_MS)
        followMoves++
    }

    /** T61's navigation: eases to a fix at [zoom] when following (its first fix sets the zoom). */
    fun followTo(lat: Double, lon: Double, zoom: Double?) = onMain {
        if (!following) return@onMain
        move(LatLng(lat, lon), targetBearing(), zoom, FOLLOW_MS)
        followMoves++
    }

    /**
     * What a tap on the position button does ([OrientationRules.tap]); [done] runs once the camera is there. T176:
     * [closeZoom] (null: navigation keeps its zoom) and [layout] (the buttons shown) feed the rule; a zoom eases over at
     * least [ZOOM_MS].
     */
    fun tap(animateMs: Int, closeZoom: Double?, layout: Int, done: () -> Unit): Map<String, Any?> = onMain {
        val fix = app.map.lastLocation
        if (fix == null) {
            done()
            return@onMain mapOf("action" to null)
        }
        val off = app.map.fixOffsetPx()
        val wasFollowing = following
        val before = orientation
        val compass = Heading.compassAvailable
        val centred = OrientationRules.centred(following, off, tolerancePx())
        val zoomBefore = app.map.map?.cameraPosition?.zoom
        val t = OrientationRules.tap(before, centred, compass, zoomBefore, closeZoom, layout, locked)
        if (t.action == OrientationRules.TapAction.ADVANCE) set(t.next, "tap", move = false)
        startFollowing("tap")
        recenters++
        val ms = if (t.zoomTo != null && animateMs > 0) maxOf(animateMs, ZOOM_MS) else animateMs
        tapSeq = ownSeq + 1 // the move's own number (it may end at once)
        move(LatLng(fix.latitude, fix.longitude), targetBearing(), t.zoomTo, ms, done)
        linkedMapOf(
            "action" to t.action.name.lowercase(),
            "centredBefore" to centred,
            "followingBefore" to wasFollowing,
            "fixOffsetPxBefore" to off?.let { Math.round(it * 100) / 100.0 },
            "orientationBefore" to before.key,
            "orientation" to orientation.key,
            // ADVANCE that stays north-up: no compass, so facing is not offered.
            "advanced" to (t.action == OrientationRules.TapAction.ADVANCE && t.next != before),
            "compass" to compass,
            // T176: the zoom rule's inputs and outcome (`zoomAfter`, the camera's zoom once there, is added by [Recenter]).
            "layout" to layout,
            "locked" to locked,
            "closeZoom" to closeZoom,
            "zoomBefore" to zoomBefore?.let { Math.round(it * 100) / 100.0 },
            "zoomTo" to t.zoomTo,
        )
    }

    /** T176: the orientation button (two and three buttons): the next mode ([OrientationRules.next]), also under the lock. */
    fun advance(reason: String): Map<String, Any?> = onMain {
        val before = orientation
        set(OrientationRules.next(before, Heading.compassAvailable), reason)
        linkedMapOf("orientationBefore" to before.key, "orientation" to orientation.key, "compass" to Heading.compassAvailable)
    }

    /**
     * T176: after the position button's settings changed (any thread): the rotate gesture follows the lock, a free-rotated
     * map turns north-up when the lock comes on ([OrientationRules.onLock]), and the button redraws.
     */
    fun onSettings() = onMain {
        app.map.map?.uiSettings?.isRotateGesturesEnabled = !locked
        if (locked) OrientationRules.onLock(orientation).let { if (it != orientation) set(it, "lock") }
        publish()
    }

    /** Sets the orientation (debug route, the navigation card); the camera turns to it. T176: never free rotate under the lock. */
    fun set(asked: Orientation, reason: String, move: Boolean = true) {
        val o = if (locked) OrientationRules.onLock(asked) else asked
        onMain {
            if (o != orientation) {
                lastChange = linkedMapOf("from" to orientation.key, "to" to o.key, "reason" to reason, "atMs" to System.currentTimeMillis())
                orientation = o
                changes++
                AppSettings.setMapOrientation(if (o == Orientation.FREE) Orientation.NORTH_UP.key else o.key)
                onOrientation()
            }
            if (move && o != Orientation.FREE) {
                val fix = app.map.lastLocation
                move(if (following && fix != null) LatLng(fix.latitude, fix.longitude) else null, targetBearing(), null, FOLLOW_MS)
            }
            publish()
        }
    }

    /** Navigation's camera modes (T61) onto the one state ([OrientationRules.fromCameraMode]). */
    fun setCameraMode(mode: CameraMode, reason: String) = onMain {
        val (o, f) = OrientationRules.fromCameraMode(mode, orientation)
        if (o != orientation) set(o, reason, move = false)
        if (f) startFollowing(reason) else stopFollowing(reason)
        if (f || o != Orientation.FREE) {
            val fix = app.map.lastLocation
            move(if (f && fix != null) LatLng(fix.latitude, fix.longitude) else null, targetBearing(), null, FOLLOW_MS)
        }
    }

    val cameraMode: CameraMode get() = OrientationRules.cameraMode(orientation, following)

    fun setForeground(on: Boolean) {
        Heading.setForeground(on)
        if (on) onOrientation() else main.removeCallbacks(ticker)
    }

    private fun startFollowing(reason: String) {
        if (following) return
        following = true
        lastFollowEnd = null
        lastFollowStart = linkedMapOf("reason" to reason, "atMs" to System.currentTimeMillis())
        focal()
        publish()
    }

    private fun stopFollowing(reason: String, offsetPx: Double? = null) {
        if (!following) return
        following = false
        lastFollowEnd = linkedMapOf("reason" to reason, "fixOffsetPx" to offsetPx?.let { Math.round(it * 10) / 10.0 }, "atMs" to System.currentTimeMillis())
        focal()
        publish()
    }

    /** The compass and the ticker run only in facing. */
    private fun onOrientation() {
        val facing = orientation == Orientation.FACING
        Heading.setCompassWanted(facing)
        main.removeCallbacks(ticker)
        if (facing) main.postDelayed(ticker, TICK_MS)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (orientation != Orientation.FACING) return
            runCatching { turn() }
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Facing: turn to the heading when it is [OrientationRules.TURN_DEG] off the bearing we last set. */
    private fun turn() {
        if (app.map.map == null || busy() || tapMoving()) return
        val h = Heading.current() ?: return
        if (!OrientationRules.turns(bearing, h.deg)) return
        val fix = app.map.lastLocation
        val target = if (following && fix != null) LatLng(fix.latitude, fix.longitude) else null
        move(target, h.deg, null, TURN_MS)
        turns++
    }

    /**
     * A gesture is in progress, or a camera move that is not ours: MapLibre's `animating` (set when a move starts,
     * cleared at rest) with none of ours in flight, allowing [OWN_GRACE_MS] after ours ends for its rest event.
     */
    private fun busy(): Boolean =
        gesturing() || (app.map.animating && !ownInFlight && SystemClock.elapsedRealtime() - ownEndedAt > OWN_GRACE_MS)

    private fun targetBearing(): Double {
        val m = app.map.map
        val current = m?.cameraPosition?.bearing ?: bearing
        return OrientationRules.bearing(orientation, current, if (orientation == Orientation.FACING) Heading.current()?.deg else null)
    }

    /** Our own camera move; [done] runs when it ends or is cancelled. */
    private fun move(target: LatLng?, bearingDeg: Double?, zoom: Double?, ms: Int, done: () -> Unit = {}) {
        val m = app.map.map ?: return done()
        val b = CameraPosition.Builder(m.cameraPosition)
        target?.let { b.target(it) }
        bearingDeg?.let { b.bearing(it) }
        zoom?.let { b.zoom(it) }
        val update = CameraUpdateFactory.newCameraPosition(b.build())
        target?.let { ownTarget = it }
        val seq = ++ownSeq
        ownInFlight = true
        bearingDeg?.let { bearing = it }
        app.map.noteCameraMove()
        if (ms <= 0) {
            m.moveCamera(update)
            ended(seq, done)
            return
        }
        m.easeCamera(update, ms, object : MapLibreMap.CancelableCallback {
            override fun onFinish() = ended(seq, done)
            override fun onCancel() {
                // Cut short by a two-finger gesture (turns and pinches act about the centre): the centre stays where
                // the move left it, and that is ours. Cut short by anything else, it is not.
                if (seq == ownSeq && gesturing()) m.cameraPosition.target?.let { ownTarget = it }
                ended(seq, done)
            }
        })
    }

    /**
     * Two fingers are on the map. Begin and end come in pairs from MapLibre's detectors; should an end ever go missing,
     * a count with no gesture event for [GESTURE_STALE_MS] is dropped, so following can never stay blocked.
     */
    private fun gesturing(): Boolean {
        if (gestures > 0 && SystemClock.elapsedRealtime() - gestureAt > GESTURE_STALE_MS) {
            gestures = 0
            multiMove = false
        }
        return gestures > 0
    }

    /**
     * T176: the tap's own move (recentre and zoom) is still easing. A new ease cancels the one in flight, so the follow
     * move of the next fix (the GPS gives one a second) cut the zoom short at once on a device; the next fix after it
     * catches up instead.
     */
    private fun tapMoving(): Boolean = ownInFlight && ownSeq == tapSeq

    private fun ended(seq: Long, done: () -> Unit) {
        if (seq == ownSeq) {
            ownInFlight = false
            ownEndedAt = SystemClock.elapsedRealtime()
        }
        done()
    }

    /** While following, zoom and rotation gestures act about the map's centre (the fix). */
    private fun focal() {
        val m = app.map.map ?: return
        val v = app.map.mapView ?: return
        m.uiSettings.focalPoint = if (following && v.width > 0) PointF(v.width / 2f, v.height / 2f) else null
    }

    /** Screen distance (px) from where our last move put the camera's centre to where it is; null before any. */
    private fun driftPx(m: MapLibreMap): Double? {
        val ours = ownTarget ?: return null
        val now = m.cameraPosition.target ?: return null
        val a = m.projection.toScreenLocation(ours)
        val b = m.projection.toScreenLocation(now)
        return Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
    }

    private fun tolerancePx(): Double = OrientationRules.CENTRED_DP.toDouble() * (app.map.mapView?.resources?.displayMetrics?.density ?: 1f)

    /** The Compose state the button and the navigation card draw from (main thread only). */
    private fun publish() {
        val s = OrientationUi.State(orientation, following, Heading.compassAvailable, Math.round(bearing).toInt(), locked, AppSettings.mapControls)
        if (Looper.myLooper() == Looper.getMainLooper()) OrientationUi.state = s else main.post { OrientationUi.state = s }
    }

    private fun <T> onMain(block: () -> T): T = app.map.onMain(block = block)

    fun json(): Map<String, Any?> {
        val off = runCatching { app.map.fixOffsetPx() }.getOrNull()
        val tol = runCatching { tolerancePx() }.getOrDefault(0.0)
        return linkedMapOf(
            "mode" to orientation.key,
            "following" to following,
            "centred" to OrientationRules.centred(following, off, tol),
            "centredTolerancePx" to Math.round(tol * 10) / 10.0,
            "cameraMode" to cameraMode.name.lowercase(),
            "compass" to Heading.compassAvailable,
            "heading" to Heading.json(),
            "bearing" to Math.round(bearing * 10) / 10.0,
            "lastChange" to lastChange,
            "lastFollowStart" to lastFollowStart,
            "lastFollowEnd" to lastFollowEnd,
            "changes" to changes, "recenters" to recenters, "followMoves" to followMoves, "catchUps" to catchUps, "turns" to turns,
            "gestures" to gestures,
            "lock" to locked, "lockReturns" to lockReturns, // T176
            "rotateGestures" to runCatching { app.map.onMain { app.map.map?.uiSettings?.isRotateGesturesEnabled } }.getOrNull(),
            "ownMoveInFlight" to ownInFlight,
            "saved" to AppSettings.mapOrientation,
        )
    }
}

/**
 * T100: the Compose state behind the recentre button's icon and the navigation card's camera action. Touched only
 * on the main thread (T86's lesson: Compose state made on a background thread while a composition's snapshot is
 * open cannot be read); [MapOrientation] posts its writes there.
 */
internal object OrientationUi {
    /**
     * [bearing]: the map's, whole degrees (the free-rotate icon points to north). T176: [locked] ("Keep north up", the
     * padlock) and [layout] (the position button's buttons as set, 1 to 3).
     */
    data class State(val orientation: Orientation, val following: Boolean, val compass: Boolean, val bearing: Int = 0, val locked: Boolean = false, val layout: Int = 1)

    var state by mutableStateOf(State(Orientation.NORTH_UP, following = false, compass = false))

    /** Forces creation now (main thread, before the first composition). */
    fun prepare() {}
}
