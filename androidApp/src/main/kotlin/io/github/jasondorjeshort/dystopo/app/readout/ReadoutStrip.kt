package io.github.jasondorjeshort.dystopo.app.readout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets
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

    fun placed(where: String, maxWidth: Int, naturalWidth: Int = 0, besideRoom: Int = 0) {
        shown = true
        hiddenBecause = null
        placement = where
        maxWidthPx = maxWidth
        naturalWidthPx = naturalWidth
        besideRoomPx = besideRoom
    }

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
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ReadoutStrip(night: Boolean) {
    var items by remember { mutableStateOf(LiveReadout.snapshot?.items ?: LiveReadout.placeholder()) }
    var line by remember { mutableStateOf<String?>(null) }
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
            line = s?.line
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
    FlowRow(
        Modifier
            .onGloballyPositioned { c ->
                val b = c.boundsInRoot()
                var r = c
                while (true) r = r.parentLayoutCoordinates ?: break
                StripLayout.boundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom)
                StripLayout.windowHeightPx = r.size.height.toFloat()
                StripLayout.density = density.density
            }
            .background(plate, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = line ?: "" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (it in items) {
            Column(Modifier.alpha(if (it.dimmed) DIM else 1f)) {
                Box {
                    BasicText(it.captionTemplate, Modifier.alpha(0f), style = captionStyle)
                    BasicText(it.caption, style = captionStyle)
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
    }
}

/**
 * One entry's number, unit, the elevation's [trend] arrow (T178: the barometer's trend) and the ±, on one baseline.
 * [trendTemplate]: the invisible template row reserves the widest arrow ([TrendBand.ARROWS] stacked).
 */
@Composable
private fun ValueRow(value: String, unit: String, plusMinus: String?, trend: String?, modifier: Modifier, valueStyle: TextStyle, unitStyle: TextStyle, pmStyle: TextStyle, trendTemplate: Boolean = false) {
    Row(modifier) {
        BasicText(value, Modifier.alignBy(FirstBaseline), style = valueStyle)
        if (unit.isNotEmpty()) BasicText(" $unit", Modifier.alignBy(FirstBaseline), style = unitStyle)
        if (trendTemplate) Box(Modifier.alignBy(FirstBaseline)) { for (a in io.github.jasondorjeshort.dystopo.readout.TrendBand.ARROWS) BasicText(" $a", style = unitStyle) }
        trend?.let { BasicText(" $it", Modifier.alignBy(FirstBaseline), style = unitStyle) }
        plusMinus?.let { pm -> BasicText(" $pm", Modifier.alignBy(FirstBaseline), style = pmStyle) }
    }
}

private const val REFRESH_MS = 500L
private const val DIM = 0.45f

// The scale bar's colours (controls/ScaleBar.kt), so the two read as one line.
private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Plate = Color(0xCCF6F1E4)
private val NightInk = Color(0xFFEDE6DA)
private val NightMuted = Color(0xFFBDB3A6)
private val NightPlate = Color(0xB3201C1A)
