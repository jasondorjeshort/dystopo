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

    /**
     * Why the map is not idle right now; empty when it is. The map is idle when the style is loaded,
     * no camera transition runs, no tile request is pending, the last frame was fully rendered, and
     * all of that has held for [QUIET_MS]. MapLibre's own idle event is not used: on the emulator it
     * fires rarely while the renderer keeps drawing frames at a few fps.
     */
    fun idleBlockers(now: Long = SystemClock.elapsedRealtime()): List<String> {
        val reasons = ArrayList<String>()
        if (map == null) reasons += "mapNotReady"
        if (!styleLoaded) reasons += "styleLoading"
        if (animating) reasons += "animating"
        val pending = pendingTiles()
        if (pending > 0) reasons += "pendingTiles"
        if (!lastFrameFully) reasons += "frameNotFullyRendered"
        val busyAt = maxOf(lastCameraAt, lastTileAt(), lastPartialFrameAt)
        if (reasons.isEmpty() && now - busyAt < QUIET_MS) reasons += "settling"
        return reasons
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
            "lastFrameFully" to lastFrameFully,
            "msSinceCamera" to if (lastCameraAt == 0L) null else now - lastCameraAt,
            "msSinceTile" to lastTileAt().takeIf { it > 0 }?.let { now - it },
            "msSincePartialFrame" to if (lastPartialFrameAt == 0L) null else now - lastPartialFrameAt,
            "quietMs" to QUIET_MS,
            "mapLibreIdle" to mapLibreIdle,
        )
    }

    fun attach(view: MapView, styleJson: () -> String, onStyle: (Style) -> Unit = {}) {
        mapView = view
        view.addOnWillStartRenderingFrameListener { mapLibreIdle = false }
        view.addOnDidFinishRenderingFrameListener { fully, _, _ ->
            framesRendered++
            lastFrameFully = fully
            if (!fully) lastPartialFrameAt = SystemClock.elapsedRealtime()
        }
        view.addOnDidBecomeIdleListener {
            mapLibreIdle = true
        }
        view.addOnDidFailLoadingMapListener { styleError = it }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = false // drawn by the Compose overlay from the packs' metadata
            m.uiSettings.isLogoEnabled = false
            m.setMinZoomPreference(3.0)
            m.setMaxZoomPreference(18.0)
            m.addOnCameraMoveStartedListener { animating = true; lastCameraAt = SystemClock.elapsedRealtime() }
            m.addOnCameraIdleListener { animating = false; lastCameraAt = SystemClock.elapsedRealtime() }
            loadStyle(styleJson(), onStyle)
        }
    }

    fun loadStyle(json: String, onStyle: (Style) -> Unit = {}) {
        val m = map ?: return
        styleLoaded = false
        styleError = null
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
            val c = m.cameraPosition
            val target = LatLng(lat ?: c.target?.latitude ?: 0.0, lon ?: c.target?.longitude ?: 0.0)
            lastCameraAt = SystemClock.elapsedRealtime()
            lastFrameFully = false // until MapLibre renders the new view
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

    private fun onFix(loc: Location) {
        lastLocation = loc
        if (puck.installed) puck.show(loc)
    }

    /** Device fixes from GPS and network providers (main thread). No-op without permission or while injecting. */
    private fun startDeviceLocation() {
        if (injectedLocation || deviceListener != null || !hasLocationPermission()) return
        val lm = activity?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = LocationListener { if (!injectedLocation) onFix(it) }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
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
            "puckLayerIndex" to layers,
            "permission" to hasLocationPermission(),
            "injected" to injectedLocation,
            "deviceProviders" to deviceProviders,
            "last" to l?.let { mapOf("lat" to it.latitude, "lon" to it.longitude, "accuracy" to it.accuracy, "provider" to it.provider) },
        )
    }

    companion object {
        /** How long everything must stay settled before the map counts as idle. */
        const val QUIET_MS = 500L
    }
}
