package io.github.jasondorjeshort.dystopo.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import io.github.jasondorjeshort.dystopo.app.controls.MapOrientation
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The bridge between the map on screen and the (background-thread) debug endpoints: camera, map
 * idle state, screenshots, injected locations. Everything that touches MapLibre runs on the main
 * thread through [onMain].
 */
class MapController {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var mapView: MapView? = null
        private set
    @Volatile var map: MapLibreMap? = null
        private set
    @Volatile var activity: Activity? = null
    @Volatile var styleLoaded = false
        private set
    @Volatile var styleError: String? = null
        private set
    /** T138: styles set on the map since the process started (`GET /health` `map.styleLoads`): a check that a tile came without a reload. */
    val styleLoads = java.util.concurrent.atomic.AtomicLong()

    /** Tile requests the loopback server is handling or has queued; set by [Routes]. */
    @Volatile var pendingTiles: () -> Int = { 0 }

    /** elapsedRealtime of the last finished tile request; set by [Routes]. */
    @Volatile var lastTileAt: () -> Long = { 0L }

    /** MapLibre's own idle signal (`onDidBecomeIdle`, reset when a frame starts). Diagnostic only, see [idle]. */
    @Volatile var mapLibreIdle = false
        private set
    @Volatile var framesRendered = 0L
        private set

    /** Whether the last rendered frame had every visible tile loaded (MapLibre's `fully` flag). */
    @Volatile var lastFrameFully = false
        private set
    @Volatile private var lastPartialFrameAt = 0L

    /** A camera transition (gesture, animation, or [setCamera]) is in progress. */
    @Volatile var animating = false
        private set
    @Volatile private var lastCameraAt = 0L
    @Volatile var lastLocation: Location? = null
        private set

    /** True while injected fixes drive the puck (device fixes are ignored). */
    @Volatile var injectedLocation = false
        private set

    /** MapLibre's `Map::isFullyLoaded` (style, every source and every visible tile loaded), sampled after each frame. */
    @Volatile var fullyLoaded = false
        private set
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastNotLoadedAt = 0L
    @Volatile private var lastRepaintKickAt = 0L
    @Volatile var repaintKicks = 0L
        private set
    @Volatile var idleEvents = 0L
        private set

    /**
     * Why the map is not idle right now; empty when it is. Idle means every visible tile of every source
     * is loaded and drawn and nothing changes any more:
     * - the style is loaded, no camera transition runs, no tile request is pending or queued at the server;
     * - MapLibre's own `isFullyLoaded` is true (`notFullyLoaded`) and the last frame was a full one
     *   (`frameNotFullyRendered`);
     * - no frame was drawn in the last [QUIET_MS] unless MapLibre itself declared idle after it
     *   (`rendering`: symbol fades, hillshade preparation and placement draw extra frames after the
     *   tiles arrive; a screenshot then would catch labels or hillshade half drawn);
     * - and all of that has held for [QUIET_MS] (`settling`).
     * MapLibre's idle event alone is not enough: it can stay unfired for long stretches, so it only
     * shortens the `rendering` wait. `/screenshot` adds a pixel check on top (see [stableSnapshot]).
     */
    fun idleBlockers(now: Long = SystemClock.elapsedRealtime()): List<String> {
        val reasons = ArrayList<String>()
        if (map == null) reasons += "mapNotReady"
        if (!styleLoaded) reasons += "styleLoading"
        if (animating) reasons += "animating"
        val pending = pendingTiles()
        if (pending > 0) reasons += "pendingTiles"
        if (!fullyLoaded) reasons += "notFullyLoaded"
        if (!lastFrameFully) reasons += "frameNotFullyRendered"
        if (now - lastFrameAt < QUIET_MS && !mapLibreIdle) reasons += "rendering"
        val busyAt = maxOf(lastCameraAt, lastTileAt(), lastPartialFrameAt, lastNotLoadedAt)
        if (reasons.isEmpty() && now - busyAt < QUIET_MS) reasons += "settling"
        if ((!fullyLoaded || !lastFrameFully) && pending == 0 && !animating && now - lastFrameAt > STUCK_MS) kickRepaint(now)
        return reasons
    }

