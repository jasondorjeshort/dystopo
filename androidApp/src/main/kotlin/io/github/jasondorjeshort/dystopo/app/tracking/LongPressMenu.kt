package io.github.jasondorjeshort.dystopo.app.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.readout.LiveReadout
import io.github.jasondorjeshort.dystopo.readout.KnownHeight
import io.github.jasondorjeshort.dystopo.readout.KnownHeights
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.options.SunPoint
import io.github.jasondorjeshort.dystopo.app.options.SunShade
import io.github.jasondorjeshort.dystopo.app.routing.RouteSession
import java.util.Locale

/**
 * T91: what a long-press on the map offers. Before, a long-press always started a route (T45); now it
 * opens a small card with "Waypoint here" (a free-standing waypoint, or on the recording track, then the
 * waypoint sheet to name it), "Route from here" (the T45 start, so the next long-press is the end) and
 * Cancel. While a route waits for its end (phase `start`) or is planning, a long-press goes straight to
 * the route as before, so planning stays two long-presses after the first menu tap.
 *
 * The map's long-press reaches us through [RouteSession.overlay]'s `onLongPress` hook ([install]), so the
 * routing files stay as they are. Compose state is written on the main thread.
 *
 * T114: "Route to here" comes first, the destination-first action of the mainstream navigation apps (finding
 * `docs/findings/2026-10-17-route-to-flows-in-other-apps.md`): it plans at once from the phone's position to the pressed
 * point ([RouteSession.routeTo]). "Waypoint here" keeps its place near the front (T109), and "Route from here" keeps the
 * two long-presses for a trip planned from a trailhead. After the route card's Choose on map (`pickStart`) a long-press
 * goes to the route like the end press does.
 *
 * T188: under the coordinates, a line on the sun at the point ([sun], [SunPoint]): "Sun reaches here 9:12, leaves 16:40
 * PDT", with the gaps when a peak blocks it, for today or the date picked under Sun and shade, in the phone's zone named.
 * The card keeps such lines in one column above its actions ([PointLines]); the next one (the viewshed's) goes there.
 *
 * T197: "I am at <name>, <height>" ([known], [setHeight]) when the press is on a named peak, saddle or pass with a
 * tagged height (shared `readout/KnownHeights`) within 100 m of the phone's fix, the barometer in use: one tap sets the
 * readout's height from it ([LiveReadout.pin]) and says "Height set from <name>". The height is in the user's units, so a
 * wrong tag shows before the tap. Offered before Cancel; `GET /map/longpress` › `knownHeight` says why it is or is not.
 *
 * T251: a press on the position marker names it in the card's first line ([mark], `MapController.puckMark`: "Your position
 * (GPS, ±8 m)", "Your position, roughly (a poor GPS fix, ±120 m)", "No position, last known 7 min ago"), with the marks'
 * help ([MAP_MARKS_HELP]) beside it.
 */
object LongPressMenu {
    private var pointState by mutableStateOf<Pair<Double, Double>?>(null)

    /** The long-pressed point the card is open for, or null. T188: opening the card at a point starts its sun line. */
    var point: Pair<Double, Double>?
        get() = pointState
        private set(v) {
            val moved = v != pointState
            pointState = v
            mark = v?.let { markAt(it.first, it.second) } // T251
            if (v == null) {
                sunRuns.incrementAndGet()
                sun = null
                knownRuns.incrementAndGet() // T197
                known = null
            } else if (moved || sun == null) {
                startSun()
                startKnown(v.first, v.second) // T197
            }
        }

    /**
     * T188: the card's sun line for the open point ([SunPoint]: when the sun reaches it and leaves it on the terrain, the
     * day of [SunShade.answerDate]); `working` while the horizon sweep runs on its own thread.
     */
    var sun by mutableStateOf<SunPoint.Answer?>(null)
        private set
    private val sunRuns = java.util.concurrent.atomic.AtomicInteger()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // T197: the card's "I am at <name>, <height>" for the open point: worked out on its own thread when the card opens.
    /** The known height the press is on, offered as an action; null while none (or while it is worked out). */
    var known by mutableStateOf<KnownHeight?>(null)
        private set
    @Volatile private var knownInfo: Map<String, Any?>? = null
    private val knownRuns = java.util.concurrent.atomic.AtomicInteger()

    /** A press counts as on a feature within this of it, or a fingertip at the map's zoom when that is wider. */
    const val FINGER_DP = 24.0

