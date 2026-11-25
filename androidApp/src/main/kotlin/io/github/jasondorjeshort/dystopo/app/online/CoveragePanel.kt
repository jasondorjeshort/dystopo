package io.github.jasondorjeshort.dystopo.app.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.PacksRoutes
import io.github.jasondorjeshort.dystopo.app.areas.DetailOutline
import androidx.compose.ui.platform.testTag
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.packs.Holdings
import kotlin.math.floor

/**
 * T360 (ADR 0066 build task 6; `docs/ideas` triage of 2026-11-22, the owner's yes): the coverage panel, a section of the status
 * chip's open card that says, for the view's centre, what the phone holds there, which holding draws the view, and what the
 * network adds. Its words come from the lookup alone: [lines] reads one [PacksRoutes.HoldingsHere] (what `GET /holdings`
 * reports), the same answer [OnlineView.resolve] reads the chip's source from ([OnlineView.here]), so the panel, the chip and
 * the route cannot disagree. Nothing here ranks a holding or reads a map: the lookup's members, its order, its off maps, its
 * network member and its shallower maps ([Holdings.Here.shallower]) are turned into words, and nothing more.
 *
 * The lines, in this order (ADR 0031: the owner's to adjust):
 * - each holding that draws the view (the tile paths' answer, `tilePathServes`): "<name> · <level> · shown", or "roads
 *   shown" / "terrain shown" where it draws one of the two; a map's or an area's level is [DetailOutline.levelOf]'s name
 *   (through `HeldHere.detailOf`, the words of the Offline maps rows); the tiles kept from viewing are "Viewed earlier", the
 *   built-in overview "World overview";
 * - one line where the view has stepped down ([stepDown]): a finer map whose box only reaches into the tile (the lookup's
 *   `edge`) is passed over, "Coarser here: the finer map ends"; offline past a map's deepest vector zoom the map's roads are
 *   enlarged ("Coarser here: roads enlarged from z13"); the world overview at zooms a map here does not start at yet
 *   ("Coarser here: <map> starts at z8");
 * - the other holdings, in the lookup's order: "<name> · <level> · under it";
 * - the maps that are off and would hold the view: "<name> · Not used" (the Offline maps row's word for a map that is off);
 * - the network ([networkLine]): what it adds, "Online · finer at z14 and up", "Online · finer here", "Online · nothing finer
 *   here", "Online · shown" where nothing on the phone holds the view, each with why it is not used where it is not;
 * - nothing at all: "Nothing on this phone here".
 * At most [MAX_LINES], then "and N more" ([fit]). No line is tappable; the card's tap still closes it.
 *
 * Pure but for the composable ([CoverageSection]), tested (`CoveragePanelTest`).
 */
object CoveragePanel {
    /** The section's heading. */
    const val HEADING = "At the centre of the view"

    /** At most this many lines, then [more]. */
    const val MAX_LINES = 5

    const val SHOWN = "shown"
    const val ROADS_SHOWN = "roads shown"
    const val TERRAIN_SHOWN = "terrain shown"
    const val UNDER = "under it"
    const val CACHE_NAME = "Viewed earlier"
    const val OVERVIEW_NAME = "World overview"
    const val ONLINE = "Online"
    const val NOTHING = "Nothing on this phone here"
    const val FINER_MAP_ENDS = "Coarser here: the finer map ends"
    const val NOTHING_FINER = "nothing finer here"
    const val FINER_HERE = "finer here"
    const val NOT_REACHABLE = "not reachable now"
    const val DETAIL_OFF = "not used: More detail from online is off"
    const val SEP = " · "

    fun enlarged(z: Int) = "Coarser here: roads enlarged from z$z"
    fun startsAt(name: String, z: Int) = "Coarser here: $name starts at z$z"
    fun finerFrom(z: Int) = "finer at z$z and up"
    fun more(n: Int) = "and $n more"

    /** The panel's lines for the lookup's answer [h] (every line, before [fit]). */
    fun lines(h: PacksRoutes.HoldingsHere): List<String> =
        lines(h.zoom, h.lat, h.lon, h.holdings, h.vector, h.dem, h.vectorPath ?: h.vectorNow.serving?.holding?.name, h.demPath ?: h.demNow.serving?.holding?.name)

