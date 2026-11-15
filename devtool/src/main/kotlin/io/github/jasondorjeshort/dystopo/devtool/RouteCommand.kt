package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.TrackDrawing
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jasondorjeshort.dystopo.model.RouteDirections
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.SavedDirections
import io.github.jasondorjeshort.dystopo.routing.Crow
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import io.github.jasondorjeshort.dystopo.sync.Bundle
import java.util.Properties
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.DriveMetric
import io.github.jasondorjeshort.dystopo.routing.DriveOverlay
import io.github.jasondorjeshort.dystopo.routing.DrivePreference
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.FuelModel
import io.github.jasondorjeshort.dystopo.routing.GraphSource
import io.github.jasondorjeshort.dystopo.routing.GraphTileArchive
import io.github.jasondorjeshort.dystopo.routing.GraphTileFile
import io.github.jasondorjeshort.dystopo.routing.OverlayCellFile
import io.github.jasondorjeshort.dystopo.routing.Surface
import io.github.jasondorjeshort.dystopo.routing.WayAttrs
import io.github.jasondorjeshort.dystopo.routing.WayClass
import io.github.jasondorjeshort.dystopo.routing.WayLine
import io.github.jasondorjeshort.dystopo.routing.TileRouter
import io.github.jasondorjeshort.dystopo.routing.TiledGraph
import io.github.jasondorjeshort.dystopo.routing.WholeGraph
import io.github.jasondorjeshort.dystopo.routing.DirectionsText
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import io.github.jasondorjeshort.dystopo.routing.RoutingGraphBuilder
import io.github.jasondorjeshort.dystopo.routing.LinePrice
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.RoutePrice
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos

/**
 * Offline route planning over a pack's trail network (T45):
 * - `route --pack out/rainier-outdoors.pmtiles --from 46.7865,-121.7365 --to 46.8025,-121.7290 [--mode hike|bike|drive]
 *   [--osm out/rainier-outdoors.pmtiles] [--gpx out/route.gpx]`: opens `<archive>-graph.idx` next to the pack
 *   (the archive's whole file name, T49: `rainier-outdoors-graph.idx`)
 *   (building it first when missing or stale), plans the route, renders it over the DEM pack's terrain
 *   with an elevation profile (`out/route-<pack>.png`) and prints the route as JSON. Exit 1 when no route.
 *   T102: the route's turn directions are in `route.instructions` (type, priority, distance along, way, turn,
 *   lead distance, the stretch after it) and, as text with the stretch each one starts, in `directions`
 *   (in `--units`, ft by default as for the render; every priority, the default level's count in `shown`).
 *   T116: the texts carry the default level's "then" cues ([Directions.cued]; `thenCues` counts them).
 *   T108: `trip` follows the route as a phone keeps it: saved as a trip with its directions (the phone's Keep) in one
 *   store, synced as a sync bundle (encoded, gzipped, decoded) into a second store with no graph (the stores
 *   `sync-sim --store sql` uses: SqlTrackStore over in-memory SQLite) and loaded there; `same` says the directions
 *   loaded there are the ones saved (`text` lists them as `directions` does), with the ops, bundle bytes and the
 *   `directions` field's size.
 *   T108: `--mode crow` is the straight line between the points (no graph: `--pack` only names the default DEM, or give
 *   `--dem`), with `route.bearingDeg`, the profile from the DEM and one arrival in `instructions`.
 *   T281: drives plan on the drive search over directed-edge states (ADR 0062); `--no-turns` plans them on the node
 *   search instead (the owner's fast option). T282: `--prefer time|quick` (the phone's Plan drives for; `--no-turns` is
 *   `--prefer quick`); a drive on the drive search pays for its turns and its `route` JSON has `turns` and `turnCostS`
 *   (the seconds, in `timeS`). `--overlay-min-km K` routes over the graph file's z11 tiles with a drive
 *   overlay for drives of K km or more (the server's 40 km is too long for a public pack) and reports the overlay
 *   (cells built, their milliseconds) in `search.overlay`.
 *   T283: `search.drivingSide` (`left` or `right`, drives only): the side the turns are priced for, from the table at
 *   the start ([io.github.jasondorjeshort.dystopo.routing.DrivingSides]).
 *   T284 (ADR 0063): `--prefer fuel` plans least fuel (the drive search on the fuel model's costs); every drive's `route`
 *   JSON names its `metric` (`time` or `fuel`) beside `cost` and carries `fuelIndex` (its fuel in the model's joules, for
 *   comparing routes only), and `search.metric` and `search.fuelModel` say which ran. `--overlay-warm` (with
 *   `--overlay-min-km`) first builds every overlay cell of the graph file for the metric, both levels, as the server's
 *   warm-up does, writes the cell file next to the graph file (`<archive>-overlay.idx`, or `<archive>-overlay-fuel.idx`
 *   for fuel) and reports `search.overlayWarm`. `--fixture devtool/fixtures/fuel-pass.json --drive west-east` routes on
 *   the committed synthetic fixture instead of a pack (no PNG: it has no DEM).
 *   T290: `--breakdown` (drives) adds `breakdown`, the route's cost table under its `--prefer`, as the drive search sums
 *   it ([TileRouter.price]: per edge, per turn, per overlay shortcut and border crossing, and the totals); `--against
 *   time|fuel|quick` also plans that preference's route and prices each route under both (`against`). `--price <gpx>`
 *   plans nothing: it prices the GPX's line (its first route, else its first track), which must lie on the graph's own
 *   geometry as `--gpx` writes it ([TileRouter.priceLine]); any other line is refused (exit 1) at its first point off it.
 * - `route build --pack ... [--dem ...]`: (re)builds the graph and reports size, timings and counters; T281:
 *   `driveStates`, the drive search's states per junction on this graph.
 *
 * Elevations come from the pack's DEM archive (`<name>-dem.pmtiles` next to it, or `--dem`).
 */
