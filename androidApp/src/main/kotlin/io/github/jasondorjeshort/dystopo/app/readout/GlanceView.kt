package io.github.jasondorjeshort.dystopo.app.readout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.controls.ScaleBarLayout
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.nav.NavSession
import io.github.jasondorjeshort.dystopo.app.nav.NavUiState
import io.github.jasondorjeshort.dystopo.app.nav.WayBackClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * T210: the glance view's state (main thread): whether it is open ([open], Compose state), how it was opened and left,
 * and what it last drew ([json], `GET /readout/glance`). Opened by a tap on the readout bar ([ReadoutStrip]), or
 * `POST /readout/glance?open=1`; left by the back gesture, a tap anywhere, or `?open=0`.
 */
object Glance {
    var open by mutableStateOf(false)
        private set

    private var opens = 0
    private var closes = 0
    private var lastOpenedBy: String? = null
    private var lastClosedBy: String? = null

    /** The view is in the composition (its effects ran) and has laid out its lines since the last [show]. */
    internal var composed = false
    internal var laidOut = false

    // What the view drew last (main thread): the lines, the fit, where they sit, and texts that overflowed.
    internal var lines: List<GlanceLine> = emptyList()
    internal var deadlineSource: String? = null
    internal var fit: GlanceFit.Fit? = null
    internal var valueSp = 0f
    internal var areaPx: IntArray? = null
    internal val linePx = LinkedHashMap<String, IntArray>()
    internal val overflow = LinkedHashMap<String, Boolean>()
    internal var night = false
    internal var fontScale = 1f
    internal var view: WeakReference<android.view.View>? = null

    fun show(by: String) {
        if (open) return
        open = true
        opens++
        lastOpenedBy = by
        laidOut = false
        linePx.clear()
        overflow.clear()
    }

    fun close(by: String) {
        if (!open) return
        open = false
        closes++
        lastClosedBy = by
    }

    /** `GET /readout/glance` (main thread). */
    fun json(): Map<String, Any?> {
        val f = fit
        val area = areaPx
        val inside = area != null && linePx.isNotEmpty() && linePx.values.all { it[0] >= area[0] && it[1] >= area[1] && it[2] <= area[2] && it[3] <= area[3] }
        val clipped = overflow.filterValues { it }.keys.toList()
        return linkedMapOf(
            "open" to open,
            "composed" to composed,
            "lines" to if (open) lines.map { l -> l.json() + mapOf("boundsPx" to linePx[l.kind]?.toList()) } else emptyList<Any>(),
            "count" to if (open) lines.size else 0,
            "description" to if (open) GlanceText.description(lines) else null,
            "deadline" to linkedMapOf("shown" to (open && lines.any { it.kind == GlanceLine.DEADLINE }), "source" to deadlineSource),
            "fit" to f?.let {
                linkedMapOf(
                    "valuePx" to r1(it.valuePx), "captionPx" to r1(it.captionPx), "valueSp" to r1(valueSp), "columns" to it.columns,
                    "fits" to it.fits, "areaPx" to area?.toList(),
                )
            },
            "inside" to inside,
            "clipped" to clipped,
            "legible" to (open && laidOut && f?.fits == true && inside && clipped.isEmpty()),
            "night" to night,
            "fontScale" to fontScale,
            "keepScreenOn" to (view?.get()?.keepScreenOn ?: false),
            "opens" to opens, "closes" to closes, "lastOpenedBy" to lastOpenedBy, "lastClosedBy" to lastClosedBy,
            "help" to "readout.glance",
        )
    }

    private fun r1(v: Float) = (v * 10).roundToInt() / 10.0
}

/** T210: the view's hint line and the bar's tap action words (ADR 0031: the owner's to adjust). */
const val GLANCE_HINT = "Tap anywhere or go back to return to the map"
const val GLANCE_OPEN_LABEL = "Show the figures full screen"
const val GLANCE_CLOSE_LABEL = "Return to the map"

/**
 * T210: the glance view: the readout's figures in the largest type that fits, over the whole screen, for a phone in a
 * chest pocket or on a pack strap. Composed over the map's screens in `MainActivity` (one host line), drawn only while
 * [Glance.open].
 *
 * - **The figures** are the readout bar's own entries as it is set ([GlanceText]: each entry's on/off setting, in the
 *   fixed order elevation, climb, speed, course, then T257's pressure, GPS accuracy and fix age, the course always with
 *   its degrees; the survey of 2026-11-02 found watches show 3 to 5 figures and let the
 *   user pick them, and here every entry already has its switch, so there is no second list; no reordering, no pages),
 *   every one drawn whatever the bar left out for room, with the time to the turn-around deadline first while the clock
 *   runs (navigating: [NavSession]'s; recording: [WayBackClock]'s).
 * - **The type** is the largest at which every line's widest text fits ([GlanceFit], fitted on the templates, so it holds
 *   while the figures change); the user's font scale changes nothing, the type is already as large as the screen allows.
 *   Dark on white by day, light on black in the night style (`ScaleBarLayout.night`), the deadline amber or red as the
 *   navigation card colours it, a dimmed line in a gap of the fixes as the bar dims it.
 * - **The screen stays on** while it is open (the view's own flag, as navigation keeps it on); brightness and touch are
 *   left alone (a touch lock or a dimmed screen traps a user who does not know the way out; follow-ups on the owner's word).
 * - **Leaving:** the back gesture or a tap anywhere (the help icon keeps its own tap).
 *
 * `GET /readout/glance` reports what it drew; `POST /readout/glance?open=1|0` opens and closes it ([GlanceRoutes]).
 */
