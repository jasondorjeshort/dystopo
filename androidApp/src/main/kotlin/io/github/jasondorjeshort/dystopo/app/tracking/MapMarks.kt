package io.github.jasondorjeshort.dystopo.app.tracking

import androidx.compose.runtime.Composable
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.LocationPuck
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.aircraft.AircraftLayer
import io.github.jasondorjeshort.dystopo.app.divergence.DivergenceLayer
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.nav.NavMapLayer
import io.github.jasondorjeshort.dystopo.app.packs.CatalogOverlay
import io.github.jasondorjeshort.dystopo.app.readout.WayDownWatch
import io.github.jasondorjeshort.dystopo.app.routing.RouteOverlay
import io.github.jasondorjeshort.dystopo.app.search.SearchMarker
import io.github.jasondorjeshort.dystopo.app.trips.TripOverlay
import io.github.jasondorjeshort.dystopo.app.viewshed.ViewshedOverlay

/**
 * T383: every mark the map draws, named (the owner's "why do blue dots appear at times on the map?"). A long-press on a mark
 * gives the long-press card's first line, a name and one fact ([LongPressMenu.mark]); this table is where the names live.
 *
 * Each [Mark] refers to its overlay's own layer id constants (the table never spells an id), with the feature property that
 * tells one kind from another where several kinds share a layer (the track's start, end, head and waypoints are one layer).
 * Layers that draw a point but belong to another card are [ELSEWHERE] (flights' own tap card, the area picker's handles, a
 * pack's or an area's own label). The position puck is the first mark, named by `MapController.puckMark` (T251), so its
 * layers are never asked here.
 *
 * **Order of precedence** (a press can land on several marks, a waypoint on the track's head): the card names the first
 * only, in [MARKS]' order. First the position (the owner's question, and while a track records it sits on the head); then
 * what the user just asked for, short-lived and drawn on top: the search result, the navigation's rejoin point, the
 * pressure warning's way down, the viewshed's marks; then what the user placed by hand (waypoints) before what the app
 * placed for them (a track's ends and head, a trip's points, a route's ends); then what the app reads from the map (water,
 * the trail-disagreement dots); lines last (a gap's dashes, the way back to the route), because a dot drawn on a line is the
 * smaller target the finger aimed at. Within one mark the feature nearest the press wins.
 *
 * **A new mark cannot ship unnamed.** `MapMarksTest` holds the table against the overlays' constants, and the debug route
 * `GET /map/marks` lists the live style's circle and symbol layers whose id starts with `dystopo-`, each with its name or
 * null; the a11y tool's `longpress` screen fails the sweep when that list has an unnamed layer.
 *
 * Debug routes (debug builds only, on the debug port):
 * - `GET /map/marks`: `layers` (each live circle or symbol layer with our prefix: `id`, `type` `circle` or `symbol`, `name`
 *   (the marks it draws, comma-separated; or what the [ELSEWHERE] rule says it is; null when nothing names it), `marks`
 *   (their keys), `elsewhere` (the rule's reason, else null)), `unnamed` (the ids whose `name` is null), `order` (the marks'
 *   keys in their precedence), `table` (each mark: `key`, `name`, `layers`, `match`, `colour`, `help`). With `lat` and
 *   `lon`, `hits` lists the marks a press there lands on, in precedence order (`key`, `line`, `layer`, `px` from the press);
 *   `puck` is the position's line when the press is on it. 503 while the map has no style.
 */
object MapMarks {
    /**
     * One kind of mark: its [key], the [name] that starts its line, the [layers] it is drawn in (its overlay's constants),
     * the feature property and values ([matchProp], [matchValues]) that pick it out of a shared layer (null: every feature),
     * its [colour] as drawn, the help key beside its line ([help], null for none), and its card [line] from the feature's
     * properties and the [Facts] at hand.
     */
    class Mark(
        val key: String,
        val name: String,
        val layers: List<String>,
        val colour: String,
        val help: String?,
        val matchProp: String? = null,
        val matchValues: Set<String> = emptySet(),
        val line: (props: (String) -> String?, facts: Facts) -> String = { _, _ -> name },
    ) {
        fun matches(props: (String) -> String?): Boolean = matchProp == null || props(matchProp) in matchValues
    }