    /**
     * The lines for a view at [zoom] centred on ([lat], [lon]) whose vector tile's answer is [vector] and elevation tile's [dem]
     * (one zoom deeper, as [PacksRoutes.holdingsHere] asks) over the lookup's inputs [holdings], with the holdings the tile paths
     * draw from, [vectorServes] and [demPathServes] (by name, `cache` for the tiles kept from viewing; null: the tile paths'
     * answer names none, and the lookup's own serving member stands, else nothing on the phone draws it).
     */
    fun lines(
        zoom: Double, lat: Double, lon: Double, holdings: List<Holdings.Holding>, vector: Holdings.Here, dem: Holdings.Here,
        vectorServes: String?, demPathServes: String?,
    ): List<String> {
        val net = vector.network ?: dem.network
        val allowed = net?.allowed == true
        // Where the tile paths' answer names nothing, the lookup's own serving member (a tile kept from viewing, which the
        // tile path reads before the network; `tilePathServes` names the cache only at the overview's zooms).
        val vectorBy = vectorServes ?: vector.serving?.holding?.name
        val demServes = demPathServes ?: dem.serving?.holding?.name
        // Offline past a map's deepest vector zoom nothing serves the vector tile and the map's roads are enlarged.
        val enlarged = vector.shallower.firstOrNull()?.takeIf { vectorBy == null && !allowed }
        val roads = vectorBy ?: enlarged?.holding?.name
        // Each holding once, in the lookup's order (vector's members, then elevation's, then the shallower ones).
        val all = LinkedHashMap<String, Holdings.Holding>()
        for (m in vector.members + dem.members + vector.shallower) all.putIfAbsent(m.holding.name, m.holding)
        fun use(name: String): String? = when {
            name == roads && name == demServes -> SHOWN
            name == roads -> ROADS_SHOWN
            name == demServes -> TERRAIN_SHOWN
            else -> null
        }
        val shown = all.values.filter { use(it.name) != null }
        val under = all.values.filter { use(it.name) == null && it.name in (vector.members + dem.members).map { m -> m.holding.name } }
        val out = ArrayList<String>()
        shown.forEach { out += line(it, use(it.name)!!) }
        stepDown(zoom, lat, lon, holdings, vector, dem, enlarged)?.let { out += it }
        under.forEach { out += line(it, UNDER) }
        (vector.off + dem.off).map { it.name }.distinct().filter { it !in all }.forEach { out += it + SEP + DetailOutline.Level.OFF.label }
        networkLine(zoom, vector, dem, roads, demServes)?.let { out += it }
        if (shown.isEmpty() && under.isEmpty() && (net == null || !allowed)) out.add(0, NOTHING)
        return out
    }

    /** One holding's line: its name, its level (maps and areas), what it draws ([use]). */
    fun line(h: Holdings.Holding, use: String): String = when (h.kind) {
        Holdings.Kind.CACHE -> CACHE_NAME + SEP + use
        Holdings.Kind.OVERVIEW -> OVERVIEW_NAME + SEP + use
        else -> h.name + SEP + DetailOutline.levelOf(h.vector?.maxZoom, h.dem?.maxZoom).label + SEP + use
    }

    /**
     * The step-down line, or null: a finer holding the lookup passes over because its box only reaches into the tile
     * ([Holdings.How.EDGE], deeper than what serves); else the map whose roads are [enlarged]; else the world overview serving
     * the vector tile where a map or an area holds the place from a deeper zoom on (its box holds the point, it starts below).
     */
    fun stepDown(zoom: Double, lat: Double, lon: Double, holdings: List<Holdings.Holding>, vector: Holdings.Here, dem: Holdings.Here, enlarged: Holdings.Member?): String? {
        for (here in listOf(vector, dem)) {
            val depth = here.serving?.depth ?: continue
            if (here.members.any { !it.serves && it.how == Holdings.How.EDGE && it.depth > depth }) return FINER_MAP_ENDS
        }
        val roads = vector.serving ?: enlarged
        if (roads?.holding?.kind == Holdings.Kind.OVERVIEW) {
            val z = floor(zoom).toInt()
            val ahead = holdings.filter { h ->
                !h.off && (h.kind == Holdings.Kind.MAP || h.kind == Holdings.Kind.AREA) &&
                    h.vector?.let { s -> s.minZoom > z && s.bounds?.let { b -> lon >= b.minLon && lon <= b.maxLon && lat >= b.minLat && lat <= b.maxLat } == true } == true
            }.minByOrNull { it.vector!!.minZoom }
            if (ahead != null) return startsAt(ahead.name, ahead.vector!!.minZoom)
        }
        if (enlarged != null) return enlarged(enlarged.depth)
        return null
    }

