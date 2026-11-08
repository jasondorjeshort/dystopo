package io.github.jasondorjeshort.dystopo.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
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
import io.github.jasondorjeshort.dystopo.ui.OverlayLatch
import io.github.jasondorjeshort.dystopo.ui.OverlayLayout
import io.github.jasondorjeshort.dystopo.ui.OverlayPlacement
import io.github.jasondorjeshort.dystopo.ui.OverlayRect
import io.github.jasondorjeshort.dystopo.ui.OverlaySize
import kotlin.math.ceil

/**
 * T238 (ADR 0053 §5 part c, build step 2): names this element as the controls manifest's item [id] (an Android
 * placement outside zone `settings` in `shared/.../controls/`), as a semantics test tag the device tour and
 * `GET /controls` read. One call on the element's outermost measured node; `ControlIdsTest` reads every call (a literal
 * id) against the manifest both ways.
 *
 * T241 (step 2 of T224's design note): and a measuring node ([ControlNode]): in measure it measures its content and gives
 * the size to [MapLayout.controls] ([MeasuredControls.measured]), a plain field; its placement block asks
 * [MeasuredControls.placement], computed once per pass from this pass's sizes, and publishes the element's rectangle
 * from this node. `GET /layout` and `GET /controls` read those rectangles. Nothing in it is Compose state, so no reader
 * waits a frame for it (T146).
 *
 * T239 (steps 3 to 5): the placement is the rule's ([OverlayLayout], through [MapLayout.rule]): an element the rule places
 * ([MapLayout.RULED]: the chips, the online column, the position column, the scale bar and the readout strip) is drawn at
 * the rule's rectangle, wherever its own parent put the node, or not drawn when the rule hides it (its size kept); every
 * other element stays where its parent put it. A change of size, or of a card's rectangle, starts the next pass and asks
 * the placed elements to be placed again in the same frame, so a card that wraps moves the chips with it (T146).
 *
 * T243: the node comes first and the tag after it, and so does every semantics and pointer modifier of the element (its
 * description, `clickable`, `pointerInput`): what lies outside the node stays where the parent put the node, so a tag
 * outside it left the element's whole semantics node there, the scale bar's and the strip's under the long-press card
 * after T239 moved their drawing up, and the card's "Route to here" node was cut to a sliver above its text.
 * `MapLayoutTest` reads this order and every call site; `GET /layout` `semanticsAway` lists an element whose semantics
 * node is not where it is drawn.
 */
fun Modifier.control(id: String): Modifier = this.then(ControlElement(id)).testTag(id)

/**
 * T239: on the part of a control the rule fits it by (the track bar's chip row): measured free of the height its column
 * leaves it, so the rule sees the row whole, and given to the enclosing [control] node ([MeasuredControls.Slot.corePx]).
 * What lies under it in the control (an open panel) takes the room the rule leaves (T224's clamp, now the rule's).
 */
fun Modifier.controlCore(): Modifier = this.then(CoreElement)

/** T241: [control]'s element. */
private class ControlElement(val id: String) : ModifierNodeElement<ControlNode>() {
    override fun create() = ControlNode(id)
    override fun update(node: ControlNode) = node.retag(id)
    override fun equals(other: Any?) = other is ControlElement && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * T241: [control]'s node. Measures its content and returns that size, so the element measures as without it. Its slot in
 * [MapLayout.controls] lives while the node is attached; the enclosing control node, if any, is its parent (the Online
 * chip's card lies in the online column), so a card whose column is not placed is not on screen either. A layout node
 * its parent does not place is not measured again, so [MapLayout] also asks its semantics whether it is placed.
 * Intrinsic queries pass straight through: the default ones would run [measure] with made-up constraints and publish a
 * size no pass measured.
 *
 * T239: measures within the rule's limit for it, if any ([MeasuredControls.constrain]: the track bar's room, the strip's
 * width), and places its content at the rule's target ([MeasuredControls.Target]) from where its own parent put it, or
 * places nothing while the rule hides it; the rectangle it publishes is where the content is drawn.
 */
internal class ControlNode(private var id: String) : Modifier.Node(), LayoutModifierNode, TraversableNode {
    var slot: MeasuredControls.Slot? = null
        private set
    override val traverseKey: Any get() = TRAVERSE_KEY

    override fun onAttach() = take()
    override fun onDetach() = release()