    /** What a line may look up beyond the feature: a track's name by its id. */
    class Facts(val trackName: (String) -> String? = { null })

    /** Point layers with our prefix that another card or screen answers for, by id prefix or exact id, with why. */
    class Elsewhere(val prefix: String, val why: String)

    private fun named(name: String, fact: String?): String = if (fact.isNullOrBlank()) name else "$name, $fact"

    // The water markers' layers: each holder's WaterMarkers("<its source>"), whose layer is "<source>-water-icons".
    val ROUTE_WATER = RouteOverlay.SOURCE + WATER_SUFFIX
    val TRIP_WATER = TripOverlay.SOURCE + WATER_SUFFIX

    /** The position puck's layers, named by [LongPressMenu]'s `puckMark` before this table is asked. */
    val POSITION = Mark("position", "Your position", listOf(LocationPuck.ACCURACY, LocationPuck.RING, LocationPuck.DOT), "#1a66d9", "map.marks")

    private fun trackMark(key: String, name: String, kinds: Set<String>, layers: List<String>, colour: String) =
        Mark(key, name, layers, colour, "map.marks.track", "kind", kinds) { p, f -> named(name, p("id")?.let(f.trackName)) }

    /** The marks after the position, in their order of precedence (see the KDoc above). */
    val MARKS: List<Mark> = listOf(
        Mark("search", "Search result", listOf(SearchMarker.DOT, SearchMarker.HALO, SearchMarker.LABEL), "#d0352b", "map.marks.search") { p, _ -> named("Search result", p("name")) },
        Mark("nav.target", "Rejoin point", listOf(NavMapLayer.TARGET), NavMapLayer.ALERT, "map.marks.nav", "kind", setOf("target")) { _, _ -> "Rejoin point, where you get back on the route" },
        Mark("wayDown", "Lower ground", listOf(WayDownWatch.END), "#1f4fa8", "map.marks.wayDown") { _, _ -> "Lower ground, where the way down ends" },
        Mark("viewshed.observer", "Viewpoint", listOf(ViewshedOverlay.OBSERVER), "#12a150", "map.marks.viewshed", "kind", setOf("observer")) { _, _ -> "Viewpoint, the view is worked out from here" },
        Mark("viewshed.peak", "Peak in view", listOf(ViewshedOverlay.PEAK_DOT, ViewshedOverlay.PEAK_NAME), "#ffd400", "map.marks.viewshed") { p, _ -> named("Peak in view", p("name")) },
        Mark("waypoint", "Waypoint", listOf(TrackOverlay.POINTS, TrackOverlay.LIVE_POINTS, TrackOverlay.LABELS), "#ffd400", "map.marks.track", "kind", setOf("waypoint")) { p, _ ->
            named("Waypoint", p("name")?.takeIf { it.isNotBlank() } ?: "no name yet")
        },
        Mark("trip.waypoint", "Trip waypoint", listOf(TripOverlay.POINTS, TripOverlay.LABELS), "#ffd400", "map.marks.trip", "kind", setOf("wpt")) { p, _ ->
            named("Trip waypoint", p("name")?.takeIf { it.isNotBlank() } ?: "no name yet")
        },
        trackMark("track.head", "Track head", setOf("current"), listOf(TrackOverlay.LIVE_POINTS), "#d01c8b"),
        trackMark("track.start", "Track start", setOf("start"), listOf(TrackOverlay.POINTS, TrackOverlay.LIVE_POINTS), "#1a9850"),
        trackMark("track.end", "Track end", setOf("end"), listOf(TrackOverlay.POINTS), "#d73027"),
        Mark("trip.point", "Trip point", listOf(TripOverlay.POINTS, TripOverlay.NUMBERS), "#1a9850, #d73027 or white in #7b3294", "map.marks.trip", "kind", setOf("start", "end", "via")) { p, _ ->
            val n = p("label")
            when (p("kind")) {
                "start" -> named("Trip start", n?.let { "point $it" })
                "end" -> named("Trip end", n?.let { "point $it" })
                else -> named("Trip point", n?.let { "number $it" })
            }
        },
        Mark("route.end", "Route end", listOf(RouteOverlay.POINTS), "#1a9850 or #d73027", "map.marks.route", "kind", setOf("start", "end")) { p, _ ->
            if (p("kind") == "start") "Route start" else "Route end"
        },
        Mark("water", "Water", listOf(ROUTE_WATER, TRIP_WATER), "#1f78b4", "map.marks.water") { p, _ -> named("Water", WATER_FACT[p("icon")]) },
        Mark("divergence", "Off the mapped trail", listOf(DivergenceLayer.DOTS), DivergenceLayer.COLOR, null, "kind", setOf("dot")) { p, _ ->
            named("Off the mapped trail", p("tracks")?.toDoubleOrNull()?.toInt()?.let { if (it == 1) "1 of your tracks" else "$it of your tracks" })
        },
        Mark("track.gap", "Gap in the track", listOf(TrackOverlay.GAP, TrackOverlay.LIVE_GAP), "#d01c8b", "map.marks.track", "kind", setOf("gap")) { _, _ ->
            "Gap in the track, no fixes along the dashes"
        },
        Mark("nav.rejoin", "Way back to the route", listOf(NavMapLayer.REJOIN), NavMapLayer.ALERT, "map.marks.nav", "kind", setOf("rejoin")) { _, _ ->
            "Way back to the route, a straight line to it"
        },
    )