    /**
     * The renderer reported a partial frame and then stopped drawing (seen after first launch: a minute of
     * `frameNotFullyRendered` until the first camera move). Nothing is pending at the server, so ask
     * MapLibre for one more frame; it re-checks its tiles and reports the frame as full once they are.
     */
    private fun kickRepaint(now: Long) {
        if (now - lastRepaintKickAt < STUCK_MS) return
        lastRepaintKickAt = now
        repaintKicks++
        main.post { map?.triggerRepaint() }
    }

    val idle: Boolean get() = idleBlockers().isEmpty()

    fun idleJson(): Map<String, Any?> {
        val now = SystemClock.elapsedRealtime()
        val blockers = idleBlockers(now)
        return mapOf(
            "idle" to blockers.isEmpty(),
            "notIdleBecause" to blockers,
            "pendingTiles" to pendingTiles(),
            "animating" to animating,
            "fullyLoaded" to fullyLoaded,
            "lastFrameFully" to lastFrameFully,
            "msSinceCamera" to if (lastCameraAt == 0L) null else now - lastCameraAt,
            "msSinceTile" to lastTileAt().takeIf { it > 0 }?.let { now - it },
            "msSinceFrame" to if (lastFrameAt == 0L) null else now - lastFrameAt,
            "msSincePartialFrame" to if (lastPartialFrameAt == 0L) null else now - lastPartialFrameAt,
            "quietMs" to QUIET_MS,
            "mapLibreIdle" to mapLibreIdle,
            "mapLibreIdleEvents" to idleEvents,
            "repaintKicks" to repaintKicks,
            "lastScreenshot" to lastScreenshot,
        )
    }

    /** How the last `/screenshot` went: idle reached, pixel-stable, comparison rounds, ms. */
    @Volatile var lastScreenshot: Map<String, Any?>? = null

    /** MapLibre's `NativeMap.isFullyLoaded` (the interface is package-private, so through reflection); null if unavailable. */
    private fun nativeFullyLoaded(view: MapView): Boolean? = runCatching {
        val native = NATIVE_FIELD?.get(view) ?: return null
        val method = native.javaClass.getMethod("isFullyLoaded").apply { isAccessible = true }
        method.invoke(native) as Boolean
    }.getOrNull()

    private fun sampleLoaded(view: MapView, fallback: Boolean) {
        val loaded = nativeFullyLoaded(view) ?: fallback
        fullyLoaded = loaded
        if (!loaded) lastNotLoadedAt = SystemClock.elapsedRealtime()
    }

