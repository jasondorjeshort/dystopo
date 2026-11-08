package io.github.jasondorjeshort.dystopo.app.readout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.controls.RecenterLayout
import io.github.jasondorjeshort.dystopo.app.controls.ScaleBarLayout
import io.github.jasondorjeshort.dystopo.app.routing.BottomPanels

/**
 * T161: where the readout strip is and why not, for `GET /readout` `layout` ([LiveReadout.json]). The scale bar's layout
 * (`controls/ScaleBar.kt`) places the strip and writes [placement] and [hiddenBecause]; the strip writes its bounds.
 */
object StripLayout {
    @Volatile var shown = false
        private set
    @Volatile var hiddenBecause: String? = "notComposed"
        private set

    /** T204: why the bar is not drawn when even the elevation alone does not fit below the pressure warning's card. */
    const val NO_ROOM_BELOW_WARNING_CARD = "noRoomBelowWarningCard"

    /** T177: why the bar is not drawn when its switch is off. */
    const val OFF = "off"

    /**
     * `beside` (on the scale bar's line, to its right) or `above` (stacked over it when the line has no room). T177: the
     * choice is made on the bar's own width, which its templates fix for the settings, so it does not change with the
     * values: beside when the whole bar fits on the line in one row, else above.
     */
    @Volatile var placement: String? = null
    @Volatile var boundsPx: FloatArray? = null
    @Volatile var density = 1f
    @Volatile var maxWidthPx = 0

    /** T177: the bar's width in one row ([placed] decides on it) and the room the line had beside the scale bar. */
    @Volatile var naturalWidthPx = 0
    @Volatile var besideRoomPx = 0
    @Volatile var windowHeightPx = 0f

    /**
     * T204: the bar's room was too small for every shown entry (font scale 2.0): it draws the first [drawnItems] of
     * them in their order (elevation first) and leaves out [droppedItems] from the end; [fits] false when even the
     * elevation alone does not fit, and the scale bar's layout then hides the bar. Set by the bar's own measure, read
     * by the scale bar's layout right after it.
     */
    @Volatile var fits = true
    @Volatile var drawnItems = 0
    @Volatile var droppedItems: List<String> = emptyList()

    /** T224: why the bar is not drawn when the right-hand chips leave even the elevation no room. */
    const val NO_ROOM_BESIDE_CHIPS = "noRoomBesideChips"

    /**
     * T224: the elevation entry's plate as last measured by the bar's own layout (px, its height with the plate's padding;
     * 0 before the first measure), kept while the bar is hidden. Compose state: the cards' reservation for the scale bar's
     * line (`BottomPanels.mapRowDp`) reads it, measured where T212 estimated it from line-height constants.
     */
    var elevationPlatePx by mutableStateOf(0)
        private set

    /** T224: every entry's measured size in drawn order (px, without the plate's padding), for `GET /layout`'s function. */
    @Volatile var entrySizesPx: List<IntArray> = emptyList()
        private set

    internal fun entriesMeasured(sizes: List<IntArray>, platePaddingPx: Int) {
        entrySizesPx = sizes
        val plate = (sizes.firstOrNull()?.get(1) ?: return) + platePaddingPx
        if (plate != elevationPlatePx) elevationPlatePx = plate
    }

    fun placed(where: String, maxWidth: Int, naturalWidth: Int = 0, besideRoom: Int = 0) {
        shown = true
        hiddenBecause = null
        placement = where
        maxWidthPx = maxWidth
        naturalWidthPx = naturalWidth
        besideRoomPx = besideRoom
    }

    // T197: the mark beside the elevation while a known height holds (window pixels; null while not drawn), and its taps.
    @Volatile var markBoundsPx: FloatArray? = null
    @Volatile var markTaps = 0
    @Volatile var markLastTap: String? = null
    // end T197

    fun hide(reason: String) {
        if (shown) LiveReadout.refresh() // the barometer stops at once
        shown = false
        hiddenBecause = reason
        placement = null
        boundsPx = null
    }

    private fun overlaps(a: FloatArray?, b: FloatArray?): Boolean? {
        if (a == null || b == null) return null
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
    }

