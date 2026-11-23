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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import io.github.jasondorjeshort.dystopo.app.firstrun.FirstRun
import io.github.jasondorjeshort.dystopo.app.firstrun.MapMessage
import io.github.jasondorjeshort.dystopo.app.launch.AppLaunch
import io.github.jasondorjeshort.dystopo.launch.Gate
import io.github.jasondorjeshort.dystopo.launch.Launch
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

    /** T219: the system's save dialog, for Export GPX ([SaveFile]). */
    val saveFile = registerForActivityResult(SaveFile.contract()) { SaveFile.finished(it) }

    // T228: StartupTrace.mark names each step of the launch; a step lasts until the next mark (marks only, nothing moved).
    // T266 (ADR 0061 step 1): the steps are the bodies of the activity's declared stages, run by AppLaunch.runner here, in the
    // same order (a later activity of the same process runs them straight).
    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.activityCreated()
        super.onCreate(savedInstanceState)
        StartupRoutes.stallPoint("activityOnCreate")
        val launch = AppLaunch.runner
        launch.run(Launch.VIEWPORT) {
            StartupTrace.mark("MapViewport.prepare")
            MapViewport.prepare() // T162: its Compose state, on the main thread before the first composition
        }
        launch.awaitGate(Gate.BEFORE_ACTIVITY) // ADR 0061: the main thread's one wait, bounded and traced (nothing to wait for while those stages run on it)
        val start = launch.run(Launch.CAMERA) {
            StartupTrace.mark("startCamera")
            startCamera(savedInstanceState)
        }
        launch.run(Launch.MAPVIEW) {
            StartupTrace.mark("MapView")
            mapView = MapView(this, MapLibreMapOptions.createFromAttributes(this).camera(start).textureMode(false))
            StartupTrace.mark("mapView.onCreate")
            mapView.onCreate(savedInstanceState)
            StartupTrace.mark("map.attach")
            app.map.activity = this
            // T347: a mark per prepare and one before the attach, so the step's growth is attributed (`map.attach: *`). Only the
            // state the first composition reads is made here; the rest after the first frame ([prepareAfterFirstFrame]).
            StartupTrace.mark("map.attach: OrientationUi")
            io.github.jasondorjeshort.dystopo.app.controls.OrientationUi.prepare() // T100: its Compose state, on the main thread
            StartupTrace.mark("map.attach: HorizonUi")
            io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi.prepare() // T307, T326: whether a sky view is open (the first composition asks)
            StartupTrace.mark("map.attach: ViewSwitch")
            io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.prepare() // T315: the switch's anchor state (both views read it)
            StartupTrace.mark("map.attach: AircraftUiState")
            io.github.jasondorjeshort.dystopo.app.aircraft.AircraftUiState.prepare() // T311: the Flights layer's Compose state (its ticker reads it)
            StartupTrace.mark("map.attach: MapController.attach")
            app.map.attach(mapView, styleJson = { app.tiles.style() })
            afterFirstFrame { prepareAfterFirstFrame() } // T347
        }
        launch.run(Launch.ACTIVITY) { links() }
    }

    /**
     * T347: Compose state that only an open sky view reads, made on the main thread after the first frame ([afterFirstFrame]),
     * out of the launch's `map.attach`. Nothing opens a sky view before then (a tap, Settings or a debug route does; no link),
     * and the first composition reads only [io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi], made above.
     * `ComposeStatePrepareTest` counts a name here as prepared.
     */
    private fun prepareAfterFirstFrame() {
        io.github.jasondorjeshort.dystopo.app.gnss.GnssUi.prepare() // T307, T308: the Horizon view's own Compose state
        io.github.jasondorjeshort.dystopo.app.panorama.PanoramaUi.prepare() // T326: the Panorama's aim
        io.github.jasondorjeshort.dystopo.app.panorama.PanoramaData.prepare() // T326: its sweeps' revision
    }

    /** The `activity` stage's body (T266): links and intents, the content, the permission request. */
    private fun links() {
        StartupTrace.mark("links and intents")
        DeepLink.install(this, mapView) // T55: links that arrive while the map is open move the camera
        io.github.jasondorjeshort.dystopo.app.share.ShareLink.handle(intent) // T57: dystopo://share links
        addOnNewIntentListener { io.github.jasondorjeshort.dystopo.app.share.ShareLink.handle(it) }
        io.github.jasondorjeshort.dystopo.app.tracking.TrackNotification.handle(intent) // T159: the recording notification's tap
        addOnNewIntentListener { io.github.jasondorjeshort.dystopo.app.tracking.TrackNotification.handle(it) }

        // Dark status-bar icons: the map, the pack list and the sheets are all light (paper) under the bar.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        StartupTrace.mark("setContent")
        setContent {
            StartupTrace.composing("first composition begins") // T228
            // Follow pack switches, downloads and /reload: DystopoApp swaps its TileService, so the attribution
            // line and the no-pack message are recomposed from the current one.
            // T144: the credit line is made off the UI thread (a pack's attribution is its metadata, read once from the
            // file under the pack's read lock, which the tile threads hold); the composable only reads the result.
            var attribution by remember { mutableStateOf("Dystopo") }
            // T332: what the map says about its maps ([MapMessage]): asked again every half second, since the view moves,
            // the packs reopen and areas finish downloading; the boxes are in memory and the camera is read here, on the UI thread.
            var message by remember { mutableStateOf<MapMessage?>(FirstRun.mapMessage(app, null)) }
            LaunchedEffect(Unit) {
                var current: TileService? = null
                while (true) {
                    val t = app.tiles
                    if (t !== current) {
                        current = t
                        attribution = withContext(Dispatchers.Default) { attribution(t) }
                    }
                    val view = runCatching { io.github.jasondorjeshort.dystopo.app.online.OnlineView.currentOnMain(app)?.bounds }.getOrNull()
                    val m = FirstRun.mapMessage(app, view)
                    FirstRun.lastView = view
                    FirstRun.lastMessage = m
                    message = m
                    Foreground.delay(500) // T150: paused while the app is in the background
                }
            }
            MapScreen(mapView, attribution, message)
        }

        StartupTrace.mark("permissions")
        val wanted = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (wanted.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) permissionRequest.launch(wanted)
    }

    /**
     * T162: the camera the map opens at ([OpenView]): a `dystopo://map` link first (T55), then the camera MapLibre
     * restores for a recreated activity, else the newest of the last fix, the saved view and the primary pack's view
     * (its box fitted to the screen, as activating it shows), else a world view. Tells [MapController.opened].
     */
    private fun startCamera(savedInstanceState: Bundle?): CameraPosition {
        DeepLink.camera(intent)?.let { link ->
            val t = link.target
            app.map.opened(OpenView.Choice(OpenView.Source.DEEP_LINK, t?.let { ViewAt(it.latitude, it.longitude, link.zoom, link.bearing, System.currentTimeMillis()) }), null, null, null)
            StartupTrace.mark("view from link") // T228: the source's name, never a coordinate
            return link
        }
        val fix = runCatching { LastFix.get() }.getOrNull()
        val saved = runCatching { SavedView.get() }.getOrNull()
        val pack = runCatching { packView() }.getOrNull()
        val choice = if (savedInstanceState != null) OpenView.Choice(OpenView.Source.RESTORED, null) else OpenView.choose(fix, saved, pack)
        app.map.opened(choice, fix, saved, pack)
        StartupTrace.mark("view from ${choice.source.name.lowercase()}") // T228: the source's name, never a coordinate
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
        StartupTrace.started()
        super.onStart()
        AppLaunch.runner.run(Launch.START) {
            StartupTrace.mark("mapView.onStart")
            mapView.onStart()
            StartupTrace.mark("MapOrientation.setForeground")
            io.github.jasondorjeshort.dystopo.app.controls.MapOrientation.setForeground(true) // T100: facing's compass and turns
            StartupTrace.mark("startDeviceLocation")
            if (Foreground.activityStarted()) app.map.setForeground(true) // T150: the polling loops resume; device fixes again
        }
    }

    override fun onResume() {
        StartupTrace.resumed()
        super.onResume()
        AppLaunch.runner.run(Launch.RESUME) {
            StartupTrace.mark("mapView.onResume")
            mapView.onResume()
        }
        MainWatchdog.resumed() // T144: the UI-thread watchdog ticks while the activity is in the foreground
    }

    override fun onPause() {
        MainWatchdog.paused()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        StartupTrace.stopped()
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

        /** T235 (ADR 0056): in its words; the list is "Offline maps". */
        const val NO_PACK = "No offline map here. Download the map around you or choose one from your server (set it in Settings › Sources), or import one (Offline maps)."
    }
}

