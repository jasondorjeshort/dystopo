package io.github.jasondorjeshort.dystopo.app.trips

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Pack
import io.github.jasondorjeshort.dystopo.app.TileService
import io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner
import io.github.jasondorjeshort.dystopo.app.sync.ShareLinkClient
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.model.RouteEditor
import io.github.jasondorjeshort.dystopo.model.RouteGpx
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.store.RouteImport
import io.github.jasondorjeshort.dystopo.sync.EntityKind
import io.github.jasondorjeshort.dystopo.sync.RouteListItem
import io.github.jasondorjeshort.dystopo.sync.SyncStore
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.File
import java.util.concurrent.Executors

/**
 * Trips on the phone (T60): planned routes as synced entities in the track store's operation log
 * ([SyncStore.putRoutePlan], `path` and `waypoints` fields), the Routes list, and the route editor on
 * the map. Store calls run on the tracking thread ([Tracking.call]) like every other store access;
 * edits (which route through the trail graph) run on their own worker, so the UI never waits on A*.
 *
 * The editor uses the active pack's trail graph ([RoutePlanner.graph], built on first use) and the
 * DEM pack for the elevation of straight legs; with no graph every leg is straight. Compose state is
 * written on the main thread only.
 */
object Trips {
    private val app: DystopoApp get() = DystopoApp.instance
    private val tracking: Tracking get() = Tracking.of(app)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-trips").apply { isDaemon = true } }
    private val ids = UuidGenerator()

    val overlay = TripOverlay().apply {
        onDragEnd = { i, lat, lon -> editingId?.let { id -> worker.execute { runCatching { moveVia(id, i, lat, lon) }.onFailure { fail(it) } } } }
        onTap = { lat, lon -> tap(lat, lon) }
    }

    /** The Routes list screen is open. */
    var listOpen by mutableStateOf(false)
    var list by mutableStateOf<List<RouteListItem>>(emptyList())
        private set
    /** The route open in the editor and its summary. */
    var editing by mutableStateOf<RoutePlan?>(null)
        private set
    var summary by mutableStateOf<RouteSummary?>(null)
        private set
    /** What a tap on the map does while a route is open: `via` (add a via point), `waypoint`, or `none` (drag only). */
    var tool by mutableStateOf("none")
    var message by mutableStateOf<String?>(null)
        private set

    /** T95: the Offline button's offer for one trip: the size of its corridor before anything downloads. */
    class OfflineOffer(val tripId: String, val text: String, val canDownload: Boolean, val plan: io.github.jasondorjeshort.dystopo.app.areas.AreaDownload.Plan? = null) {
        fun json(): Map<String, Any?> = linkedMapOf("tripId" to tripId, "text" to text, "canDownload" to canDownload, "plan" to plan?.json())
    }

    /** T95: the offer the trip card shows under its actions (Download and Cancel, or Close when it does not fit). */
    var offline by mutableStateOf<OfflineOffer?>(null)
        private set
    /** The last edit: what it did, how long, and why legs are straight. */
    @Volatile var lastEdit: Map<String, Any?>? = null
        private set
    @Volatile var busy = false
        private set

    val editingId: String? get() = editing?.id

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun <T> store(block: (SyncStore) -> T): T = tracking.call { block(tracking.store as SyncStore) }

    // ---- the editor's inputs ----

    @Volatile private var dem: Pair<String, ElevationSampler>? = null

    /** A DEM sampler on the active DEM pack (its own small tile cache), or null without one. */
    private fun elevation(): ElevationSampler? {
        val p: Pack = app.tiles.packs.dem ?: return null
        dem?.takeIf { it.first == p.file.path }?.let { return it.second }
        val pyramid = DemPyramid(p.header.minZoom, p.header.maxZoom, TileService.demDecoder(), cacheTiles = 16) { z, x, y -> p.tile(z, x, y) }
        val sampler = DemElevation(pyramid)
        val lock = Any()
        val safe = ElevationSampler { lat, lon -> synchronized(lock) { sampler.elevation(lat, lon) } }
        dem = p.file.path to safe
        return safe
    }

    /** T91: the active DEM pack's elevation at a point (metres, one decimal), or null without a DEM or data there. */
    fun elevationAt(lat: Double, lon: Double): Double? =
        elevation()?.elevation(lat, lon)?.takeUnless { it.isNaN() }?.let { Math.round(it * 10) / 10.0 }

