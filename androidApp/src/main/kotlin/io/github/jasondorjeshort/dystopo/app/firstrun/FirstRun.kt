package io.github.jasondorjeshort.dystopo.app.firstrun

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.OnlineRoutes
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.app.packs.PacksUi
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.math.PI
import kotlin.math.cos

/**
 * T79: the first run on a real phone. A fresh install has no pack and no server; the map then shows the
 * first-run card ([FirstRunCard]) with the three ways to get a map, each reachable through the UI alone:
 * - "Download the map around me": the phone's location (the last fix, else the last known location, else
 *   waiting up to [LOCATION_WAIT_MS] for one) and a box of [HALF_SIDE_KM] around it handed to T38's region
 *   download from the configured server (`POST /build`, then the pack downloader); T80: without a server the
 *   phone builds the pack itself from the public sources ([io.github.jasondorjeshort.dystopo.app.online.LocalPackBuild]).
 * - T80 "Show the map around me" ([showAroundMe]): the camera to the location, the map read live from the
 *   sources (or the server), nothing saved.
 * - "Choose from server": the pack list with T53's "From server" section open.
 * - "Import pack files": the system file picker ([ImportPicker]) for a `-dem.pmtiles` and an optional
 *   `-outdoors.pmtiles` ([PackManager.importFiles]).
 * The server itself is set and tested in Settings › Sources ([Sources]).
 *
 * Debug routes in [FirstRunRoutes]: `GET /firstrun`, `POST /firstrun/download`, `POST /firstrun/test`, `POST /firstrun/dismiss`.
 */
object FirstRun {
    /** "Not now" on the card: hidden for this session (the plain no-pack message shows instead). */
    var dismissed by mutableStateOf(false)

    /** A line under the card's buttons (what the last action did or why it could not). */
    var message by mutableStateOf<String?>(null)

    /** The "around me" step before the region download takes over: idle, locating, starting, started, failed. */
    @Volatile var aroundMe: String = "idle"
        private set
    @Volatile var lastBox: PackBounds? = null
        private set
    @Volatile var lastFix: Map<String, Any?>? = null
        private set

    const val HALF_SIDE_KM = 8.0
    const val LOCATION_WAIT_MS = 30_000L

    /** True while no pack is open on the map (what the card is for). */
    fun noPack(app: DystopoApp = DystopoApp.instance): Boolean = app.tiles.packs.regions.isEmpty()

    /** Starts "Download the map around me" on a background thread ([lat]/[lon]: debug override of the fix). */
    fun downloadAroundMe(lat: Double? = null, lon: Double? = null): Map<String, Any?> {
        val app = DystopoApp.instance
        val online = OnlineService.get(app)
        if (online.fetcher == null) {
            // T80: only when the phone's own source reading is off (no server and direct sources disabled).
            message = "Set your Dystopo server in ${Screens.SETTINGS} › ${Screens.SOURCES} first, then tap Download again."
            app.map.onMain { SettingsUi.open(io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout.SERVER_GROUP) } // T103: the page with the server field
            return json()
        }
        val region = online.region
        if (region.running || aroundMe == "locating") return json()
        aroundMe = "locating"
        message = "Finding your location..."
        Thread({
            try {
                val fix = if (lat != null && lon != null) Triple(lat, lon, "given") else waitForFix(app)
                    ?: throw IllegalStateException(if (hasPermission(app)) "No location yet: go outside or turn on location, then try again." else "Location permission is off: allow it for Dystopo, or choose a pack from the server.")
                lastFix = mapOf("lat" to fix.first, "lon" to fix.second, "source" to fix.third)
                val box = boxAround(fix.first, fix.second, HALF_SIDE_KM)
                lastBox = box
                aroundMe = "starting"
                runCatching { app.map.setCamera(fix.first, fix.second, 11.0, 0.0, 0.0) }
                region.start(box, null, null, null)
                aroundMe = "started"
                message = null
            } catch (e: Exception) {
                Log.w(PackManager.TAG, "download around me failed", e)
                aroundMe = "failed"
                message = e.message ?: e.javaClass.simpleName
            }
        }, "dystopo-firstrun").apply { isDaemon = true }.start()
        return json()
    }