@Composable
private fun MapScreen(mapView: MapView, attribution: String, message: MapMessage?) {
    // T332: Recentre, the scale bar and the view switch hide only on a phone that holds no map with nothing online.
    val controls = message?.hidesControls != true
    // T228: StartupTrace.composing names the first composition's steps, so a long splash is attributed (marks only, until the first frame).
    Box(Modifier.fillMaxSize()) {
        StartupTrace.composing("compose the map view")
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        StartupTrace.composing("compose TrackControls")
        io.github.jasondorjeshort.dystopo.app.tracking.TrackControls() // T31: record/stop, tracks list, track overlay
        StartupTrace.composing("compose RouteControls")
        io.github.jasondorjeshort.dystopo.app.routing.RouteControls() // T45: long-press route planning, route overlay and card
        StartupTrace.composing("compose TripControls")
        io.github.jasondorjeshort.dystopo.app.trips.TripControls() // T60: Routes list, trip card, route editor overlay
        io.github.jasondorjeshort.dystopo.app.activity.ActivityChipOverlay() // T263: the current activity's chip, under Routes
        StartupTrace.composing("compose AttributionLine")
        // T315: beside the "Map | Horizon" switch at its right, inside the same insets as the switch's anchor. T326 (wave 45's
        // choice (h)): beside the three segments with 240 dp of room there, else above the switch across the window
        // (`OverlayLayout.creditAbove`, the layout rule's own test), its line cap kept either way.
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize().navigationBarsPadding().windowInsetsPadding(io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.anchorInsets),
        ) {
            val start = io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.creditStartDp
            val above = start > 0f && io.github.jasondorjeshort.dystopo.ui.OverlayLayout.creditAbove((maxWidth - start.dp - 12.dp).value)
            AttributionLine(
                attribution,
                Modifier.align(Alignment.BottomStart).padding(
                    start = 6.dp + (if (above) 0f else start).dp, top = 6.dp, end = 6.dp,
                    bottom = 6.dp + (if (above) io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.reserveDp else 0f).dp,
                ),
            )
        }
        StartupTrace.composing("compose OnlineOverlay")
        io.github.jasondorjeshort.dystopo.app.online.OnlineOverlay() // T38: online indicator, Download this region
        StartupTrace.composing("compose SearchOverlay")
        io.github.jasondorjeshort.dystopo.app.search.SearchOverlay() // T39: place search box
        StartupTrace.composing("compose NavOverlay")
        io.github.jasondorjeshort.dystopo.app.nav.NavOverlay() // T61: navigation stats bar, off-route banner
        StartupTrace.composing("compose PressureCard")
        io.github.jasondorjeshort.dystopo.app.readout.PressureCard() // T178: the pressure-fall warning's card, under the search box and the nav card
        StartupTrace.composing("compose RecenterButton and ScaleBar")
        if (controls) io.github.jasondorjeshort.dystopo.app.controls.RecenterButton() // T93, T100: recentre and orientation, lower right, under the cards and sheets below
        if (controls) io.github.jasondorjeshort.dystopo.app.controls.ScaleBar() // T101: scale bar lower left, above the credit line and the bottom panels
        if (controls) io.github.jasondorjeshort.dystopo.app.controls.MapViewSwitch() // T309, T315: "Map | Horizon" at its anchor, the bottom-left corner (`controls/ViewSwitch.kt`)
        StartupTrace.composing("compose FirstRunCard")
        io.github.jasondorjeshort.dystopo.app.firstrun.FirstRunCard(message) // T79: the first-run card (no map held), else the no-map line; T332: or the outside line
        StartupTrace.composing("compose UpdateOverlay")
        io.github.jasondorjeshort.dystopo.app.update.UpdateOverlay() // T83: "Update to <version>" (Install, Not now) or why an update was refused
        io.github.jasondorjeshort.dystopo.app.aircraft.AircraftOverlay() // T311: the Flights layer's card (over the bottom controls) and status line
        StartupTrace.composing("compose TracksScreen")
        io.github.jasondorjeshort.dystopo.app.tracks.TracksScreen() // T262: Tracks and routes, full screen over the map's own controls (T219's two sheets before)
        StartupTrace.composing("compose SettingsSheet")
        SettingsSheet() // T33: units, text size, warm-up
        StartupTrace.composing("compose StorageScreen and SyncStatusSheet")
        io.github.jasondorjeshort.dystopo.app.storage.StorageScreen() // T74: what each store uses, budgets, Clear
        io.github.jasondorjeshort.dystopo.app.sync.SyncStatusSheet() // T51: sync status
        StartupTrace.composing("compose PacksOverlay and AreasOverlay")
        io.github.jasondorjeshort.dystopo.app.packs.PacksOverlay() // T29: pack list button and screen
        io.github.jasondorjeshort.dystopo.app.areas.AreasOverlay() // T75: offline areas (from the pack list), outlines on the map
        StartupTrace.composing("compose ShareOverlay and QrScannerOverlay")
        io.github.jasondorjeshort.dystopo.app.share.ShareOverlay() // T54: Share screen (LAN listener + NSD while open)
        io.github.jasondorjeshort.dystopo.app.share.QrScannerOverlay() // T57: the Share screen's QR scanner
        io.github.jasondorjeshort.dystopo.app.horizon.HorizonScreen() // T307, T308: the Horizon view (Settings > Navigation and tracking > Horizon view)
        io.github.jasondorjeshort.dystopo.app.panorama.PanoramaScreen() // T326: the Panorama view, the switch's third segment
        io.github.jasondorjeshort.dystopo.app.skylist.SkyListScreen() // T344: the List view, the switch's fourth segment
        StartupTrace.composing("compose GlanceView and HelpHost")
        io.github.jasondorjeshort.dystopo.app.readout.GlanceView() // T210: the glance view (a tap on the readout bar), over every screen but help
        io.github.jasondorjeshort.dystopo.app.help.HelpHost() // T109, T115: the open help pop-up, last so it is over every screen above
        SideEffect { StartupTrace.composing("composition applied: layout and draw") }
    }
}
