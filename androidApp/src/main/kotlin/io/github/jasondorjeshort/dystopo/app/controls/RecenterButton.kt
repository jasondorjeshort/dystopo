package io.github.jasondorjeshort.dystopo.app.controls

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.TopChips
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets
import io.github.jasondorjeshort.dystopo.app.routing.BottomPanels
import java.util.concurrent.ConcurrentHashMap

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xF2FFFCF5)
private val Line = Color(0x552B2420)
private val Blue = Color(0xFF2C7BB6)
private val Red = Color(0xFFD7191C)

/**
 * T93: where the position button sits, for `GET /camera` (`recenter.button`): its bounds in window pixels
 * and whether it clears the panels it must not touch. Written on the main thread. T176: the buttons are a column
 * of one to three ([shownButtons]); [boundsPx] and `clear` cover the whole column, and [buttonBounds] holds each
 * button's (`position`, `orientation`, `lock`).
 */
object RecenterLayout {
    @Volatile var shown = false
    @Volatile var hiddenBecause: String? = null
    @Volatile var boundsPx: FloatArray? = null
    @Volatile var windowHeightPx = 0f
    @Volatile var density = 1f

    /** T176: the buttons drawn (the layout as set, fewer when the room below the top-right chips is short). */
    @Volatile var shownButtons = 0

    /** T176: each drawn button's bounds in window pixels, by id. */
    val buttonBounds = ConcurrentHashMap<String, FloatArray>()

    /** T100: what the icon drew last (orientation, colour), for `GET /camera`. */
    @Volatile var icon: Map<String, Any?>? = null

    /** T110: not shown (no stale bounds kept); `noRoomBelowChips` when the bottom panels reach up to the top-right chips. */
    fun hide(reason: String) {
        shown = false
        hiddenBecause = reason
        boundsPx = null
        shownButtons = 0
        buttonBounds.clear()
    }

    /** The button order from the bottom up (T176): the position button, then the orientation button, then the lock. */
    val ORDER = listOf("position", "orientation", "lock")

    fun json(): Map<String, Any?> {
        val b = boundsPx
        val d = density
        val h = windowHeightPx
        // Panel edges as the panels report them (dp from the window bottom; the nav card's and the chips' from the top).
        val credit = AttributionInsets.bottom.value
        val routeCard = BottomPanels.routeCardTop.value
        val panelsShown = BottomPanels.trackPanelsTop > BottomPanels.trackPanelsBottom
        val trackPanels = if (panelsShown) BottomPanels.trackPanelsTop.value else 0f
        val navBottom = TopChips.topRightBottom.value // T110: the nav card, the track controls and the Routes chip
        val cards = io.github.jasondorjeshort.dystopo.app.MapInsets.bottomCardsTop.value // T106: the trip card and the update offer
        val bottomDp = if (b != null && h > 0) (h - b[3]) / d else null // the column's bottom edge from the window bottom
        val topDp = b?.let { it[1] / d } // its top edge from the window top
        val visible = shown && b != null
        return linkedMapOf(
            "shown" to visible,
            "hiddenBecause" to if (shown) null else hiddenBecause,
            "boundsPx" to b?.map { Math.round(it) },
            "shownButtons" to if (visible) shownButtons else 0, // T176
            "buttons" to if (!visible) emptyList() else ORDER.take(shownButtons).map { id -> linkedMapOf("id" to id, "boundsPx" to buttonBounds[id]?.map { Math.round(it) }) },
            "bottomFromWindowBottomDp" to bottomDp?.let { Math.round(it * 10) / 10.0 },
            "creditTopDp" to credit, "routeCardTopDp" to routeCard, "trackPanelsTopDp" to trackPanels, "navCardBottomDp" to TopChips.navCardBottom.value,
            "topRightBottomDp" to navBottom, // T110
            "tripCardTopDp" to io.github.jasondorjeshort.dystopo.app.MapInsets.tripCardTop.value, "updateCardTopDp" to io.github.jasondorjeshort.dystopo.app.MapInsets.updateCardTop.value, // T106
            // Clear: the column's bottom is above every bottom panel's top edge, and its top below the nav card and (T110)
            // the top-right chips.
            "clear" to if (!visible || bottomDp == null || topDp == null) null else
                bottomDp >= credit && bottomDp >= routeCard && bottomDp >= trackPanels && bottomDp >= cards && (navBottom == 0f || topDp >= navBottom),
            "icon" to icon,
        )
    }
}