    fun json(): Map<String, Any?> {
        val b = boundsPx
        val d = density
        val h = windowHeightPx
        val bottomDp = if (b != null && h > 0) (h - b[3]) / d else null
        val panelsShown = BottomPanels.trackPanelsTop > BottomPanels.trackPanelsBottom
        val floor = maxOf(
            AttributionInsets.bottom.value, BottomPanels.routeCardTop.value, if (panelsShown) BottomPanels.trackPanelsTop.value else 0f,
            io.github.jasondorjeshort.dystopo.app.MapInsets.bottomCardsTop.value,
        )
        val navBottom = ScaleBarLayout.navCardBottomDp
        return linkedMapOf(
            "shown" to (shown && b != null),
            "hiddenBecause" to hiddenBecause,
            "placement" to placement,
            "boundsPx" to b?.map { Math.round(it) },
            "maxWidthPx" to maxWidthPx,
            "naturalWidthPx" to naturalWidthPx, "besideRoomPx" to besideRoomPx, // T177
            "plateOpaque" to PLATE_OPAQUE, // T192
            "drawnItems" to drawnItems, "droppedItems" to droppedItems, "fits" to fits, // T204: entries left out for room, from the end
            "sizePx" to b?.let { listOf(Math.round(it[2] - it[0]), Math.round(it[3] - it[1])) },
            "topDp" to b?.let { Math.round(it[1] / d * 10) / 10.0 },
            "bottomFromWindowBottomDp" to bottomDp?.let { Math.round(it * 10) / 10.0 },
            "scaleBarBoundsPx" to ScaleBarLayout.boundsPx?.map { Math.round(it) },
            "recentreBoundsPx" to RecenterLayout.boundsPx?.map { Math.round(it) },
            "navCardBottomDp" to navBottom,
            // Clear of the scale bar, the recentre button, the navigation card, and the bottom panels and the credit line.
            "clearOfScaleBar" to overlaps(b, ScaleBarLayout.boundsPx)?.not(),
            "clearOfRecentre" to overlaps(b, RecenterLayout.boundsPx)?.not(),
            "clearOfNavCard" to b?.let { navBottom <= 0f || it[1] / d >= navBottom },
            "clearOfBottomPanels" to bottomDp?.let { it >= floor - 0.5f },
        )
    }
}

/**
 * T161: the live readout's bar, drawn by the scale bar's layout on its line (`controls/ScaleBar.kt`): a small plate like
 * the bar's, one entry per shown readout value, each a caption over its number ("elevation" over "1650 m"), dimmed in a
 * gap of the fixes with "no fix" and how long as the first caption. Never a box of its own over the map. Polls
 * [LiveReadout.refresh] twice a second while the app is in the foreground ([Foreground.delay]).
 *
 * T177: always drawn while the bar switch is on (`readoutBar`; the scale bar leaves it out otherwise): every shown entry
 * is drawn whatever its value, a dash where it is not known, each entry as wide as its widest text ([StripItem.template]
 * drawn invisibly under the real one), so the bar never comes and goes, never changes width and nothing in it moves.
 * In both-units mode each entry has the other system's text under it, as the scale bar has. Only every entry switched
 * off draws nothing.
 *
 * T204: where the room the scale bar's layout gives it is too small for every entry (font scale 2.0), entries are left
 * out from the end of their order until the rest fits ([DroppingFlow]); it hides only when the elevation alone does
 * not fit. `GET /readout` > `layout` says which (`drawnItems`, `droppedItems`, `fits`); T210: the description reads the entries drawn only.
 *
 * T197: while a known height holds (`readout/KnownHeights`, [LiveReadout.pin]) a small triangle sits beside the
 * elevation's caption ([PinMark]); its description is "Set at <name>" ("Set by you" for a typed height), a tap shows the
 * time and the height, and it goes without a word when the estimate no longer trusts the set height.
 *
 * T210: a tap on the bar (the mark keeps its own) opens the glance view ([GlanceView], every entry in the largest type
 * that fits); a screen reader offers it as the bar's action. The description reads the entries drawn only (T204's
 * follow-up: it read the ones left out for room too); the glance view reads them all.
 */