class RouteCommand : CliktCommand(name = "route") {
    init {
        subcommands(RouteBuildCommand())
    }

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) =
        "Plan a route along the trails of a vector pack (graph built on demand next to it as <archive name>-graph.idx, e.g. out/rainier-outdoors-graph.idx), render it and print JSON; `route build` (re)builds the graph."

    private val pack by option("--pack", help = "Vector PMTiles pack (outdoors or OpenMapTiles schema)").file(mustExist = true, canBeDir = false)
    private val dem by option("--dem", help = "DEM pack for elevation and the render (default: <name>-dem.pmtiles next to --pack)").file(mustExist = true, canBeDir = false)
    private val from by option("--from", help = "Start lat,lon")
    private val to by option("--to", help = "End lat,lon")
    private val mode by option("--mode", help = "hike (default), bike, drive (T73: the roads in the pack, one-ways and speed limits) or crow (T108: a straight line, no graph)").choice("hike", "bike", "drive", "crow").default("hike")
    private val snap by option("--snap", help = "How far (m) a point may be from the nearest trail or road").double().default(Router.DEFAULT_SNAP_M)
    private val name by option("--name", help = "Route name").default("Route")
    private val graphFile by option("--graph", help = "Graph file (default: <archive name>-graph.idx next to the pack, e.g. rainier-outdoors-graph.idx)").file()
    private val gpx by option("--gpx", help = "Write the route as GPX here").file()
    private val noRender by option("--no-render", help = "Skip the PNG").flag()
    private val prefer by option("--prefer", help = "How a drive is planned (T282, the phone's Plan drives for): time (default: least time, turns priced), quick (quicker planning, blind to turns) or fuel (T284: least fuel, an estimate for an average car)").choice(*DrivePreference.entries.map { it.key }.toTypedArray())
    private val noTurns by option("--no-turns", help = "The same as --prefer quick (T281's switch)").flag()
    private val overlayMinKm by option("--overlay-min-km", help = "Route over the graph tiles with a drive overlay for drives this many km or more apart (T281; the server uses 40)").double()
    private val overlayWarm by option("--overlay-warm", help = "With --overlay-min-km: first build every overlay cell of the graph file for the drive's metric and write its cell file next to the graph file (T284)").flag()
    private val fixture by option("--fixture", help = "Route on a synthetic road fixture (JSON, e.g. devtool/fixtures/fuel-pass.json) instead of a pack (T284)").file(mustExist = true, canBeDir = false)
    private val drive by option("--drive", help = "With --fixture: the label of one of its drives (its start and end; a drive)")
    private val zoomOption by option("--zoom", help = "Render zoom (default: the deepest up to 15 that fits about 12 Mpx)").int().restrictTo(8..16)
    private val style by StyleOptions()
    private val out by option("--out", help = "Output PNG (default out/route-<pack>.png)").file()
    private val breakdown by option("--breakdown", help = "Drives: add the route's cost table under its --prefer, edge by edge and turn by turn, as the search sums it (T290)").flag()
    private val against by option("--against", help = "With --breakdown: also plan the drive under this preference and price each route under both (T290)").choice(*DrivePreference.entries.map { it.key }.toTypedArray())
    private val priceGpx by option("--price", help = "Instead of planning, price the drive along this GPX's line under --prefer: a line route --gpx wrote from this graph; any other is refused (T290)").file(mustExist = true, canBeDir = false)

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        if ((breakdown || priceGpx != null) && mode != "drive" && fixture == null) throw UsageError("--breakdown and --price price drives: add --mode drive")
        if (against != null && !breakdown) throw UsageError("--against needs --breakdown")
        fixture?.let { return runFixture(it) }
        val crow = mode == "crow"
        val packFile = pack ?: dem.takeIf { crow } ?: throw UsageError("--pack is required (for --mode crow, --pack or --dem)")
        priceGpx?.let { return runPrice(packFile, it) }
        val (fromLat, fromLon) = latLon(from ?: throw UsageError("--from lat,lon is required"), "--from")
        val (toLat, toLon) = latLon(to ?: throw UsageError("--to lat,lon is required"), "--to")
        val timings = Timings()
        val demFile = dem ?: RouteGraphs.defaultDem(packFile)
        val report = linkedMapOf<String, Any?>("pack" to pack?.path, "dem" to demFile?.path)
        val result = if (crow) {
            // T108: no graph; the DEM pack's heights along the line.
            val sampler = demFile?.let { DemElevation(RouteGraphs.pyramid(it, cacheTiles = 64)) }
            report["graph"] = null
            timings.time("route") { RouteResult.Found(Crow.plan(fromLat, fromLon, toLat, toLon, sampler, name)) }
        } else {
            val (graph, info) = timings.time("graph") { RouteGraphs.openOrBuild(packFile, demFile, graphFile) }
            // T281: the whole graph as one piece (as before), or its z11 tiles with a drive overlay (--overlay-min-km).
            val minKm = overlayMinKm
            // T281: the one place the switches are read; T282: --prefer, --no-turns its alias for quick.
            val preference = if (noTurns) DrivePreference.QUICK else DrivePreference.read(prefer)
            val overlay = minKm?.let { DriveOverlay(clock = { System.nanoTime() / 1_000_000 }) }
            var warmed: Map<String, Any?>? = null
            // T290: a router per preference (--against plans a second drive on the same graph and overlay); a plan keeps
            // its steps only when --breakdown asks for its table.
            var tiled: TiledGraph? = null
            if (overlay != null && minKm != null) {
                val graphFile = File(info["file"] as String)
                val archive = GraphTileFile.open(graphFile.readBytes())
                tiled = TiledGraph(listOf(archive))
                if (overlayWarm) warmed = timings.time("overlayWarm") { warm(overlay, archive, tiled, preference.metric, graphFile, packFile) }
            }
            fun router(p: DrivePreference): TileRouter = if (tiled == null || minKm == null) TileRouter(WholeGraph(graph), driveTurns = p.turns, metric = p.metric, keepTrace = breakdown)
                else TileRouter(tiled, overlay, driveTurns = p.turns, minDistanceM = minKm * 1000, metric = p.metric, keepTrace = breakdown)
            val router = router(preference)
            timings.time("derive") { graph.adjStart; graph.component; graph.nearest(fromLat, fromLon, 1.0) }
            report["graph"] = info + ("summary" to graph.json())
            val r = timings.time("route") { router.route(fromLat, fromLon, toLat, toLon, TravelMode.of(mode)!!, snap, name) }
            report["search"] = searchJson(preference, mode, fromLat, fromLon, minKm, overlay, warmed)
            if (r is RouteResult.Found && breakdown) breakdownInto(report, router, r.plan, timings) { p ->
                router(p).route(fromLat, fromLon, toLat, toLon, TravelMode.DRIVE, snap, name)
            }
            r
        }
        when (result) {
            is RouteResult.Failed -> {
                report["route"] = null
                report["failure"] = result.json()
                report["timingsMs"] = timings.ms
                echo(pretty(report))
                throw ProgramResult(1)
            }
            is RouteResult.Found -> {
                val plan = result.plan
                report["route"] = plan.json()
                // T276: the phone's guess of the route's kind (TripKinds), with the town share it rests on.
                report["tripKind"] = io.github.jasondorjeshort.dystopo.routing.TripKinds.of(plan).let { linkedMapOf("value" to it.kind.key, "reason" to it.reason.key, "townShare" to it.townShare?.let { s -> Math.round(s * 1000) / 1000.0 }, "threshold" to io.github.jasondorjeshort.dystopo.routing.TripKinds.threshold(plan.mode)) }
                // T102: the directions as text, each with the stretch it starts (P1 major, P2 normal, P3 minor).
                // T116: with the default level's "then" cues, as the phone's lists say them (Directions.cued).
                val u = if (style.units == "ft") ContourUnits.FEET else ContourUnits.METERS // the render's --units
                val cued = Directions.cued(plan.instructions)
                report["directions"] = linkedMapOf(
                    "count" to plan.instructions.size, "shown" to Directions.shown(plan.instructions).size,
                    "byPriority" to plan.instructions.groupingBy { "P${it.priority}" }.eachCount().toSortedMap(),
                    "thenCues" to cued.count { it.then != null },
                    "text" to cued.map { "P${it.priority} ${DirectionsText.of(it, plan.mode)}" + if (it.stretchM > 0) " (${UnitFormat.distance(it.stretchM, u)})" else "" },
                )
                report["trip"] = timings.time("trip") { TripCheck.run(plan, u) } // T108
                // Drawn as a track: a solid line with direction chevrons, start ring, end square and the elevation profile.
                val asTrack = Track(plan.route.id, plan.route.name, listOf(TrackSegment(plan.route.points.map { TrackPoint(it.lat, it.lon, it.ele) })))
                val doc = GpxDocument(tracks = listOf(asTrack))
                gpx?.let { f ->
                    f.absoluteFile.parentFile?.mkdirs()
                    f.writeText(Gpx.write(GpxDocument(routes = listOf(plan.route))), Charsets.UTF_8)
                    report["gpx"] = f.path
                }
                if (!noRender) report["render"] = render(doc, demFile, packFile, timings)
                report["timingsMs"] = timings.ms
                echo(pretty(report))
            }
        }
    }

    /** T281 to T284: the `search` object: how the route was planned. */
    private fun searchJson(preference: DrivePreference, mode: String, fromLat: Double, fromLon: Double, minKm: Double?, overlay: DriveOverlay?, warmed: Map<String, Any?>?): Map<String, Any?> {
        val isDrive = TravelMode.of(mode) == TravelMode.DRIVE
        // T283: the driving side the drive search prices its turns with (looked up at the start; null for hike and bike).
        val side = if (isDrive) io.github.jasondorjeshort.dystopo.routing.DrivingSides.at(fromLat, fromLon).key else null
        return linkedMapOf(
            "prefer" to preference.key, "driveTurns" to preference.turns, "metric" to if (isDrive) preference.metric.key else null, // T284
            "fuelModel" to if (isDrive && preference.metric == DriveMetric.FUEL) FuelModel.DEFAULT.json() else null,
            "drivingSide" to side, "overlayMinKm" to minKm, "overlayWarm" to warmed, "overlay" to overlay?.json(),
        )
    }

    /**
     * T284: the server's full warm-up of [archive] for [metric] ([DriveOverlay.warm]), its cell file written next to the
     * graph file ([graphFile]) as `<pack archive>-overlay.idx` or `-overlay-fuel.idx`.
     */
    private fun warm(overlay: DriveOverlay, archive: GraphTileArchive, tiled: TiledGraph, metric: DriveMetric, graphFile: File, packFile: File): Map<String, Any?> {
        val t0 = System.nanoTime()
        val w = overlay.warm(archive, metric) { x, y -> tiled.piece(0, x, y) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val cellFile = File(graphFile.absoluteFile.parentFile, OverlayCellFile.fileNameFor(packFile.name, metric))
        w.file?.let { cellFile.writeBytes(it) }
        return linkedMapOf(
            "metric" to metric.key, "tiles" to archive.entries.size, "cells" to w.cells, "level2Cells" to w.level2Cells,
            "ms" to ms, "level1Ms" to w.level1Ms, "level2Ms" to w.level2Ms, "file" to cellFile.path.takeIf { w.file != null }, "bytes" to w.file?.size,
        )
    }

    /** T284: one drive of a synthetic fixture ([RouteFixture]) under `--prefer`, printed as a pack's route is (no PNG: no DEM). */
    private fun runFixture(file: File) {
        val fx = RouteFixture.load(file)
        val label = drive ?: throw UsageError("--drive <label> is required with --fixture (${fx.drives.keys.joinToString()})")
        val (a, b) = fx.drives[label] ?: throw UsageError("no drive '$label' in ${file.path} (${fx.drives.keys.joinToString()})")
        val preference = if (noTurns) DrivePreference.QUICK else DrivePreference.read(prefer)
        val timings = Timings()
        val minKm = overlayMinKm
        val overlay = minKm?.let { DriveOverlay(clock = { System.nanoTime() / 1_000_000 }) }
        val tiled = if (overlay == null || minKm == null) null else TiledGraph(listOf(GraphTileFile.open(GraphTileFile.encode(fx.graph))))
        fun router(p: DrivePreference): TileRouter = if (tiled == null || minKm == null) TileRouter(WholeGraph(fx.graph), driveTurns = p.turns, metric = p.metric, keepTrace = breakdown)
            else TileRouter(tiled, overlay, driveTurns = p.turns, minDistanceM = minKm * 1000, metric = p.metric, keepTrace = breakdown)
        val router = router(preference)
        val r = timings.time("route") { router.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE, snap, label) }
        val report = linkedMapOf<String, Any?>("fixture" to file.path, "drive" to label, "graph" to fx.graph.json())
        report["search"] = searchJson(preference, "drive", a.first, a.second, minKm, overlay, null)
        if (r is RouteResult.Found && breakdown) breakdownInto(report, router, r.plan, timings) { p -> router(p).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE, snap, label) }
        when (r) {
            is RouteResult.Failed -> {
                report["route"] = null; report["failure"] = r.json(); report["timingsMs"] = timings.ms
                echo(pretty(report))
                throw ProgramResult(1)
            }
            is RouteResult.Found -> {
                report["route"] = r.plan.json(profileSamples = false)
                report["render"] = mapOf("skipped" to "a fixture has no DEM pack")
                report["timingsMs"] = timings.ms
                echo(pretty(report))
            }
        }
    }

    /**
     * T290: `breakdown`, [plan]'s cost table under its own preference; with `--against`, `against`: the other preference's
     * route ([planOther] plans it on the same graph), each route priced under both preferences, and the differences under
     * each (the other route less this one; `fuelSavedPercent` is this route's saving against the other).
     */
    private fun breakdownInto(report: MutableMap<String, Any?>, router: TileRouter, plan: PlannedRoute, timings: Timings, planOther: (DrivePreference) -> RouteResult) {
        val own = timings.time("breakdown") { router.price(plan) }
        // The table's sums against the plan's own figures, unrounded: (plan - table) / plan, each within 1e-6 when right.
        fun rel(p: Double?, t: Double): Double? = p?.let { if (it == 0.0) t else (it - t) / it }
        report["breakdown"] = own.json() + ("sumsAgainstPlan" to linkedMapOf(
            "timeS" to rel(plan.timeS, own.timeS), "turnCostS" to rel(plan.turnCostS, own.turnS), "cost" to rel(plan.cost, own.cost),
            "fuelIndex" to rel(plan.fuelIndex, own.fuelJ), "turns" to (plan.turns == own.turns), "points" to (plan.route.points.size == own.points),
        ))
        val other = DrivePreference.of(against) ?: return
        val r = timings.time("against") { planOther(other) }
        if (r !is RouteResult.Found) {
            report["against"] = linkedMapOf("prefer" to other.key, "failure" to (r as RouteResult.Failed).json())
            return
        }
        val o = r.plan
        val oRouter = o.trace!!.router
        val thisUnderOther = router.price(plan, other)
        val otherOwn = oRouter.price(o)
        val otherUnderThis = oRouter.price(o, own.preference)
        report["against"] = linkedMapOf(
            "prefer" to other.key,
            "route" to linkedMapOf(
                "distanceM" to PlannedRoute.r1(o.distanceM), "timeS" to Math.round(o.timeS), "cost" to PlannedRoute.r1(o.cost), "metric" to o.metric?.key,
                "fuelIndex" to o.fuelIndex?.let { Math.round(it) }, "turns" to o.turns, "turnCostS" to o.turnCostS?.let(PlannedRoute::r1),
                "points" to o.route.points.size, "sameLine" to (o.route.points == plan.route.points),
            ),
            // Under each preference: the other route less this one.
            "differences" to linkedMapOf(own.preference.key to RoutePrice.difference(own, otherUnderThis), other.key to RoutePrice.difference(thisUnderOther, otherOwn)),
            "thisUnderOther" to thisUnderOther.json(), "other" to otherOwn.json(), "otherUnderThis" to otherUnderThis.json(),
        )
    }

    /** T290: `--price <gpx>`: the drive along the GPX's line under `--prefer` ([TileRouter.priceLine]); exit 1 when refused. */
    private fun runPrice(packFile: File, gpxFile: File) {
        val doc = Gpx.parse(gpxFile.readText(Charsets.UTF_8))
        val line = doc.routes.firstOrNull()?.points?.map { it.lat to it.lon }
            ?: doc.tracks.firstOrNull()?.segments?.flatMap { s -> s.points.map { it.lat to it.lon } }
            ?: throw UsageError("${gpxFile.path} has no route and no track")
        val timings = Timings()
        val (graph, info) = timings.time("graph") { RouteGraphs.openOrBuild(packFile, dem ?: RouteGraphs.defaultDem(packFile), graphFile) }
        val preference = if (noTurns) DrivePreference.QUICK else DrivePreference.read(prefer)
        val router = TileRouter(WholeGraph(graph), driveTurns = preference.turns, metric = preference.metric)
        val report = linkedMapOf<String, Any?>("pack" to packFile.path, "gpx" to gpxFile.path, "graph" to info, "linePoints" to line.size)
        report["search"] = searchJson(preference, "drive", line.first().first, line.first().second, null, null, null)
        val r = timings.time("price") { router.priceLine(line, preference) }
        when (r) {
            is LinePrice.Priced -> { report["steps"] = r.steps; report["price"] = r.price.json() }
            is LinePrice.Refused -> report["failure"] = r.json()
        }
        report["timingsMs"] = timings.ms
        echo(pretty(report))
        if (r is LinePrice.Refused) throw ProgramResult(1)
    }

    private fun render(doc: GpxDocument, demFile: File?, packFile: File, timings: Timings): Map<String, Any?> {
        if (demFile == null) return mapOf("skipped" to "no DEM pack (pass --dem)")
        val fit = TrackDrawing.fitBounds(doc.tracks, emptyList(), emptyList(), 0.15) ?: return mapOf("skipped" to "empty route")
        // At least 3:4 either way, so a route running north-south still shows the country beside it.
        val k = cos((fit.south + fit.north) / 2 * PI / 180)
        val wM = (fit.east - fit.west) * k
        val hM = fit.north - fit.south
        val box = when {
            wM < 0.75 * hM -> {
                val pad = (0.75 * hM / k - (fit.east - fit.west)) / 2
                BoundingBox(fit.west - pad, fit.south, fit.east + pad, fit.north)
            }
            hM < 0.75 * wM -> {
                val pad = (0.75 * wM - hM) / 2
                BoundingBox(fit.west, fit.south - pad, fit.east, fit.north + pad)
            }
            else -> fit
        }
        val zoom = zoomOption ?: GpxCommand.fitZoom(box, maxZoom = 15)
        val window = MercatorWindow.forBbox(box, zoom)
        val pyramid = RouteGraphs.pyramid(demFile)
        val grid = timings.time("demWindow") { pyramid.window(zoom, window.x0, window.y0, window.width, window.height) }
        val (map, _, _, osm) = MapRenderer.render(grid, zoom, style, timings, window = window)
        val overlay = GpxOverlay(window, style.textScale, 110.0)
        val image = timings.time("drawRoute") { overlay.draw(map, doc, 150) }
        val outFile = out ?: File("out/route-${PackNames.parse(packFile.name)?.pack ?: packFile.nameWithoutExtension}.png")
        timings.time("writePng") {
            outFile.absoluteFile.parentFile?.mkdirs()
            ImageIO.write(image, "png", outFile)
        }
        return linkedMapOf(
            "output" to outFile.path, "zoom" to zoom, "widthPx" to image.width, "heightPx" to image.height,
            "bbox" to listOf(box.west, box.south, box.east, box.north), "osmTiles" to osm?.tiles,
            "routePoints" to overlay.stats.trackPoints, "profileSamples" to overlay.stats.profileSamples, "pointsOutside" to overlay.stats.pointsOutside,
        )
    }

    private fun latLon(s: String, opt: String): Pair<Double, Double> {
        val parts = s.split(',').map { it.trim().toDoubleOrNull() }
        val lat = parts.getOrNull(0)
        val lon = parts.getOrNull(1)
        if (parts.size != 2 || lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) throw UsageError("$opt must be lat,lon in degrees")
        return lat to lon
    }
}