    /** T251: the card's first line when the press is on the position marker (what the marker is), else null. */
    var mark by mutableStateOf<String?>(null)
        private set

    /** T251: the marker under a fingertip at [lat], [lon] (main thread). */
    private fun markAt(lat: Double, lon: Double): String? = runCatching {
        val app = DystopoApp.instance
        val metersPerDp = app.map.map?.projection?.getMetersPerPixelAtLatitude(lat) ?: return@runCatching null
        app.map.puckMark(lat, lon, metersPerDp * FINGER_DP)
    }.getOrNull()
    // end T251

    private fun startKnown(lat: Double, lon: Double) {
        val run = knownRuns.incrementAndGet()
        known = null
        knownInfo = mapOf("state" to "working")
        val app = DystopoApp.instance
        val metersPerDp = runCatching { app.map.map?.projection?.getMetersPerPixelAtLatitude(lat) }.getOrNull()
        val radius = maxOf(KnownHeights.NEAR_FIX_M, (metersPerDp ?: 0.0) * FINGER_DP)
        Thread({
            val t0 = System.nanoTime()
            val fix = LiveReadout.freshFix()
            val b = LiveReadout.snapshot?.readout?.barometer
            val pinnable = b?.pinnable == true
            val found = fix?.let { f ->
                runCatching {
                    KnownHeights.read({ z, x, y -> app.tiles.packs.osmCandidates(z, x, y).firstNotNullOfOrNull { it.tile(z, x, y) } }, f.lat, f.lon, io.github.jasondorjeshort.dystopo.routing.ElevationSampler { la, lo -> LiveReadout.demAt(la, lo) })
                }.getOrNull()
            }
            val pick = found?.let { KnownHeights.pick(it.candidates, lat, lon, radius) }
            val offer = pick?.takeIf { pinnable }
            val why = when {
                fix == null -> "noFix"
                found == null -> "readFailed"
                found.candidates.isEmpty() -> "nothingNearFix"
                pick == null -> "pressNotOnOne"
                !pinnable -> "barometerNotInUse"
                else -> null
            }
            val info = linkedMapOf(
                "state" to "done", "offered" to (offer != null), "why" to why,
                "label" to offer?.let { KnownHeights.menuLabel(it, AppSettings.units) },
                "candidate" to pick?.json(), "pressRadiusM" to Math.round(radius * 10) / 10.0, "nearFixM" to KnownHeights.NEAR_FIX_M,
                "fix" to fix?.let { linkedMapOf("lat" to it.lat, "lon" to it.lon, "time" to it.time) },
                "barometer" to b?.state?.code, "pinnable" to pinnable,
                "ms" to (System.nanoTime() - t0) / 1_000_000,
            ) + (found?.json() ?: emptyMap())
            main.post {
                if (run != knownRuns.get()) return@post
                known = offer
                knownInfo = info
            }
        }, "dystopo-known-height").apply { isDaemon = true }.start()
    }

