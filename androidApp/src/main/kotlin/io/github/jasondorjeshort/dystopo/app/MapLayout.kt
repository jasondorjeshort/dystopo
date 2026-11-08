package io.github.jasondorjeshort.dystopo.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jasondorjeshort.dystopo.app.controls.RecenterLayout
import io.github.jasondorjeshort.dystopo.app.controls.ScaleBarLayout
import io.github.jasondorjeshort.dystopo.app.nav.NavCardBand
import io.github.jasondorjeshort.dystopo.app.readout.CompassView
import io.github.jasondorjeshort.dystopo.app.readout.StripLayout
import io.github.jasondorjeshort.dystopo.app.readout.WarningCardLayout
import io.github.jasondorjeshort.dystopo.app.routing.BottomPanels
import io.github.jasondorjeshort.dystopo.app.routing.CardLayout
import io.github.jasondorjeshort.dystopo.ui.OverlayCard
import io.github.jasondorjeshort.dystopo.ui.OverlayIds
import io.github.jasondorjeshort.dystopo.ui.OverlayInput
import io.github.jasondorjeshort.dystopo.ui.OverlayLayout
import io.github.jasondorjeshort.dystopo.ui.OverlayPlacement
import io.github.jasondorjeshort.dystopo.ui.OverlaySize

/**
 * T238 (ADR 0053 §5 part c, build step 2): names this element as the controls manifest's item [id] (an Android
 * placement outside zone `settings` in `shared/.../controls/`), as a semantics test tag the device tour and
 * `GET /controls` read. One call on the element's outermost measured node; `ControlIdsTest` reads every call (a literal
 * id) against the manifest both ways.
 *
 * T241 (step 2 of T224's design note): and a measuring node ([ControlNode]) in observing mode. In measure it measures its
 * content and gives the size to [MapLayout.controls] ([MeasuredControls.measured]), a plain field; its placement block asks
 * [MeasuredControls.placement], computed once per pass from this pass's sizes, and places the content where its own
 * parent put it (today the placement moves nothing; T239 places from the rule here), then publishes the element's
 * rectangle from this node. `GET /layout` and `GET /controls` read those rectangles. Nothing in it is Compose state, so
 * no reader waits a frame for it (T146).
 */
fun Modifier.control(id: String): Modifier = this.testTag(id).then(ControlElement(id))

