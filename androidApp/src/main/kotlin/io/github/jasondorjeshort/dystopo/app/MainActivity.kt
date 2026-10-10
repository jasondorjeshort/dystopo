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
import kotlinx.coroutines.delay
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
        val tiles = app.tiles
        val dem = tiles.packs.dem?.header
        // Start over the middle of the DEM pack (a public test area such as Rainier), else a world view.
        val start = DeepLink.camera(intent) ?: if (dem != null) { // T55: dystopo://map?lat=&lon=&z= wins
            CameraPosition.Builder()
                .target(LatLng((dem.minLat + dem.maxLat) / 2, (dem.minLon + dem.maxLon) / 2))
                .zoom(12.0).build()
        } else CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(1.0).build()
        mapView = MapView(this, MapLibreMapOptions.createFromAttributes(this).camera(start).textureMode(false))
        mapView.onCreate(savedInstanceState)
        app.map.activity = this
        app.map.attach(mapView, styleJson = { app.tiles.style() })
        DeepLink.install(this, mapView) // T55: links that arrive while the map is open move the camera

        // Dark status-bar icons: the map, the pack list and the sheets are all light (paper) under the bar.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        setContent {
            // Follow pack switches, downloads and /reload: DystopoApp swaps its TileService, so the attribution
            // line and the no-pack message are recomposed from the current one.
            var current by remember { mutableStateOf(app.tiles) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(500)
                    if (app.tiles !== current) current = app.tiles
                }
            }
            MapScreen(mapView, attribution(current), if (current.packs.dem == null && current.online == null) NO_PACK else null) // T38: no message in online mode
        }

        val wanted = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (wanted.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) permissionRequest.launch(wanted)
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
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
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
        const val NO_PACK = "No region pack. Download one from Packs, or push one with tools/android-push-pack.ps1."
    }
}

@Composable
private fun MapScreen(mapView: MapView, attribution: String, message: String?) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        io.github.jasondorjeshort.dystopo.app.tracking.TrackControls() // T31: record/stop, tracks list, track overlay
        io.github.jasondorjeshort.dystopo.app.routing.RouteControls() // T45: long-press route planning, route overlay and card
        if (message != null) {
            BasicText(
                message,
                style = TextStyle(color = Color(0xFF2B2420), fontSize = 18.sp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(Color(0xEEFFFCF5), RoundedCornerShape(8.dp))
                    .padding(16.dp),
            )
        }
        AttributionLine(attribution, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(6.dp))
        io.github.jasondorjeshort.dystopo.app.online.OnlineOverlay() // T38: online indicator, Download this region
        io.github.jasondorjeshort.dystopo.app.search.SearchOverlay() // T39: place search box
        SettingsSheet() // T33: units, text size, warm-up
        io.github.jasondorjeshort.dystopo.app.packs.PacksOverlay() // T29: pack list button and screen
    }
}