@Composable
fun ReadoutStrip(night: Boolean) {
    var items by remember { mutableStateOf(LiveReadout.snapshot?.items ?: LiveReadout.placeholder()) }
    var markLabel by remember { mutableStateOf<String?>(null) } // T210: the description's last words while the mark shows
    var drawn by remember { mutableStateOf(Int.MAX_VALUE) } // T210: the entries the bar's measure kept ([DroppingFlow])
    var mark by remember { mutableStateOf(false) } // T197
    DisposableEffect(Unit) {
        LiveReadout.setComposed(true)
        onDispose {
            LiveReadout.setComposed(false)
            StripLayout.hide(if (AppSettings.readoutBar) "notComposed" else StripLayout.OFF)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            LiveReadout.refresh()
            val s = LiveReadout.snapshot
            items = s?.items ?: LiveReadout.placeholder()
            markLabel = if (s?.markShown == true) io.github.jasondorjeshort.dystopo.readout.KnownHeights.markLabel(s.readout.barometer.pinnedName) else null
            mark = s?.markShown == true // T197
            if (!mark) StripLayout.markBoundsPx = null
            Foreground.delay(REFRESH_MS)
        }
    }
    if (items.isEmpty()) return
    val ink = if (night) NightInk else Ink
    val muted = if (night) NightMuted else Muted
    val plate = if (night) NightPlate else Plate
    val density = LocalDensity.current
    val captionStyle = TextStyle(color = muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val valueStyle = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val unitStyle = TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val pmStyle = TextStyle(color = muted, fontSize = 11.sp)
    val otherStyle = TextStyle(color = muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val markStyle = TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold) // T197
    val context = LocalContext.current
    val entries = items.map { it.entry.code }
    val description = StripText.line(items.take(drawn), markLabel) ?: "" // T210
    Layout(
        modifier = Modifier
            .control("readoutStrip") // T238
            .onGloballyPositioned { c ->
                val b = c.boundsInRoot()
                var r = c
                while (true) r = r.parentLayoutCoordinates ?: break
                StripLayout.boundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom)
                StripLayout.windowHeightPx = r.size.height.toFloat()
                StripLayout.density = density.density
            }
            .background(plate, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = PLATE_PADDING_Y)
            // T210: a tap opens the glance view; the mark's own tap (a child's) is taken first and does not open it.
            .pointerInput(Unit) { detectTapGestures(onTap = { Glance.show("bar") }) }
            .clearAndSetSemantics {
                contentDescription = description
                onClick(label = GLANCE_OPEN_LABEL) { Glance.show("bar"); true }
            },
        measurePolicy = remember(entries) { DroppingFlow(entries, ITEM_GAP, ROW_GAP) { k -> if (drawn != k) drawn = k } },
        content = {
        for (it in items) {
            // T197: the elevation carries the known height's mark; a tap on it says where and when the height was set.
            val markSlot = it.entry == io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.ELEVATION && it.trendSlot
            val tapMark = markSlot && mark
            Column(Modifier.alpha(if (it.dimmed) DIM else 1f).then(if (tapMark) Modifier.clickable { onMarkTap(context) } else Modifier)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        BasicText(it.captionTemplate, Modifier.alpha(0f), style = captionStyle)
                        BasicText(it.caption, style = captionStyle)
                    }
                    if (markSlot) PinMark(mark, markStyle)
                }
                Box {
                    ValueRow(it.template.text, it.template.unit, it.template.plusMinus, null, Modifier.alpha(0f), valueStyle, unitStyle, pmStyle, trendTemplate = it.trendSlot)
                    ValueRow(it.value, it.unit, it.plusMinus, it.trend, Modifier, valueStyle, unitStyle, pmStyle)
                }
                it.otherTemplate?.let { t ->
                    Box {
                        BasicText(t, Modifier.alpha(0f), style = otherStyle)
                        BasicText(it.other.orEmpty(), style = otherStyle)
                    }
                }
            }
        }
        },
    )
}

/**
 * T204: the bar's entries flowed in rows like a flow row (gaps [itemGap] and [rowGap]), but when the rows do not fit
 * the height the scale bar's layout leaves (font scale 2.0 with the bar above the scale bar), the entries are left out
 * from the end of their order one by one ([entries]: the order the readout settings list them, elevation first) until
 * the rest fits; with only the elevation left and still too tall, [StripLayout.fits] is false and the scale bar's
 * layout hides the bar. The intrinsic width is every entry on one row (the scale bar's beside-or-above choice).
 *
 * T239: how many entries it draws is the layout rule's (`app/MapLayout.kt`, the readout's entries one rung each of its
 * ladder, the elevation last): it starts from the rule's count, within the width the rule wrapped them at (its control
 * node's limit), and drops more only if a height still cuts it; before the rule's first pass, every entry.
 */