    private fun take() {
        slot = MapLayout.controls.attach(id, findNearestAncestor()?.slot).also { s ->
            s.relayout = { if (isAttached) invalidatePlacement() }
            s.remeasure = { if (isAttached) invalidateMeasurement() }
        }
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
        val s = slot?.takeIf { !isLookingAhead }
        val p = measurable.measure(if (s != null) MapLayout.controls.constrain(s, constraints) else constraints)
        if (s != null) MapLayout.controls.measured(s, IntSize(p.width, p.height))
        return layout(p.width, p.height) {
            if (s == null) {
                p.place(0, 0)
                return@layout
            }
            val at = coordinates?.positionInRoot() ?: Offset.Zero
            val target = MapLayout.controls.placement().target(s.id)
            if (target?.hidden == true) {
                MapLayout.controls.hidden(s, target.reason)
                return@layout
            }
            val dx = if (target == null) 0 else target.leftPx - Math.round(at.x)
            val dy = if (target == null) 0 else target.topPx - Math.round(at.y)
            p.place(dx, dy)
            val x = Math.round(at.x) + dx
            val y = Math.round(at.y) + dy
            MapLayout.controls.placed(s, floatArrayOf(x.toFloat(), y.toFloat(), (x + p.width).toFloat(), (y + p.height).toFloat()))
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = measurable.minIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = measurable.maxIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = measurable.minIntrinsicHeight(width)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = measurable.maxIntrinsicHeight(width)

    companion object {
        const val TRAVERSE_KEY = "dystopo.control"
    }
}

/** T239: [controlCore]'s element. */
private object CoreElement : ModifierNodeElement<CoreNode>() {
    override fun create() = CoreNode()
    override fun update(node: CoreNode) {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7239
}

/** T239: [controlCore]'s node (T224's track row node, now giving its height to the enclosing [ControlNode]'s slot). */
private class CoreNode : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        if (!isLookingAhead) (findNearestAncestor(ControlNode.TRAVERSE_KEY) as? ControlNode)?.slot?.let { MapLayout.controls.core(it, p.height) }
        return layout(p.width, constraints.constrainHeight(p.height)) { p.place(0, 0) }
    }
}

/**
 * T241 (pure, `MapLayoutTest`): what the [control] nodes measured and placed, for `GET /layout`, `GET /controls` and,
 * from T239, the placement itself. Plain fields, never Compose state (T146: state written in layout reaches its readers a
 * frame later); main thread only. [MapLayout.controls] is the app's; a test makes its own, with its own [ruler].
 *
 * The pass counter: Compose measures the nodes that need it before it places them, so a size measured after [placement]
 * was computed starts the next pass ([pass]), and the next [placement] is computed again from the sizes measured so far,
 * this pass's for every node measured in it ([computed] counts the computations: once per pass however many nodes ask).
 * A hidden element (composed and measured, not placed) keeps its last size, so the rule can bring it back (T224's rule);
 * one that leaves the composition drops it.
 *
 * T239: [ruler] turns the pass's sizes into the rule's [Ruled] targets. Compose re-places only the nodes it was asked to,
 * so when a pass starts after its placement was computed (a size measured, a card drawn at a new rectangle ([FIXED]),
 * a node gone, or [invalidate] from outside the layout), every node the last placement placed is asked to be placed
 * again ([Slot.relayout]) within the same frame's layout, where it reads the new placement; a node measured within a
 * limit the new placement changed is measured again ([Slot.remeasure]).
 */
class MeasuredControls(private val ruler: ((MeasuredControls) -> Ruled?)? = null) {
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

        /** T239: its [controlCore]'s height as last measured, px (0 with none). */
        var corePx = 0
            internal set

        /** T239: the rule's reason while it hides this element (nothing placed), else null. */
        var ruledOut: String? = null
            internal set

        /** T239: what the node does to be placed again or measured again in this frame's layout (the app's nodes set them). */
        internal var relayout: (() -> Unit)? = null
        internal var remeasure: (() -> Unit)? = null
        internal var measuredWith: Limit? = null

        /** Placed since its last measure, attached, inside a parent that is on screen, and not empty: drawn in the window now. */
        val onScreen: Boolean
            get() = attached && placed && (parent?.onScreen ?: true) && rectPx.let { it != null && it[2] - it[0] > 0f && it[3] - it[1] > 0f }
    }

    /** T239: where the rule draws an element's content, px from the window's top left, or that it hides it ([reason]). */
    data class Target(val leftPx: Int, val topPx: Int, val hidden: Boolean, val reason: String? = null)

    /** T239: what an element is measured within (px): the track bar's room, the strip's width and the entries it draws. */
    data class Limit(val maxWidthPx: Int? = null, val maxHeightPx: Int? = null, val entries: Int? = null)

    /** T239: one pass's rule: each placed element's [targets] (by manifest id), the [limits], and what it was made of. */
    class Ruled(
        val targets: Map<String, Target>,
        val limits: Map<String, Limit> = emptyMap(),
        val placement: OverlayPlacement? = null,
        val input: OverlayInput? = null,
        val density: Float = 1f,
    )

    /** One pass's placement: the sizes it was computed from (by id), and T239's [ruled] targets. */
    class Placement internal constructor(val pass: Int, private val sizes: Map<String, IntSize>, val ruled: Ruled? = null) {
        fun size(id: String): IntSize? = sizes[id]

        /** T239: where the rule draws [id], or null when the rule does not place it (it stays where its parent put it). */
        fun target(id: String): Target? = ruled?.targets?.get(id)
    }