    /** The water marker's fact by its icon (`WaterMarkers.iconOf`). */
    val WATER_FACT: Map<String, String> = mapOf(
        io.github.jasondorjeshort.dystopo.app.routing.WaterMarkers.STREAM_FLOWING to "a stream that likely flows",
        io.github.jasondorjeshort.dystopo.app.routing.WaterMarkers.STREAM_MAY_DRY to "a stream that may run dry",
        io.github.jasondorjeshort.dystopo.app.routing.WaterMarkers.STREAM_DRY to "a stream bed, likely dry",
        io.github.jasondorjeshort.dystopo.app.routing.WaterMarkers.SPRING to "a spring",
        io.github.jasondorjeshort.dystopo.app.routing.WaterMarkers.TAP to "a tap",
        io.github.jasondorjeshort.dystopo.app.routing.WaterMarkers.LAKE to "a lake",
    )

    /** Point layers with our prefix that are not marks of this card. */
    val ELSEWHERE: List<Elsewhere> = listOf(
        Elsewhere(AircraftLayer.SOURCE + "-", "flights: their own tap card"),
        Elsewhere("dystopo-picker-", "the area picker's handles, shown only while it is open"),
        Elsewhere("dystopo-areas-", "an offline map's outline label, which names its map"),
        Elsewhere(CatalogOverlay.LABEL, "a pack's own label on the pack screen"),
    )

    /** Our layers' prefix: `GET /map/marks` lists the live circle and symbol layers that start with it. */
    const val PREFIX = "dystopo-"

    /** Every mark, the position first, then [MARKS] in their order. */
    val ALL: List<Mark> get() = listOf(POSITION) + MARKS

    /** The name of layer [id]: the names of the marks drawn in it, or what [ELSEWHERE] says, or null (pure). */
    fun nameOf(id: String): String? {
        val marks = ALL.filter { id in it.layers }
        if (marks.isNotEmpty()) return marks.joinToString(", ") { it.name }
        return elsewhereOf(id)?.why
    }