    /**
     * The network's line, or null with no source set: where nothing on the phone draws the roads ([roads] null) or the terrain
     * ([terrain] null) and a fetch is allowed, "shown" for those; where it stores deeper than what serves ([Holdings.Network.adds]),
     * the zoom of the view from which it is finer (a vector tile is the view's zoom, an elevation tile one deeper), "finer
     * here" once the view is there; "nothing finer here" otherwise; and why it is not used where it is not: no fetch allowed
     * ("not reachable now"), the setting off.
     */
    fun networkLine(zoom: Double, vector: Holdings.Here, dem: Holdings.Here, roads: String?, terrain: String?): String? {
        val net = vector.network ?: dem.network ?: return null
        val parts = ArrayList<String>()
        val z = floor(zoom).toInt()
        if (net.allowed) {
            when {
                roads == null && terrain == null -> parts += SHOWN
                roads == null -> parts += ROADS_SHOWN
                terrain == null && dem.network?.storedMaxZoom != null && dem.z > 0 -> parts += TERRAIN_SHOWN
            }
        }
        val from = listOfNotNull(
            vector.network?.takeIf { it.adds != null }?.let { vector.serving?.depth?.plus(1) },
            dem.network?.takeIf { it.adds != null }?.let { dem.serving?.depth },
        ).minOrNull()
        if (from != null) {
            parts += if (from <= z) FINER_HERE else finerFrom(from)
            if (!net.detail) parts += DETAIL_OFF
        }
        if (!net.allowed) parts += NOT_REACHABLE
        if (parts.isEmpty()) parts += NOTHING_FINER
        return (listOf(ONLINE) + parts).joinToString(SEP)
    }

    /** [lines] cut to at most [max], the rest as "and N more": the lines shown and how many were left out. */
    fun fit(lines: List<String>, max: Int = MAX_LINES): Pair<List<String>, Int> =
        if (lines.size <= max) lines to 0 else lines.take(max) to (lines.size - max)

    /** The panel as `GET /online` `chip.coverage` reports it: every line, the lines shown, the count left out. */
    fun json(lines: List<String>?): Map<String, Any?>? = lines?.let { l ->
        val (shown, rest) = fit(l)
        linkedMapOf("heading" to HEADING, "lines" to l, "shown" to shown, "more" to rest.takeIf { it > 0 }?.let { more(it) })
    }

    /**
     * The help beside the section's heading (`online.coverage`). Declared beside its icon ([CoverageSection]), gathered by
     * [io.github.jasondorjeshort.dystopo.app.help.HelpRegistry].
     */
    val COVERAGE_HELP: List<HelpText> = listOf(
        HelpText("online.coverage", HEADING, "The map: the status chip's card", needs = "the status chip's card open", show = { OnlineChip.open = true }) {
            "What the phone holds at the centre of the view, the one shown first. A map's level is its detail, as in Offline maps. " +
                "Coarser here means a finer map ends or stops at a lower zoom. Online says what the network adds."
        },
    )
}

/** The section's ink, as the card's. */
private val SectionInk = Color(0xFF2B2420)

/**
 * T360: the panel as a section of the chip's open card ([lines] from [OnlineUi.coverage]): its heading with the help icon, then
 * at most [CoveragePanel.MAX_LINES] lines and "and N more". The lines are plain text (none tappable): a tap on them reaches the
 * card, which closes. The section is held to [maxHeightDp] and scrolls past it (on its side without the heading: [heading] false), so the card shortens before the layout rule
 * would hide the whole online column for want of room (T343's follow-up).
 */
@Composable
internal fun CoverageSection(lines: List<String>, maxHeightDp: Int, heading: Boolean = true) {
    val (shown, rest) = CoveragePanel.fit(lines)
    Column(
        Modifier.testTag("coveragePanel").heightIn(max = maxHeightDp.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (heading) Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(CoveragePanel.HEADING, Modifier.weight(1f, fill = false), style = TextStyle(color = SectionInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            HelpIcon("online.coverage")
        }
        shown.forEach { BasicText(it, style = TextStyle(color = SectionInk, fontSize = 12.sp)) }
        if (rest > 0) BasicText(CoveragePanel.more(rest), style = TextStyle(color = SectionInk, fontSize = 12.sp))
    }
}