@Composable
fun GlanceView() {
    if (!Glance.open) return
    val view = LocalView.current
    DisposableEffect(Unit) {
        val was = view.keepScreenOn
        view.keepScreenOn = true
        Glance.view = WeakReference(view)
        Glance.composed = true
        onDispose {
            view.keepScreenOn = was
            Glance.composed = false
            Glance.laidOut = false
        }
    }
    BackHandler { Glance.close("back") }
    var lines by remember { mutableStateOf(GlanceText.lines(LiveReadout.snapshot?.items ?: LiveReadout.placeholder())) }
    var source by remember { mutableStateOf<String?>(null) }
    var night by remember { mutableStateOf(ScaleBarLayout.night) } // the map's night style, as the scale bar last drew it
    LaunchedEffect(Unit) {
        while (true) {
            LiveReadout.refresh()
            val items = LiveReadout.snapshot?.items ?: LiveReadout.placeholder()
            // The clock runs when its card shows it (read here, on the main thread); its reading off it.
            val navigating = NavSession.active
            val runs = if (navigating) NavUiState.ui?.turnBack != null else NavUiState.wayBack != null
            val d = if (!runs) null else withContext(Dispatchers.Default) { deadlineNow(navigating) }
            source = d?.let { if (navigating) "navigation" else "recording" }
            lines = GlanceText.lines(items, d)
            night = ScaleBarLayout.night
            Foreground.delay(REFRESH_MS)
        }
    }
    val bg = if (night) Color.Black else Color.White
    val ink = if (night) NightInk else Color.Black
    val muted = if (night) NightMuted else DayMuted
    SideEffect {
        Glance.lines = lines
        Glance.deadlineSource = source
        Glance.night = night
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = GLANCE_CLOSE_LABEL) { Glance.close("tap") },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(GLANCE_HINT, Modifier.weight(1f), style = TextStyle(color = muted, fontSize = 14.sp))
                HelpIcon("readout.glance")
            }
            Figures(lines, ink, muted, night, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** The lines fitted to the room left under the hint line. */
@Composable
private fun Figures(lines: List<GlanceLine>, ink: Color, muted: Color, night: Boolean, modifier: Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    BoxWithConstraints(
        modifier
            .onGloballyPositioned { c -> c.boundsInWindow().let { b -> Glance.areaPx = intArrayOf(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()) } }
            .clearAndSetSemantics { contentDescription = GlanceText.description(lines) },
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Fitted on the templates only: the size changes with the entries shown and the room, never with the figures.
        val shape = lines.map { it.copy(caption = "", value = "", unit = "", trend = null, plusMinus = null, other = null, dimmed = false, colour = "") }
        val fit = remember(shape, w, h, density.density, density.fontScale) {
            // A text's size grows in proportion to its type size on one line: each text is measured once, at REF_DP.
            val ref = with(density) { REF_DP.dp.toPx() }
            val cache = HashMap<Pair<String, Boolean>, Pair<Float, Float>>()
            val m = GlanceFit.Measure { text, px, bold ->
                val at = cache.getOrPut(text to bold) {
                    val r = measurer.measure(text, style(density, ref, bold), softWrap = false, maxLines = 1, density = density)
                    r.size.width.toFloat() to r.size.height.toFloat()
                }
                at.first * px / ref to at.second * px / ref
            }
            GlanceFit.fit(shape, w * WIDTH_SAFETY, h * HEIGHT_SAFETY, with(density) { MIN_DP.dp.toPx() }, with(density) { MAX_DP.dp.toPx() }, m)
        }
        SideEffect {
            Glance.fit = fit
            Glance.valueSp = with(density) { fit.valuePx.toSp().value }
            Glance.fontScale = density.fontScale
        }
        val gap = with(density) { fit.gapPx.toDp() }
        val rows = lines.chunked(fit.columns)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (l in row) Line(l, fit, ink, muted, night, Modifier.weight(1f))
                    if (row.size < fit.columns) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Line(l: GlanceLine, fit: GlanceFit.Fit, ink: Color, muted: Color, night: Boolean, modifier: Modifier) {
    val accent = when (l.colour) {
        "red" -> if (night) NightRed else DayRed
        "amber" -> if (night) NightAmber else DayAmber
        else -> null
    }
    val density = LocalDensity.current
    val valueColor = accent ?: ink
    val captionColor = accent ?: muted
    fun overflow(part: String) = { r: TextLayoutResult -> Glance.overflow["${l.kind}.$part"] = r.hasVisualOverflow }
    Column(
        modifier
            .alpha(if (l.dimmed) DIM else 1f)
            .onGloballyPositioned { c ->
                c.boundsInWindow().let { b -> Glance.linePx[l.kind] = intArrayOf(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()) }
                Glance.laidOut = true
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(l.caption, style = style(density, fit.captionPx, false, captionColor), softWrap = false, maxLines = 1, onTextLayout = overflow("caption"))
        Row {
            BasicText(l.value, Modifier.alignBy(FirstBaseline), style = style(density, fit.valuePx, true, valueColor), softWrap = false, maxLines = 1, onTextLayout = overflow("value"))
            if (l.unit.isNotEmpty()) BasicText(" ${l.unit}", Modifier.alignBy(FirstBaseline), style = style(density, fit.unitPx, false, valueColor), softWrap = false, maxLines = 1, onTextLayout = overflow("unit"))
            l.trend?.let { BasicText(" $it", Modifier.alignBy(FirstBaseline), style = style(density, fit.valuePx, true, valueColor), softWrap = false, maxLines = 1, onTextLayout = overflow("trend")) }
            l.plusMinus?.let { BasicText(" $it", Modifier.alignBy(FirstBaseline), style = style(density, fit.unitPx, false, muted), softWrap = false, maxLines = 1, onTextLayout = overflow("plusMinus")) }
        }
        l.other?.let { BasicText(it, style = style(density, fit.otherPx, false, muted), softWrap = false, maxLines = 1, onTextLayout = overflow("other")) }
    }
}

/** The one text style of the view at type size [px] (through sp, which the font scale maps back to [px]): the value bold, the rest medium. */
private fun style(density: androidx.compose.ui.unit.Density, px: Float, bold: Boolean, color: Color = Color.Unspecified) =
    TextStyle(color = color, fontSize = with(density) { px.toSp() }, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)

/** The turn-around clock's line now (off the main thread: the recording's reading takes its lock). */
private fun deadlineNow(navigating: Boolean): GlanceLine? {
    val json = if (navigating) NavSession.turnJson() else WayBackClock.json()
    val c = json?.get("clock") as? Map<*, *> ?: return null
    val state = c["state"] as? String ?: return null
    val arrival = (c["arrivalMs"] as? Number)?.toLong() ?: return null
    return GlanceText.deadline(state, c["colour"] as? String ?: GlanceLine.NORMAL, (c["turnBackMs"] as? Number)?.toLong(), arrival, System.currentTimeMillis(), ::clock)
}

/** The navigation card's clock text (T218: the phone's 12/24-hour setting, [io.github.jasondorjeshort.dystopo.app.ClockFormat]). */
private fun clock(ms: Long): String = io.github.jasondorjeshort.dystopo.app.ClockFormat.text(ms)

private const val REFRESH_MS = 500L
private const val DIM = 0.6f
private const val REF_DP = 100f

/** The type size's bounds: the smallest still legible at arm's length, the largest a figure needs. */
private const val MIN_DP = 12f
private const val MAX_DP = 320f

/** The fit leaves a little room: a text's size at its fitted size can round a pixel past its measure at [REF_DP]. */
private const val WIDTH_SAFETY = 0.97f
private const val HEIGHT_SAFETY = 0.98f

// High contrast in both themes (each at least 6:1 on its ground): the deadline's amber and red darkened by day and
// lightened at night so they read on white and on black.
private val DayMuted = Color(0xFF3A3A3A)
private val NightInk = Color(0xFFF2ECE2)
private val NightMuted = Color(0xFFC9C1B5)
private val DayAmber = Color(0xFFB35806)
private val DayRed = Color(0xFFC0161A)
private val NightAmber = Color(0xFFFDAE61)
private val NightRed = Color(0xFFFF7A7A)

/** T210: the glance view's help pop-up (its icon in the view's corner). */
internal val GLANCE_HELP: List<HelpText> = listOf(
    HelpText(
        "readout.glance", "Figures full screen", "The map: readout bar, tapped", needs = "the figures full screen",
        show = { Glance.show("help") },
    ) {
        "The readout's figures as large as the screen allows, with the time to turn back first while the turn-around clock " +
            "runs. It shows the entries switched on in the readout's settings. The screen stays on; tap or go back to return."
    },
)