    fun elsewhereOf(id: String): Elsewhere? = ELSEWHERE.firstOrNull { id == it.prefix || (it.prefix.endsWith("-") && id.startsWith(it.prefix)) }

    /** A mark found under a press: its [mark], its card [line], the [layer] it was found in and its distance in [px]. */
    class Hit(val mark: Mark, val line: String, val layer: String, val px: Float) {
        fun json(): Map<String, Any?> = linkedMapOf("key" to mark.key, "line" to line, "layer" to layer, "px" to Math.round(px * 10) / 10.0)
    }

    /** [hits] in their order of precedence ([MARKS]' order, then the nearest first), pure. The card names the first. */
    fun ordered(hits: List<Hit>): List<Hit> = hits.sortedWith(compareBy<Hit> { h -> MARKS.indexOfFirst { it.key == h.mark.key } }.thenBy { it.px })

    /** The facts the app has at hand: a track's name from the track store (one row; main thread is fine). */
    private fun facts(): Facts = Facts(trackName = { id -> runCatching { Tracking.readyOrNull?.store?.summary(id)?.name }.getOrNull() })

    /**
     * The marks under a press at ([lat], [lon]) within [radiusPx] screen pixels (a fingertip), in their order of precedence:
     * each mark's layers asked for the features rendered in the box, a shared layer's features picked by the mark's
     * property. Main thread; empty with no map or style.
     */
    fun at(lat: Double, lon: Double, radiusPx: Float): List<Hit> {
        val map = DystopoApp.instance.map.map ?: return emptyList()
        val style = map.style ?: return emptyList()
        val p = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(lat, lon))
        val box = android.graphics.RectF(p.x - radiusPx, p.y - radiusPx, p.x + radiusPx, p.y + radiusPx)
        val facts = facts()
        val hits = ArrayList<Hit>()
        for (m in MARKS) {
            for (layer in m.layers) {
                if (style.getLayer(layer) == null) continue
                val found = runCatching { map.queryRenderedFeatures(box, layer) }.getOrNull() ?: continue
                for (f in found) {
                    val props: (String) -> String? = { k -> f.properties()?.get(k)?.takeIf { it.isJsonPrimitive }?.asString }
                    if (!m.matches(props)) continue
                    val g = f.geometry() as? org.maplibre.geojson.Point
                    val px = if (g == null) 0f else map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(g.latitude(), g.longitude())).let {
                        kotlin.math.hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()).toFloat()
                    }
                    hits += Hit(m, m.line(props, facts), layer, px)
                }
            }
        }
        return ordered(hits)
    }

    /** `GET /map/marks`, see the KDoc above. */
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.method != "GET" || r.path != "/map/marks") return null
        val lat = r.query["lat"]?.toDoubleOrNull()
        val lon = r.query["lon"]?.toDoubleOrNull()
        val out = app.map.onMain {
            val style = app.map.map?.style ?: return@onMain null
            val layers = style.layers.mapNotNull { l ->
                val type = when (l) {
                    is org.maplibre.android.style.layers.CircleLayer -> "circle"
                    is org.maplibre.android.style.layers.SymbolLayer -> "symbol"
                    else -> return@mapNotNull null
                }
                if (!l.id.startsWith(PREFIX)) return@mapNotNull null
                linkedMapOf(
                    "id" to l.id, "type" to type, "name" to nameOf(l.id),
                    "marks" to ALL.filter { l.id in it.layers }.map { it.key }, "elsewhere" to elsewhereOf(l.id)?.why,
                )
            }
            val res = linkedMapOf<String, Any?>(
                "layers" to layers,
                "unnamed" to layers.filter { it["name"] == null }.map { it["id"] },
                "order" to ALL.map { it.key },
                "table" to ALL.map { m ->
                    linkedMapOf("key" to m.key, "name" to m.name, "layers" to m.layers, "match" to m.matchProp?.let { mapOf(it to m.matchValues.sorted()) }, "colour" to m.colour, "help" to m.help)
                },
            )
            if (lat != null && lon != null) {
                val density = app.resources.displayMetrics.density
                val metersPerDp = app.map.map?.projection?.getMetersPerPixelAtLatitude(lat)
                res["puck"] = metersPerDp?.let { app.map.puckMark(lat, lon, it * LongPressMenu.FINGER_DP) }
                res["hits"] = at(lat, lon, (LongPressMenu.FINGER_DP * density).toFloat()).map { it.json() }
            }
            res
        } ?: return Response.json(mapOf("error" to "the map has no style yet"), 503)
        return Response.json(out)
    }
}

