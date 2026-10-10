package io.github.jasondorjeshort.dystopo.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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

    /** True once MapLibre reports the map idle (all tiles loaded, no transition) and no frame has started since. */
    @Volatile var idle = false
        private set
    @Volatile var lastIdleAt = 0L
        private set
    @Volatile var framesRendered = 0L
        private set
    @Volatile var lastLocation: Location? = null
        private set

    fun attach(view: MapView, styleJson: () -> String, onStyle: (Style) -> Unit = {}) {
        mapView = view
        view.addOnWillStartRenderingFrameListener { idle = false }
        view.addOnDidFinishRenderingFrameListener { _, _, _ -> framesRendered++ }
        view.addOnDidBecomeIdleListener {
            idle = true
            lastIdleAt = SystemClock.elapsedRealtime()
        }
        view.addOnDidFailLoadingMapListener { styleError = it }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = false // drawn by the Compose overlay from the packs' metadata
            m.uiSettings.isLogoEnabled = false
            m.setMinZoomPreference(3.0)
            m.setMaxZoomPreference(18.0)
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
            idle = false
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
        // Give MapLibre a moment to start the frames a camera change triggers before trusting `idle`.
        Thread.sleep(150)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (idle && styleLoaded) return true
            Thread.sleep(50)
        }
        return idle && styleLoaded
    }

    /** PNG of the map surface (MapLibre snapshot: the GL content only, no Compose overlay). */
    fun mapSnapshotPng(timeoutMs: Long = 10_000): ByteArray {
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
        return png(result.get() ?: throw IllegalStateException("snapshot returned no bitmap"))
    }

    /** PNG of the whole activity window (map plus overlays) through PixelCopy. */
    fun windowPng(timeoutMs: Long = 10_000): ByteArray {
        val act = activity ?: throw IllegalStateException("no activity")
        val window = act.window
        val view = window.decorView
        val bmp = onMain { Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888) }
        val latch = CountDownLatch(1)
        var status = -1
        onMain {
            PixelCopy.request(window, bmp, { s ->
                status = s
                latch.countDown()
            }, main)
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "PixelCopy timed out" }
        check(status == PixelCopy.SUCCESS) { "PixelCopy failed with status $status" }
        return png(bmp)
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

    /** Shows the location puck; with permission it follows the device's location engine. */
    fun enableLocation(style: Style) {
        val m = map ?: return
        val ctx = mapView?.context ?: return
        val granted = hasLocationPermission()
        try {
            val lc = m.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(ctx, style).useDefaultLocationEngine(granted).build(),
            )
            lc.isLocationComponentEnabled = true
            lc.cameraMode = CameraMode.NONE
            lc.renderMode = RenderMode.NORMAL
            lastLocation?.let { lc.forceLocationUpdate(it) }
        } catch (e: SecurityException) {
            styleError = "location: ${e.message}"
        }
    }

    /** Called when the user grants location permission after the style loaded. */
    fun onLocationPermissionChanged() {
        main.post {
            map?.style?.let { if (it.isFullyLoaded) enableLocation(it) }
        }
    }

    /** Injects a location fix (tests): the puck moves there as if the GPS had reported it. */
    fun injectLocation(lat: Double, lon: Double, accuracy: Float, bearing: Float?): Map<String, Any?> {
        val loc = Location("dystopo-debug").apply {
            latitude = lat
            longitude = lon
            this.accuracy = accuracy
            bearing?.let { this.bearing = it }
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        lastLocation = loc
        onMain {
            val m = map ?: return@onMain
            val lc = m.locationComponent
            if (lc.isLocationComponentActivated) lc.forceLocationUpdate(loc)
        }
        return locationJson()
    }

    fun locationJson(): Map<String, Any?> {
        val active = runCatching { onMain { map?.locationComponent?.isLocationComponentActivated == true } }.getOrDefault(false)
        val l = lastLocation
        return mapOf(
            "componentActive" to active,
            "permission" to hasLocationPermission(),
            "last" to l?.let { mapOf("lat" to it.latitude, "lon" to it.longitude, "accuracy" to it.accuracy, "provider" to it.provider) },
        )
    }
}
