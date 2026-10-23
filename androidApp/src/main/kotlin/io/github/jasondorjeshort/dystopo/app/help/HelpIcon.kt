package io.github.jasondorjeshort.dystopo.app.help

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import io.github.jasondorjeshort.dystopo.app.AppSettings
import kotlin.math.roundToInt

/** T109: the icon's size at font scale 1.0, in sp so it grows with the font scale like the text beside it. */
private const val ICON_SP = 24f

/** T109: the smallest touch target around the icon; it grows with the icon (the icon plus [TARGET_PAD]). */
private val MIN_TARGET: Dp = 48.dp
private val TARGET_PAD: Dp = 20.dp

/**
 * T109: the familiar question mark beside a control, opening its help pop-up ([HelpHost]). The icon is 24 dp at
 * font scale 1.0 (sized in sp, so it grows with the font scale as the row's label does) inside a touch target of at
 * least 48 dp (the icon plus 20 dp). [key] names a [HelpText] in [HelpRegistry]; a key without text draws nothing
 * (`HelpRegistryTest` fails first). While composed it reports where it is ([HelpUi.anchors], `GET /help`); when its
 * pop-up opens it scrolls itself into view, so `POST /help/open` finds it on a long page; when it leaves the screen
 * (its sheet closed) its pop-up closes too.
 *
 * T128: [flat] keeps the touch target but lays the icon out at its own height, the target reaching past the row above
 * and below without making it taller (the navigation card's header was 48 dp at font scale 1.0 for a 13 sp line). Compose
 * hit-tests a child outside its parent's bounds when nothing clips, so the whole target still takes the tap.
 *
 * Spoken as "Help: <title>" (a button); the question mark itself is not text in the accessibility tree, so the
 * scripts that find a row by its label never meet it.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun HelpIcon(key: String, modifier: Modifier = Modifier, flat: Boolean = false) {
    val help = HelpRegistry.byKey(key) ?: return
    val icon = with(LocalDensity.current) { ICON_SP.sp.toDp() }
    val target = maxOf(MIN_TARGET, icon + TARGET_PAD)
    val open = HelpUi.openKey == key
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(open, if (open) HelpUi.opened else -1) { if (open) runCatching { requester.bringIntoView() } } // T122: again on a reopen
    DisposableEffect(key) {
        onDispose {
            HelpUi.anchors.remove(key)
            // Its screen closed: no pop-up left over another one. T122: unless the icon comes straight back, as a lazy
            // list's row does while it scrolls itself into view (the Storage rows lost their pop-up that way at 1.0).
            if (HelpUi.openKey == key) HelpUi.closeUnlessBack(key)
        }
    }
    Box(
        modifier
            .let { m -> if (flat) m.layout { measurable, constraints -> flatLayout(measurable, constraints, icon.roundToPx()) } else m }
            .size(target)
            .bringIntoViewRequester(requester)
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                val r = PxRect(p.x.roundToInt(), p.y.roundToInt(), (p.x + c.size.width).roundToInt(), (p.y + c.size.height).roundToInt())
                if (HelpUi.anchors[key] != r) HelpUi.anchors[key] = r
            }
            .clickable(role = Role.Button, onClickLabel = "Show help") { HelpUi.open(key) }
            .semantics { contentDescription = "Help: ${help.title}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(icon).background(if (open) Accent else Paper, CircleShape).border(1.5.dp, Accent, CircleShape).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                "?",
                style = TextStyle(color = if (open) Paper else Accent, fontSize = (ICON_SP * 0.62f).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

/**
 * T128: lays the target out at [heightPx] (the icon's height) and centres it on that height, so the target overflows the
 * row by the same amount above and below. The row's other content decides its height.
 */