/**
 * T108: a planned route through a phone's life as a trip: kept with its directions (the phone's Keep, [RoutePath.planned]
 * and [SavedDirections]) in one store, a sync round to a second store (a bundle encoded, gzipped and decoded, as a push
 * carries it), and loaded there with no graph. Both stores are [SqlTrackStore] over in-memory SQLite, as
 * `sync-sim --store sql` runs them.
 */
internal object TripCheck {
    fun run(plan: io.github.jasondorjeshort.dystopo.routing.PlannedRoute, units: ContourUnits): Map<String, Any?> {
        val now = System.currentTimeMillis()
        fun store(device: String): SqlTrackStore =
            SqlTrackStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema), { now }) { device }
        val phone = store("00000000-0000-4000-8000-0000000000a1")
        val other = store("00000000-0000-4000-8000-0000000000b2")
        val path = RoutePath.planned(plan.route.points, plan.mode.label, plan.timeS)
        val saved = SavedDirections.of(plan.instructions, path)
        phone.putRoutePlan(RoutePlan(plan.route.id, plan.route.name, path, type = plan.mode.label, description = plan.route.description, directions = saved), now)
        val bundle = phone.bundle()
        val wire = Gzip.compress(bundle.encode())
        val applied = other.apply(Bundle.decode(Gzip.decompress(wire)), now)
        val loaded = other.routePlan(plan.route.id)
        val list = loaded?.savedDirections
        fun text(l: List<io.github.jasondorjeshort.dystopo.routing.Instruction>) = // T116: with their "then" cues, as `directions` lists them
            Directions.cued(l).map { "P${it.priority} ${DirectionsText.of(it, plan.mode)}" + if (it.stretchM > 0) " (${UnitFormat.distance(it.stretchM, units)})" else "" }
        val field = RouteDirections.encode(saved)
        return linkedMapOf(
            "id" to plan.route.id, "savedCount" to saved.list.size, "loadedCount" to list?.size,
            "same" to (list == saved.list && list != null), "sameText" to (list != null && text(list) == text(plan.instructions)),
            "pathSame" to (loaded?.path == path), "ops" to bundle.ops.size, "applied" to applied.applied,
            "bundleBytes" to wire.size, "directionsFieldChars" to field?.length,
            "text" to list?.let { text(it) },
        )
    }
}