    private val slots = ArrayList<Slot>()
    private var last: Placement? = null

    /** T239: called with each new rule (the compass's margins, the buttons drawn) and as a placed element hides or shows. */
    var onRuled: ((Ruled) -> Unit)? = null
    var onHidden: ((String, String?) -> Unit)? = null
    var onShown: ((String) -> Unit)? = null
    var onMeasured: ((Slot) -> Unit)? = null
    var onGone: ((String) -> Unit)? = null

    /** The layout pass the sizes belong to (see the class). */
    var pass = 0
        private set

    /** How many times [placement] was computed. */
    var computed = 0
        private set

    /** T239: the last placement's rule (null before one). */
    val lastRuled: Ruled? get() = last?.ruled

    private fun sizesChanged() {
        val l = last ?: return
        if (l.pass != pass) return
        pass++
        val placedByRule = l.ruled?.targets?.keys ?: return
        for (s in slots.toList()) if (s.id in placedByRule) s.relayout?.invoke()
    }

    fun attach(id: String, parent: Slot?): Slot = Slot(id, parent).also { slots += it }

    fun detach(slot: Slot) {
        slot.attached = false
        slot.placed = false
        slots -= slot
        sizesChanged()
        onGone?.invoke(slot.id)
    }

    /** T239: a change outside the layout that the rule reads (the map turned, the position moved off centre, a panel). */
    fun invalidate() {
        if (last == null) return
        sizesChanged()
    }

    /** T239: [slot]'s constraints within the last rule's limit for it, if any; remembered, so a new limit measures it again. */
    fun constrain(slot: Slot, c: Constraints): Constraints {
        val l = last?.ruled?.limits?.get(slot.id)
        slot.measuredWith = l
        if (l == null) return c
        var maxW = c.maxWidth
        var maxH = c.maxHeight
        l.maxWidthPx?.let { if (it < maxW) maxW = maxOf(it, c.minWidth) }
        l.maxHeightPx?.let { if (it < maxH) maxH = maxOf(it, c.minHeight) }
        return c.copy(maxWidth = maxW, maxHeight = maxH)
    }

    /** T239: the entries the readout strip draws by the last rule, or null before the rule placed it. */
    fun entriesFor(id: String): Int? = last?.ruled?.limits?.get(id)?.entries

    /** In measure: [slot]'s content measured [size] in this pass; not placed until [placed] says so. */
    fun measured(slot: Slot, size: IntSize) {
        sizesChanged()
        slot.size = size
        slot.placed = false
        onMeasured?.invoke(slot)
    }

    /** T239: in measure: [slot]'s [controlCore] measured [heightPx] tall. */
    fun core(slot: Slot, heightPx: Int) {
        slot.corePx = heightPx
    }

    /** In placement: [slot] placed at [rectPx] (px from the window's top left). T239: a card at a new rectangle starts a pass. */
    fun placed(slot: Slot, rectPx: FloatArray) {
        val moved = slot.id in FIXED && !rectPx.contentEquals(slot.rectPx)
        slot.rectPx = rectPx
        slot.placed = true
        if (slot.ruledOut != null) {
            slot.ruledOut = null
            onShown?.invoke(slot.id)
        }
        if (moved) sizesChanged()
    }

    /** T239: in placement: the rule hides [slot] ([reason]); its content is not placed and its size stays. */
    fun hidden(slot: Slot, reason: String?) {
        slot.placed = false
        val why = reason ?: OverlayLayout.REASON_NO_ROOM
        if (slot.ruledOut != why) {
            slot.ruledOut = why
            onHidden?.invoke(slot.id, why)
        }
    }

    /** The placement from this pass's sizes, computed at the pass's first ask; T239: with the rule's targets. */
    fun placement(): Placement {
        last?.let { if (it.pass == pass) return it }
        val sizes = LinkedHashMap<String, IntSize>()
        for (s in slots) s.size?.let { sizes[s.id] = it }
        computed++
        last = Placement(pass, sizes) // the ruler reads the slots, never this placement
        val ruled = ruler?.invoke(this)
        val p = Placement(pass, sizes, ruled)
        last = p
        if (ruled != null) {
            for (s in slots.toList()) if (s.measuredWith != ruled.limits[s.id] && (s.measuredWith != null || ruled.limits.containsKey(s.id))) s.remeasure?.invoke()
            onRuled?.invoke(ruled)
        }
        return p
    }

    /** T239: the slot of [id] (the first composed). */
    fun slot(id: String): Slot? = slots.firstOrNull { it.id == id && it.onScreen } ?: slots.firstOrNull { it.id == id }

    /**
     * The rectangle of [id]'s first node on screen, or null when none is. [laidOut]: the ids whose layout node is placed
     * now (their semantics say so, [MapLayout.tagsInWindow]); a node its parent stopped placing is not measured again, so
     * its own record cannot see it. Null: not asked.
     */
    fun rectOf(id: String, laidOut: Set<String>? = null): FloatArray? =
        slots.firstOrNull { it.id == id && it.onScreen && (laidOut == null || id in laidOut) }?.rectPx

