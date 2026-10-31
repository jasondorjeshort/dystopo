package io.github.jasondorjeshort.dystopo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jasondorjeshort.dystopo.app.about.AttributionLine
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * The map screen: a MapLibre [MapView] showing the region pack through the loopback server, with an
 * attribution line from the packs' metadata. The MapView lifecycle follows this activity.
 */
class MainActivity : ComponentActivity() {
    private lateinit var mapView: MapView
    private val app get() = application as DystopoApp

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.map.onLocationPermissionChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapViewport.prepare() // T162: its Compose state, on the main thread before the first composition
        val start = startCamera(savedInstanceState)
        mapView = MapView(this, MapLibreMapOptions.createFromAttributes(this).camera(start).textureMode(false))
        mapView.onCreate(savedInstanceState)
        app.map.activity = this
        io.github.jasondorjeshort.dystopo.app.controls.OrientationUi.prepare() // T100: its Compose state, on the main thread
        app.map.attach(mapView, styleJson = { app.tiles.style() })
        DeepLink.install(this, mapView) // T55: links that arrive while the map is open move the camera
        io.github.jasondorjeshort.dystopo.app.share.ShareLink.handle(intent) // T57: dystopo://share links
        addOnNewIntentListener { io.github.jasondorjeshort.dystopo.app.share.ShareLink.handle(it) }
        io.github.jasondorjeshort.dystopo.app.tracking.TrackNotification.handle(intent) // T159: the recording notification's tap
        addOnNewIntentListener { io.github.jasondorjeshort.dystopo.app.tracking.TrackNotification.handle(it) }

        // Dark status-bar icons: the map, the pack list and the sheets are all light (paper) under the bar.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        setContent {
            // Follow pack switches, downloads and /reload: DystopoApp swaps its TileService, so the attribution
            // line and the no-pack message are recomposed from the current one.
            // T144: the credit line is made off the UI thread (a pack's attribution is its metadata, read once from the
            // file under the pack's read lock, which the tile threads hold); the composable only reads the result.
            var shown by remember { mutableStateOf(Shown("Dystopo", app.tiles.let { t -> if (t.packs.dem == null && t.online == null) NO_PACK else null })) }
            LaunchedEffect(Unit) {
                var current: TileService? = null
                while (true) {
                    val t = app.tiles
                    if (t !== current) {
                        current = t
                        shown = withContext(Dispatchers.Default) { Shown(attribution(t), if (t.packs.dem == null && t.online == null) NO_PACK else null) } // T38: no message in online mode
                    }
                    Foreground.delay(500) // T150: paused while the app is in the background
                }
            }
            MapScreen(mapView, shown.attribution, shown.message)
        }

        val wanted = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (wanted.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) permissionRequest.launch(wanted)
    }

    private class Shown(val attribution: String, val message: String?)

    /**
     * T162: the camera the map opens at ([OpenView]): a `dystopo://map` link first (T55), then the camera MapLibre
     * restores for a recreated activity, else the newest of the last fix, the saved view and the primary pack's view
     * (its box fitted to the screen, as activating it shows), else a world view. Tells [MapController.opened].
     */
    private fun startCamera(savedInstanceState: Bundle?): CameraPosition {
        DeepLink.camera(intent)?.let { link ->
            val t = link.target
            app.map.opened(OpenView.Choice(OpenView.Source.DEEP_LINK, t?.let { ViewAt(it.latitude, it.longitude, link.zoom, link.bearing, System.currentTimeMillis()) }), null, null, null)
            return link
        }
        val fix = runCatching { LastFix.get() }.getOrNull()
        val saved = runCatching { SavedView.get() }.getOrNull()
        val pack = runCatching { packView() }.getOrNull()
        val choice = if (savedInstanceState != null) OpenView.Choice(OpenView.Source.RESTORED, null) else OpenView.choose(fix, saved, pack)
        app.map.opened(choice, fix, saved, pack)
        val v = choice.view ?: ViewAt(0.0, 0.0, WORLD_ZOOM, 0.0, 0L)
        return CameraPosition.Builder().target(LatLng(v.lat, v.lon)).zoom(v.zoom).bearing(v.bearing).build()
    }

    /** The primary pack's view: its box fitted to the screen (as `PackManager.jumpTo` shows it), at its activation time. */
    private fun packView(): ViewAt? {
        val packs = app.tiles.packs
        val b = io.github.jasondorjeshort.dystopo.packs.PackBounds.of(packs.dem?.header ?: return null)
        val m = resources.displayMetrics
        val zoom = b.fitZoom(m.widthPixels / m.density.toDouble(), m.heightPixels / m.density.toDouble(), 512.0, 24.0).coerceIn(3.0, 16.0)
        return ViewAt(b.centerLat, b.centerLon, zoom, 0.0, io.github.jasondorjeshort.dystopo.app.PackView.activatedAt(packs.name))
    }

    private fun attribution(tiles: TileService): String {
        val packs = tiles.packs
        // Pack metadata holds HTML (`&copy; OpenStreetMap`); the overlay shows plain text. T41: every pack on the map.
        return (packs.osmPacks + packs.demPacks).map { it.attribution }
            .map(RequestChecks::htmlToText)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" | ")
            .ifEmpty { "Dystopo" }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        io.github.jasondorjeshort.dystopo.app.controls.MapOrientation.setForeground(true) // T100: facing's compass and turns
        if (Foreground.activityStarted()) app.map.setForeground(true) // T150: the polling loops resume; device fixes again
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        MainWatchdog.resumed() // T144: the UI-thread watchdog ticks while the activity is in the foreground
    }

    override fun onPause() {
        MainWatchdog.paused()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        SavedView.flush() // T162: a view still waiting for its write
        io.github.jasondorjeshort.dystopo.app.controls.MapOrientation.setForeground(false) // T100: no compass in the background
        if (Foreground.activityStopped()) app.map.setForeground(false) // T150: no device fixes and no polling in the background
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        app.map.detach()
        if (app.map.activity === this) app.map.activity = null
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        /** T79: the three ways to a map, all in the app (the first-run card has them as buttons). */
        /** T162: the world view with no pack, no fix and no saved view (the map's minimum zoom). */
        const val WORLD_ZOOM = 3.0

        const val NO_PACK = "No region pack. Download the map around you or choose a pack from your server (set it in Settings › Sources), or import pack files (Packs)."
    }
}