private fun MeasureScope.flatLayout(measurable: Measurable, constraints: Constraints, heightPx: Int): MeasureResult {
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val h = minOf(p.height, heightPx).coerceIn(constraints.minHeight, constraints.maxHeight)
    return layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

/**
 * T109: the open help pop-up, drawn over the screens (call it after them): a faint scrim over everything and a small
 * card with the title, the text in the user's units and OK, beside its icon (below it when it fits there, else
 * above, with a caret pointing at the icon); T122: when the text fits on neither side (large font scales) on the larger
 * side with the text a step smaller ([PopupPlace]), never over the icon; in the middle when the icon is not on screen. A tap anywhere or Back closes it. Its layout is reported in
 * [HelpUi.popup] for `POST /help/open`.
 */
@Composable
fun HelpHost() {
    val key = HelpUi.openKey ?: return
    val help = HelpRegistry.byKey(key) ?: return
    val anchorWin = HelpUi.anchors[key]
    BackHandler { HelpUi.close() }
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    var windowH by remember { mutableStateOf(0) }
    // T122: an icon laid out outside the window (a row still scrolling into view) counts as none: the pop-up waits in the
    // middle rather than above an icon below the screen, and moves beside it once it is in view.
    val anchor = anchorWin?.let { PxRect(it.left - origin.x, it.top - origin.y, it.right - origin.x, it.bottom - origin.y) }
        ?.takeIf { a -> windowH == 0 || (a.top >= 0 && a.bottom <= windowH) }
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                val o = IntOffset(p.x.roundToInt(), p.y.roundToInt())
                if (o != origin) origin = o
                if (c.size.height != windowH) windowH = c.size.height
            }
            .background(Scrim)
            .pointerInput(Unit) { detectTapGestures { HelpUi.close() } },
    ) {
        val baseDensity = density.density
        SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val margin = MARGIN.roundToPx()
            val gap = CARET_H.roundToPx()
            val bubbleW = minOf(w - 2 * margin, MAX_WIDTH.roundToPx()).coerceAtLeast(1)
            val below = anchor?.let { h - margin - (it.bottom + gap) } ?: 0
            val above = anchor?.let { it.top - gap - margin } ?: 0
            // The bubble at a text scale (the system's, or less to fit beside the icon), measured once per scale.
            val bubbles = HashMap<Float, Pair<Measurable, Int>>()
            fun bubbleAt(scale: Float): Pair<Measurable, Int> = bubbles.getOrPut(scale) {
                val m = subcompose("bubble@$scale") {
                    CompositionLocalProvider(LocalDensity provides Density(baseDensity, scale)) { Bubble(help) }
                }.single()
                m to m.maxIntrinsicHeight(bubbleW)
            }
            // T122: always beside the icon, never over it (two pop-ups covered their icon at font scale 2.0). Below or
            // above at the system's text size when it fits there; else on the larger side with the text a step
            // smaller at a time, down to [PopupPlace.MIN_TEXT_SCALE] of it, until it fits; else that size, scrolling.
            val place = PopupPlace.choose(anchor != null, below, above, fontScale) { s -> bubbleAt(s).second }
            val (measurable, need) = bubbleAt(place.textScale)
            val room = when (place.side) {
                "below" -> below
                "above" -> above
                else -> h - 2 * margin
            }.coerceAtLeast(1)
            val bubble = measurable.measure(Constraints(minWidth = bubbleW, maxWidth = bubbleW, maxHeight = room))
            val caret = subcompose(if (place.side == "above") "caretDown" else "caretUp") { Caret(up = place.side != "above") }
                .single().measure(Constraints())
            val cx = anchor?.let { (it.left + it.right) / 2 } ?: (w / 2)
            val x = (cx - bubbleW / 2).coerceIn(margin, (w - margin - bubbleW).coerceAtLeast(margin))
            val y = when (place.side) {
                "below" -> anchor!!.bottom + gap
                "above" -> anchor!!.top - gap - bubble.height
                else -> (h - bubble.height) / 2
            }
            val caretX = (cx - caret.width / 2).coerceIn(x + margin, (x + bubbleW - margin - caret.width).coerceAtLeast(x + margin))
            HelpUi.popup = linkedMapOf(
                "key" to key, "side" to place.side,
                "boundsPx" to listOf(x + origin.x, y + origin.y, x + bubbleW + origin.x, y + bubble.height + origin.y),
                "windowPx" to listOf(origin.x, origin.y, origin.x + w, origin.y + h),
                "iconPx" to anchorWin?.json(),
                "inside" to (x >= 0 && y >= 0 && x + bubbleW <= w && y + bubble.height <= h),
                "textFits" to (need <= bubble.height),
                "coversIcon" to (anchor != null && PxRect(x, y, x + bubbleW, y + bubble.height).overlaps(anchor)),
                "fontScale" to fontScale,
                "textScale" to (place.textScale * 100).roundToInt() / 100.0, // T122: the text scale the pop-up drew at (at most fontScale)
            )
            layout(w, h) {
                bubble.place(x, y)
                if (place.side != "centre") caret.place(caretX, if (place.side == "above") y + bubble.height - 1 else y - caret.height + 1)
            }
        }
    }
}

@Composable
private fun Bubble(help: HelpText) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.shadow(8.dp, shape).background(Paper, shape).border(1.dp, Line, shape)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(help.title, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        BasicText(help.text(AppSettings.units), style = TextStyle(color = Ink, fontSize = 13.sp, lineHeight = 19.sp))
        BasicText(
            "OK",
            Modifier.align(Alignment.End).clickable { HelpUi.close() }.padding(horizontal = 12.dp, vertical = 6.dp),
            style = TextStyle(color = LinkBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** The small triangle between the pop-up and its icon ([up]: the pop-up is below the icon, the caret points up). */
@Composable
private fun Caret(up: Boolean) {
    Canvas(Modifier.size(CARET_W, CARET_H)) {
        val base = if (up) size.height else 0f
        val tip = if (up) 0f else size.height
        val p = Path().apply {
            moveTo(0f, base)
            lineTo(size.width / 2, tip)
            lineTo(size.width, base)
            close()
        }
        drawPath(p, Paper)
        drawLine(Line, Offset(0f, base), Offset(size.width / 2, tip), strokeWidth = 1.dp.toPx())
        drawLine(Line, Offset(size.width / 2, tip), Offset(size.width, base), strokeWidth = 1.dp.toPx())
    }
}

private val MARGIN: Dp = 12.dp
private val MAX_WIDTH: Dp = 420.dp
private val CARET_W: Dp = 18.dp
private val CARET_H: Dp = 9.dp

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
private val LinkBlue = Color(0xFF2C7BB6)
private val Scrim = Color(0x29000000)
