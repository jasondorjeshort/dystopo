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
import io.github.jasondorjeshort.dystopo.model.SavedDirections
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
 *
 * T108 (ADR 0033): a trip keeps its turn directions as a sync field (`directions`, [SavedDirections]): Keep saves the
 * planned route's own (local or from the server), a new trip and every edit save them from the editor (legs an edit
 * left alone keep theirs, [RouteEditor.directions]), so the trip card and navigation have them with no graph. A trip
 * without them (saved before T108, in the browser, or edited by an older build since) gets them when it is opened
 * where a graph covers it ([open], [migrateDirections]), or at once when every leg is straight; otherwise the card
 * says they were not saved ([DIRECTIONS_NOT_SAVED]). A trip in a mode this build does not know is shown and can be
 * followed but not edited (the editor refuses it, [io.github.jasondorjeshort.dystopo.routing.TravelMode.unknownModeMessage]).
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
    /** T108: the trip card's list of directions is open ("Show N directions"; `POST /routes/{id}/directions?open=1`). */
    var directionsOpen by mutableStateOf(false)

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

    /**
     * T130 (T124's follow-up): the editor's Compose state is written on the main thread, and a change made on another
     * thread (the trips worker, a debug route, the corridors' thread) now waits until it has been applied ([UiPublish], as
     * `RouteSession` does since T119), at most [UI_WAIT_MS]; before, it was posted and the caller went on, so a route
     * answered by the worker's own later read could see the previous state.
     */
    private val uiPublish = io.github.jasondorjeshort.dystopo.app.routing.UiPublish({ Looper.myLooper() == Looper.getMainLooper() }, { main.post(it) })

    private fun ui(block: () -> Unit) {
        uiPublish.publish(UI_WAIT_MS, block)
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

    /** T108: the active DEM pack's sampler for a crow line on the route card ([RoutePlanner.plan]), or null without one. */
    fun demSampler(): ElevationSampler? = elevation()

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
        if (items != lastItems) { lastItems = items; io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.request("routes changed") } // T130: synced, imported, renamed
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

    @Volatile private var lastItems: List<RouteListItem>? = null

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

    /** Creates a route through [points] (snapped to trails, legs routed), stores it with its directions (T108) and opens it. */
    fun create(points: List<Pair<Double, Double>>, name: String, mode: String = "hike", waitMs: Long = 120_000): RoutePlan {
        val t0 = System.nanoTime()
        val ed = editor(waitMs)
        val path = ed.create(points, mode)
        val plan = RoutePlan(ids.next(), name, path, type = mode, directions = SavedDirections.of(ed.directions(path), path))
        store { it.putRoutePlan(plan, System.currentTimeMillis()) }
        note("create", t0, ed)
        return opened(plan.id)
    }

    /**
     * Keeps the T45 planned route as a trip: its start and end become via points, its line one trail leg (T108: crow,
     * one straight leg). T108: with the plan's own directions (local, or the server's for a route it planned).
     */
    fun keep(p: PlannedRoute, name: String = p.route.name): RoutePlan {
        val path = RoutePath.planned(p.route.points, p.mode.label, p.timeS) // T89: a drive keeps the router's time
        val directions = p.instructions.takeIf { it.isNotEmpty() }?.let { SavedDirections.of(it, path) }
        val plan = RoutePlan(p.route.id, name, path, type = p.mode.label, description = p.route.description, directions = directions)
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
            // T108: the directions follow the path (legs the edit left alone keep theirs), written right after it.
            val directions = ed.directions(path, before.path, before.savedDirections)
            check(store { s -> s.setRoutePath(id, path, System.currentTimeMillis()).also { ok -> if (ok) s.setRouteDirections(id, directions, path, System.currentTimeMillis()) } }) { "route $id is gone" }
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
        if (ok) io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.release(id) // T130: a deleted trip releases its corridor
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
     * T75: "Download for offline": the route's corridor (T130: the corridor setting's width, 1 km by default, 500 m before;
     * z8 to z15; T91: z8 to z13 for a drive) as an
     * area of interest, fetched into the tile cache and pinned ([io.github.jasondorjeshort.dystopo.app.areas.AreaDownload]);
     * progress in the card's message line and `GET /areas/download`. T95: [manifest] is the planned one, when there is one.
     */
    fun downloadOffline(id: String, manifest: io.github.jasondorjeshort.dystopo.areas.AreaManifest? = null): Map<String, Any?> {
        val (tripId, area) = io.github.jasondorjeshort.dystopo.app.areas.AreaRoutes.tripArea(id)
        io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.optIn(tripId) // T130: a download by hand keeps the trip again
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

    /**
     * T108, the migration of trips saved without directions (before T108, in the browser, or edited since by a build
     * that does not write them): their directions made now and saved, when every leg is straight (nothing to look up)
     * or a graph of the packs on the phone covers the route ([RoutePlanner.localJunctions]); null when neither, and the
     * card then says they were not saved. [p] already having them returns it unchanged. Not on the main thread.
     */
    fun migrateDirections(p: RoutePlan, waitMs: Long = 5_000): RoutePlan? {
        if (p.savedDirections != null) return p
        if (p.path.points.size < 2 || !io.github.jasondorjeshort.dystopo.routing.TravelMode.known(p.path.mode)) return null
        val allStraight = p.path.legs.isNotEmpty() && p.path.legs.all { it.kind == io.github.jasondorjeshort.dystopo.model.LegKind.STRAIGHT }
        val junctions = if (allStraight || p.path.mode == "crow") null
        else RoutePlanner.localJunctions(p.path.points.map { it.lat to it.lon }, waitMs) ?: return null
        val list = io.github.jasondorjeshort.dystopo.routing.Directions.ofPath(p.path, junctions)
        if (!store { it.setRouteDirections(p.id, list, p.path, System.currentTimeMillis()) }) return null
        directionsMigrated++
        Log.i(TAG, "directions saved for trip ${p.id}: ${list.size} (${if (junctions == null) "straight legs" else "from the packs' graph"})")
        return plan(p.id)
    }

    /** T108: trips whose directions [migrateDirections] made and saved since the app started (`GET /routes`). */
    @Volatile var directionsMigrated = 0
        private set

    /** T108: the trip whose directions [open] is making now (the card says so instead of "not saved"). */
    var directionsWorking by mutableStateOf<String?>(null)
        private set

    /**
     * Opens route [id] in the editor (and shows it on the map). T108: a trip without directions shows at once and gets
     * them in the background where it can ([migrateDirections], waiting up to [MIGRATE_WAIT_MS] for the packs' graphs,
     * which a first use builds), then the card shows them.
     */
    fun open(id: String, fit: Boolean = true): RoutePlan? {
        val p = plan(id) ?: return null
        if (p.savedDirections == null && io.github.jasondorjeshort.dystopo.routing.TravelMode.known(p.path.mode)) {
            ui { directionsWorking = p.id }
            worker.execute {
                try {
                    runCatching { migrateDirections(p, MIGRATE_WAIT_MS) }.onFailure { Log.w(TAG, "directions for ${p.id}", it) }
                } finally {
                    ui { if (directionsWorking == p.id) directionsWorking = null }
                }
                if (editingId == p.id) refresh()
            }
        }
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
        io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.request("saved ${p.id.take(8)}") // T130: made, kept, imported or edited: its corridor follows
        val first = editingId != id
        ui { showPlan(p); listOpen = false; if (first) fitCamera(p) }
        overlay.show(p)
        refresh()
        return p
    }

    fun close() {
        ui { showPlan(null); message = null; offline = null; directionsOpen = false }
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

    /** T122: the editor's last camera fit (bounds, padding, the edges it cleared), `GET /route` `ui.tripFit`. */
    @Volatile var lastFit: Map<String, Any?>? = null
        private set

    /**
     * Shows the whole trip. T122: once the trip card has been laid out ([FIT_AFTER_MS]), clear of it ([MapInsets.tripCardTop])
     * and of the chrome at the top ([io.github.jasondorjeshort.dystopo.app.FitPadding]); fixed 110 and 380 dp before, which
     * put the trip under the Routes chip at the top and left a band of map empty above a short card. The fit's padding is
     * dropped from the camera when it lands, as `RouteSession` does (T114), so later moves centre on the screen. Main thread.
     */
    private fun fitCamera(p: RoutePlan) {
        main.postDelayed({ if (editingId == p.id) fitNow(p) }, FIT_AFTER_MS)
    }

    private fun fitNow(p: RoutePlan) {
        val m = app.map.map ?: return
        val pts = p.path.points
        if (pts.isEmpty()) return
        if (pts.size == 1) { m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(pts[0].lat, pts[0].lon)), 600); return }
        val b = LatLngBounds.Builder().apply { for (pt in pts) include(LatLng(pt.lat, pt.lon)) }.build()
        if (b.latitudeSpan < 1e-6 && b.longitudeSpan < 1e-6) return
        val d = app.resources.displayMetrics.density
        val insets = io.github.jasondorjeshort.dystopo.app.MapInsets
        val chrome = insets.topChromeBottomPx(d)
        val cardPx = (maxOf(insets.tripCardTop.value, CARD_MIN_DP) * d).toInt()
        val mapH = app.map.mapView?.height ?: 0
        val pad = io.github.jasondorjeshort.dystopo.app.FitPadding.of(chrome, cardPx, mapH, d)
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, pad[0], pad[1], pad[2], pad[3]), 600, object : org.maplibre.android.maps.MapLibreMap.CancelableCallback {
            override fun onCancel() = dropPadding(m)
            override fun onFinish() = dropPadding(m)
        })
        lastFit = linkedMapOf(
            "bounds" to listOf(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth), "paddingPx" to pad.toList(),
            "tripId" to p.id, "chromeBottomPx" to chrome, "cardTopDp" to insets.tripCardTop.value, "mapHeightPx" to mapH,
            "clearOfChrome" to (pad[1] >= chrome),
        )
    }

    /** T122: the same view without the fit's padding (MapLibre keeps it on the camera otherwise). Main thread. */
    private fun dropPadding(m: org.maplibre.android.maps.MapLibreMap) {
        val c = m.cameraPosition
        if (c.padding?.any { it != 0.0 } != true) return
        val v = app.map.mapView ?: return
        val centre = m.projection.fromScreenLocation(android.graphics.PointF(v.width / 2f, v.height / 2f))
        m.moveCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder(c).target(centre).padding(0.0, 0.0, 0.0, 0.0).build()))
        lastFit = lastFit?.plus("paddingDropped" to true)
    }

    /** T130: how long a change off the main thread waits for its Compose state to be applied (a stuck main thread). */
    const val UI_WAIT_MS = 5_000L

    /** T122: how long after opening the editor fits the trip, so the trip card has been measured; its height assumed before. */
    const val FIT_AFTER_MS = 250L
    const val CARD_MIN_DP = 150f

    const val TAG = "DystopoTrips"

    /** T108: how long [open]'s background migration waits for the packs' graphs (a first use builds them, about 3 to 6 s for Rainier). */
    const val MIGRATE_WAIT_MS = 60_000L
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

/** T108: the trip card's line when a trip has no saved directions and no graph here to make them (pure text, tested). */
internal const val DIRECTIONS_NOT_SAVED = "Directions were not saved with this route. Open it with its map pack on this phone to add them."

/** T108: the trip card's line while [Trips.open] makes the directions of a trip saved without them. */
internal const val DIRECTIONS_WORKING = "Making the directions from the map pack…"

/** T108: the trip card's directions action: "Show N directions", one in the singular (pure, tested). */
internal fun showDirectionsLabel(n: Int): String = if (n == 1) "Show 1 direction" else "Show $n directions"

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