    /**
     * T239: the rectangle [id]'s node was last placed at while it is composed, placed again in this pass or not yet: a card
     * measured in this pass is placed after the chips in the same layout, and a move then starts the next pass ([placed]).
     */
    fun lastRectOf(id: String): FloatArray? = slots.firstOrNull { it.id == id && it.attached && it.ruledOut == null }?.rectPx

    /** [id]'s size as last measured, on screen or hidden; null while no node of it is composed. */
    fun sizeOf(id: String): IntSize? = slot(id)?.size

    /** The ids with a node on screen now ([laidOut] as in [rectOf]). */
    fun onScreen(laidOut: Set<String>? = null): Set<String> =
        slots.filter { it.onScreen && (laidOut == null || it.id in laidOut) }.mapTo(LinkedHashSet()) { it.id }

    /**
     * T243: the elements on screen whose semantics node is not where they are drawn: none of the rectangles [semantics]
     * gives for its tag (unclipped, px from the window's top left) within [slackPx] of its own on every side. An element
     * with no semantics rectangle is not judged. What accessibility services and `uiautomator` read is that node, and
     * Compose cuts the bounds of every node under it by its bounds, so one left behind takes a card's action from its text.
     */
    fun semanticsAway(semantics: Map<String, List<FloatArray>>, laidOut: Set<String>? = null, slackPx: Float = 1.5f): List<Map<String, Any?>> =
        slots.filter { it.onScreen && (laidOut == null || it.id in laidOut) }.mapNotNull { s ->
            val drawn = s.rectPx ?: return@mapNotNull null
            val found = semantics[s.id]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (found.any { r -> (0..3).all { i -> kotlin.math.abs(r[i] - drawn[i]) <= slackPx } }) null
            else linkedMapOf("id" to s.id, "rectPx" to drawn.map { Math.round(it) }, "semanticsPx" to found.map { r -> r.map { Math.round(it) } })
        }

    companion object {
        /** T239: the cards, drawn by their own code until Layout C: the rule reads their rectangles, so a move starts a pass. */
        val FIXED = setOf("navigationCard", "warningCard", "planningCard", "tripCard", "updateCard")
    }
}

/**
 * T224: the map screen's layout as it is and as the pure rule ([OverlayLayout]) places it, for `GET /layout`
 * ([LayoutRoutes]). T241: every element's rectangle is read from its [control] node ([controls]); the compass, MapLibre's
 * own view, from [CompassView]. Every read is on the main thread ([observed], [input]).
 *
 * T239 (Layout B): the rule places the chips, the online column, the position column, the scale bar and the readout strip
 * ([RULED], through [rule] in [controls]) and the compass ([compassTarget], which `MapController.attach` turns into
 * MapLibre's compass margins); the cards still place themselves and come into the rule where they are drawn.
 */
object MapLayout {
    /** T239: the elements the rule places, by manifest id (a node of another id stays where its parent put it). */
    val RULED: Set<String> = setOf(
        OverlayIds.ONLINE, OverlayIds.TRACK_BAR, OverlayIds.ROUTES, OverlayIds.POSITION, OverlayIds.ORIENTATION, OverlayIds.LOCK,
        OverlayIds.SCALE_BAR, OverlayIds.STRIP,
    ).mapTo(LinkedHashSet()) { OverlayIds.NAMES.getValue(it) }

    /** T241: the [control] nodes' sizes and rectangles; T239: and the rule's placement of them. Main thread. */
    val controls = MeasuredControls(::rule).also { c ->
        c.onRuled = ::ruled
        c.onHidden = ::ruledHidden
        c.onShown = ::ruledShown
        c.onMeasured = { s -> TopChips.measured(s.id, s.size) }
        c.onGone = { id -> TopChips.measured(id, null) }
    }

    /** T241: the Online chip's card and the first-run card, which have no M-number; `GET /layout` lists them by name. */
    const val ONLINE_CARD = "onlineCard"
    const val FIRST_RUN_CARD = "firstRunCard"

    /**
     * T239: the map is turned from north (MapLibre then shows its compass) and the map's centre is off the fix (the
     * position button is then never hidden), as last seen by [cameraMoved]; read by the rule, which is asked again when
     * either changes.
     */
    @Volatile var turned = false
        private set
    @Volatile var offCentre = false
        private set

    /** T239: the camera moved or came to rest (main thread, `MapController.attach`): the rule is asked again when its inputs change. */
    fun cameraMoved(bearing: Double, atRest: Boolean) {
        val t = Math.abs(((bearing % 360.0) + 360.0) % 360.0).let { it > 0.05 && it < 359.95 }
        var changed = t != turned
        turned = t
        if (atRest) offCentreNow().let { if (it != offCentre) { offCentre = it; changed = true } }
        if (changed) controls.invalidate()
    }