private class DroppingFlow(val entries: List<String>, val itemGap: Dp, val rowGap: Dp, val onDrawn: (Int) -> Unit = {}) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gapX = itemGap.roundToPx()
        val gapY = rowGap.roundToPx()
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val placeables = measurables.map { it.measure(Constraints(maxWidth = maxW)) }
        StripLayout.entriesMeasured(placeables.map { intArrayOf(it.width, it.height) }, PLATE_PADDING_Y.roundToPx() * 2) // T224

        // The rows of the first k entries: (first index, width, height) per row.
        fun rows(k: Int): List<IntArray> {
            val out = ArrayList<IntArray>()
            var start = 0
            var w = 0
            var h = 0
            for (i in 0 until k) {
                val p = placeables[i]
                if (i > start && w + gapX + p.width > maxW) {
                    out += intArrayOf(start, w, h)
                    start = i
                    w = 0
                    h = 0
                }
                w += (if (i > start) gapX else 0) + p.width
                h = maxOf(h, p.height)
            }
            if (k > start) out += intArrayOf(start, w, h)
            return out
        }

        fun height(r: List<IntArray>) = r.sumOf { it[2] } + gapY * (r.size - 1).coerceAtLeast(0)
        var k = io.github.jasondorjeshort.dystopo.app.MapLayout.controls.entriesFor("readoutStrip")?.takeIf { it >= 1 }?.coerceAtMost(placeables.size) ?: placeables.size
        var r = rows(k)
        while (k > 1 && height(r) > maxH) {
            k--
            r = rows(k)
        }
        StripLayout.fits = k == 0 || height(r) <= maxH
        StripLayout.drawnItems = k
        StripLayout.droppedItems = entries.drop(k)
        onDrawn(k) // T210: the description reads these
        val width = (r.maxOfOrNull { it[1] } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = height(r).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            var y = 0
            for ((ri, row) in r.withIndex()) {
                val end = if (ri + 1 < r.size) r[ri + 1][0] else k
                var x = 0
                for (i in row[0] until end) {
                    placeables[i].place(x, y)
                    x += placeables[i].width + gapX
                }
                y += row[2] + gapY
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.sumOf { it.maxIntrinsicWidth(height) } + itemGap.roundToPx() * (measurables.size - 1).coerceAtLeast(0)

    /** The narrowest the bar draws: the first entry (the elevation) alone, on its row; the scale bar's layout reads it. */
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.firstOrNull()?.maxIntrinsicWidth(height) ?: 0

    /** The lowest the bar draws: the first entry alone. */
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.firstOrNull()?.maxIntrinsicHeight(width) ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.sumOf { it.maxIntrinsicHeight(width) } + rowGap.roundToPx() * (measurables.size - 1).coerceAtLeast(0)
}

/** T161: the gap between entries; T204: and between rows, as the flow row had them. */
private val ITEM_GAP = 10.dp
private val ROW_GAP = 2.dp

/** T224: the plate's padding above and below the entries ([StripLayout.elevationPlatePx] adds it to the measured entry). */
private val PLATE_PADDING_Y = 3.dp

/**
 * T197: the known height's mark beside the elevation's caption: a survey point's triangle ([LiveReadout.PIN_GLYPH]),
 * as topographic maps mark a height measured on the ground; its slot is kept while hidden so the bar never changes
 * width (T177). Cleared silently when the pin no longer holds.
 */
@Composable
private fun PinMark(shown: Boolean, style: TextStyle) {
    BasicText(
        " " + LiveReadout.PIN_GLYPH,
        Modifier.alpha(if (shown) 1f else 0f).onGloballyPositioned { c ->
            if (shown) c.boundsInRoot().let { b -> StripLayout.markBoundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom) }
        },
        style = style,
    )
}

/** T197: a tap on the mark: one line, where and when the height was set and to what ("Set at Alta Vista, 10:42, 5938 ft"). */
private fun onMarkTap(context: android.content.Context) {
    val text = LiveReadout.markDetail() ?: return
    StripLayout.markTaps++
    StripLayout.markLastTap = text
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
}
// end T197

/**
 * One entry's number, unit, the elevation's [trend] arrow (T178: the barometer's trend) and the ±, on one baseline.
 * [trendTemplate]: the invisible template row reserves the widest arrow ([TrendBand.ARROWS] stacked).
 */
@Composable
private fun ValueRow(value: String, unit: String, plusMinus: String?, trend: String?, modifier: Modifier, valueStyle: TextStyle, unitStyle: TextStyle, pmStyle: TextStyle, trendTemplate: Boolean = false) {
    Row(modifier) {
        BasicText(value, Modifier.alignBy(FirstBaseline), style = valueStyle)
        if (unit.isNotEmpty()) BasicText(" $unit", Modifier.alignBy(FirstBaseline), style = unitStyle)
        // T178: the arrow at the number's size, so it reads at a glance.
        if (trendTemplate) Box(Modifier.alignBy(FirstBaseline)) { for (a in io.github.jasondorjeshort.dystopo.readout.TrendBand.ARROWS) BasicText(" $a", style = valueStyle) }
        trend?.let { BasicText(" $it", Modifier.alignBy(FirstBaseline), style = valueStyle) }
        plusMinus?.let { pm -> BasicText(" $pm", Modifier.alignBy(FirstBaseline), style = pmStyle) }
    }
}

private const val REFRESH_MS = 500L
private const val DIM = 0.45f

/** T192: both plates are drawn at full alpha (`GET /readout` `layout.plateOpaque`). */
internal val PLATE_OPAQUE: Boolean get() = Plate.alpha == 1f && NightPlate.alpha == 1f

// The scale bar's ink (controls/ScaleBar.kt), so the two read as one line; T192: the plate opaque, as T177 specified,
// so contours and labels never show through the numbers.
private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Plate = Color(0xFFF6F1E4)
private val NightInk = Color(0xFFEDE6DA)
private val NightMuted = Color(0xFFBDB3A6)
private val NightPlate = Color(0xFF201C1A)
