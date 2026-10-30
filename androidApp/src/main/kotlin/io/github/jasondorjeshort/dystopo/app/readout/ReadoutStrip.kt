package io.github.jasondorjeshort.dystopo.app.readout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    /** `beside` (on the scale bar's line, to its right) or `above` (stacked over it when the line has no room). */
    @Volatile var placement: String? = null
    @Volatile var boundsPx: FloatArray? = null
    @Volatile var density = 1f
    @Volatile var maxWidthPx = 0
    @Volatile var windowHeightPx = 0f

    fun placed(where: String, maxWidth: Int) {
        shown = true
        hiddenBecause = null
        placement = where
        maxWidthPx = maxWidth
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
 * T161: the live readout's strip, drawn by the scale bar's layout on its line (`controls/ScaleBar.kt`): a small plate like
 * the bar's, one entry per shown readout value, each a caption over its number ("speed" over "4 km/h"), dimmed in a gap
 * of the fixes with "no fix" and how long first. Never a box of its own over the map: nothing shows before a fix or with
 * every entry off, and it wraps onto a second line upward rather than run under the recentre button. Polls
 * [LiveReadout.refresh] twice a second while the app is in the foreground ([Foreground.delay]).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ReadoutStrip(night: Boolean) {
    var items by remember { mutableStateOf(LiveReadout.snapshot?.items.orEmpty()) }
    var line by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        LiveReadout.setComposed(true)
        onDispose {
            LiveReadout.setComposed(false)
            StripLayout.hide("notComposed")
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            LiveReadout.refresh()
            val s = LiveReadout.snapshot
            items = s?.items.orEmpty()
            line = s?.line
            Foreground.delay(REFRESH_MS)
        }
    }
    if (items.isEmpty()) return
    val ink = if (night) NightInk else Ink
    val muted = if (night) NightMuted else Muted
    val plate = if (night) NightPlate else Plate
    val density = LocalDensity.current
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
            .semantics { contentDescription = line ?: "" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (it in items) {
            Column(Modifier.alpha(if (it.dimmed) DIM else 1f)) {
                BasicText(it.caption, style = TextStyle(color = muted, fontSize = 10.sp, fontWeight = FontWeight.Medium))
                Row {
                    BasicText(it.value, Modifier.alignBy(FirstBaseline), style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    if (it.unit.isNotEmpty()) BasicText(" " + it.unit, Modifier.alignBy(FirstBaseline), style = TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                    it.plusMinus?.let { pm -> BasicText(" $pm", Modifier.alignBy(FirstBaseline), style = TextStyle(color = muted, fontSize = 11.sp)) }
                }
            }
        }
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