/**
 * T93: the position button, round, lower right on the map. It sits above the credit line and above whatever bottom
 * panel shows (the route card, the track profile and waypoint panels: [BottomPanels]), so it moves up with them
 * (T106: also above the trip card and the update offer, `MapInsets.bottomCardsTop`), but never up to the top-right
 * chips (T110: it hides when no room is left below them, `hiddenBecause: noRoomBelowChips`). Greyed out without a
 * fix; a tap without the location permission asks for it. Logic in [Recenter].
 *
 * T100: the icon shows the orientation before the tap ([MapOrientation]): north-up a compass needle under an N,
 * free rotate two turning arrows with a red pointer to north, facing a beam from a dot (the way the phone points).
 * Blue while the camera follows the fix (the next tap advances the orientation), dark when panned away (the next
 * tap recentres), grey without a fix.
 *
 * T176: one, two or three 48 dp buttons as a column growing upward ([AppSettings.mapControls]): the position button at
 * the bottom (one button: the orientation icon as before; two or three: a locate mark, since the orientation has its
 * own button), the orientation button above it, the lock (a padlock, blue and closed when on) at the top. With too
 * little room below the top-right chips the lock goes first, then the orientation button, then the whole column; the
 * buttons left act as that layout. A long press on one or two buttons toggles the lock ("Lock orientation", also a
 * TalkBack action) with a short toast, and a small padlock on the orientation icon shows it.
 */