/** The water markers' layer suffix (`WaterMarkers.layer` is "<its holder's source>-water-icons"). */
private const val WATER_SUFFIX = "-water-icons"

/** T383: the help icon beside a mark's line, by the mark's help key (each key named here, beside its text). */
@Composable
internal fun MarkHelpIcon(key: String?) {
    when (key) {
        "map.marks.track" -> HelpIcon("map.marks.track")
        "map.marks.trip" -> HelpIcon("map.marks.trip")
        "map.marks.route" -> HelpIcon("map.marks.route")
        "map.marks.water" -> HelpIcon("map.marks.water")
        "map.marks.nav" -> HelpIcon("map.marks.nav")
        "map.marks.search" -> HelpIcon("map.marks.search")
        "map.marks.viewshed" -> HelpIcon("map.marks.viewshed")
        "map.marks.wayDown" -> HelpIcon("map.marks.wayDown")
        else -> Unit
    }
}

/** T383: what each kind of mark is, beside the long-press card's first line when the press is on one (gathered with [MAP_MARKS_HELP]). */
internal val MAP_MARK_KINDS_HELP: List<HelpText> = listOf(
    HelpText("map.marks.track", "Track marks", "The map: long-press a track's dot or waypoint") {
        "A recorded track starts at a green dot and ends at a red one; while it records, its newest point is magenta. " +
            "Dashes bridge a gap where no position was recorded, drawn straight. Yellow dots with a dark ring are waypoints."
    },
    HelpText("map.marks.trip", "Trip marks", "The map: long-press a trip's point") {
        "A trip shows its points in order: green for the start, red for the end, numbered white dots for the points " +
            "between, yellow dots for its waypoints."
    },
    HelpText("map.marks.route", "Route ends", "The map: long-press a planned route's end") {
        "A planned route starts at a green dot and ends at a red one, joined by its orange line."
    },
    HelpText("map.marks.water", "Water along the route", "The map: long-press a blue water mark") {
        "Blue marks along a planned route are water the map knows of: a full drop for a stream that likely flows, a half " +
            "full one for a stream that may run dry, an empty one for a dry bed; a ringed dot for a spring, a tap, waves for " +
            "a lake. Water can be gone when you get there: carry enough to reach the next."
    },
    HelpText("map.marks.nav", "Rejoin point", "The map: long-press the red dot while off the route") {
        "While you navigate off the route, a dashed red line runs from you to the red dot where you get back on it."
    },
    HelpText("map.marks.search", "Search result", "The map: long-press the red dot of a search") {
        "The red dot in a white ring is the place picked in search, with its name beside it."
    },
    HelpText("map.marks.viewshed", "Viewpoint and peak", "The map: long-press the green or yellow dot of What can I see from here") {
        "While What can I see from here is open, the green dot is where the view is worked out from, and a yellow dot " +
            "is the peak picked in its list."
    },
    HelpText("map.marks.wayDown", "Way down", "The map: long-press the dark blue dot of the pressure warning") {
        "When the pressure warning finds lower ground nearby, a dashed dark blue line runs from you to the dot where it ends."
    },
)