    /** An editor on the active pack's graph (waits up to [waitMs] for it to load or build; straight legs without one). */
    fun editor(waitMs: Long = 120_000): RouteEditor {
        val graph = try { RoutePlanner.router(waitMs) } catch (e: RoutePlanner.NotReady) { null }
        return RouteEditor(graph, elevation())
    }

    // ---- reading ----

    /** Small profiles for the Routes list, by route id (computed while the list is open). */
    var listProfiles by mutableStateOf<Map<String, List<io.github.jasondorjeshort.dystopo.gpx.ProfileSample>>>(emptyMap())
        private set

    fun refresh() {
        val items = store { it.routeList() }
        val readId = editingId
        val open = readId?.let { id -> store { it.routePlan(id) } }
        val profiles = if (listOpen) store { it.routePlans() }.associate { it.id to RouteSummary.of(it, 40).profile } else null
        ui {
            list = items
            profiles?.let { listProfiles = it }
            // T91 (the T65 race): apply the open route only if it is still the one this refresh read. A route
            // opened (or closed) after the read is newer than this answer, and its opener refreshes again.
            if (refreshApplies(readId, editingId)) {
                showPlan(open)
                overlay.show(open)
            }
        }
    }

    private fun showPlan(p: RoutePlan?) {
        // T91 (the T77 follow-up): a route opened in the editor replaces the T45 planned route and its card, which
        // otherwise stayed under the trip card (and its line on the map). Keep it first to have it as a trip.
        if (p != null && editing?.id != p.id && io.github.jasondorjeshort.dystopo.app.routing.RouteSession.phase != "idle") {
            io.github.jasondorjeshort.dystopo.app.routing.RouteSession.clear()
        }
        editing = p
        summary = p?.let { RouteSummary.of(it) }
        if (p == null) tool = "none"
    }

    fun plan(id: String): RoutePlan? = store { it.routePlan(id) }

    // ---- changes ----

    /** Creates a route through [points] (snapped to trails, legs routed), stores it and opens it. */
    fun create(points: List<Pair<Double, Double>>, name: String, mode: String = "hike", waitMs: Long = 120_000): RoutePlan {
        val t0 = System.nanoTime()
        val ed = editor(waitMs)
        val path = ed.create(points, mode)
        val plan = RoutePlan(ids.next(), name, path, type = mode)
        store { it.putRoutePlan(plan, System.currentTimeMillis()) }
        note("create", t0, ed)
        return opened(plan.id)
    }

    /** Keeps the T45 planned route as a trip: its start and end become via points, its line one trail leg. */
    fun keep(p: PlannedRoute, name: String = p.route.name): RoutePlan {
        val path = RoutePath.planned(p.route.points, p.mode.label, p.timeS) // T89: a drive keeps the router's time
        val plan = RoutePlan(p.route.id, name, path, type = p.mode.label, description = p.route.description)
        store { it.putRoutePlan(plan, System.currentTimeMillis()) }
        return opened(plan.id)
    }

    private fun edit(id: String, what: String, change: (RouteEditor, RoutePath) -> RoutePath): RoutePlan {
        val t0 = System.nanoTime()
        busy = true
        try {
            val before = plan(id) ?: throw NoSuchElementException("no route $id")
            val ed = editor()
            val path = change(ed, before.path)
            check(store { it.setRoutePath(id, path, System.currentTimeMillis()) }) { "route $id is gone" }
            note(what, t0, ed)
            return opened(id)
        } finally {
            busy = false
        }
    }

    fun moveVia(id: String, index: Int, lat: Double, lon: Double) = edit(id, "move $index") { ed, p -> ed.moveVia(p, index, lat, lon) }
    fun insertVia(id: String, index: Int, lat: Double, lon: Double) = edit(id, "insert $index") { ed, p -> ed.insertVia(p, index, lat, lon) }
    fun addVia(id: String, lat: Double, lon: Double) = edit(id, "add") { ed, p -> ed.addVia(p, lat, lon).first }
    fun removeVia(id: String, index: Int) = edit(id, "remove $index") { ed, p -> ed.removeVia(p, index) }
    fun setMode(id: String, mode: String) = edit(id, "mode $mode") { ed, p -> ed.setMode(p, mode) }
    fun reroute(id: String) = edit(id, "reroute") { ed, p -> ed.reroute(p) }