@Composable
fun RecenterButton() {
    val app = DystopoApp.instance
    // Fixes and the permission are not Compose state: poll them (cheap) for the enabled look.
    var state by remember { mutableStateOf(Recenter.state(app)) }
    LaunchedEffect(Unit) {
        while (true) {
            state = Recenter.state(app)
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(500) // T150: paused in the background
        }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.map.onLocationPermissionChanged()
        state = Recenter.state(app)
    }
    val panels = if (BottomPanels.trackPanelsTop > BottomPanels.trackPanelsBottom) BottomPanels.trackPanelsTop else 0.dp
    val floor = maxOf(AttributionInsets.bottom, BottomPanels.routeCardTop, panels, io.github.jasondorjeshort.dystopo.app.MapInsets.bottomCardsTop) // T106: above the trip card and the update offer
    val density = LocalDensity.current
    val enabled = state == "recenter"
    val o = OrientationUi.state
    val look = when {
        !enabled -> "disabled"
        o.following -> "following"
        else -> "offCentre"
    }
    val tint = when (look) {
        "disabled" -> Ink.copy(alpha = 0.35f)
        "following" -> Blue
        else -> Ink
    }
    DisposableEffect(Unit) { onDispose { RecenterLayout.hide("notComposed") } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // T110 (T102's follow-up): a tall bottom panel (the route card with its directions open at font scale 2.0) pushed
        // the button up over the Tracks chip. The column now never reaches up to the top-right chips (the track controls
        // with their list, the Routes chip, the navigation card): T176 drops the extra buttons first, then hides.
        val bottomGap = if (floor > 0.dp) floor + 8.dp else with(density) { WindowInsets.navigationBars.getBottom(density).toDp() } + 32.dp
        val ceiling = TopChips.topRightBottom
        val n = OrientationRules.shownButtons(o.layout) { k -> ceiling <= 0.dp || maxHeight - bottomGap - columnHeight(k) >= ceiling + CHIP_GAP }
        if (n == 0) {
            SideEffect { RecenterLayout.hide("noRoomBelowChips") }
            return@BoxWithConstraints
        }
        val badge = o.locked && n < 3 // the lock has no button of its own: the orientation icon carries the padlock
        val lockLabel = if (o.locked) "Unlock orientation" else "Lock orientation"
        SideEffect {
            RecenterLayout.shownButtons = n
            RecenterLayout.ORDER.drop(n).forEach { RecenterLayout.buttonBounds.remove(it) }
            // The bearing only means something on the free-rotate icon (its north pointer).
            RecenterLayout.icon = mapOf(
                "orientation" to o.orientation.key, "look" to look, "bearing" to if (o.orientation == Orientation.FREE) o.bearing else null,
                "position" to if (n == 1) "orientation" else "locate", // T176: what the position button draws
                "lockBadge" to badge, "lock" to if (o.locked) "locked" else "unlocked",
            )
        }
        val context = androidx.compose.ui.platform.LocalContext.current
        fun longPress() {
            Thread {
                val r = Recenter.longPress(app, "button")
                (r["toast"] as? String)?.let { t -> app.map.onMain { Toast.makeText(context, t, Toast.LENGTH_SHORT).show() } }
            }.start()
        }
        fun record(id: String, c: LayoutCoordinates) {
            val b = c.boundsInRoot()
            RecenterLayout.buttonBounds[id] = floatArrayOf(b.left, b.top, b.right, b.bottom)
        }
        Column(
            Modifier.align(Alignment.BottomEnd)
                .let { m -> if (floor > 0.dp) m.padding(end = 12.dp, bottom = floor + 8.dp) else m.navigationBarsPadding().padding(end = 12.dp, bottom = 32.dp) }
                .onGloballyPositioned { c ->
                    val b = c.boundsInRoot()
                    var r = c
                    while (true) r = r.parentLayoutCoordinates ?: break
                    RecenterLayout.boundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom)
                    RecenterLayout.windowHeightPx = r.size.height.toFloat()
                    RecenterLayout.density = density.density
                    RecenterLayout.shown = true
                    RecenterLayout.hiddenBecause = null
                },
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            if (n >= 3) {
                RoundButton(
                    Modifier.onGloballyPositioned { record("lock", it) }
                        .semantics { contentDescription = "Orientation lock"; stateDescription = if (o.locked) "Locked" else "Unlocked"; role = Role.Button },
                    onClick = {
                        Thread {
                            val r = Recenter.toggleLock(app, "button")
                            (r["toast"] as? String)?.let { t -> app.map.onMain { Toast.makeText(context, t, Toast.LENGTH_SHORT).show() } }
                        }.start()
                    },
                ) { PadlockIcon(if (o.locked) Blue else Ink, closed = o.locked, Modifier.size(ICON)) }
            }
            if (n >= 2) {
                RoundButton(
                    Modifier.onGloballyPositioned { record("orientation", it) }
                        .semantics {
                            contentDescription = "Orientation"; stateDescription = o.orientation.label; role = Role.Button
                            if (n == 2) customActions = listOf(CustomAccessibilityAction(lockLabel) { longPress(); true })
                        },
                    onClick = { Recenter.orientationButton("button") },
                    onLongClick = if (n == 2) ({ longPress() }) else null,
                    longClickLabel = lockLabel,
                ) { OrientationIcon(o, Ink, true, badge) }
            }
            RoundButton(
                // "Recenter" stays the name (tools find the button by it); the orientation is its state.
                Modifier.onGloballyPositioned { record("position", it) }
                    .semantics {
                        contentDescription = "Recenter"; stateDescription = o.orientation.label; role = Role.Button
                        if (n <= 2) customActions = listOf(CustomAccessibilityAction(lockLabel) { longPress(); true })
                    },
                onClick = {
                    if (state == "noPermission") {
                        askPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        Recenter.tap(app, source = "button")
                    }
                },
                onLongClick = if (n <= 2) ({ longPress() }) else null,
                longClickLabel = lockLabel,
            ) {
                if (n == 1) OrientationIcon(o, tint, enabled, badge) else LocateIcon(tint)
            }
        }
    }
}