class RouteBuildCommand : CliktCommand(name = "build") {
    override fun help(context: Context) = "Build (or rebuild) a pack's routing graph and report size, timings and counters."

    private val pack by option("--pack", help = "Vector PMTiles pack").file(mustExist = true, canBeDir = false).required()
    private val dem by option("--dem", help = "DEM pack (default: <name>-dem.pmtiles next to --pack; none: no elevation)").file(mustExist = true, canBeDir = false)
    private val out by option("--out", help = "Graph file (default: <archive name>-graph.idx next to the pack, e.g. rainier-outdoors-graph.idx)").file()

    override fun run() {
        val (graph, info) = RouteGraphs.build(pack, dem ?: RouteGraphs.defaultDem(pack), out ?: RouteGraphs.defaultFile(pack))
        echo(pretty(info + ("summary" to graph.json()) + ("networks" to RouteGraphs.networks(graph, 6)) + ("driveStates" to RouteGraphs.driveStates(graph))))
    }
}

internal object RouteGraphs {
    fun defaultFile(pack: File): File = File(pack.absoluteFile.parentFile, RoutingGraph.fileNameFor(pack.name))

    fun defaultDem(pack: File): File? {
        val name = PackNames.parse(pack.name)?.pack ?: return null
        return File(pack.absoluteFile.parentFile, PackNames.fileName(name, PackRole.DEM)).takeIf { it.isFile }
    }