    /**
     * T106 (a T89 follow-up): Re-plan on a drive planned before T89 ([untimedDriveLegs]): its trail and straight legs
     * routed again in the same mode, so each routed leg gets the router's per-road time (fixed legs, imported lines,
     * stay as they are). A leg the router cannot route now stays straight, as in any edit.
     */
    fun replan(id: String) = edit(id, "replan") { ed, p -> ed.setMode(p, p.mode) }

    fun addWaypoint(id: String, lat: Double, lon: Double, name: String, symbol: String? = null): Waypoint {
        val w = Waypoint(ids.next(), name, lat, lon, elevation()?.elevation(lat, lon)?.takeUnless { it.isNaN() }?.let { Math.round(it * 10) / 10.0 }, System.currentTimeMillis(), symbol = symbol)
        val p = plan(id) ?: throw NoSuchElementException("no route $id")
        check(store { it.setRouteWaypoints(id, p.waypoints + w, System.currentTimeMillis()) })
        opened(id)
        return w
    }

    fun removeWaypoint(id: String, waypointId: String): Boolean {
        val p = plan(id) ?: return false
        if (p.waypoint(waypointId) == null) return false
        store { it.setRouteWaypoints(id, p.waypoints.filter { w -> w.id != waypointId }, System.currentTimeMillis()) }
        opened(id)
        return true
    }

    fun rename(id: String, name: String): Boolean = store { it.setField(EntityKind.ROUTE, id, "name", name, System.currentTimeMillis()) }.also { refresh() }

    fun delete(id: String): Boolean {
        val ok = store { it.deleteRoute(id, System.currentTimeMillis()) }
        if (editingId == id) close()
        refresh()
        return ok
    }

    fun import(text: String, replace: Boolean = false): RouteImport.Result =
        store { RouteImport.import(it, text, System.currentTimeMillis(), replace = replace) }.also { r ->
            r.imported.lastOrNull()?.let { opened(it.id) } ?: refresh()
        }

    fun gpx(id: String): String? = plan(id)?.let(RouteGpx::gpx)

    /**
     * T67: a read-only share link for route [id] on the sync server ([ShareLinkClient]: syncs first so the
     * server has the route as it is now), copied to the clipboard. Returns the server's answer plus
     * `copied` (the clipboard holds the URL afterwards).
     */
    fun shareLink(id: String): Map<String, Any?> {
        plan(id) ?: throw NoSuchElementException("no route $id")
        ui { message = "Making a share link…" }
        val r = ShareLinkClient.of(app).create(id)
        val url = r["url"] as String
        val copied = copyToClipboard("Dystopo trip", url)
        ui { message = (if (copied) "Share link copied: " else "Share link: ") + url }
        return r + mapOf("copied" to copied)
    }

    /** T67: revokes the last share link made for route [id] on this device. */
    fun revokeShareLink(id: String): Map<String, Any?> =
        ShareLinkClient.of(app).revoke(id).also { r -> ui { message = if (r["revoked"] == true) "Share link revoked" else "Share link was already gone" } }