    /** T239: [offCentre] read again (a fix that arrives moves no camera; the position column polls it). Main thread. */
    fun recheckOffCentre() {
        offCentreNow().let { if (it != offCentre) { offCentre = it; controls.invalidate() } }
    }

    /** Off centre: a fix, not followed, more than the centred tolerance from the map's centre (no fix: nothing to go back to). */
    private fun offCentreNow(): Boolean = runCatching {
        val app = DystopoApp.instance
        !io.github.jasondorjeshort.dystopo.app.controls.MapOrientation.following &&
            (app.map.fixOffsetPx()?.let { it > io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.CENTRED_DP * app.resources.displayMetrics.density } ?: false)
    }.getOrDefault(false)

    /** T239: what changed the rule's inputs outside the layout (a track panel drawn, a setting): the rule is asked again. */
    fun invalidate() = controls.invalidate()

    /**
     * T239: where the rule puts MapLibre's compass, px from the window's top left, and whether it is drawn: while the map
     * faces north the compass is not up, and this is where it would go, so it fades in there. `MapController.attach` reads
     * it through [compassListener] and sets the compass's margins (it sets no margin anywhere else).
     */
    class CompassTarget(val rectPx: FloatArray?, val hidden: Boolean, val reason: String?)

    @Volatile var compassTarget: CompassTarget? = null
        private set
    var compassListener: ((CompassTarget) -> Unit)? = null

    /** T239: the position column's buttons the rule draws (for their icons and actions); Compose state, a frame late for the icons only. */
    var buttonsDrawn by mutableStateOf(1)
        private set

    @Volatile private var latch: OverlayLatch? = null
    @Volatile var ruleErrors = 0
        private set
    @Volatile private var lastRuleError: String? = null

    private fun nameOf(id: String) = OverlayIds.NAMES[id] ?: id

    /** T239: the rule for this pass: the input from the nodes' sizes and the cards' rectangles, the placement, its targets. Main thread. */
    private fun rule(c: MeasuredControls): MeasuredControls.Ruled? = Snapshot.withoutReadObservation {
        try {
            ruleNow(c)
        } catch (e: RuntimeException) {
            ruleErrors++
            lastRuleError = e.toString().take(200)
            null
        }
    }

    private fun ruleNow(c: MeasuredControls): MeasuredControls.Ruled? {
        val res = DystopoApp.instance.resources
        val d = res.displayMetrics.density
        val root = rootSize()
        if (root[0] <= 0f || root[1] <= 0f) return null
        val input = inputFrom(c, d, root, insetsPx(), res.configuration.fontScale)
        val placed = OverlayLayout.place(input, latch)
        latch = placed.latch
        fun px(v: Float) = Math.round(v * d)
        val targets = LinkedHashMap<String, MeasuredControls.Target>()
        for (e in placed.elements) {
            val name = OverlayIds.NAMES[e.id] ?: continue
            if (name !in RULED) continue
            targets[name] = e.rect?.let { MeasuredControls.Target(px(it.left), px(it.top), false, e.reason) } ?: MeasuredControls.Target(0, 0, true, e.reason)
        }
        val limits = LinkedHashMap<String, MeasuredControls.Limit>()
        if (placed.trackRoom > 0f && placed[OverlayIds.TRACK_BAR]?.rect != null) {
            limits[nameOf(OverlayIds.TRACK_BAR)] = MeasuredControls.Limit(maxHeightPx = ceil(placed.trackRoom * d).toInt())
        }
        if (placed[OverlayIds.STRIP]?.rect != null && placed.stripRoom > 0f) {
            // The width the rule wrapped the entries at (a pixel more for the rounding), so the strip's rows are the rule's.
            limits[nameOf(OverlayIds.STRIP)] = MeasuredControls.Limit(maxWidthPx = ceil(placed.stripRoom * d).toInt() + 1, entries = placed.entriesDrawn)
        }
        // The compass: where the rule puts it, or with the map facing north where it would go once turned.
        val compassAt = placed[OverlayIds.COMPASS] ?: if (input.compass == null) {
            CompassView.sizePx()?.let { s -> OverlayLayout.place(input.copy(compass = OverlaySize(s[0] / d, s[1] / d)), latch)[OverlayIds.COMPASS] }
        } else null
        compassAt?.let { e ->
            val t = CompassTarget(e.rect?.let { floatArrayOf(it.left * d, it.top * d, it.right * d, it.bottom * d) }, e.rect == null && input.compass != null, e.reason)
            val prev = compassTarget
            if (prev == null || prev.hidden != t.hidden || !prev.rectPx.contentEquals(t.rectPx)) {
                if (t.rectPx != null || t.hidden) {
                    compassTarget = t
                    compassListener?.invoke(t)
                }
            }
        }
        return MeasuredControls.Ruled(targets, limits, placed, input, d)
    }

    /** T239: the tops (px) the last rule drew its elements at, by manifest id; for readers off the main thread (`GET /nav`). */
    @Volatile var ruledTopsPx: Map<String, Int> = emptyMap()
        private set

