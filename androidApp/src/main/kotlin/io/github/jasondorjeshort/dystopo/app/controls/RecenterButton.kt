package io.github.jasondorjeshort.dystopo.app.controls

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.TopChips
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets
import io.github.jasondorjeshort.dystopo.app.routing.BottomPanels
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import kotlinx.coroutines.delay

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xF2FFFCF5)
private val Line = Color(0x552B2420)
private val Blue = Color(0xFF2C7BB6)

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

    fun json(): Map<String, Any?> {
        val b = boundsPx
        val d = density
        val h = windowHeightPx
        // Panel edges as the panels report them (dp from the window bottom; the nav card's from the top).
        val credit = AttributionInsets.bottom.value
        val routeCard = BottomPanels.routeCardTop.value
        val panelsShown = BottomPanels.trackPanelsTop > BottomPanels.trackPanelsBottom
        val trackPanels = if (panelsShown) BottomPanels.trackPanelsTop.value else 0f
        val navBottom = TopChips.navCardBottom.value
        val bottomDp = if (b != null && h > 0) (h - b[3]) / d else null // the button's bottom edge from the window bottom
        val topDp = b?.let { it[1] / d } // its top edge from the window top
        val visible = shown && b != null
        return linkedMapOf(
            "shown" to visible,
            "hiddenBecause" to if (shown) null else hiddenBecause,
            "boundsPx" to b?.map { Math.round(it) },
            "bottomFromWindowBottomDp" to bottomDp?.let { Math.round(it * 10) / 10.0 },
            "creditTopDp" to credit, "routeCardTopDp" to routeCard, "trackPanelsTopDp" to trackPanels, "navCardBottomDp" to navBottom,
            // Clear: the button's bottom is above every bottom panel's top edge, and its top below the nav card.
            "clear" to if (!visible || bottomDp == null || topDp == null) null else
                bottomDp >= credit && bottomDp >= routeCard && bottomDp >= trackPanels && (navBottom == 0f || topDp >= navBottom),
        )
    }
}

/**
 * T93: the recentre button, a round bullseye lower right on the map. It sits above the credit line and above
 * whatever bottom panel shows (the route card, the track profile and waypoint panels: [BottomPanels]), so it
 * moves up with them, and hides while the trip card is open (that card spans the bottom and reports no edge).
 * Greyed out without a fix; a tap without the location permission asks for it. Logic in [Recenter].
 */
@Composable
fun RecenterButton() {
    val app = DystopoApp.instance
    if (Trips.editing != null) {
        SideEffect { RecenterLayout.shown = false; RecenterLayout.hiddenBecause = "tripCard" }
        return
    }
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
    val floor = maxOf(AttributionInsets.bottom, BottomPanels.routeCardTop, panels)
    val density = LocalDensity.current
    val enabled = state == "recenter"
    Box(Modifier.fillMaxSize()) {
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
                .semantics { contentDescription = "Recenter"; role = Role.Button }
                .clickable {
                    if (state == "noPermission") {
                        askPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        Recenter.tap(app, source = "button")
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Bullseye(if (enabled) Blue else Ink.copy(alpha = 0.35f))
        }
    }
}

/** The "my location" crosshair: a ring, a filled centre and four ticks. */
@Composable
private fun Bullseye(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.32f
        val w = size.minDimension * 0.09f
        val outer = size.minDimension / 2
        drawCircle(color, radius = r, center = c, style = Stroke(width = w))
        drawCircle(color, radius = r * 0.45f, center = c)
        drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y - outer), w)
        drawLine(color, Offset(c.x, c.y + r), Offset(c.x, c.y + outer), w)
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x - outer, c.y), w)
        drawLine(color, Offset(c.x + r, c.y), Offset(c.x + outer, c.y), w)
    }
}

private val SIZE = 48.dp