@Composable
private fun MapScreen(mapView: MapView, attribution: String, message: String?) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        io.github.jasondorjeshort.dystopo.app.tracking.TrackControls() // T31: record/stop, tracks list, track overlay
        io.github.jasondorjeshort.dystopo.app.routing.RouteControls() // T45: long-press route planning, route overlay and card
        io.github.jasondorjeshort.dystopo.app.trips.TripControls() // T60: Routes list, trip card, route editor overlay
        AttributionLine(attribution, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(6.dp))
        io.github.jasondorjeshort.dystopo.app.online.OnlineOverlay() // T38: online indicator, Download this region
        io.github.jasondorjeshort.dystopo.app.search.SearchOverlay() // T39: place search box
        io.github.jasondorjeshort.dystopo.app.nav.NavOverlay() // T61: navigation stats bar, off-route banner
        io.github.jasondorjeshort.dystopo.app.readout.PressureCard() // T178: the pressure-fall warning's card, under the search box and the nav card
        if (message == null) io.github.jasondorjeshort.dystopo.app.controls.RecenterButton() // T93, T100: recentre and orientation, lower right, under the cards and sheets below
        if (message == null) io.github.jasondorjeshort.dystopo.app.controls.ScaleBar() // T101: scale bar lower left, above the credit line and the bottom panels
        io.github.jasondorjeshort.dystopo.app.firstrun.FirstRunCard(message) // T79: the first-run card (no pack), else the no-pack line
        io.github.jasondorjeshort.dystopo.app.update.UpdateOverlay() // T83: "Update to <version>" (Install, Not now) or why an update was refused
        SettingsSheet() // T33: units, text size, warm-up
        io.github.jasondorjeshort.dystopo.app.storage.StorageScreen() // T74: what each store uses, budgets, Clear
        io.github.jasondorjeshort.dystopo.app.sync.SyncStatusSheet() // T51: sync status
        io.github.jasondorjeshort.dystopo.app.packs.PacksOverlay() // T29: pack list button and screen
        io.github.jasondorjeshort.dystopo.app.areas.AreasOverlay() // T75: offline areas (from the pack list), outlines on the map
        io.github.jasondorjeshort.dystopo.app.share.ShareOverlay() // T54: Share screen (LAN listener + NSD while open)
        io.github.jasondorjeshort.dystopo.app.share.QrScannerOverlay() // T57: the Share screen's QR scanner
        io.github.jasondorjeshort.dystopo.app.help.HelpHost() // T109, T115: the open help pop-up, last so it is over every screen above
    }
}