    /** T239: after each new rule: the buttons drawn (Compose state for their icons) and the strip's place for `GET /readout`. */
    private fun ruled(r: MeasuredControls.Ruled) {
        ruledTopsPx = r.targets.filterValues { !it.hidden }.mapValues { it.value.topPx }
        val p = r.placement ?: return
        val n = p.buttonsDrawn
        if (n != buttonsDrawn) buttonsDrawn = n
        val strip = p[OverlayIds.STRIP]?.rect
        val bar = p[OverlayIds.SCALE_BAR]?.rect
        if (strip != null) {
            val d = r.density
            StripLayout.placed(if (bar != null && strip.left >= bar.right) "beside" else "above", Math.round(p.stripRoom * d))
        }
    }

    /** T239: an element the rule placed is hidden now: what its own position callbacks published goes, since they do not run unplaced. */
    private fun ruledHidden(name: String, reason: String?) {
        when (name) {
            nameOf(OverlayIds.ONLINE) -> MapInsets.onlineChipsHidden()
            nameOf(OverlayIds.SCALE_BAR) -> ScaleBarLayout.hide(reason ?: OverlayLayout.REASON_NO_ROOM)
            nameOf(OverlayIds.STRIP) -> StripLayout.hide(reason ?: OverlayLayout.REASON_NO_ROOM)
            nameOf(OverlayIds.POSITION), nameOf(OverlayIds.ORIENTATION), nameOf(OverlayIds.LOCK) -> RecenterLayout.buttonHidden(name, reason)
        }
        TopChips.ruledHidden(name, reason)
    }

    private fun ruledShown(name: String) = TopChips.ruledShown(name)

    /** T239: why the rule hides [id]'s element now (by its `GET /layout` id), or null. */
    fun ruledOut(id: String): String? = controls.slot(nameOf(id))?.ruledOut

    /**
     * T239: the rule's input from the nodes: sizes as last measured (kept while hidden), the cards where they are drawn
     * ([OverlayCard.drawn]), the track bar by its row ([controlCore]) with what opens under it, the track panels, the window
     * and its insets, the settings and [turned] and [offCentre]. Main thread.
     */
    private fun inputFrom(c: MeasuredControls, d: Float, root: FloatArray, ins: FloatArray, fontScale: Float): OverlayInput {
        fun dp(s: IntSize?) = s?.let { OverlaySize(it.width / d, it.height / d) }
        fun rectDp(r: FloatArray) = OverlayRect(r[0] / d, r[1] / d, r[2] / d, r[3] / d)
        val twoLines = 36f * minOf(fontScale, 1f + (fontScale - 1f) * 0.75f)
        fun card(id: String, l: CardLayout?, extraPx: Float, chromeDp: Float): OverlayCard? {
            val r = c.lastRectOf(nameOf(id))?.takeIf { it[2] > it[0] && it[3] > it[1] } ?: return null
            val h = (r[3] - r[1]) / d
            val base = if (l != null) cardInput(r, l, extraPx, twoLines, chromeDp, d) else OverlayCard(h, h, h, (r[2] - r[0]) / d)
            return base?.copy(drawn = rectDp(r))
        }
        val bar = c.slot(nameOf(OverlayIds.TRACK_BAR))
        val barSize = bar?.size
        val core = bar?.corePx ?: 0
        val rowPx = if (barSize != null && core in 1..barSize.height) core else barSize?.height ?: 0
        val search = c.sizeOf(nameOf(OverlayIds.SEARCH))
        val panels = BottomPanels.trackPanelsTop.value.takeIf { it > BottomPanels.trackPanelsBottom.value }?.let { top ->
            val h = root[1] / d
            OverlayRect(EDGE_DP, h - top, root[0] / d - EDGE_DP, h - BottomPanels.trackPanelsBottom.value)
        }
        val stripUp = AppSettings.readoutBar && c.slot(nameOf(OverlayIds.STRIP)) != null
        return OverlayInput(
            windowWidth = root[0] / d, windowHeight = root[1] / d,
            insetLeft = ins[0] / d, insetTop = ins[1] / d, insetRight = ins[2] / d, insetBottom = ins[3] / d,
            fontScale = fontScale,
            search = OverlaySize(OverlayLayout.SEARCH_WIDTH, search?.let { it.height / d } ?: 48f),
            topRow = dp(c.sizeOf(nameOf(OverlayIds.SETTINGS_ROW))) ?: OverlaySize(150f, 40f),
            credit = dp(c.sizeOf(nameOf(OverlayIds.CREDIT))) ?: OverlaySize(0f, 0f),
            online = dp(c.sizeOf(nameOf(OverlayIds.ONLINE))),
            trackBar = barSize?.let { OverlaySize(it.width / d, rowPx / d) },
            trackPanel = barSize?.let { (it.height - rowPx).coerceAtLeast(0) / d } ?: 0f,
            routes = dp(c.sizeOf(nameOf(OverlayIds.ROUTES))),
            compass = if (turned) CompassView.sizePx()?.let { OverlaySize(it[0] / d, it[1] / d) } else null,
            scaleBar = dp(c.sizeOf(nameOf(OverlayIds.SCALE_BAR))),
            strip = if (stripUp) StripLayout.entrySizesPx.map { OverlaySize(it[0] / d, it[1] / d) } else emptyList(),
            buttons = AppSettings.mapControls.coerceIn(1, 3),
            offCentre = offCentre,
            navigation = card(OverlayIds.NAV_CARD, NavCardBand.layout, 0f, 28f),
            warning = card(OverlayIds.WARNING_CARD, WarningCardLayout.layout, if (WarningCardLayout.expanded) 0f else (WarningCardLayout.detailDp ?: 0f) * d, 28f),
            planning = card(OverlayIds.PLANNING_CARD, BottomPanels.routeCard, 0f, 32f),
            trip = card(OverlayIds.TRIP_CARD, null, 0f, 0f),
            update = card(OverlayIds.UPDATE_CARD, null, 0f, 0f),
            panels = panels,
            cardsDrawn = true,
        )
    }