/** T241: [control]'s element. */
private class ControlElement(val id: String) : ModifierNodeElement<ControlNode>() {
    override fun create() = ControlNode(id)
    override fun update(node: ControlNode) = node.retag(id)
    override fun equals(other: Any?) = other is ControlElement && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * T241: [control]'s node. Measures its content with the constraints it is given and returns that size, so the element
 * measures and places exactly as without it. Its slot in [MapLayout.controls] lives while the node is attached; the
 * enclosing control node, if any, is its parent (the Online chip's card lies in the online column), so a card whose
 * column is not placed is not on screen either. A node measured and then not placed in the same pass (a chip column the
 * credit line leaves no room, `TopChips.FollowNode`) is hidden; a layout node its parent does not place (the scale bar's
 * line, `controls/ScaleBar.kt`) is not measured again, so [MapLayout] also asks its semantics whether it is placed.
 * Intrinsic queries pass straight through: the default ones would run [measure] with made-up constraints and publish a
 * size no pass measured.
 */
internal class ControlNode(private var id: String) : Modifier.Node(), LayoutModifierNode, TraversableNode {
    private var slot: MeasuredControls.Slot? = null
    override val traverseKey: Any get() = TRAVERSE_KEY

    override fun onAttach() = take()
    override fun onDetach() = release()

    private fun take() {
        slot = MapLayout.controls.attach(id, findNearestAncestor()?.slot)
    }

    private fun release() {
        slot?.let { MapLayout.controls.detach(it) }
        slot = null
    }

    fun retag(id: String) {
        if (id == this.id) return
        this.id = id
        if (isAttached) {
            release()
            take()
            invalidateMeasurement()
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        val s = slot?.takeIf { !isLookingAhead }
        if (s != null) MapLayout.controls.measured(s, IntSize(p.width, p.height))
        return layout(p.width, p.height) {
            p.place(if (s != null) MapLayout.controls.placement().offset(s.id) else IntOffset.Zero)
            if (s != null) coordinates?.let { c -> c.boundsInRoot().let { b -> MapLayout.controls.placed(s, floatArrayOf(b.left, b.top, b.right, b.bottom)) } }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = measurable.minIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = measurable.maxIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = measurable.minIntrinsicHeight(width)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = measurable.maxIntrinsicHeight(width)

    private companion object {
        const val TRAVERSE_KEY = "dystopo.control"
    }
}

/**
 * T241 (pure, `MapLayoutTest`): what the [control] nodes measured and placed, for `GET /layout`, `GET /controls` and,
 * from T239, the placement itself. Plain fields, never Compose state (T146: state written in layout reaches its readers a
 * frame later); main thread only. [MapLayout.controls] is the app's; a test makes its own.
 *
 * The pass counter: Compose measures the nodes that need it before it places them, so a size measured after [placement]
 * was computed starts the next pass ([pass]), and the next [placement] is computed again from the sizes measured so far,
 * this pass's for every node measured in it ([computed] counts the computations: once per pass however many nodes ask).
 * A hidden element (composed and measured, not placed) keeps its last size, so the rule can bring it back (T224's rule);
 * one that leaves the composition drops it.
 */
class MeasuredControls {
    /** One [control] node: [id] its manifest item, [parent] the control it lies inside, if any. */
    class Slot internal constructor(val id: String, val parent: Slot?) {
        /** As last measured, px; kept while the element is hidden; null before the first measure. */
        var size: IntSize? = null
            internal set

        /** As last placed, px from the window's top left (left, top, right, bottom); null before the first placement. */
        var rectPx: FloatArray? = null
            internal set
        internal var placed = false
        internal var attached = true

        /** Placed since its last measure, attached, inside a parent that is on screen, and not empty: drawn in the window now. */
        val onScreen: Boolean
            get() = attached && placed && (parent?.onScreen ?: true) && rectPx.let { it != null && it[2] - it[0] > 0f && it[3] - it[1] > 0f }
    }

    /** One pass's placement: the sizes it was computed from (by id), and where each element goes. */
    class Placement internal constructor(val pass: Int, private val sizes: Map<String, IntSize>) {
        fun size(id: String): IntSize? = sizes[id]

        /** Where [id]'s content goes from where its own parent put it: today nowhere else (nothing moves until T239). */
        @Suppress("UNUSED_PARAMETER")
        fun offset(id: String): IntOffset = IntOffset.Zero
    }

    private val slots = ArrayList<Slot>()
    private var last: Placement? = null

    /** The layout pass the sizes belong to (see the class). */
    var pass = 0
        private set

    /** How many times [placement] was computed. */
    var computed = 0
        private set

    private fun sizesChanged() {
        if (last?.pass == pass) pass++
    }

    fun attach(id: String, parent: Slot?): Slot = Slot(id, parent).also { slots += it }

    fun detach(slot: Slot) {
        slot.attached = false
        slot.placed = false
        slots -= slot
        sizesChanged()
    }

    /** In measure: [slot]'s content measured [size] in this pass; not placed until [placed] says so. */
    fun measured(slot: Slot, size: IntSize) {
        sizesChanged()
        slot.size = size
        slot.placed = false
    }

    /** In placement: [slot] placed at [rectPx] (px from the window's top left). */
    fun placed(slot: Slot, rectPx: FloatArray) {
        slot.rectPx = rectPx
        slot.placed = true
    }

    /** The placement from this pass's sizes, computed at the pass's first ask. */
    fun placement(): Placement {
        last?.let { if (it.pass == pass) return it }
        val sizes = LinkedHashMap<String, IntSize>()
        for (s in slots) s.size?.let { sizes[s.id] = it }
        computed++
        return Placement(pass, sizes).also { last = it }
    }

    /**
     * The rectangle of [id]'s first node on screen, or null when none is. [laidOut]: the ids whose layout node is placed
     * now (their semantics say so, [MapLayout.tagsInWindow]); a node its parent stopped placing is not measured again, so
     * its own record cannot see it. Null: not asked.
     */
    fun rectOf(id: String, laidOut: Set<String>? = null): FloatArray? =
        slots.firstOrNull { it.id == id && it.onScreen && (laidOut == null || id in laidOut) }?.rectPx

    /** [id]'s size as last measured, on screen or hidden; null while no node of it is composed. */
    fun sizeOf(id: String): IntSize? = (slots.firstOrNull { it.id == id && it.onScreen } ?: slots.firstOrNull { it.id == id })?.size

    /** The ids with a node on screen now ([laidOut] as in [rectOf]). */
    fun onScreen(laidOut: Set<String>? = null): Set<String> =
        slots.filter { it.onScreen && (laidOut == null || it.id in laidOut) }.mapTo(LinkedHashSet()) { it.id }
}

/**
 * T224: the map screen's layout as it is and as the pure rule ([OverlayLayout]) would place it, for `GET /layout`
 * ([LayoutRoutes]). The first half of a layout authority: no element reads the rule yet. T241: every element's rectangle
 * is read from its [control] node ([controls]); the compass, MapLibre's own view, from [CompassView]. Every read is on
 * the main thread ([observed], [input]).
 */
object MapLayout {
    /** T241: the [control] nodes' sizes and rectangles. Main thread. */
    val controls = MeasuredControls()

    /** T241: the Online chip's card and the first-run card, which have no M-number; `GET /layout` lists them by name. */
    const val ONLINE_CARD = "onlineCard"
    const val FIRST_RUN_CARD = "firstRunCard"

    /**
     * One element as drawn now: px from the window's top left, null while not drawn. T241: [within], the element it lies
     * inside by design (the Online chip's card in the online column), whose pair is no overlap.
     */
    class Seen(val id: String, val rectPx: FloatArray?, val state: String, val reason: String?, val within: String? = null)

    private fun nameOf(id: String) = OverlayIds.NAMES[id] ?: id

    /** T224 (pure, `MapLayoutTest`): every pair of drawn elements that shares at least a whole pixel, in the order given. */
    fun overlaps(seen: List<Seen>): List<Map<String, Any?>> {
        val drawn = seen.filter { it.rectPx != null }
        val out = ArrayList<Map<String, Any?>>()
        for (i in drawn.indices) for (j in i + 1 until drawn.size) {
            val a = drawn[i]
            val b = drawn[j]
            if (a.within == b.id || b.within == a.id) continue
            val ra = a.rectPx!!
            val rb = b.rectPx!!
            val w = minOf(ra[2], rb[2]) - maxOf(ra[0], rb[0])
            val h = minOf(ra[3], rb[3]) - maxOf(ra[1], rb[1])
            if (w >= 1f && h >= 1f) out += linkedMapOf(
                "a" to a.id, "b" to b.id, "aName" to nameOf(a.id), "bName" to nameOf(b.id),
                "sizePx" to listOf(Math.round(w), Math.round(h)),
            )
        }
        return out
    }

    /** T224 (pure): the elements whose state the rule would change, with both states. */
    fun differences(seen: List<Seen>, placed: OverlayPlacement): List<Map<String, Any?>> = seen.mapNotNull { s ->
        val e = placed[s.id] ?: return@mapNotNull null
        if (e.state.code == s.state) null else linkedMapOf("id" to s.id, "name" to OverlayIds.NAMES[s.id], "today" to s.state, "rule" to e.state.code, "reason" to e.reason)
    }

    private const val SHOWN = "shown"
    private const val COMPACT = "compact"
    private const val HIDDEN = "hidden"

    private fun gone(id: String, why: String?) = Seen(id, null, HIDDEN, why ?: "notComposed")

    private fun card(id: String, rect: FloatArray?, l: CardLayout, compactWhy: String?, whyGone: String = "notUp"): Seen =
        if (rect == null) gone(id, whyGone)
        else if (compactWhy != null) Seen(id, rect, COMPACT, compactWhy)
        else if (l.scrollMaxPx > 0) Seen(id, rect, COMPACT, OverlayLayout.REASON_SCROLLS)
        else Seen(id, rect, SHOWN, null)

    /** T241: the rectangle [id]'s [control] node publishes while it is on screen (by its `GET /layout` id or its name). */
    private fun read(id: String, laidOut: Set<String>): FloatArray? = controls.rectOf(nameOf(id), laidOut)

    /**
     * T241: the window's Compose semantics now: how many nodes carry each test tag ([counts]), and the tags of the nodes
     * whose layout node is placed ([laidOut]; a parent that stops placing a child, as the scale bar's line does, marks it so).
     */
    class TagsInWindow(val counts: Map<String, Int>, val laidOut: Set<String>)

    /** T241 (T238's walk, from `ControlsRoutes`): every Compose root of the map's window. Main thread. */
    fun tagsInWindow(): TagsInWindow {
        val counts = HashMap<String, Int>()
        val laidOut = HashSet<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { tag ->
                counts[tag] = (counts[tag] ?: 0) + 1
                if (generateSequence(n.layoutInfo) { it.parentInfo }.all { it.isPlaced }) laidOut += tag
            }
            n.children.forEach(::walk)
        }
        fun views(v: View) {
            if (v is RootForTest) walk(v.semanticsOwner.unmergedRootSemanticsNode)
            if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i))
        }
        DystopoApp.instance.map.mapView?.rootView?.let(::views)
        return TagsInWindow(counts, laidOut)
    }

    /** What the elements draw now, read from their [control] nodes (T241) and laid out ([TagsInWindow.laidOut]). Main thread. */
    fun observed(laidOut: Set<String> = tagsInWindow().laidOut): List<Seen> {
        fun read(id: String) = read(id, laidOut)
        /** The element [id] as read from its node: shown, or hidden for [why]. */
        fun seen(id: String, why: String?): Seen = read(id)?.let { Seen(id, it, SHOWN, null) } ?: gone(id, why)
        val out = ArrayList<Seen>()
        out += seen(OverlayIds.SETTINGS_ROW, if (io.github.jasondorjeshort.dystopo.app.packs.PacksUi.visible) "packsScreenOpen" else null)
        val narrowed = TopChips.searchWidth().value < OverlayLayout.SEARCH_WIDTH
        out += read(OverlayIds.SEARCH)?.let { Seen(OverlayIds.SEARCH, it, if (narrowed) COMPACT else SHOWN, if (narrowed) OverlayLayout.REASON_NARROWED else null) } ?: gone(OverlayIds.SEARCH, null)
        out += seen(OverlayIds.ONLINE, TopChips.hiddenReason(TopChips.Chip.ONLINE))
        out += seen(OverlayIds.TRACK_BAR, TopChips.hiddenReason(TopChips.Chip.TRACKS))
        out += seen(OverlayIds.ROUTES, TopChips.hiddenReason(TopChips.Chip.ROUTES))
        out += CompassView.boundsPx()?.let { Seen(OverlayIds.COMPASS, it, SHOWN, null) } ?: gone(OverlayIds.COMPASS, "mapFacingNorth")
        out += seen(OverlayIds.TRIP_CARD, "notUp")
        out += seen(OverlayIds.UPDATE_CARD, "notUp")
        out += card(OverlayIds.PLANNING_CARD, read(OverlayIds.PLANNING_CARD), BottomPanels.routeCard, null)
        out += card(OverlayIds.NAV_CARD, read(OverlayIds.NAV_CARD), NavCardBand.layout, null)
        val warning = WarningCardLayout
        out += card(OverlayIds.WARNING_CARD, read(OverlayIds.WARNING_CARD), warning.layout, if (!warning.expanded) "collapsed" else null)
        for (id in listOf(OverlayIds.POSITION, OverlayIds.ORIENTATION, OverlayIds.LOCK).take(AppSettings.mapControls.coerceIn(1, 3))) {
            out += seen(id, RecenterLayout.hiddenBecause ?: "noRoomBelowChips")
        }
        out += seen(OverlayIds.SCALE_BAR, ScaleBarLayout.hiddenBecause)
        out += read(OverlayIds.STRIP)?.let {
            if (StripLayout.droppedItems.isEmpty()) Seen(OverlayIds.STRIP, it, SHOWN, null) else Seen(OverlayIds.STRIP, it, COMPACT, OverlayLayout.REASON_ENTRIES)
        } ?: gone(OverlayIds.STRIP, StripLayout.hiddenBecause)
        out += seen(OverlayIds.CREDIT, null)
        // T241: the two cards T224 could not see. The Online chip's card lies inside the online column by design.
        out += read(ONLINE_CARD)?.let { Seen(ONLINE_CARD, it, SHOWN, null, within = OverlayIds.ONLINE) } ?: gone(ONLINE_CARD, "notUp")
        out += seen(FIRST_RUN_CARD, io.github.jasondorjeshort.dystopo.app.firstrun.FirstRunCardState.yieldedTo ?: "notUp")
        return out
    }

    /** [l]'s heights in dp: the card's [rect] whole with what scrolls or folds, its least and its headline. */
    private fun cardInput(rect: FloatArray?, l: CardLayout, extraPx: Float, lineDp: Float, chromeDp: Float, d: Float): OverlayCard? {
        val r = rect ?: return null
        val natural = (r[3] - r[1] + l.scrollMaxPx + extraPx) / d
        val head = l.headerPx / d + chromeDp
        val least = (l.headerPx + l.footerPx) / d + lineDp + chromeDp
        return OverlayCard(maxOf(natural, least), least, head, (r[2] - r[0]) / d)
    }

    /** The measured inputs of the rule, dp. Main thread. */
    fun input(laidOut: Set<String> = tagsInWindow().laidOut): OverlayInput {
        fun read(id: String) = read(id, laidOut)
        val res = DystopoApp.instance.resources
        val d = res.displayMetrics.density
        val fontScale = res.configuration.fontScale
        val root = rootSize()
        val ins = insetsPx()
        fun size(r: FloatArray?) = r?.let { OverlaySize((it[2] - it[0]) / d, (it[3] - it[1]) / d) }
        // T241: as the element's node last measured it, kept while it is hidden.
        fun measured(id: String) = controls.sizeOf(nameOf(id))?.let { OverlaySize(it.width / d, it.height / d) }
        fun own(kind: TopChips.Chip) = TopChips.ownHeightPx(kind).takeIf { it > 0 }?.let { OverlaySize(TopChips.ownWidthPx(kind) / d, it / d) }
        // Two 13 sp lines of a card's scrolling body, as `CappedCard` keeps them (the system's curve is near this at 2.0).
        val twoLines = 36f * minOf(fontScale, 1f + (fontScale - 1f) * 0.75f)
        val tracks = own(TopChips.Chip.TRACKS)
        // The track bar's own row when a panel is open under it: the rule places the chips, not the panel.
        val trackBar = tracks?.let { if (TopChips.trackRowPx > 0) OverlaySize(it.width, TopChips.trackRowPx / d) else it }
        val search = TopChips.searchBottom.value
        return OverlayInput(
            windowWidth = root[0] / d, windowHeight = root[1] / d,
            insetLeft = ins[0] / d, insetTop = ins[1] / d, insetRight = ins[2] / d, insetBottom = ins[3] / d,
            fontScale = fontScale,
            search = OverlaySize(OverlayLayout.SEARCH_WIDTH, if (search > 0f) (search - ins[1] / d - 8f).coerceAtLeast(0f) else 48f),
            topRow = measured(OverlayIds.SETTINGS_ROW) ?: OverlaySize(150f, 40f),
            credit = measured(OverlayIds.CREDIT) ?: OverlaySize(0f, 0f),
            online = own(TopChips.Chip.ONLINE),
            trackBar = trackBar,
            routes = own(TopChips.Chip.ROUTES),
            compass = size(CompassView.boundsPx()),
            scaleBar = ScaleBarLayout.plateHeightPx.takeIf { it > 0 }?.let { OverlaySize(ScaleBarLayout.plateWidthPx / d, it / d) },
            strip = if (AppSettings.readoutBar) StripLayout.entrySizesPx.map { OverlaySize(it[0] / d, it[1] / d) } else emptyList(),
            buttons = AppSettings.mapControls.coerceIn(1, 3),
            offCentre = runCatching { offCentre() }.getOrDefault(false),
            navigation = cardInput(read(OverlayIds.NAV_CARD), NavCardBand.layout, 0f, twoLines, 28f, d),
            warning = cardInput(read(OverlayIds.WARNING_CARD), WarningCardLayout.layout, if (WarningCardLayout.expanded) 0f else (WarningCardLayout.detailDp ?: 0f) * d, twoLines, 28f, d),
            planning = cardInput(read(OverlayIds.PLANNING_CARD), BottomPanels.routeCard, 0f, twoLines, 32f, d),
        )
    }

    private fun offCentre(): Boolean {
        val o = io.github.jasondorjeshort.dystopo.app.controls.MapOrientation.json()
        return o["centred"] == false
    }

    private fun mapView(): View? = DystopoApp.instance.map.mapView

    /** The window: the map view's root, px. */
    private fun rootSize(): FloatArray {
        val root = mapView()?.rootView
        val m = DystopoApp.instance.resources.displayMetrics
        return floatArrayOf((root?.width ?: m.widthPixels).toFloat(), (root?.height ?: m.heightPixels).toFloat())
    }

    /** The system bars and the display cutout, px: left, top, right, bottom. */
    private fun insetsPx(): FloatArray {
        val v = mapView() ?: return FloatArray(4)
        val i = ViewCompat.getRootWindowInsets(v)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: return FloatArray(4)
        return floatArrayOf(i.left.toFloat(), i.top.toFloat(), i.right.toFloat(), i.bottom.toFloat())
    }

    private fun rectJson(r: FloatArray?) = r?.map { Math.round(it) }

    /** `GET /layout`'s answer. Main thread. */
    fun json(): Map<String, Any?> {
        val res = DystopoApp.instance.resources
        val d = res.displayMetrics.density
        val root = rootSize()
        val ins = insetsPx()
        val laidOut = tagsInWindow().laidOut
        val seen = observed(laidOut)
        val i = input(laidOut)
        val placed = OverlayLayout.place(i)
        return linkedMapOf(
            "window" to linkedMapOf(
                "widthPx" to root[0].toInt(), "heightPx" to root[1].toInt(), "density" to d,
                "widthDp" to Math.round(root[0] / d), "heightDp" to Math.round(root[1] / d),
                "insetsPx" to ins.map { Math.round(it) },
                "orientation" to if (root[0] > root[1]) "landscape" else "portrait",
            ),
            "fontScale" to res.configuration.fontScale,
            "foreground" to Foreground.started,
            "elements" to seen.map { s ->
                linkedMapOf(
                    "id" to s.id, "name" to nameOf(s.id), "controls" to OverlayIds.CONTROLS[s.id],
                    "rectPx" to rectJson(s.rectPx), "state" to s.state, "reason" to s.reason,
                ).apply { if (s.within != null) put("within", s.within) }
            },
            "overlaps" to overlaps(seen),
            // T236: the chip's card, inside M8's column; T241: its rectangle read from its node, as in `elements`.
            "onlineCard" to linkedMapOf("open" to io.github.jasondorjeshort.dystopo.app.online.OnlineChip.open, "rectPx" to rectJson(read(ONLINE_CARD, laidOut))),
            "measure" to linkedMapOf("pass" to controls.pass, "placements" to controls.computed), // T241: the nodes' pass counter
            "rule" to linkedMapOf(
                "sideColumn" to placed.sideColumn, "cardColumns" to placed.cardColumns, "buttonsDrawn" to placed.buttonsDrawn, "entriesDrawn" to placed.entriesDrawn,
                "elements" to placed.elements.map { e ->
                    linkedMapOf(
                        "id" to e.id, "name" to OverlayIds.NAMES[e.id],
                        "rectPx" to e.rect?.let { listOf(Math.round(it.left * d), Math.round(it.top * d), Math.round(it.right * d), Math.round(it.bottom * d)) },
                        "state" to e.state.code, "reason" to e.reason,
                    )
                },
                "overlaps" to placed.overlaps().map { listOf(it.first, it.second) },
                "differs" to differences(seen, placed),
                "offCentre" to i.offCentre,
            ),
        )
    }
}