    /** Puts [text] on the clipboard (on the main thread) and reads it back; true when it is there. */
    private fun copyToClipboard(label: String, text: String): Boolean {
        val done = java.util.concurrent.CompletableFuture<Boolean>()
        main.post {
            done.complete(
                runCatching {
                    val cm = app.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
                    // Reading back works while the app has focus (Android 10+); otherwise trust the set.
                    cm.primaryClip?.getItemAt(0)?.text?.toString()?.let { it == text } ?: true
                }.getOrElse { Log.w(TAG, "clipboard", it); false },
            )
        }
        return runCatching { done.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
    }

    /**
     * T95: the Offline button. Works out the route's corridor ([io.github.jasondorjeshort.dystopo.app.areas.AreaRoutes.tripArea]:
     * the mode's zooms, z8 to z13 for a drive, z8 to z15 otherwise) and its manifest, and shows its size in the card
     * with Download and Cancel, or, when the offline areas' budget would be passed, the size and why it does not fit
     * with Close. Nothing is downloaded or recorded until Download ([confirmOffline]).
     */
    fun planOffline(id: String): Map<String, Any?> {
        val (tripId, area) = io.github.jasondorjeshort.dystopo.app.areas.AreaRoutes.tripArea(id)
        ui { offline = OfflineOffer(tripId, OFFLINE_WORKING, false); message = null }
        val plan = try {
            io.github.jasondorjeshort.dystopo.app.areas.AreaDownload.plan(area)
        } catch (e: Exception) {
            Log.w(TAG, "offline plan failed", e)
            val o = OfflineOffer(tripId, OFFLINE_FAILED + (e.message ?: e.javaClass.simpleName), false)
            ui { offline = o }
            return o.json()
        }
        val o = OfflineOffer(tripId, offlineOfferText(plan.bytes, plan.tiles, plan.refusal), plan.refusal == null, plan)
        ui { offline = o }
        return o.json()
    }

    /** T95: Download in the offer: starts the planned download (the budget is checked again first). */
    fun confirmOffline(id: String): Map<String, Any?> {
        val o = offline?.takeIf { it.canDownload && it.plan != null && (it.tripId == id || (id.length >= 4 && it.tripId.startsWith(id))) }
            ?: throw IllegalStateException("no offline offer for this route that fits: tap Offline first")
        ui { offline = null }
        return downloadOffline(o.tripId, o.plan!!.manifest)
    }

    /** T95: Cancel or Close in the offer. */
    fun cancelOffline() = ui { offline = null }

    /**
     * T75: "Download for offline": the route's corridor (500 m each side, z8 to z15; T91: z8 to z13 for a drive) as an
     * area of interest, fetched into the tile cache and pinned ([io.github.jasondorjeshort.dystopo.app.areas.AreaDownload]);
     * progress in the card's message line and `GET /areas/download`. T95: [manifest] is the planned one, when there is one.
     */
    fun downloadOffline(id: String, manifest: io.github.jasondorjeshort.dystopo.areas.AreaManifest? = null): Map<String, Any?> {
        val (tripId, area) = io.github.jasondorjeshort.dystopo.app.areas.AreaRoutes.tripArea(id)
        val r = io.github.jasondorjeshort.dystopo.app.areas.AreaDownload.start(area, area.name, tripId, manifest = manifest)
        ui { message = "Downloading the route for offline use…" }
        Thread({
            while (true) {
                Thread.sleep(1000)
                val j = io.github.jasondorjeshort.dystopo.app.areas.AreaDownload.job ?: break
                val done = !j.running
                val text = if (!done) "Offline download: ${j.done.get()} of ${j.planned.get()} tiles" else when (j.state) {
                    "done" -> "Ready offline: ${j.planned.get()} tiles, ${String.format(java.util.Locale.US, "%.1f", j.bytes.get() / 1_048_576.0)} MB fetched"
                    else -> "Offline download ${j.state}" + (j.error?.let { ": $it" } ?: "")
                }
                ui { if (editingId == tripId) message = text }
                if (done) break
            }
        }, "trip-offline-progress").apply { isDaemon = true }.start()
        return r
    }

    /** Writes the route's GPX to `files/exports/`. */
    fun export(id: String): File? {
        val p = plan(id) ?: return null
        val dir = File(app.filesDir, "exports").apply { mkdirs() }
        val safe = p.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(40).ifEmpty { "route" }
        return File(dir, "trip-$safe-${p.id.take(8)}.gpx").apply { writeText(RouteGpx.gpx(p), Charsets.UTF_8) }.also { f ->
            ui { message = "GPX written to exports/${f.name}" }
        }
    }

    // ---- UI ----

    /** Opens route [id] in the editor (and shows it on the map). */
    fun open(id: String, fit: Boolean = true): RoutePlan? {
        val p = plan(id) ?: return null
        ui {
            showPlan(p)
            listOpen = false
            message = null
            if (offline?.tripId != p.id) offline = null // T95
            if (fit) fitCamera(p)
        }
        overlay.show(p)
        refresh()
        return p
    }

    private fun opened(id: String): RoutePlan {
        val p = plan(id) ?: throw NoSuchElementException("no route $id")
        val first = editingId != id
        ui { showPlan(p); listOpen = false; if (first) fitCamera(p) }
        overlay.show(p)
        refresh()
        return p
    }

    fun close() {
        ui { showPlan(null); message = null; offline = null }
        overlay.show(null)
    }

    private fun tap(lat: Double, lon: Double): Boolean {
        val id = editingId ?: return false
        when (tool) {
            "via" -> worker.execute { runCatching { addVia(id, lat, lon) }.onFailure { fail(it) } }
            "waypoint" -> worker.execute { runCatching { addWaypoint(id, lat, lon, "Waypoint ${(editing?.waypoints?.size ?: 0) + 1}") }.onFailure { fail(it) } }
            else -> return false
        }
        return true
    }

    /** Runs [block] on the editor's worker (UI actions). */
    fun background(block: () -> Unit) = worker.execute { runCatching(block).onFailure { fail(it) } }

    /** Waits until everything queued on the editor's worker (a drag's reroute) has run. */
    fun drain() { worker.submit { }.get(120, java.util.concurrent.TimeUnit.SECONDS) }

    private fun fail(e: Throwable) {
        Log.w(TAG, "trip edit failed", e)
        ui { message = e.message ?: e.javaClass.simpleName }
    }

    private fun note(what: String, t0: Long, ed: RouteEditor) {
        val notes = ed.takeNotes()
        val messages = ed.takeMessages()
        lastEdit = linkedMapOf(
            "what" to what, "ms" to (System.nanoTime() - t0) / 1_000_000, "straightBecause" to notes, "messages" to messages,
            "at" to System.currentTimeMillis(),
        )
        // T73: a pack built before the road data says so (rebuild it), not "no trail".
        val text = messages["drive_unsupported"]
            ?: if (notes.isEmpty()) null else "No trail for ${notes.size} leg(s): drawn straight"
        ui { message = text }
    }

    private fun fitCamera(p: RoutePlan) {
        val m = app.map.map ?: return
        val pts = p.path.points
        if (pts.isEmpty()) return
        val d = app.resources.displayMetrics.density
        if (pts.size == 1) { m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(pts[0].lat, pts[0].lon)), 600); return }
        val b = LatLngBounds.Builder().apply { for (pt in pts) include(LatLng(pt.lat, pt.lon)) }.build()
        if (b.latitudeSpan < 1e-6 && b.longitudeSpan < 1e-6) return
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, (40 * d).toInt(), (110 * d).toInt(), (40 * d).toInt(), (380 * d).toInt()), 600)
    }

    const val TAG = "DystopoTrips"
}