    /**
     * T80: "Show the map around me": the camera goes to the phone's location at [SHOW_ZOOM] and the card steps
     * aside for the session; with no server and no pack the map there comes straight from the public sources
     * ([io.github.jasondorjeshort.dystopo.app.online.DirectSources]), nothing is saved for offline use.
     */
    fun showAroundMe(lat: Double? = null, lon: Double? = null): Map<String, Any?> {
        val app = DystopoApp.instance
        if (aroundMe == "locating") return json()
        aroundMe = "locating"
        message = "Finding your location..."
        Thread({
            try {
                val fix = if (lat != null && lon != null) Triple(lat, lon, "given") else waitForFix(app)
                    ?: throw IllegalStateException(if (hasPermission(app)) "No location yet: go outside or turn on location, then try again." else "Location permission is off: allow it for Dystopo, or import pack files.")
                lastFix = mapOf("lat" to fix.first, "lon" to fix.second, "source" to fix.third)
                app.map.setCamera(fix.first, fix.second, SHOW_ZOOM, 0.0, 0.0)
                aroundMe = "shown"
                message = null
                app.map.onMain { dismissed = true }
            } catch (e: Exception) {
                Log.w(PackManager.TAG, "show around me failed", e)
                aroundMe = "failed"
                message = e.message ?: e.javaClass.simpleName
            }
        }, "dystopo-firstrun-show").apply { isDaemon = true }.start()
        return json()
    }

    /** Map zoom of "Show the map around me" (DEM tiles one zoom deeper: 3DEP 1 m lidar is read from z13 tiles). */
    const val SHOW_ZOOM = 12.0

    fun chooseFromServer() {
        // Without a server the pack list's "From server" status says so and links to Settings › Sources.
        PacksUi.catalogOpen = true
        PacksUi.fitRequest++
        PacksUi.visible = true
    }

    /** A square box of 2 x [halfKm] around a point, for the region download. */
    fun boxAround(lat: Double, lon: Double, halfKm: Double): PackBounds {
        val dLat = halfKm / 110.57
        val dLon = halfKm / (111.32 * cos(lat * PI / 180).coerceAtLeast(0.05))
        return PackBounds(lon - dLon, (lat - dLat).coerceAtLeast(-85.0), lon + dLon, (lat + dLat).coerceAtMost(85.0))
    }

    private fun hasPermission(app: DystopoApp) =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** The map's last fix, else the newest last-known location, else the first fix within [LOCATION_WAIT_MS]. */
    private fun waitForFix(app: DystopoApp): Triple<Double, Double, String>? {
        val deadline = System.currentTimeMillis() + LOCATION_WAIT_MS
        while (true) {
            app.map.lastLocation?.let { return Triple(it.latitude, it.longitude, "fix") }
            lastKnown(app)?.let { return Triple(it.latitude, it.longitude, "lastKnown:${it.provider}") }
            if (!hasPermission(app) || System.currentTimeMillis() > deadline) return null
            Thread.sleep(500)
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // checked just below; a SecurityException is caught too
    private fun lastKnown(app: DystopoApp): Location? {
        if (!hasPermission(app)) return null
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return runCatching {
            lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull()
    }

    fun json(): Map<String, Any?> {
        val app = DystopoApp.instance
        val m = PackManager.get(app)
        return linkedMapOf(
            "noPack" to noPack(app),
            "dismissed" to dismissed,
            "cardVisible" to FirstRunCardState.visible,
            "server" to AppSettings.server.ifEmpty { null },
            "direct" to OnlineService.get(app).isDirect, // T80: no server, the phone reads the sources itself
            "message" to message,
            "aroundMe" to aroundMe, "fix" to lastFix,
            "box" to lastBox?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
            "region" to OnlineService.get(app).region.json(),
            "import" to m.importState,
            "serverTest" to Sources.test,
            "assets" to BundledAssets.last, // T80
            "packs" to m.list().map { it.info.name },
        )
    }

    /** Sets the server like `POST /settings?server=` (validated, [CleartextPolicy] included); error message or null. Call off the main thread. */
    fun setServer(value: String): String? = OnlineRoutes.setServer(DystopoApp.instance, value)
}

/** What the card last drew (for `GET /firstrun`). */
internal object FirstRunCardState {
    @Volatile var visible = false
}