    fun attach(view: MapView, styleJson: () -> String, onStyle: (Style) -> Unit = {}) {
        mapView = view
        view.addOnWillStartRenderingFrameListener { mapLibreIdle = false }
        view.addOnDidFinishRenderingFrameListener { fully, _, _ ->
            framesRendered++
            lastFrameAt = SystemClock.elapsedRealtime()
            lastFrameFully = fully
            if (!fully) lastPartialFrameAt = lastFrameAt
            sampleLoaded(view, fully)
        }
        view.addOnDidBecomeIdleListener {
            idleEvents++
            mapLibreIdle = true
            sampleLoaded(view, true)
            runCatching { onMapIdle() } // T110: the online credits are asked again once the view's tiles are in
        }
        view.addOnDidFailLoadingMapListener { styleError = it }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = false // drawn by the Compose overlay from the packs' metadata
            m.uiSettings.isLogoEnabled = false
            m.setMinZoomPreference(3.0)
            m.setMaxZoomPreference(18.0)
            m.addOnCameraMoveStartedListener { reason ->
                animating = true
                lastCameraAt = SystemClock.elapsedRealtime()
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) endOpenWindow("gesture") // T162
            }
            m.addOnCameraIdleListener {
                animating = false
                lastCameraAt = SystemClock.elapsedRealtime()
                runCatching { onCameraIdle() } // T41: online mode retries negative-cached tiles, refreshes its credits
                runCatching { MapViewport.update(m) } // T49: the credit line covers the packs in view
                runCatching { atRest(m) } // T162: the saved view; a move elsewhere ends the open window
                runCatching { MapOrientation.onCameraIdle(m) } // T100: a move that was not ours ends following
            }
            MapOrientation.install(m) // T100: pan, fling and rotate gestures; the persisted orientation
            loadStyle(styleJson(), onStyle)
        }
    }

    fun loadStyle(json: String, onStyle: (Style) -> Unit = {}) {
        val m = map ?: return
        styleLoads.incrementAndGet()
        styleLoaded = false
        styleError = null
        fullyLoaded = false
        mapLibreIdle = false
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            styleLoaded = true
            enableLocation(style)
            onStyle(style)
        }
    }

    fun detach() {
        stopDeviceLocation()
        puck.uninstall()
        mapView = null
        map = null
        styleLoaded = false
    }

    /**
     * T123: why the activity cannot serve a route that needs it on screen, or null when it is started (visible):
     * `noActivity` (none yet, or destroyed), `finishing`, or `stopped (<lifecycle state>)` after the app went to the
     * background (HOME, another app on top). Read from any thread: the lifecycle state is a plain field.
     */
    fun backgroundReason(): String? {
        val act = activity ?: return "noActivity"
        if (act.isFinishing || act.isDestroyed) return "finishing"
        val owner = act as? androidx.lifecycle.LifecycleOwner ?: return null
        val state = owner.lifecycle.currentState
        return if (state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) null else "stopped ($state)"
    }

    /** Runs [block] on the main thread and waits for its result. */
    fun <T> onMain(timeoutMs: Long = 5000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return task.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun camera(): Map<String, Any?> = onMain {
        val m = map ?: return@onMain mapOf("ready" to false)
        val c = m.cameraPosition
        mapOf(
            "ready" to true,
            "lat" to c.target?.latitude, "lon" to c.target?.longitude,
            "zoom" to c.zoom, "bearing" to c.bearing, "tilt" to c.tilt,
        )
    }

    /** Moves the camera at once (no animation); null fields keep their current value. */
    fun setCamera(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?): Map<String, Any?> {
        onMain {
            val m = map ?: throw IllegalStateException("map not ready")
            endOpenWindow("cameraSet") // T162: a fix after this does not take the camera back
            val c = m.cameraPosition
            val target = LatLng(lat ?: c.target?.latitude ?: 0.0, lon ?: c.target?.longitude ?: 0.0)
            lastCameraAt = SystemClock.elapsedRealtime()
            lastFrameFully = false // until MapLibre renders the new view
            fullyLoaded = false
            mapLibreIdle = false
            m.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(target).zoom(zoom ?: c.zoom).bearing(bearing ?: c.bearing).tilt(tilt ?: c.tilt).build(),
                ),
            )
        }
        return camera()
    }

    /** Waits up to [timeoutMs] for the map to be idle; returns whether it got there. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        // Give MapLibre a moment to start the frames and requests a camera change triggers.
        Thread.sleep(150)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (idle) return true
            Thread.sleep(50)
        }
        return idle
    }

    /** The outcome of [stableSnapshot]. */
    class Stable(val bitmap: Bitmap, val idle: Boolean, val stable: Boolean, val rounds: Int, val changedShare: Double, val ms: Long) {
        fun json(): Map<String, Any?> = mapOf(
            "idle" to idle, "stable" to stable, "rounds" to rounds, "changedShare" to round4(changedShare), "ms" to ms,
        )
    }

    /**
     * Waits for [idle], then proves it with pixels: snapshots the map, asks for one more frame, waits for idle
     * again and snapshots once more, until two snapshots in a row agree (at most [STABLE_SHARE] of the sampled
     * pixels differ) or [timeoutMs] runs out. Returns the last snapshot. This is what `/screenshot` uses by
     * default: an idle flag alone once let a half-loaded map through (no hillshade, no labels).
     */
    fun stableSnapshot(timeoutMs: Long, snapshotTimeoutMs: Long = 10_000): Stable {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + timeoutMs
        var idleNow = awaitIdle(timeoutMs)
        var previous = mapSnapshot(snapshotTimeoutMs)
        var rounds = 0
        var share = 1.0
        while (true) {
            rounds++
            val before = framesRendered
            onMain { map?.triggerRepaint() }
            val frameDeadline = SystemClock.elapsedRealtime() + 1_000
            while (framesRendered == before && SystemClock.elapsedRealtime() < frameDeadline) Thread.sleep(20)
            Thread.sleep(STABLE_GAP_MS)
            idleNow = awaitIdle((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            val next = mapSnapshot(snapshotTimeoutMs)
            share = changedShare(previous, next)
            previous.recycle()
            previous = next
            if ((idleNow && share <= STABLE_SHARE) || SystemClock.elapsedRealtime() >= deadline || rounds >= MAX_STABLE_ROUNDS) break
        }
        val stable = idleNow && share <= STABLE_SHARE
        return Stable(previous, idleNow, stable, rounds, share, SystemClock.elapsedRealtime() - started)
    }

    /** Share of a grid of sampled pixels whose largest channel difference exceeds 32 (antialiasing noise stays below). */
    private fun changedShare(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        val step = maxOf(1, minOf(a.width, a.height) / 200)
        var n = 0
        var changed = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                n++
                if (p != q && maxOf(
                        Math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(q)),
                        Math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(q)),
                        Math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(q)),
                    ) > 32
                ) changed++
                x += step
            }
            y += step
        }
        return if (n == 0) 0.0 else changed.toDouble() / n
    }

    fun pngOf(bmp: Bitmap): ByteArray = png(bmp)

    /** PNG of the map surface (MapLibre snapshot: the GL content only, no Compose overlay). */
    fun mapSnapshotPng(timeoutMs: Long = 10_000): ByteArray = png(mapSnapshot(timeoutMs))

    private fun mapSnapshot(timeoutMs: Long): Bitmap {
        val result = AtomicReference<Bitmap?>()
        val latch = CountDownLatch(1)
        onMain {
            val m = map ?: throw IllegalStateException("map not ready")
            m.snapshot { bmp ->
                result.set(bmp)
                latch.countDown()
            }
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "snapshot timed out" }
        return result.get() ?: throw IllegalStateException("snapshot returned no bitmap")
    }

    /**
     * PNG of the activity's content area: the map plus everything drawn over it (attribution,
     * messages). A window PixelCopy cannot do this: the map draws into its own SurfaceView, a
     * separate surface, so the window copy has only a hole where the map is. Instead the map
     * surface is copied with PixelCopy (exactly what the screen shows, puck included; MapLibre's
     * snapshot if that fails) and the view hierarchy is drawn over it in software, where the
     * SurfaceView draws as transparent.
     */
    fun windowPng(timeoutMs: Long = 10_000): ByteArray {
        val act = activity ?: throw IllegalStateException("no activity")
        class Layout(val content: View, val surface: SurfaceView?, val left: Int, val top: Int, val w: Int, val h: Int)
        val layout = onMain {
            val content = act.findViewById<ViewGroup>(android.R.id.content)
            val mv = mapView ?: throw IllegalStateException("map not attached")
            val surface = findSurface(mv)
            val target = surface ?: mv
            val a = IntArray(2).also { target.getLocationInWindow(it) }
            val b = IntArray(2).also { content.getLocationInWindow(it) }
            Layout(content, surface, a[0] - b[0], a[1] - b[1], target.width, target.height)
        }
        val mapBitmap = layout.surface?.let { copySurface(it, layout.w, layout.h, timeoutMs) } ?: mapSnapshot(timeoutMs)
        return onMain {
            val content = layout.content
            val out = Bitmap.createBitmap(content.width.coerceAtLeast(1), content.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawARGB(255, 255, 255, 255)
            canvas.drawBitmap(Bitmap.createScaledBitmap(mapBitmap, layout.w, layout.h, true), layout.left.toFloat(), layout.top.toFloat(), null)
            // Overlays go to their own layer: SurfaceView.draw clears its rectangle (CLEAR mode), which
            // must punch through to the map pixels instead of erasing them.
            val overlay = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
            content.draw(Canvas(overlay))
            canvas.drawBitmap(overlay, 0f, 0f, null)
            png(out)
        }
    }

    private fun findSurface(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findSurface(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun copySurface(surface: SurfaceView, w: Int, h: Int, timeoutMs: Long): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val status = AtomicInteger(-1)
        onMain {
            PixelCopy.request(surface, bmp, { s ->
                status.set(s)
                latch.countDown()
            }, main)
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return bmp.takeIf { status.get() == PixelCopy.SUCCESS }
    }

    private fun png(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(1 shl 20)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun hasLocationPermission(): Boolean {
        val act = activity ?: return false
        return act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private val puck = LocationPuck()
    private var deviceListener: LocationListener? = null
    @Volatile private var deviceProviders: List<String> = emptyList()

    /** Adds the puck to a freshly loaded style and starts device fixes (unless fixes are being injected). */
    fun enableLocation(style: Style) {
        puck.uninstall()
        puck.install(style, lastLocation)
        startDeviceLocation()
    }

    /** Called when the user grants location permission after the style loaded. */
    fun onLocationPermissionChanged() {
        main.post { startDeviceLocation() }
    }

    /** Called on the main thread when the camera comes to rest (T41; set by the online service). */
    @Volatile var onCameraIdle: () -> Unit = {}

    /**
     * T110: called on the main thread when MapLibre reports the map idle (every visible tile loaded and drawn, no
     * transition running); set by the online service, which asks again which sources the view's tiles came from.
     */
    @Volatile var onMapIdle: () -> Unit = {}

    /** Called (main thread) when a fix seeded the units preference and they changed; set by [DystopoApp]. */
    @Volatile var onUnitsSeeded: () -> Unit = {}

    private fun onFix(loc: Location) {
        val shown = lastLocation
        lastLocation = loc
        val recorded = runCatching { LastFix.record(loc) }.getOrNull() // T162: the open view's last fix
        if (recorded != null) runCatching { openFix(recorded) }
        if (AppSettings.unitPreference.units == null && AppSettings.seedUnits(loc.latitude, loc.longitude, "fix")) onUnitsSeeded()
        // T41: the half-second redraw on a still map was this: GPS and network each report the same fix every
        // second, and every update of the puck's source makes MapLibre draw a frame. Skip fixes that would not
        // move the puck or change its halo.
        if (puck.installed && (shown == null || movesPuck(shown, loc))) {
            puck.show(loc)
            puckUpdates++
        } else {
            puckUpdatesSkipped++
        }
        runCatching { MapOrientation.onFix(loc) } // T100: the camera follows the puck; facing turns to the heading
    }

    @Volatile var puckUpdates = 0L
        private set
    @Volatile var puckUpdatesSkipped = 0L
        private set

    private fun movesPuck(a: Location, b: Location): Boolean =
        a.distanceTo(b) >= PUCK_MOVE_M || kotlin.math.abs(a.accuracy - b.accuracy) >= PUCK_MOVE_M

    /**
     * T150: the map's device fixes follow the app's foreground ([Foreground], from [MainActivity]'s `onStart` and
     * `onStop`): stopped in the background, where each fix ran the puck and follow mode on the main thread of a stopped map
     * once a second; started again at the next start (follow mode and the puck resume on the next fix). Track recording
     * (`tracking/TrackingService`, its own `LocationSource`) and navigation (`NavSession`'s own GPS listener) keep theirs.
     * Main thread.
     */
    fun setForeground(on: Boolean) {
        if (on) startDeviceLocation() else stopDeviceLocation()
    }

    /** Device fixes from GPS and network providers (main thread). No-op without permission, while injecting or in the background. */
    private fun startDeviceLocation() {
        if (injectedLocation || deviceListener != null || !Foreground.started || !hasLocationPermission()) return
        val lm = activity?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = io.github.jasondorjeshort.dystopo.app.tracking.locationListener { if (!injectedLocation) onFix(it) }
        // T114: every provider the phone has, enabled or not (Android delivers once it is enabled): with the location
        // setting off at start, the map never heard a fix after it was turned on until the app restarted.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { it in lm.allProviders }.getOrDefault(false)
        }
        try {
            for (p in providers) lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            deviceListener = listener
            deviceProviders = providers
        } catch (e: SecurityException) {
            styleError = "location: ${e.message}"
        }
    }

    private fun stopDeviceLocation() {
        val l = deviceListener ?: return
        (activity?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(l)
        deviceListener = null
        deviceProviders = emptyList()
    }

    /**
     * Injects a location fix (tests): the puck moves there as if the GPS had reported it. Device fixes
     * are ignored from then on (an emulator reports its own default position, which would move the
     * puck away) until [useDeviceLocation].
     */
    fun injectLocation(lat: Double, lon: Double, accuracy: Float, bearing: Float?): Map<String, Any?> {
        val loc = Location("dystopo-debug").apply {
            latitude = lat
            longitude = lon
            this.accuracy = accuracy
            bearing?.let { this.bearing = it }
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        injectedLocation = true
        onMain {
            stopDeviceLocation()
            onFix(loc)
        }
        return locationJson()
    }

    /** Ends injection: the puck follows the device's own fixes again (when permitted). */
    fun useDeviceLocation(): Map<String, Any?> {
        injectedLocation = false
        onMain { startDeviceLocation() }
        return locationJson()
    }

    fun locationJson(): Map<String, Any?> {
        val layers = runCatching {
            onMain { map?.style?.layers?.map { it.id }?.let { ids -> LocationPuck.LAYERS.associateWith { ids.indexOf(it) } } }
        }.getOrNull()
        val l = lastLocation
        return mapOf(
            "puckInstalled" to puck.installed,
            "puckUpdates" to puckUpdates, "puckUpdatesSkipped" to puckUpdatesSkipped, // T41: unchanged fixes skipped
            "puckLayerIndex" to layers,
            "permission" to hasLocationPermission(),
            "injected" to injectedLocation,
            "deviceProviders" to deviceProviders,
            "deviceListening" to (deviceListener != null), // T150: false in the background
            "foreground" to Foreground.started,
            "last" to l?.let { mapOf("lat" to it.latitude, "lon" to it.longitude, "accuracy" to it.accuracy, "provider" to it.provider) },
        )
    }

    // ---- T162: the open view (OpenView in MapViewport.kt) ----

    /** What the map opened at, and what happened to the fix window after it. Main thread writes; any thread reads. */
    private class Opened(val choice: OpenView.Choice, val atWall: Long, val atElapsed: Long, val windowMs: Long, val fix: LastFix.Fix?, val saved: ViewAt?, val pack: ViewAt?) {
        @Volatile var window = if (choice.source == OpenView.Source.DEEP_LINK || choice.source == OpenView.Source.RESTORED) "none" else "open"
        @Volatile var endedAfterMs: Long? = null
        @Volatile var recentredTo: Map<String, Any?>? = null
        @Volatile var fixesInWindow = 0
    }

    @Volatile private var opened: Opened? = null

    /** Where the app itself put the camera (the open view, its recentre, an activated pack's view): a rest there is not saved. */
    private var placed: ViewAt? = null

    /**
     * Called by [MainActivity] when it builds the map (main thread): what the camera opens at and the three candidates,
     * for `GET /camera` `openView`; starts the fix window ([OpenView.recentres]).
     */
    fun opened(choice: OpenView.Choice, fix: LastFix.Fix?, saved: ViewAt?, pack: ViewAt?) {
        val now = System.currentTimeMillis()
        opened = Opened(choice, now, SystemClock.elapsedRealtime(), OpenView.windowMs(choice.view, now), fix, saved, pack)
        placed = choice.view
    }

    /** A rest at this view is the app's own placement (`PackManager.jumpTo` for an activated pack), not saved. Main thread. */
    fun placedByApp(lat: Double, lon: Double, zoom: Double) {
        placed = ViewAt(lat, lon, zoom, 0.0, System.currentTimeMillis())
    }

    private fun endOpenWindow(why: String) {
        val o = opened ?: return
        if (o.window != "open") return
        o.window = why
        o.endedAfterMs = SystemClock.elapsedRealtime() - o.atElapsed
    }

    /** T162: a fix in the open window recentres the camera once when it is newer than the open view (main thread). */
    private fun openFix(fix: LastFix.Fix) {
        val o = opened ?: return
        if (o.window != "open") return
        val since = SystemClock.elapsedRealtime() - o.atElapsed
        if (since > o.windowMs) { endOpenWindow("expired"); return }
        o.fixesInWindow++
        if (!OpenView.recentres(o.choice.view, fix, since, System.currentTimeMillis())) return
        val m = map ?: return
        placed = ViewAt(fix.lat, fix.lon, m.cameraPosition.zoom, 0.0, fix.at)
        lastCameraAt = SystemClock.elapsedRealtime()
        m.moveCamera(CameraUpdateFactory.newLatLng(LatLng(fix.lat, fix.lon)))
        o.recentredTo = linkedMapOf("lat" to fix.lat, "lon" to fix.lon, "afterMs" to since)
        endOpenWindow("recentred")
    }

    /** T162: the camera came to rest (main thread): saved unless the app put it there; a move away ends the open window. */
    private fun atRest(m: MapLibreMap) {
        val c = m.cameraPosition
        val t = c.target ?: return
        val p = placed
        if (p != null && distanceM(p.lat, p.lon, t.latitude, t.longitude) < PLACED_TOLERANCE_M && kotlin.math.abs(p.zoom - c.zoom) < 0.01) return
        placed = null
        endOpenWindow("cameraMoved")
        SavedView.save(ViewAt(t.latitude, t.longitude, c.zoom, c.bearing, System.currentTimeMillis()))
    }

    /** `GET /camera` `openView`: which candidate won, the three candidates with their times, and the fix window. */
    fun openViewJson(): Map<String, Any?>? {
        val o = opened ?: return null
        fun view(v: ViewAt?) = v?.let { linkedMapOf("lat" to it.lat, "lon" to it.lon, "zoom" to it.zoom, "bearing" to it.bearing, "at" to it.at) }
        return linkedMapOf(
            "won" to o.choice.source.json,
            "openedAt" to o.atWall,
            "view" to view(o.choice.view),
            "fixAt" to o.fix?.movedAt, "savedAt" to o.saved?.at, "packAt" to o.pack?.at,
            "fix" to o.fix?.let { linkedMapOf("lat" to it.lat, "lon" to it.lon, "accuracy" to it.accuracyM, "at" to it.at, "movedAt" to it.movedAt) },
            "saved" to view(o.saved), "pack" to view(o.pack),
            "window" to linkedMapOf(
                "ms" to o.windowMs, "state" to o.window, "endedAfterMs" to o.endedAfterMs,
                "fixes" to o.fixesInWindow, "recentredTo" to o.recentredTo,
            ),
            "lastFix" to LastFix.json(),
            "savedNow" to view(SavedView.get()), "savedWrites" to SavedView.writes,
        )
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0].toDouble()
    }

    // ---- T93, T100: recentre (controls/Recenter, controls/MapOrientation) ----

    /** T100: our own camera moves (controls/MapOrientation) count as camera activity for [idle]. */
    fun noteCameraMove() {
        lastCameraAt = SystemClock.elapsedRealtime()
    }

    /** Screen distance (px) of the last fix from the map view's centre; null without a fix or a map. */
    fun fixOffsetPx(): Double? = onMain {
        val m = map ?: return@onMain null
        val v = mapView ?: return@onMain null
        val fix = lastLocation ?: return@onMain null
        val p = m.projection.toScreenLocation(LatLng(fix.latitude, fix.longitude))
        Math.hypot((p.x - v.width / 2f).toDouble(), (p.y - v.height / 2f).toDouble())
    }

    // ---- T61: camera modes for navigation; T100: a view of the app's one orientation state ----

    /**
     * FREE: the user moves the map (not following). FOLLOW: centred on each fix, north up (or as the user turned
     * it). HEADING_UP: centred and turned to the heading. Since T100 these are [MapOrientation]'s state seen from
     * navigation ([io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.cameraMode]); setting one sets it.
     */
    enum class CameraMode { FREE, FOLLOW, HEADING_UP }

    var cameraMode: CameraMode
        get() = MapOrientation.cameraMode
        set(v) { MapOrientation.setCameraMode(v, "nav") }

    val followMoves: Long get() = MapOrientation.followMoves

    /** Eases the camera to a fix when following, at [zoom] if given (navigation's first fix); the bearing follows the orientation. */
    @Suppress("UNUSED_PARAMETER")
    fun follow(lat: Double, lon: Double, headingDeg: Double?, zoom: Double? = null) = MapOrientation.followTo(lat, lon, zoom)

    companion object {
        /** T162: a rest this close to where the app put the camera (at the same zoom) is the app's placement, not saved. */
        const val PLACED_TOLERANCE_M = 1.0

        /** How long everything must stay settled before the map counts as idle. */
        const val QUIET_MS = 500L

        /** A fix closer than this to the shown one (and with an accuracy this close) does not redraw the puck (T41). */
        const val PUCK_MOVE_M = 0.5f

        /** A partial frame with nothing pending and no new frame for this long gets a repaint ([kickRepaint]). */
        const val STUCK_MS = 2_000L

        /** Pause between the two snapshots of [stableSnapshot] (symbol fades take 300 ms). */
        const val STABLE_GAP_MS = 350L

        /** Two snapshots agree when at most this share of the sampled pixels differ. */
        const val STABLE_SHARE = 0.002
        const val MAX_STABLE_ROUNDS = 8

        private val NATIVE_FIELD = runCatching {
            MapView::class.java.getDeclaredField("nativeMapView").apply { isAccessible = true }
        }.getOrNull()

        private fun round4(d: Double) = Math.round(d * 10_000) / 10_000.0
    }
}