    /** T197, "I am at <name>, <height>": the readout's height set from the feature, a one-line confirmation. Main thread. */
    fun setHeight() {
        val k = known ?: return
        point = null
        lastAction = "height"
        LiveReadout.pinThen(k.name, k.eleM, k.sigmaM, "menu") { line ->
            line?.let { android.widget.Toast.makeText(DystopoApp.instance, it, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    // end T197

    private fun startSun() {
        val (lat, lon) = point ?: return
        val date = SunShade.answerDate()
        val run = sunRuns.incrementAndGet()
        sun = SunPoint.working(lat, lon, date)
        Thread({
            val a = SunPoint.compute(lat, lon, date)
            main.post { if (run == sunRuns.get()) sun = a }
        }, "dystopo-sun-point").apply { isDaemon = true }.start()
    }

    /** T188: the sun-and-shade day changed (turned on or off, a date picked): the open card answers for the new day. Main thread. */
    fun onSunDay() {
        if (point != null && sun?.date != SunShade.answerDate()) startSun()
    }

    @Volatile var presses = 0
        private set
    /**
     * What the last long-press or menu tap did: `menu`, `route` (passed to the route, or Route from here), `routeTo`
     * (T114: Route to here), `waypoint`, `cancel`.
     */
    @Volatile var lastAction: String? = null
        private set

    /** T114: the card's actions in their order (`GET /map/longpress` `actions`). */
    val ACTIONS = listOf(ROUTE_TO, "Waypoint here", "Route from here", io.github.jasondorjeshort.dystopo.app.areas.AROUND_HERE, io.github.jasondorjeshort.dystopo.app.viewshed.ViewshedText.ACTION, "Cancel") // T182: the viewshed

    /** T213 (T197's follow-up): the actions the card draws now, T197's "I am at <name>, <height>" before Cancel when offered. */
    fun actions(): List<String> = withKnown(ACTIONS, known?.let { KnownHeights.menuLabel(it, AppSettings.units) })

    /** Takes the map's long-press from the route session (idempotent). */
    fun install() {
        RouteSession.overlay.onLongPress = { lat, lon -> longPress(lat, lon) }
    }

    /** True while a long-press belongs to the route: its end (`start`), its start after Choose on map, or planning. */
    private fun toRoute(): Boolean = RouteSession.phase.let { it == "start" || it == "planning" || it == "pickStart" }

    /** A long-press at ([lat], [lon]); main thread. */
    fun longPress(lat: Double, lon: Double) {
        presses++
        if (io.github.jasondorjeshort.dystopo.app.areas.AreaPicker.onMap()) return // T142: the area picker's card is up
        if (toRoute()) {
            point = null
            lastAction = "route"
            RouteSession.longPress(lat, lon)
            return
        }
        point = lat to lon
        lastAction = "menu"
    }

    /** T114, "Route to here": a route from the phone's position to the pressed point, planned at once. Main thread. */
    fun routeTo() {
        val (lat, lon) = point ?: return
        point = null
        lastAction = "routeTo"
        RouteSession.routeTo(lat, lon)
    }

    /** "Waypoint here": a waypoint at the pressed point, its sheet opened. Main thread. */
    fun waypoint() {
        val (lat, lon) = point ?: return
        point = null
        lastAction = "waypoint"
        Thread { Tracking.of(DystopoApp.instance).dropWaypoint(lat, lon, via = "longpress") }.start()
    }

    /** "Route from here": the route's start, as a long-press did before T91. Main thread. */
    fun route() {
        val (lat, lon) = point ?: return
        point = null
        lastAction = "route"
        RouteSession.longPress(lat, lon)
    }

    /** T142 (ADR 0041 §5): "Keep offline around here": the area picker's circle around the pressed point. Main thread. */
    fun aroundHere() {
        val (lat, lon) = point ?: return
        point = null
        lastAction = "around"
        io.github.jasondorjeshort.dystopo.app.areas.AreaPicker.startAround(lat, lon, null)
    }

    /** T182, "What can I see from here": the viewshed from the pressed point, its sheet opened. Main thread. */
    fun viewshed() {
        val (lat, lon) = point ?: return
        point = null
        lastAction = "viewshed"
        io.github.jasondorjeshort.dystopo.app.viewshed.ViewshedSession.start(lat, lon, "longpress")
    }

    fun cancel() {
        point = null
        lastAction = "cancel"
    }

    /**
     * T109: opens the card at the map's centre, for `POST /help/open?key=longPress.menu` (main thread); not while a route
     * waits for its end, where a long-press goes to the route.
     */
    fun showAtCentre() {
        if (point != null || toRoute()) return
        val c = DystopoApp.instance.map.map?.cameraPosition?.target ?: return
        point = c.latitude to c.longitude
        lastAction = "menu"
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "open" to (point != null), "lat" to point?.first, "lon" to point?.second,
        "presses" to presses, "lastAction" to lastAction, "routePhase" to RouteSession.phase,
        "actions" to actions(), // T114; T213: with T197's "I am at" item when the card draws it
        "sun" to sun?.json(), // T188: the sun line and the terrain day behind it
        "knownHeight" to (if (point != null) knownInfo else null), // T197: "I am at <name>, <height>" and why (not)
        "mark" to mark, // T251: the position marker's name when the press is on it
    )
}

/** T213 (T197's follow-up): [actions] with the "I am at" item before Cancel when [knownLabel] is offered (pure). */
internal fun withKnown(actions: List<String>, knownLabel: String?): List<String> =
    if (knownLabel == null) actions else actions.dropLast(1) + knownLabel + actions.last()

/** T114: the card's first action. */
internal const val ROUTE_TO = "Route to here"

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xEEFFFCF5)
private val Muted = Color(0xFF6B5A4A)
private val Link = Color(0xFF2C7BB6)

/**
 * The long-press card (bottom of the map, with the waypoint sheet). T106: its actions wrap (at font scale 2.0 "Cancel" was
 * squeezed into a column of letters), and the column it sits in stays above the update offer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LongPressCard() {
    val p = LongPressMenu.point ?: return
    Column(
        Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LongPressMenu.mark?.let { m -> // T251: the position marker under the finger, named first
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(m, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                HelpIcon("map.marks")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(String.format(Locale.US, "%.5f, %.5f", p.first, p.second), Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 12.sp))
            HelpIcon("longPress.menu") // T109: dropping a waypoint, which was hard to find
        }
        PointLines()
        // The actions; a new entry goes in this row in its place in [LongPressMenu.ACTIONS].
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MenuAction(ROUTE_TO) { LongPressMenu.routeTo() } // T114: destination first
            MenuAction("Waypoint here") { LongPressMenu.waypoint() }
            MenuAction("Route from here") { LongPressMenu.route() }
            MenuAction(io.github.jasondorjeshort.dystopo.app.areas.AROUND_HERE) { LongPressMenu.aroundHere() } // T142
            MenuAction(io.github.jasondorjeshort.dystopo.app.viewshed.ViewshedText.ACTION) { LongPressMenu.viewshed() } // T182
            LongPressMenu.known?.let { k -> MenuAction(KnownHeights.menuLabel(k, AppSettings.units)) { LongPressMenu.setHeight() } } // T197
            MenuAction("Cancel") { LongPressMenu.cancel() }
        }
    }
}

/**
 * T188: what the card says about the pressed point, one line each, under its coordinates: the sun line ([SunPoint]).
 * The viewshed's line or entry (T182) joins this column.
 */
@Composable
private fun PointLines() {
    io.github.jasondorjeshort.dystopo.app.divergence.DivergenceCardLine() // T213: on a stretch where the tracks leave the trail
    val sun = LongPressMenu.sun ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            sun.line, Modifier.weight(1f),
            style = TextStyle(color = if (sun.state == "done") Ink else Muted, fontSize = 13.sp),
        )
        HelpIcon("longPress.sun")
    }
}

/**
 * T109: the help pop-up of the card above, by the key its [HelpIcon] names (the owner found dropping a waypoint hard
 * to find); removing the card's icon removes its text. `POST /help/open` opens the card at the map's centre.
 * T114: Route to here first, and where the route card changes its start.
 */
internal val LONG_PRESS_HELP: List<HelpText> = listOf(
    HelpText("longPress.menu", "Long-press menu", "The map: long-press anywhere", show = { LongPressMenu.showAtCentre() }) {
        "A long-press on the map opens this card. Route to here plans a route from where you are to the point. " +
            "Waypoint here marks the point with a waypoint you can name. Route " +
            "from here starts a route at the point: long-press again where it should end. What can I see from here " +
            "shows the ground and peaks in view."
    },
    // T188: the sun line.
    HelpText("longPress.sun", "Sun at this point", "The map: long-press anywhere, the sun line", show = { LongPressMenu.showAtCentre() }) {
        "When the sun first reaches this point and when it leaves, behind the real ridges around it rather than a flat " +
            "horizon, with any time a peak hides it in between. It is for today, or the date picked under Shade " +
            "in Map options. Times are in this phone's time zone, named at the end."
    },
)

@Composable
private fun MenuAction(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

/**
 * T251: what the marks on the map are (the owner's "why do blue dots appear at times on the map?"), shown beside the
 * long-press card's first line when the press is on the position marker; gathered by `HelpRegistry` (`help/Help.kt`).
 */
internal val MAP_MARKS_HELP: List<HelpText> = listOf(
    HelpText("map.marks", "Marks on the map", "The map: long-press the position marker", show = {
        val l = DystopoApp.instance.map.lastLocation
        if (l != null) LongPressMenu.longPress(l.latitude, l.longitude) else LongPressMenu.showAtCentre()
    }) {
        "Blue dot: your GPS position, the pale disc its accuracy. Hollow dot in a dashed ring: a rough position (a poor " +
            "fix, network location or an estimate). Grey dot: last known, no fix for a while. While a track records, the " +
            "dot sits on its newest point (magenta); it starts at a green one. Yellow dots are waypoints."
    },
)