/** T176: one round 48 dp button of the column; a long press only where [onLongClick] is given. */
@Composable
private fun RoundButton(
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(SIZE)
            .background(Paper, CircleShape)
            .border(1.dp, Line, CircleShape)
            .combinedClickable(onLongClickLabel = if (onLongClick != null) longClickLabel else null, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The orientation icon (T100), with T176's small padlock at its upper right while locked and no lock button is shown. */
@Composable
private fun OrientationIcon(o: OrientationUi.State, tint: Color, enabled: Boolean, badge: Boolean) {
    Box(Modifier.size(SIZE), contentAlignment = Alignment.Center) {
        when (o.orientation) {
            Orientation.NORTH_UP -> NorthUpIcon(tint, enabled)
            Orientation.FREE -> FreeRotateIcon(tint, enabled, o.bearing)
            Orientation.FACING -> FacingIcon(tint)
        }
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 1.dp).size(BADGE).background(Paper, CircleShape).border(1.dp, Blue, CircleShape),
                contentAlignment = Alignment.Center,
            ) { PadlockIcon(Blue, closed = true, Modifier.size(BADGE - 6.dp)) }
        }
    }
}

/** T176: the position button of two and three buttons: a ring with four ticks and a dot (go to my position). */
@Composable
private fun LocateIcon(tint: Color) {
    Canvas(Modifier.size(ICON)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.3f
        val sw = size.minDimension * 0.085f
        drawCircle(tint, radius = r, center = c, style = Stroke(width = sw))
        drawCircle(tint, radius = size.minDimension * 0.12f, center = c)
        val t0 = r
        val t1 = size.minDimension * 0.48f
        for ((dx, dy) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
            drawLine(tint, Offset(c.x + dx * t0, c.y + dy * t0), Offset(c.x + dx * t1, c.y + dy * t1), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

/** T176: a padlock, the shackle closed over the body when [closed], lifted and open to the right otherwise. */
@Composable
private fun PadlockIcon(tint: Color, closed: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        val bodyTop = h * 0.46f
        drawRoundRect(tint, topLeft = Offset(w * 0.18f, bodyTop), size = Size(w * 0.64f, h * 0.48f), cornerRadius = CornerRadius(w * 0.08f))
        drawCircle(Paper, radius = w * 0.07f, center = Offset(w * 0.5f, bodyTop + h * 0.22f))
        val r = w * 0.2f
        val cx = w * 0.5f
        val lift = if (closed) 0f else h * 0.12f
        val top = h * 0.1f - lift
        val shackle = Path().apply {
            moveTo(cx - r, bodyTop)
            lineTo(cx - r, top + r)
            arcTo(androidx.compose.ui.geometry.Rect(cx - r, top, cx + r, top + 2 * r), 180f, 180f, false)
            lineTo(cx + r, if (closed) bodyTop else top + r + h * 0.06f)
        }
        drawPath(shackle, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** T176: the column's height for [n] buttons. */
private fun columnHeight(n: Int): Dp = SIZE * n + GAP * (n - 1)

/** North-up: a compass needle, red half to the top, under a drawn N (a path, so it does not follow the font scale). */
@Composable
private fun NorthUpIcon(tint: Color, enabled: Boolean) {
    Canvas(Modifier.size(ICON)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.075f
        val n = Path().apply {
            moveTo(w * 0.39f, h * 0.29f); lineTo(w * 0.39f, h * 0.03f); lineTo(w * 0.61f, h * 0.29f); lineTo(w * 0.61f, h * 0.03f)
        }
        drawPath(n, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // One diamond: the north half filled red, the south half pale with the outline in the button's colour.
        val north = Path().apply { moveTo(w * 0.5f, h * 0.36f); lineTo(w * 0.65f, h * 0.67f); lineTo(w * 0.35f, h * 0.67f); close() }
        val south = Path().apply { moveTo(w * 0.35f, h * 0.67f); lineTo(w * 0.65f, h * 0.67f); lineTo(w * 0.5f, h * 0.98f); close() }
        drawPath(south, tint.copy(alpha = tint.alpha * 0.22f))
        drawPath(south, tint, style = Stroke(width = sw * 0.6f, join = StrokeJoin.Round))
        drawPath(north, if (enabled) Red else tint)
    }
}

/**
 * Free rotate: two arrows turning round the centre (the map was turned with two fingers), with a red pointer to
 * where north is on the turned map, so a rotated map still shows north.
 */
@Composable
private fun FreeRotateIcon(tint: Color, enabled: Boolean, bearingDeg: Int) {
    Canvas(Modifier.size(ICON)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.36f
        val sw = size.minDimension * 0.085f
        val box = Size(2 * r, 2 * r)
        val tl = Offset(c.x - r, c.y - r)
        val a = size.minDimension * 0.17f
        for (start in listOf(200f, 20f)) {
            val sweep = 110f
            drawArc(tint, start, sweep, useCenter = false, topLeft = tl, size = box, style = Stroke(width = sw, cap = StrokeCap.Round))
            val end = Math.toRadians((start + sweep).toDouble())
            val p = Offset(c.x + r * Math.cos(end).toFloat(), c.y + r * Math.sin(end).toFloat())
            val d = Offset(-Math.sin(end).toFloat(), Math.cos(end).toFloat()) // clockwise tangent
            val nrm = Offset(Math.cos(end).toFloat(), Math.sin(end).toFloat())
            val head = Path().apply {
                moveTo(p.x + d.x * a, p.y + d.y * a)
                lineTo(p.x + nrm.x * a * 0.7f - d.x * a * 0.2f, p.y + nrm.y * a * 0.7f - d.y * a * 0.2f)
                lineTo(p.x - nrm.x * a * 0.7f - d.x * a * 0.2f, p.y - nrm.y * a * 0.7f - d.y * a * 0.2f)
                close()
            }
            drawPath(head, tint)
        }
        // North on the turned map: the map turns clockwise by its bearing, so north is at screen angle -90 - bearing.
        val t = Math.toRadians(-90.0 - bearingDeg)
        val ux = Math.cos(t).toFloat()
        val uy = Math.sin(t).toFloat()
        val tip = r * 0.72f
        val half = r * 0.26f
        val pointer = Path().apply {
            moveTo(c.x + ux * tip, c.y + uy * tip)
            lineTo(c.x - uy * half, c.y + ux * half)
            lineTo(c.x + uy * half, c.y - ux * half)
            close()
        }
        drawPath(pointer, if (enabled) Red else tint)
    }
}

/** Facing: a dot (the phone) with a beam spreading up from it, the way it points. */
@Composable
private fun FacingIcon(tint: Color) {
    Canvas(Modifier.size(ICON)) {
        val w = size.width
        val h = size.height
        val c = Offset(w * 0.5f, h * 0.7f)
        val beamR = h * 0.68f
        val box = Size(2 * beamR, 2 * beamR)
        val tl = Offset(c.x - beamR, c.y - beamR)
        drawArc(tint.copy(alpha = tint.alpha * 0.3f), 238f, 64f, useCenter = true, topLeft = tl, size = box)
        drawArc(tint, 238f, 64f, useCenter = true, topLeft = tl, size = box, style = Stroke(width = w * 0.065f, join = StrokeJoin.Round))
        drawCircle(Paper, radius = w * 0.19f, center = c)
        drawCircle(tint, radius = w * 0.14f, center = c)
    }
}

private val SIZE = 48.dp
private val ICON = 28.dp
/** T176: the gap between the column's buttons, and the padlock badge on the orientation icon. */
private val GAP = 8.dp
private val BADGE = 18.dp

/** T110: the least gap between the button and the top-right chips above it. */
private val CHIP_GAP = 8.dp
