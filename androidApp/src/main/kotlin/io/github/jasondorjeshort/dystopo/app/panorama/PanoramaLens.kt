package io.github.jasondorjeshort.dystopo.app.panorama

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.jasondorjeshort.dystopo.app.gnss.GnssPalette
import io.github.jasondorjeshort.dystopo.app.horizon.LayerToggles
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayer
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayers
import io.github.jasondorjeshort.dystopo.horizon.OffViewArrow
import io.github.jasondorjeshort.dystopo.horizon.PanoramaOverlays
import io.github.jasondorjeshort.dystopo.horizon.PanoramaOverlays.Item
import io.github.jasondorjeshort.dystopo.horizon.RimBox
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * T351: the Panorama's overlays on a plot [plotW] by [plotH], each [slots]' content placed where [PanoramaOverlays]' rule
 * puts it: each plate is asked its size for the width it may take (its intrinsic width and height, so a line wraps at
 * large text), the rule places them, and each is laid out in its box. The boxes go to [PanoramaUi.overlays] (the scene's
 * names keep off them; the waiting line and a flight's card stand in its free band). [switchBox] is the view switch's box
 * on the plot (px), [arrow] the edge arrow's point (its plate is the [Item.ARROW] slot).
 */
@Composable
internal fun LensOverlays(plotW: Dp, plotH: Dp, switchBox: RimBox?, arrow: OffViewArrow?, slots: Map<Item, @Composable () -> Unit>) {
    val density = LocalDensity.current
    SubcomposeLayout(Modifier.size(plotW, plotH).testTag("panoramaOverlays")) { c ->
        val w = c.maxWidth.toDouble()
        val h = c.maxHeight.toDouble()
        val ms = slots.mapValues { (k, content) -> subcompose(k) { Box { content() } }.first() }
        fun plate(k: Item): PanoramaOverlays.Plate? = ms[k]?.let { m ->
            PanoramaOverlays.Plate { maxW ->
                val width = min(m.maxIntrinsicWidth(Constraints.Infinity).toDouble(), maxW)
                PanoramaOverlays.Size(width, m.maxIntrinsicHeight(ceil(width).toInt().coerceAtLeast(1)).toDouble())
            }
        }
        val layers = plate(Item.LAYERS) ?: PanoramaOverlays.Plate { PanoramaOverlays.Size(0.0, 0.0) }
        val list = plate(Item.LIST) ?: PanoramaOverlays.Plate { PanoramaOverlays.Size(0.0, 0.0) }
        val input = PanoramaOverlays.Input(
            w, h, density.density.toDouble(), switchBox, layers, list,
            followWidth = ms[Item.FOLLOW]?.maxIntrinsicWidth(Constraints.Infinity)?.toDouble(),
            place = plate(Item.PLACE), picked = plate(Item.PICKED), hint = plate(Item.HINT),
            credit = plate(Item.CREDIT), correction = plate(Item.CORRECTION),
            arrow = arrow?.let { a -> plate(Item.ARROW)?.let { PanoramaOverlays.Arrow(a.side, a.x, a.y, it) } },
        )
        val l = PanoramaOverlays.place(input)
        if (PanoramaUi.overlays != l) PanoramaUi.overlays = l
        val placed = ms.mapNotNull { (k, m) ->
            l[k]?.let { b ->
                val bw = (b.right - b.left).roundToInt().coerceAtLeast(0)
                val bh = (b.bottom - b.top).roundToInt().coerceAtLeast(0)
                m.measure(Constraints.fixed(bw, bh)) to b
            }
        }
        layout(c.maxWidth, c.maxHeight) { for ((p, b) in placed) p.place(b.left.roundToInt(), b.top.roundToInt()) }
    }
}

/**
 * T351: the layers' panel the Layers button opens (the survey: the layers behind one control): the view's heading with its
 * help ([heading]) and "Show:" with the Horizon view's toggles (Peaks, Sun, Satellites, and Flights while Flight tracking
 * is on; the same synced settings; at a chosen point Satellites and Flights disabled with the sentence why), under the
 * Layers button at the right, at most 360 dp wide; it scrolls when the window is short. A tap outside it, Back or the
 * Layers button closes it; the lens does not turn while it is open.
 */
@Composable
internal fun LayersPanel(
    layers: HorizonLayers, buttons: List<HorizonLayer>, disabled: Set<HorizonLayer>, moved: Boolean, pal: GnssPalette,
    plotW: Dp, plotH: Dp, heading: @Composable () -> Unit,
) {
    val o = PanoramaUi.overlays ?: return
    val density = LocalDensity.current
    val button = o[Item.LAYERS] ?: return
    val gap = PanoramaOverlays.GAP_DP * density.density
    val margin = PanoramaOverlays.MARGIN_DP * density.density
    val top = button.bottom + gap
    val bottom = (o[Item.SWITCH]?.top ?: (with(density) { plotH.toPx() }.toDouble())) - gap
    val width = minOf(plotW - (2 * PanoramaOverlays.MARGIN_DP).dp, 360.dp)
    Box(Modifier.size(plotW, plotH)) {
        // Outside the panel a tap closes it (and does nothing else).
        Box(Modifier.fillMaxSize().testTag("panoramaLayersOutside").pointerInput(Unit) { detectTapGestures { PanoramaUi.layersOpen = false } })
        val x = with(density) { (plotW.toPx() - margin).toFloat() - width.toPx() }
        Column(
            Modifier.offset { IntOffset(x.roundToInt(), top.roundToInt()) }.width(width)
                .heightIn(max = with(density) { (bottom - top).coerceAtLeast(48.0 * density.density).toFloat().toDp() })
                .testTag("panoramaLayers")
                .onGloballyPositioned { c ->
                    val at = c.positionInRoot()
                    PanoramaUi.layersPx = listOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height).map { Math.round(it) }
                }
                .background(pal.card, RoundedCornerShape(12.dp)).border(1.dp, pal.line, RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            heading()
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column { LayerToggles(layers, buttons, disabled, moved, pal) }
            }
        }
    }
}
