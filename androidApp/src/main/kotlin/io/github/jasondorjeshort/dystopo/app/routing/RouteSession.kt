package io.github.jasondorjeshort.dystopo.app.routing

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.util.concurrent.Executors

/**
 * The route being planned on the map: long-press once for the start, again for the end; the route is
 * planned in the background and drawn by [overlay]; a third long-press starts over. `POST /route` goes
 * through [plan] too, so the debug route and a user's long-presses end in the same state.
 * The Compose fields are written on the main thread only.
 */
object RouteSession {
    val overlay = RouteOverlay().apply { onLongPress = { lat, lon -> longPress(lat, lon) } }

    /** idle, start (waiting for the end), planning, route, failed. */
    var phase by mutableStateOf("idle")
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var mode by mutableStateOf(TravelMode.HIKE)
        private set
    var plan by mutableStateOf<PlannedRoute?>(null)
        private set

    @Volatile var start: LatLng? = null
        private set
    @Volatile var end: LatLng? = null
        private set
    @Volatile var failure: RouteResult.Failed? = null
        private set
    @Volatile var lastFile: File? = null
        private set
    @Volatile var longPresses = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-route-plan").apply { isDaemon = true } }

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** A long-press at ([lat], [lon]): the start, then the end (which plans the route), then a new start. */
    fun longPress(lat: Double, lon: Double) {
        longPresses++
        if (phase == "planning") return
        if (phase == "start" && start != null) {
            end = LatLng(lat, lon)
            val s = start!!
            val travel = mode
            worker.execute { runCatching { plan(s.latitude, s.longitude, lat, lon, travel, "Route", WAIT_MS) } }
            return
        }
        start = LatLng(lat, lon)
        end = null
        failure = null
        ui { phase = "start"; plan = null; message = "Long-press the end point" }
        overlay.show(null, start, null)
        RoutePlanner.ensure() // build or load the graph while the user picks the end
    }

    /** Plans from start to end and shows the result (blocking; never on the main thread). */
    fun plan(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, travel: TravelMode, name: String, waitMs: Long): RouteResult {
        start = LatLng(fromLat, fromLon)
        end = LatLng(toLat, toLon)
        ui { phase = "planning"; mode = travel; plan = null; message = "Planning..." }
        overlay.show(null, start, end)
        val result = try {
            RoutePlanner.plan(fromLat, fromLon, toLat, toLon, travel, name, waitMs)
        } catch (e: RoutePlanner.NotReady) {
            val s = e.state
            val why = when {
                s == null -> "this pack has no trail data (no vector archive)"
                s.status == "error" -> "the trail network could not be built: ${s.error}"
                else -> "the trail network is still being built (${s.json()["progress"] ?: s.status}); try again in a moment"
            }
            ui { phase = "failed"; message = why }
            throw e
        }
        when (result) {
            is RouteResult.Found -> {
                failure = null
                ui { phase = "route"; plan = result.plan; message = null }
                overlay.show(result.plan.route.points, null, null)
            }
            is RouteResult.Failed -> {
                failure = result
                ui { phase = "failed"; plan = null; message = result.message }
                overlay.show(null, start, end)
            }
        }
        return result
    }

    /** Plans the current start and end again in [travel] mode. */
    fun changeMode(travel: TravelMode) {
        val s = start
        val e = end
        ui { mode = travel }
        if (s != null && e != null && phase != "planning") {
            worker.execute { runCatching { plan(s.latitude, s.longitude, e.latitude, e.longitude, travel, plan?.route?.name ?: "Route", WAIT_MS) } }
        }
    }

    fun clear() {
        start = null
        end = null
        failure = null
        ui { phase = "idle"; plan = null; message = null }
        overlay.show(null, null, null)
    }

    /** Saves the current route in `files/routes/`; null when there is none. */
    fun save(name: String? = null): File? {
        val p = plan ?: return null
        val route = if (name.isNullOrBlank()) p.route else p.route.copy(name = name)
        return RoutePlanner.save(route).also { f ->
            lastFile = f
            ui { message = "Saved to routes/${f.name}" }
            Log.i(RoutePlanner.TAG, "saved route ${route.id} to ${f.path}")
        }
    }

    /** Writes the current route as GPX into `files/exports/`; null when there is none. */
    fun exportGpx(): File? {
        val p = plan ?: return null
        val dir = File(DystopoApp.instance.filesDir, "exports").apply { mkdirs() }
        val safe = p.route.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "route" }
        return File(dir, "route-$safe-${p.route.id.take(8)}.gpx").apply { writeText(RoutePlanner.gpx(p.route), Charsets.UTF_8) }.also { f ->
            lastFile = f
            ui { message = "GPX written to exports/${f.name}" }
        }
    }

    /** One line for the card: distance, climb, time, in the user's units. */
    fun summary(p: PlannedRoute): String {
        val u = AppSettings.units
        val climb = p.ascentM?.let { " · +${UnitFormat.elevation(it, u)} −${UnitFormat.elevation(p.descentM ?: 0.0, u)}" } ?: ""
        return "${UnitFormat.distance(p.distanceM, u)}$climb · ${PlannedRoute.hm(p.timeS)} ${p.mode.label}"
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "phase" to phase, "message" to message, "mode" to mode.label,
        "start" to start?.let { listOf(it.latitude, it.longitude) }, "end" to end?.let { listOf(it.latitude, it.longitude) },
        "longPresses" to longPresses, "overlayFeatures" to overlay.features, "overlayInstalled" to overlay.installedOnStyle,
        "summary" to plan?.let { summary(it) }, "lastFile" to lastFile?.path,
    )

    /** How long a long-press waits for the graph to be built on first use. */
    const val WAIT_MS = 120_000L
}
