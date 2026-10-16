package io.github.jasondorjeshort.dystopo.app.controls

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.TopChips
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets
import io.github.jasondorjeshort.dystopo.app.routing.BottomPanels
import kotlinx.coroutines.delay

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xF2FFFCF5)
private val Line = Color(0x552B2420)
private val Blue = Color(0xFF2C7BB6)
private val Red = Color(0xFFD7191C)

/**
 * T93: where the recentre button sits, for `GET /camera` (`recenter.button`): its bounds in window pixels
 * and whether it clears the panels it must not touch. Written on the main thread.
 */
object RecenterLayout {
    @Volatile var shown = false
    @Volatile var hiddenBecause: String? = null
    @Volatile var boundsPx: FloatArray? = null
    @Volatile var windowHeightPx = 0f
    @Volatile var density = 1f

    /** T100: what the icon drew last (orientation, colour), for `GET /camera`. */
    @Volatile var icon: Map<String, Any?>? = null

    /** T110: not shown (no stale bounds kept); `noRoomBelowChips` when the bottom panels reach up to the top-right chips. */
    fun hide(reason: String) {
        shown = false
        hiddenBecause = reason
        boundsPx = null
    }

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
        val bottomDp = if (b != null && h > 0) (h - b[3]) / d else null // the button's bottom edge from the window bottom
        val topDp = b?.let { it[1] / d } // its top edge from the window top
        val visible = shown && b != null
        return linkedMapOf(
            "shown" to visible,
            "hiddenBecause" to if (shown) null else hiddenBecause,
            "boundsPx" to b?.map { Math.round(it) },
            "bottomFromWindowBottomDp" to bottomDp?.let { Math.round(it * 10) / 10.0 },
            "creditTopDp" to credit, "routeCardTopDp" to routeCard, "trackPanelsTopDp" to trackPanels, "navCardBottomDp" to TopChips.navCardBottom.value,
            "topRightBottomDp" to navBottom, // T110
            "tripCardTopDp" to io.github.jasondorjeshort.dystopo.app.MapInsets.tripCardTop.value, "updateCardTopDp" to io.github.jasondorjeshort.dystopo.app.MapInsets.updateCardTop.value, // T106
            // Clear: the button's bottom is above every bottom panel's top edge, and its top below the nav card and (T110)
            // the top-right chips.
            "clear" to if (!visible || bottomDp == null || topDp == null) null else
                bottomDp >= credit && bottomDp >= routeCard && bottomDp >= trackPanels && bottomDp >= cards && (navBottom == 0f || topDp >= navBottom),
            "icon" to icon,
        )
    }
}

/**
 * T93: the recentre button, round, lower right on the map. It sits above the credit line and above whatever bottom
 * panel shows (the route card, the track profile and waypoint panels: [BottomPanels]), so it moves up with them
 * (T106: also above the trip card and the update offer, `MapInsets.bottomCardsTop`), but never up to the top-right
 * chips (T110: it hides when no room is left below them, `hiddenBecause: noRoomBelowChips`). Greyed out without a
 * fix; a tap without the location permission asks for it. Logic in [Recenter].
 *
 * T100: the icon shows the orientation before the tap ([MapOrientation]): north-up a compass needle under an N,
 * free rotate two turning arrows with a red pointer to north, facing a beam from a dot (the way the phone points).
 * Blue while the camera follows the fix (the next tap advances the orientation), dark when panned away (the next
 * tap recentres), grey without a fix.
 */
@Composable
fun RecenterButton() {
    val app = DystopoApp.instance
    // Fixes and the permission are not Compose state: poll them (cheap) for the enabled look.
    var state by remember { mutableStateOf(Recenter.state(app)) }
    LaunchedEffect(Unit) {
        while (true) {
            state = Recenter.state(app)
            delay(500)
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
    // The bearing only means something on the free-rotate icon (its north pointer).
    SideEffect { RecenterLayout.icon = mapOf("orientation" to o.orientation.key, "look" to look, "bearing" to if (o.orientation == Orientation.FREE) o.bearing else null) }
    DisposableEffect(Unit) { onDispose { RecenterLayout.hide("notComposed") } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // T110 (T102's follow-up): a tall bottom panel (the route card with its directions open at font scale 2.0) pushed
        // the button up over the Tracks chip. The button now never reaches up to the top-right chips (the track controls
        // with their list, the Routes chip, the navigation card): with no room left between them it hides.
        val bottomGap = if (floor > 0.dp) floor + 8.dp else with(density) { WindowInsets.navigationBars.getBottom(density).toDp() } + 32.dp
        val ceiling = TopChips.topRightBottom
        if (ceiling > 0.dp && maxHeight - bottomGap - SIZE < ceiling + CHIP_GAP) {
            SideEffect { RecenterLayout.hide("noRoomBelowChips") }
            return@BoxWithConstraints
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .let { m -> if (floor > 0.dp) m.padding(end = 12.dp, bottom = floor + 8.dp) else m.navigationBarsPadding().padding(end = 12.dp, bottom = 32.dp) }
                .size(SIZE)
                .onGloballyPositioned { c ->
                    val b = c.boundsInRoot()
                    var r = c
                    while (true) r = r.parentLayoutCoordinates ?: break
                    RecenterLayout.boundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom)
                    RecenterLayout.windowHeightPx = r.size.height.toFloat()
                    RecenterLayout.density = density.density
                    RecenterLayout.shown = true
                    RecenterLayout.hiddenBecause = null
                }
                .background(Paper, CircleShape)
                .border(1.dp, Line, CircleShape)
                // "Recenter" stays the name (tools find the button by it); the orientation is its state.
                .semantics { contentDescription = "Recenter"; stateDescription = o.orientation.label; role = Role.Button }
                .clickable {
                    if (state == "noPermission") {
                        askPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        Recenter.tap(app, source = "button")
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (o.orientation) {
                Orientation.NORTH_UP -> NorthUpIcon(tint, enabled)
                Orientation.FREE -> FreeRotateIcon(tint, enabled, o.bearing)
                Orientation.FACING -> FacingIcon(tint)
            }
        }
    }
}

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

/** T110: the least gap between the button and the top-right chips above it. */
private val CHIP_GAP = 8.dp