    /** The [n] largest connected networks: km of line and one node in each (a point to route to or from). */
    fun networks(g: RoutingGraph, n: Int): List<Map<String, Any?>> {
        val sample = HashMap<Int, Int>()
        for (v in 0 until g.nodeCount) sample.putIfAbsent(g.component[v], v)
        return g.componentLengthM.withIndex().sortedByDescending { it.value }.take(n).map { (c, m) ->
            val v = sample.getValue(c)
            linkedMapOf("network" to c, "km" to Router.km(m), "node" to listOf(g.nodeLat[v] / 1e6, g.nodeLon[v] / 1e6))
        }
    }

    /**
     * T281 (ADR 0062 Decision 6): the drive search's states per junction on this graph: the directed edges a car may
     * travel (a two-way road counts twice, a one-way once) over the nodes a car may reach (any drivable edge at them).
     * [Router.DRIVE_STATES_PER_JUNCTION] is this factor on the public packs, rounded up.
     */
    fun driveStates(g: RoutingGraph): Map<String, Any?> {
        val model = io.github.jasondorjeshort.dystopo.routing.CostModel(TravelMode.DRIVE)
        val driveNode = BooleanArray(g.nodeCount)
        var directed = 0L
        for (e in 0 until g.edgeCount) {
            if (!model.usable(g, e)) continue
            driveNode[g.edgeFrom[e]] = true; driveNode[g.edgeTo[e]] = true
            if (model.allows(g, e, true)) directed++
            if (model.allows(g, e, false)) directed++
        }
        val nodes = driveNode.count { it }
        return linkedMapOf(
            "driveNodes" to nodes, "directedDriveEdges" to directed,
            "statesPerJunction" to if (nodes == 0) null else Math.round(directed * 1000.0 / nodes) / 1000.0,
            "factorUsed" to Router.DRIVE_STATES_PER_JUNCTION,
        )
    }

