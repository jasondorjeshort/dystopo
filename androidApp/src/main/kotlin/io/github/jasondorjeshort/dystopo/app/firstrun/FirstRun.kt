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
import io.github.jasondorjeshort.dystopo.app.areas.AreaDownload
import io.github.jasondorjeshort.dystopo.app.areas.AreaPicker
import io.github.jasondorjeshort.dystopo.app.areas.aboutSize
import io.github.jasondorjeshort.dystopo.app.areas.regionArea
import io.github.jasondorjeshort.dystopo.app.online.RegionDownload
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * T79: the first run on a real phone. A fresh install has no pack and no server; the map then shows the
 * first-run card ([FirstRunCard]) with the ways to get a map, each with one sentence and one of them primary
 * ([firstRunChoices], T220, the survey `first-run-and-share.md`), each reachable through the UI alone:
 * - "Download the map around me": the phone's location (the last fix, else the last known location, else
 *   waiting up to [LOCATION_WAIT_MS] for one) and a box of [HALF_SIDE_KM] around it handed to T38's region
 *   download from the configured server (`POST /build`, then the pack downloader); T80: without a server the
 *   phone builds the pack itself from the public sources ([io.github.jasondorjeshort.dystopo.app.online.LocalPackBuild]).
 * - T80 "Just look at the map" ([showAroundMe]; "Show the map around me" until T220): the camera to the location, the map read live from the
 *   sources (or the server), nothing saved.
 * - "Choose from server": the pack list with T53's "From server" section open.
 * - "Import pack files": the system file picker ([ImportPicker]) for a `-dem.pmtiles` and an optional
 *   `-outdoors.pmtiles` ([PackManager.importFiles]).
 * The server itself is set and tested in Settings › Sources ([Sources]).
 *
 * The location permission is asked when the app starts ([io.github.jasondorjeshort.dystopo.app.MainActivity]); a tap
 * without it says why and what else there is.
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

    /**
     * T220: what "Download the map around me" would bring, for the card's "Uses about N MB of data": the phone's own plan of
     * the 16 km box at the fix it already has, tiles and trail graph, as "Download this region" sizes a pack. Null with no
     * fix or no source: the card then names no size. No wait for a fix and no permission asked for this.
     */
    @Volatile var estimateBytes: Long? = null
        private set
    @Volatile private var estimateKey: String? = null
    @Volatile private var estimateTriedAt = 0L
    private val estimating = AtomicBoolean(false)
    private const val ESTIMATE_EVERY_MS = 5_000L

    /** Off the UI thread and at most every [ESTIMATE_EVERY_MS]; the card calls it while it is up. */
    fun refreshEstimate(app: DystopoApp = DystopoApp.instance) {
        val now = System.currentTimeMillis()
        if (now - estimateTriedAt < ESTIMATE_EVERY_MS || !estimating.compareAndSet(false, true)) return
        estimateTriedAt = now
        Thread({
            try {
                val server = OnlineService.get(app).fetcher?.server
                val fix = app.map.lastLocation ?: lastKnown(app)
                if (server == null) estimateBytes = null
                if (server != null && fix != null) {
                    val key = "$server ${(fix.latitude * 100).roundToInt()} ${(fix.longitude * 100).roundToInt()}" // about a kilometre
                    if (key != estimateKey) {
                        val plan = AreaDownload.plan(regionArea(boxAround(fix.latitude, fix.longitude, HALF_SIDE_KM), RegionDownload.DEFAULT_MAX_ZOOM, null), withLessDetail = false)
                        estimateBytes = plan.bytes + AreaPicker.graphBytesOf(plan)
                        estimateKey = key
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(PackManager.TAG, "first-run estimate failed", e)
                estimateBytes = null
            } finally {
                estimating.set(false)
            }
        }, "dystopo-firstrun-size").apply { isDaemon = true }.start()
    }

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
                    ?: throw IllegalStateException(if (hasPermission(app)) "No location yet: go outside or turn on location, then try again." else "Location permission is off: allow it for Dystopo, or ${if (AppSettings.server.isNotEmpty()) "choose a pack from the server" else "import map files you have"}.")
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
     * T80: "Just look at the map": the camera goes to the phone's location at [SHOW_ZOOM] and the card steps
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

    /** Map zoom of "Just look at the map" (DEM tiles one zoom deeper: 3DEP 1 m lidar is read from z13 tiles). */
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
        val online = OnlineService.get(app)
        val choices = firstRunChoices(AppSettings.server, online.isDirect, estimateBytes, AppSettings.units)
        return linkedMapOf(
            "noPack" to noPack(app),
            "dismissed" to dismissed,
            "cardVisible" to FirstRunCardState.visible, "cardYieldedTo" to FirstRunCardState.yieldedTo, // T106
            // T220: what the card draws, in its order: the state's sentence, the choices, the primary one, the way back.
            "body" to firstRunBody(AppSettings.server, online.isDirect, if (online.isDirect) online.direct.notice() else null),
            "choices" to choices.map { it.toMap() },
            "primary" to choices.single { it.weight == ChoiceWeight.FILLED }.id,
            "notNowLine" to NOT_NOW_LINE,
            "estimateBytes" to estimateBytes,
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

/** T220: how much a choice on the card asks for attention: filled (the primary one), outlined, or a text action. */
internal enum class ChoiceWeight(val json: String) { FILLED("filled"), OUTLINED("outlined"), TEXT("text") }

/** T220: one way to a map on the first-run card, with the one sentence that says what it does. */
internal data class FirstRunChoice(val id: String, val label: String, val sentence: String, val weight: ChoiceWeight) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "label" to label, "sentence" to sentence, "weight" to weight.json, "primary" to (weight == ChoiceWeight.FILLED))
}

/** T220: what "Not now" means for later, under the card's links. */
internal const val NOT_NOW_LINE = "You can download later from ${Screens.OFFLINE_MAPS}."

/**
 * T220 (the survey `first-run-and-share.md`, the critic's objection 16): the card's choices in order. The size is said
 * only when [sizeBytes] is known and names no network type (ADR 0023); the box's width follows the units setting
 * (ADR 0022). "Choose from server" is the server flow's entry, so it shows when a server is set. Pure, tested.
 */
internal fun firstRunChoices(server: String, direct: Boolean, sizeBytes: Long?, units: ContourUnits): List<FirstRunChoice> = buildList {
    val width = if (units == ContourUnits.FEET) "${(FirstRun.HALF_SIDE_KM * 2 / 1.609344).roundToInt()} mi" else "${(FirstRun.HALF_SIDE_KM * 2).roundToInt()} km"
    val size = sizeBytes?.let { " Uses about ${aboutSize(it)} of data." } ?: ""
    add(FirstRunChoice("download", "Download the map around me", "Saves about $width around you on this phone so it works without signal.$size", ChoiceWeight.FILLED))
    if (direct || server.isNotEmpty()) add(FirstRunChoice("look", "Just look at the map", "Moves the map to where you are. It loads over the network as you look and is not saved.", ChoiceWeight.OUTLINED))
    if (server.isNotEmpty()) add(FirstRunChoice("server", "Choose from server", "Pick from the maps your server already has.", ChoiceWeight.TEXT))
    add(FirstRunChoice("import", "Import pack files", "Use map files you already have.", ChoiceWeight.TEXT))
}

/**
 * T220: the card's one sentence for each of its four states; the provider names moved to Settings › Sources (the credit
 * line on the map still names every source in view), [notice] is ADR 0023's. Pure, tested.
 */
internal fun firstRunBody(server: String, direct: Boolean, notice: String?): String = when {
    server.isNotEmpty() -> "Server: $server"
    direct && notice != null -> notice
    direct -> "The map comes straight from public sources, named in ${Screens.SETTINGS} › ${Screens.SOURCES}."
    else -> "No server set: set one in ${Screens.SETTINGS} › ${Screens.SOURCES} to download maps, or import map files you have."
}

/** What the card last drew (for `GET /firstrun`). */
internal object FirstRunCardState {
    @Volatile var visible = false
    /** T106: why the card is not shown although it would be (`bottomCards`: a trip, a route or navigation owns the bottom). */
    @Volatile var yieldedTo: String? = null
}