/**
 * T91: whether a [Trips.refresh] that read the open route id [readId] may apply its answer now that the
 * editor holds [nowId]: only to the same route (a refresh that read none has nothing to apply). Pure, tested.
 */
internal fun refreshApplies(readId: String?, nowId: String?): Boolean = readId != null && readId == nowId

/**
 * T106 (a T89 follow-up): the legs of a drive [path] that the router planned but that carry no router time: a drive
 * planned before T89 (its `path` value is version 1, [io.github.jasondorjeshort.dystopo.model.RoutePath.VERSION]) or
 * synced from a build before it. Their time is the flat [io.github.jasondorjeshort.dystopo.routing.CostModel.DRIVE_SUMMARY_KMH]
 * estimate, not the per-road time every other surface shows, until the trip is re-planned ([Trips.replan]); the trip
 * card says so. 0 for anything else (hike and bike model their time; fixed legs are imported lines). Pure, tested.
 */
internal fun untimedDriveLegs(path: RoutePath): Int =
    if (path.mode != "drive") 0 else path.legs.count { it.kind == io.github.jasondorjeshort.dystopo.model.LegKind.TRAIL && it.timeS == null }

/** T106: the trip card's line on a drive with [untimedDriveLegs] (Re-plan follows it), the flat speed in [units]. Pure, tested. */
internal fun driveTimeEstimated(units: io.github.jasondorjeshort.dystopo.render.ContourUnits): String {
    val kmh = io.github.jasondorjeshort.dystopo.routing.CostModel.DRIVE_SUMMARY_KMH
    val speed = if (units == io.github.jasondorjeshort.dystopo.render.ContourUnits.FEET) "${Math.round(kmh / 1.609344)} mph" else "${Math.round(kmh)} km/h"
    return "Drive time estimated at a flat $speed: planned before road speeds. Re-plan for the road time."
}

/** T95: the Offline offer while the corridor's manifest is being made. */
internal const val OFFLINE_WORKING = "Working out the offline size…"

/** T95: the Offline offer when the manifest could not be made (the reason follows). */
internal const val OFFLINE_FAILED = "Could not work out the offline size: "

/**
 * T95: the Offline offer's text: the corridor's estimated size and tiles, and when the offline areas' budget would
 * be passed, [refusal] (the T91 budget message) after it. Pure, tested.
 */
internal fun offlineOfferText(bytes: Long, tiles: Int, refusal: String?): String {
    val size = io.github.jasondorjeshort.dystopo.app.areas.aboutMb(bytes) // the budget message's rounding, so one size reads the same
    val head = "This route offline: about $size (${String.format(java.util.Locale.US, "%,d", tiles)} tiles)."
    return if (refusal == null) head else "$head $refusal."
}