    fun reader(file: File): PmtilesReader = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors)

    private val decoder = demDecoder

    fun pyramid(dem: File, cacheTiles: Int = 256): DemPyramid {
        val r = reader(dem)
        val h = r.header
        return DemPyramid(h.minZoom, h.maxZoom, decoder, cacheTiles) { z, x, y -> synchronized(r) { r.tile(z, x, y) } }
    }

    fun demFingerprint(dem: File?): String = dem?.let { RoutingGraphBuilder.fingerprint(reader(it).header) }.orEmpty()

    /** Builds the graph for [pack] (elevation from [dem]) into [out]; returns it with a report. */
    fun build(pack: File, dem: File?, out: File): Pair<RoutingGraph, Map<String, Any?>> {
        try {
            val t0 = System.nanoTime()
            // A small cache, as on the phone: the builder samples in tile order.
            val pyramid = dem?.let { pyramid(it, cacheTiles = 32) }
            val sampler = pyramid?.let { DemElevation(it) }
            val builder = RoutingGraphBuilder(sampler, demFingerprint(dem)).withClock { System.nanoTime() / 1_000_000 }
            val graph = builder.build(reader(pack), pack.name, System.currentTimeMillis())
            val t1 = System.nanoTime()
            val bytes = io.github.jasondorjeshort.dystopo.routing.GraphTileFile.encode(graph) // T76: the tiled graph file
            out.absoluteFile.parentFile?.mkdirs()
            out.writeBytes(bytes)
            val t2 = System.nanoTime()
            return graph to linkedMapOf(
                "pack" to pack.path, "dem" to dem?.path, "file" to out.path, "action" to "built", "bytes" to bytes.size,
                "buildMs" to (t1 - t0) / 1_000_000, "writeMs" to (t2 - t1) / 1_000_000,
                "demTilesDecoded" to pyramid?.tilesDecoded, "elevationZoom" to sampler?.zoom,
                "stats" to builder.stats!!.json(),
            )
        } catch (e: IllegalArgumentException) {
            throw CliktError("routing graph build failed for ${pack.path}: ${e.message}", statusCode = 1)
        }
    }

    /** Opens the graph next to [pack] (or [file]); builds it first when missing, unreadable or stale. */
    fun openOrBuild(pack: File, dem: File?, file: File?): Pair<RoutingGraph, Map<String, Any?>> {
        val f = file ?: defaultFile(pack)
        val fingerprint = RoutingGraphBuilder.fingerprint(reader(pack).header)
        val demFp = demFingerprint(dem)
        var reason = "missing"
        if (f.isFile) {
            val t0 = System.nanoTime()
            val g = runCatching { io.github.jasondorjeshort.dystopo.routing.GraphTileFile.open(f.readBytes()).whole() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            g.onSuccess {
                if (it.source.fingerprint == fingerprint && it.source.demFingerprint == demFp) {
                    return it to linkedMapOf("file" to f.path, "action" to "loaded", "bytes" to f.length(), "loadMs" to ms)
                }
            }
            reason = g.exceptionOrNull()?.message ?: "stale (pack changed)"
        }
        val (g, info) = build(pack, dem, f)
        return g to (info + ("rebuildReason" to reason))
    }
}