    /** T239: the cards by `GET /layout` id, placed by their own code until Layout C. */
    private val CARDS = setOf(OverlayIds.NAV_CARD, OverlayIds.WARNING_CARD, OverlayIds.PLANNING_CARD, OverlayIds.TRIP_CARD, OverlayIds.UPDATE_CARD)

    /** T239: the track panels' side margins (`tracking/TrackControls.kt` pads their column 8 dp each side). */
    private const val EDGE_DP = 8f

    /**
     * One element as drawn now: px from the window's top left, null while not drawn. T241: [within], the element it lies
     * inside by design (the Online chip's card in the online column), whose pair is no overlap.
     */
    class Seen(val id: String, val rectPx: FloatArray?, val state: String, val reason: String?, val within: String? = null)

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
     * whose layout node is placed ([laidOut]; a parent that stops placing a child marks it so).
     */
    class TagsInWindow(val counts: Map<String, Int>, val laidOut: Set<String>, val bounds: Map<String, List<FloatArray>> = emptyMap())

    /**
     * T241 (T238's walk, from `ControlsRoutes`): every Compose root of the map's window. Main thread. T243: and where each
     * laid-out tagged node's semantics are ([TagsInWindow.bounds], unclipped, px), for [MeasuredControls.semanticsAway].
     */
    fun tagsInWindow(): TagsInWindow {
        val counts = HashMap<String, Int>()
        val laidOut = HashSet<String>()
        val bounds = HashMap<String, MutableList<FloatArray>>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { tag ->
                counts[tag] = (counts[tag] ?: 0) + 1
                if (generateSequence(n.layoutInfo) { it.parentInfo }.all { it.isPlaced }) {
                    laidOut += tag
                    val p = n.positionInRoot
                    bounds.getOrPut(tag) { ArrayList() } += floatArrayOf(p.x, p.y, p.x + n.size.width, p.y + n.size.height)
                }
            }
            n.children.forEach(::walk)
        }
        fun views(v: View) {
            if (v is RootForTest) walk(v.semanticsOwner.unmergedRootSemanticsNode)
            if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i))
        }
        DystopoApp.instance.map.mapView?.rootView?.let(::views)
        return TagsInWindow(counts, laidOut, bounds)
    }

    /** What the elements draw now, read from their [control] nodes (T241) and laid out ([TagsInWindow.laidOut]). Main thread. */
    fun observed(laidOut: Set<String> = tagsInWindow().laidOut): List<Seen> {
        fun read(id: String) = read(id, laidOut)
        /** The element [id] as read from its node: shown, or hidden for the rule's reason (T239) or [why]. */
        fun seen(id: String, why: String?): Seen = read(id)?.let { Seen(id, it, SHOWN, null) } ?: gone(id, ruledOut(id) ?: why)
        val out = ArrayList<Seen>()
        out += seen(OverlayIds.SETTINGS_ROW, if (io.github.jasondorjeshort.dystopo.app.packs.PacksUi.visible) "packsScreenOpen" else null)
        val narrowed = TopChips.searchWidth().value < OverlayLayout.SEARCH_WIDTH
        out += read(OverlayIds.SEARCH)?.let { Seen(OverlayIds.SEARCH, it, if (narrowed) COMPACT else SHOWN, if (narrowed) OverlayLayout.REASON_NARROWED else null) } ?: gone(OverlayIds.SEARCH, null)
        out += seen(OverlayIds.ONLINE, null)
        out += seen(OverlayIds.TRACK_BAR, null)
        out += seen(OverlayIds.ROUTES, null)
        val compass = compassTarget
        out += CompassView.boundsPx()?.let { Seen(OverlayIds.COMPASS, it, SHOWN, null) }
            ?: gone(OverlayIds.COMPASS, if (turned && compass?.hidden == true) compass.reason ?: OverlayLayout.REASON_NO_ROOM else "mapFacingNorth")
        out += seen(OverlayIds.TRIP_CARD, "notUp")
        out += seen(OverlayIds.UPDATE_CARD, "notUp")
        out += card(OverlayIds.PLANNING_CARD, read(OverlayIds.PLANNING_CARD), BottomPanels.routeCard, null)
        out += card(OverlayIds.NAV_CARD, read(OverlayIds.NAV_CARD), NavCardBand.layout, null)
        val warning = WarningCardLayout
        out += card(OverlayIds.WARNING_CARD, read(OverlayIds.WARNING_CARD), warning.layout, if (!warning.expanded) "collapsed" else null)
        for (id in listOf(OverlayIds.POSITION, OverlayIds.ORIENTATION, OverlayIds.LOCK).take(AppSettings.mapControls.coerceIn(1, 3))) {
            out += seen(id, RecenterLayout.hiddenBecause ?: OverlayLayout.REASON_NO_ROOM)
        }
        out += seen(OverlayIds.SCALE_BAR, ScaleBarLayout.hiddenBecause)
        out += read(OverlayIds.STRIP)?.let {
            if (StripLayout.droppedItems.isEmpty()) Seen(OverlayIds.STRIP, it, SHOWN, null) else Seen(OverlayIds.STRIP, it, COMPACT, OverlayLayout.REASON_ENTRIES)
        } ?: gone(OverlayIds.STRIP, ruledOut(OverlayIds.STRIP) ?: StripLayout.hiddenBecause)
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

    /** The measured inputs of the rule, dp: the last pass's (T239), else made now. Main thread. */
    fun input(): OverlayInput? = controls.lastRuled?.input ?: run {
        val res = DystopoApp.instance.resources
        val root = rootSize()
        if (root[0] <= 0f) null else inputFrom(controls, res.displayMetrics.density, root, insetsPx(), res.configuration.fontScale)
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

    /** `GET /layout`'s answer. Main thread. T239: `rule` is the placement the elements were drawn from in the last pass. */
    fun json(): Map<String, Any?> {
        val res = DystopoApp.instance.resources
        val d = res.displayMetrics.density
        val root = rootSize()
        val ins = insetsPx()
        val tags = tagsInWindow()
        val laidOut = tags.laidOut
        val seen = observed(laidOut)
        val i = input()
        val placed = controls.lastRuled?.placement ?: i?.let { OverlayLayout.place(it, latch) }
        val compass = compassTarget
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
            "semanticsAway" to controls.semanticsAway(tags.bounds, laidOut), // T243: a semantics node not where its element is drawn
            // T236: the chip's card, inside M8's column; T241: its rectangle read from its node, as in `elements`.
            "onlineCard" to linkedMapOf("open" to io.github.jasondorjeshort.dystopo.app.online.OnlineChip.open, "rectPx" to rectJson(read(ONLINE_CARD, laidOut))),
            "measure" to linkedMapOf("pass" to controls.pass, "placements" to controls.computed, "ruleErrors" to ruleErrors, "lastRuleError" to lastRuleError), // T241, T239
            // T239: the compass's place by the rule (MapLibre's margins are set from it), and whether the rule hides it.
            "compass" to linkedMapOf("turned" to turned, "rectPx" to rectJson(compass?.rectPx), "hidden" to compass?.hidden, "reason" to compass?.reason),
            "rule" to placed?.let { p ->
                linkedMapOf(
                    "placesNow" to RULED.sorted(), // T239: the elements drawn at the rule's rectangles
                    "sideColumn" to p.sideColumn, "cardColumns" to p.cardColumns, "buttonsDrawn" to p.buttonsDrawn, "entriesDrawn" to p.entriesDrawn,
                    "latch" to p.latch?.let { l -> linkedMapOf("widthDp" to l.windowWidth, "heightDp" to l.windowHeight, "fontScale" to l.fontScale, "cards" to l.cards.sorted()) },
                    "trackRoomDp" to p.trackRoom, "stripRoomDp" to p.stripRoom,
                    "elements" to p.elements.map { e ->
                        linkedMapOf(
                            "id" to e.id, "name" to OverlayIds.NAMES[e.id],
                            "rectPx" to e.rect?.let { listOf(Math.round(it.left * d), Math.round(it.top * d), Math.round(it.right * d), Math.round(it.bottom * d)) },
                            "state" to e.state.code, "reason" to e.reason,
                        )
                    },
                    // The rule's own pairs: two cards drawn by their own code over each other (Layout C's) are in `overlaps`.
                    "overlaps" to p.overlaps().filterNot { (a, b) -> a in CARDS && b in CARDS && i?.cardsDrawn == true }.map { listOf(it.first, it.second) },
                    "differs" to differences(seen, p),
                    "offCentre" to (i?.offCentre ?: offCentre),
                )
            },
        )
    }
}