private fun pretty(value: Any?): String = reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(value)))

/**
 * T284: a synthetic road network for devtool `route --fixture` (ADR 0063: no public drive need differ between least time
 * and least fuel, so the committed fixture shows that they can). JSON: `baseM` (the ground's height), `hills` (each
 * `lat`, `lon`, `heightM`, `radiusM`: a smooth bump, `heightM x (1 + cos(pi d / radiusM)) / 2` within the radius), `lines`
 * (each `class`, optional `name`, `maxspeed`, `surface`, `oneway`, and `points` as [lat, lon]) and `drives` (each `label`,
 * `from` and `to` as [lat, lon]). The graph is built as a pack's is, its heights sampled from the hills.
 */
internal object RouteFixture {
    class Fixture(val graph: RoutingGraph, val drives: Map<String, Pair<Pair<Double, Double>, Pair<Double, Double>>>)

    fun load(file: File): Fixture {
        val root = MiniJson.parseObject(file.readText())
        fun num(v: Any?, what: String): Double = (v as? Number)?.toDouble() ?: throw CliktError("${file.path}: $what must be a number")
        fun point(v: Any?, what: String): Pair<Double, Double> {
            val l = v as? List<*> ?: throw CliktError("${file.path}: $what must be [lat, lon]")
            return num(l.getOrNull(0), what) to num(l.getOrNull(1), what)
        }
        val base = (root["baseM"] as? Number)?.toDouble() ?: 0.0
        val hills = (root["hills"] as? List<*> ?: emptyList<Any?>()).map { h ->
            val m = h as? Map<*, *> ?: throw CliktError("${file.path}: a hill must be an object")
            doubleArrayOf(num(m["lat"], "hill lat"), num(m["lon"], "hill lon"), num(m["heightM"], "hill heightM"), num(m["radiusM"], "hill radiusM"))
        }
        val sampler = ElevationSampler { lat, lon ->
            base + hills.sumOf { h ->
                val d = io.github.jasondorjeshort.dystopo.gpx.TrackStats.distanceM(lat, lon, h[0], h[1])
                if (d >= h[3]) 0.0 else h[2] * (1 + cos(PI * d / h[3])) / 2
            }
        }
        val lines = (root["lines"] as? List<*> ?: throw CliktError("${file.path}: lines required")).map { l ->
            val m = l as? Map<*, *> ?: throw CliktError("${file.path}: a line must be an object")
            val cls = WayClass.entries.firstOrNull { it.label == m["class"] } ?: throw CliktError("${file.path}: unknown class ${m["class"]}")
            val attrs = WayAttrs(
                cls, Surface.of((m["surface"] as? String) ?: "paved"), flags = if (m["oneway"] == true) WayAttrs.ONEWAY else 0,
                name = m["name"] as? String, maxspeedKmh = (m["maxspeed"] as? Number)?.toInt() ?: 0,
            )
            val pts = (m["points"] as? List<*> ?: throw CliktError("${file.path}: a line needs points")).map { point(it, "a line point") }
            WayLine.of(attrs, *pts.toTypedArray())
        }
        val source = GraphSource("fixture-${file.nameWithoutExtension}.pmtiles", "fixture", "fixture", 14, 0, drive = true)
        val graph = RoutingGraphBuilder(sampler, "fixture").build(lines, source)
        val drives = LinkedHashMap<String, Pair<Pair<Double, Double>, Pair<Double, Double>>>()
        for (d in root["drives"] as? List<*> ?: emptyList<Any?>()) {
            val m = d as? Map<*, *> ?: continue
            drives[m["label"] as? String ?: continue] = point(m["from"], "a drive's from") to point(m["to"], "a drive's to")
        }
        return Fixture(graph, drives)
    }
}
