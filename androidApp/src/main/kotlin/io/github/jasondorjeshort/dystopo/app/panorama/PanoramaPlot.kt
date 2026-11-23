package io.github.jasondorjeshort.dystopo.app.panorama

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.gnss.GnssPalette
import io.github.jasondorjeshort.dystopo.app.gnss.GnssUi
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonText
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.horizon.HorizonLabel
import io.github.jasondorjeshort.dystopo.horizon.HorizonLabels
import io.github.jasondorjeshort.dystopo.horizon.HorizonPlace
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim
import io.github.jasondorjeshort.dystopo.horizon.PanoramaScene
import io.github.jasondorjeshort.dystopo.horizon.PanoramaText
import io.github.jasondorjeshort.dystopo.maplibre.NightColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The names' and the tape's text sizes, dp (devtool `panorama` draws at the same). */
private const val NAME_DP = 12f
private const val TAPE_TEXT_DP = 10f

/** A colour of the style's terrain and paper tokens, turned for night as the map's style turns them. */
private fun tok(hex: Long, night: Boolean): Color {
    if (!night) return Color(hex or 0xFF000000)
    val v = hex.toInt()
    val (r, g, b) = NightColors.of((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    return Color(r, g, b)
}

/**
 * T326: the layers' tints, far to near (index 0 the nearest): from the style's paper-line tone to its hillshade shadow, by
 * the map's own terrain tokens, day and night; the sky from the paper's light at the level to a cool tone overhead.
 */
class PanoramaTints(night: Boolean) {
    val far = tok(0xc9bca7, night)
    val near = tok(0x4a3f36, night)
    val skyLow = tok(0xfffdf7, night)
    val skyHigh = tok(0xd5e2ea, night)

    fun layer(index: Int, count: Int): Color {
        val t = if (count <= 1) 1f else (count - 1 - index).toFloat() / (count - 1)
        return androidx.compose.ui.graphics.lerp(far, near, t)
    }
}

/**
 * T326: the lens plot (wave 45's choices (c), (d), (f) and (g)): the [PanoramaScene] T324 builds for the aim
 * ([PanoramaUi.aim]) over the finest skyline swept so far ([PanoramaData.Place.finest]): the layers far to near, the level
 * line, the bearing tape, the peaks' triangles and names (placed by [HorizonLabels.place] through the lens; during a drag or
 * a pinch each keeps its offset from its mark, placed again when the gesture ends). A tap on a name picks its peak as on
 * the round plot. A horizontal drag turns without end, a vertical drag tilts, a pinch and the two buttons zoom, all through
 * [PanoramaAim]. The node says the place line, the bearing and the field of view, and carries the actions turn left, turn
 * right, look up and look down. A line stands where the plot is while nothing is ready.
 */
@Composable
fun PanoramaPlot(
    plotW: Dp, plotH: Dp, pal: GnssPalette, night: Boolean, work: PanoramaData.Place?, ranked: List<HorizonLabels.Scored>,
    picked: HorizonLabel?, placeText: String, wanted: List<Double>,
    /** T327: the Sun, Satellites and Flights drawn through the scene's lens (null: none on). */
    lensLayers: LensLayers? = null,
) {
    val density = LocalDensity.current
    val d = density.density.toDouble()
    val wPx = with(density) { plotW.toPx() }.toDouble()
    val hPx = with(density) { plotH.toPx() }.toDouble()
    PanoramaUi.plotSize = Triple(wPx, hPx, d)
    val tints = remember(night) { PanoramaTints(night) }
    val namePaint = remember(d, night) { android.graphics.Paint().apply { isAntiAlias = true; textSize = (NAME_DP * d).toFloat(); color = pal.ink.toArgb() } }
    val tapePaint = remember(d, night) { android.graphics.Paint().apply { isAntiAlias = true; textSize = (TAPE_TEXT_DP * d).toFloat(); color = pal.ink.toArgb() } }
    val lensPaints = remember(d, night) { LensPaints(d.toFloat(), pal) } // T331: made once, not each frame
    val nameText = remember(namePaint) { namePaint.fontMetrics.let { fm -> PanoramaText({ namePaint.measureText(it).toDouble() }, fm.ascent.toDouble(), fm.descent.toDouble()) } }
    val tapeText = remember(tapePaint) { tapePaint.fontMetrics.let { fm -> PanoramaText({ tapePaint.measureText(it).toDouble() }, fm.ascent.toDouble(), fm.descent.toDouble()) } }

    // The aim at opening, once per opening, when the plot knows its size (choice c).
    val openings = PanoramaUi.openings
    val pending = PanoramaUi.aim == null
    LaunchedEffect(openings, pending, wPx > 0) {
        if (!pending || wPx <= 0) return@LaunchedEffect
        // T330 (choice (j)): in Follow the opening is the phone's attitude at once (its first sample writes the aim); Drag's
        // opening stands only when no sample came within the compass's wait, and the first sample later replaces it.
        if (PanoramaFollow.following) {
            val until = PanoramaUi.openedAtMs + PanoramaUi.HEADING_WAIT_MS
            while (PanoramaUi.aim == null && SystemClock.elapsedRealtime() < until) kotlinx.coroutines.delay(20)
            if (PanoramaUi.aim != null) {
                PanoramaUi.openingSource = "attitude"
                PanoramaUi.turnToTarget()
                return@LaunchedEffect
            }
        }
        val pickedBearing = HorizonPlace.bearingOfKey(HorizonUi.selected)
        val atFix = HorizonUi.point == null
        val heading = GnssUi.headingDeg()
        PanoramaUi.openingSource = when {
            pickedBearing != null -> "peak"
            atFix && heading != null -> "heading"
            else -> "north"
        }
        PanoramaUi.aim = PanoramaAim.opening(wPx, hPx, HorizonPlace.openingBearing(pickedBearing, atFix, heading))
        // T327: a pick kept across the switch is the target at the opening (choice b): the view turns to it (a peak's
        // bearing is the opening's already; the tilt follows when it would be off the view, and a kept flight is turned to).
        PanoramaUi.turnToTarget()
        if (pickedBearing == null && atFix && heading == null) {
            // The compass started at the opening (SkyViewEffects holds it): its first reading within 1.5 s, if nobody steered.
            val until = PanoramaUi.openedAtMs + PanoramaUi.HEADING_WAIT_MS
            while (SystemClock.elapsedRealtime() < until) {
                kotlinx.coroutines.delay(100)
                GnssUi.headingDeg()?.let { PanoramaUi.lateHeading(it); return@LaunchedEffect }
            }
        }
    }

    val aim = PanoramaUi.aim
    val sky = work?.finest
    val gesturing = PanoramaUi.gesturing
    val zoomCorner = PanoramaScene.zoomCorner(wPx, hPx, d)
    // T330: the third button, "Follow phone" (choice (g)), above the zoom buttons, present only on a phone with an attitude
    // sensor; names keep off its box, and the edge arrow off the corner the three stand in.
    val sensor = io.github.jasondorjeshort.dystopo.app.controls.Heading.attitudeAvailable
    val following = PanoramaFollow.following
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val followW = remember(density, measurer) {
        measurer.measure(PanoramaStrings.FOLLOW_PHONE, TextStyle(fontSize = FOLLOW_TEXT_SP.sp, fontWeight = FontWeight.Medium)).size.width + 2 * FOLLOW_PAD_DP * d
    }
    val followBox = if (sensor && aim != null) followButtonBox(wPx, hPx, d, followW) else null
    val corner = followBox?.let { io.github.jasondorjeshort.dystopo.horizon.RimBox(minOf(zoomCorner.left, it.left - PanoramaScene.ZOOM_MARGIN_DP * d), it.top - PanoramaScene.ZOOM_MARGIN_DP * d, wPx, hPx) } ?: zoomCorner
    // T331 (choice (f)): the correction's line with Reset, at the lower left while a correction stands; its box (sized for
    // the widest line, so it does not move as the degrees change) is given to the scene and the arrow.
    val correction = PanoramaFollow.correctionDeg?.takeIf { following && aim?.follow == true }
    val correctionSize = remember(density, measurer, wPx) { correctionPlateSize(measurer, density, wPx) }
    val correctionBox = if (correction != null) correctionBoxAt(hPx, d, correctionSize, if (lensLayers?.flights != null) CREDIT_ROOM_DP else 0.0) else null
    val keepOff = listOfNotNull(zoomCorner, followBox, correctionBox)
    // T330 (choice (c)): while the followed attitude moves the names keep their offsets, as in a drag; placed when it rests.
    val holding = gesturing || (aim?.follow == true && PanoramaFollow.moving)
    val scene = remember(aim, wPx, hPx, sky, ranked, picked, holding, keepOff) {
        if (aim == null || sky == null || wPx <= 0 || hPx <= 0) null else {
            val t0 = System.nanoTime()
            val held = if (holding) PanoramaUi.scene?.takeIf { it.lens.width == wPx && it.lens.height == hPx }?.names else null
            val s = PanoramaScene.build(aim, wPx, hPx, d, sky, ranked, nameText, tapeText, keepOff, picked = picked, held = held)
            PanoramaUi.sceneBuilt(s, (System.nanoTime() - t0) / 1e6)
            s
        }
    }
    val selected = HorizonUi.selected
    // The spoken label starts with the place line, then the bearing and the field of view, then the names drawn.
    val description = listOfNotNull(
        "${PanoramaStrings.PLACE_LABEL}: $placeText",
        aim?.let { if (it.follow) PanoramaStrings.following(it.bearingDeg, it.tiltDeg, it.fovDeg) else PanoramaStrings.facing(it.bearingDeg, it.fovDeg) }, // T330
        scene?.names?.takeIf { it.isNotEmpty() }?.let { n -> "${HorizonText.PEAKS}: " + n.take(5).joinToString(", ") { it.label.name } },
    ).joinToString("; ")
    val touchSlop = LocalViewConfiguration.current.touchSlop
    Box(
        // T327: clipped, so a layer that runs off the view (the sun's path, a mark past the edge) never draws over the pane.
        Modifier.size(plotW, plotH).clipToBounds().testTag("panoramaPlot").onGloballyPositioned { c ->
            val at = c.positionInRoot()
            PanoramaUi.plotPx = listOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height).map { Math.round(it) }
        },
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = description
                    // T330: the four turn and look actions are Drag's; in Follow the phone turns the view. T331 (choice (f)):
                    // in Follow the two compass actions, a degree each, the drag's equal.
                    if (aim?.follow == true) customActions = listOf(
                        CustomAccessibilityAction(PanoramaStrings.ADJUST_LEFT) { PanoramaFollow.correct(PanoramaCorrection.Stepped(right = false)); true },
                        CustomAccessibilityAction(PanoramaStrings.ADJUST_RIGHT) { PanoramaFollow.correct(PanoramaCorrection.Stepped(right = true)); true },
                    )
                    else customActions = listOf(
                        CustomAccessibilityAction(PanoramaStrings.TURN_LEFT) { PanoramaUi.steer { a, _ -> a.copy(bearingDeg = PanoramaAim.wrapDeg(a.bearingDeg - PanoramaUi.turnStepDeg(a))) }; true },
                        CustomAccessibilityAction(PanoramaStrings.TURN_RIGHT) { PanoramaUi.steer { a, _ -> a.copy(bearingDeg = PanoramaAim.wrapDeg(a.bearingDeg + PanoramaUi.turnStepDeg(a))) }; true },
                        CustomAccessibilityAction(PanoramaStrings.LOOK_UP) { PanoramaUi.steer { a, _ -> a.copy(tiltDeg = (a.tiltDeg + PanoramaUi.TILT_STEP_DEG).coerceIn(PanoramaAim.MIN_TILT_DEG, PanoramaAim.MAX_TILT_DEG)) }; true },
                        CustomAccessibilityAction(PanoramaStrings.LOOK_DOWN) { PanoramaUi.steer { a, _ -> a.copy(tiltDeg = (a.tiltDeg - PanoramaUi.TILT_STEP_DEG).coerceIn(PanoramaAim.MIN_TILT_DEG, PanoramaAim.MAX_TILT_DEG)) }; true },
                    )
                }
                .pointerInput(Unit) {
                    // A drag turns and tilts, a pinch zooms about its centre (choice d); the names are held while it lasts (choice g).
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var started = false
                        var pan = Offset.Zero
                        var zoom = 1f
                        var multi = false // T331: once a second finger came down, the gesture is a pinch to its end
                        do {
                            val event = awaitPointerEvent()
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            if (event.changes.count { it.pressed } > 1) multi = true
                            var first = false
                            if (!started) {
                                pan += p
                                zoom *= z
                                if (pan.getDistance() > touchSlop || abs(zoom - 1f) > 0.04f) {
                                    started = true
                                    first = true
                                    PanoramaUi.gesturing = true
                                }
                            }
                            if (started) {
                                val c = event.calculateCentroid(useCurrent = true)
                                // T331 (choice (f)): in Follow a one-finger drag is the heading correction along the level
                                // line, the travel within the touch slop included so the drawing keeps up with the finger;
                                // a pinch's drift corrects nothing (choice (e)).
                                if (p != Offset.Zero) {
                                    val move = if (first) pan else p
                                    if (PanoramaUi.aim?.follow == true) { if (!multi) PanoramaFollow.dragBy(move.x.toDouble(), move.y.toDouble()) }
                                    else PanoramaUi.steer { a, (w, _, _) -> a.drag(p.x.toDouble(), p.y.toDouble(), w) }
                                }
                                if (z != 1f && c != Offset.Unspecified) PanoramaUi.steer { a, (w, h, _) -> a.pinch(z.toDouble(), c.x.toDouble(), c.y.toDouble(), w, h) }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (started) PanoramaUi.gesturing = false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        // The nearest name drawn (its box, or within 24 dp of it) picks its peak; T327: a flight's dot or name, a
                        // satellite's mark or the sun's within 24 dp make it the target, the nearest winning; a tap elsewhere lets go.
                        val reach = 24 * d
                        val names = PanoramaUi.scene?.names.orEmpty()
                        val hit = names.map { n -> n to hypot((n.placed.box.left + n.placed.box.right) / 2 - tap.x, (n.placed.box.top + n.placed.box.bottom) / 2 - tap.y) }
                            .filter { (n, _) -> val b = n.placed.box; tap.x >= b.left - reach && tap.x <= b.right + reach && tap.y >= b.top - reach && tap.y <= b.bottom + reach }
                            .minByOrNull { it.second }
                        val flight = PanoramaUi.flightMarks.map { m -> m to m.distanceTo(tap).toDouble() }.filter { (m, dd) -> dd <= reach || m.box?.contains(tap) == true }.minByOrNull { it.second }
                        val mark = PanoramaUi.lensMarks.map { m -> m to hypot((m.at.x - tap.x).toDouble(), (m.at.y - tap.y).toDouble()) }.filter { it.second <= reach }.minByOrNull { it.second }
                        val best = listOfNotNull(hit?.let { 0 to it.second }, flight?.let { 1 to it.second }, mark?.let { 2 to it.second }).minByOrNull { it.second }?.first
                        when (best) {
                            0 -> {
                                val key = (hit!!.first.label.ref as? PeakView)?.let { HorizonUi.keyOf(it) }
                                HorizonUi.selected = key?.takeIf { it != HorizonUi.selected }
                            }
                            1 -> HorizonUi.selectedFlight = flight!!.first.hex.takeIf { it != HorizonUi.selectedFlight }
                            2 -> mark!!.first.let { m ->
                                if (m.kind == TargetKind.SUN) PanoramaUi.apply(PanoramaTargets.SunTapped(up = true))
                                else PanoramaUi.apply(PanoramaTargets.SatelliteTapped(m.id, m.name))
                            }
                            else -> {
                                HorizonUi.selected = null
                                HorizonUi.selectedFlight = null
                                val k = PanoramaUi.targets.target?.kind
                                if (k == TargetKind.SUN || k == TargetKind.SATELLITE) PanoramaUi.apply(PanoramaTargets.LetGo)
                            }
                        }
                    }
                },
        ) {
            val t0 = System.nanoTime() // T330: the draw pass's time (task (5))
            drawRect(skyBrush(aim, tints, scene?.lens, size.width.toDouble(), size.height.toDouble()))
            if (scene != null) {
                drawGround(scene, tints, pal, sky!!.profiles.size)
                drawLensLayers(scene, lensLayers, pal, namePaint, nameText, lensPaints)
                drawNames(scene, pal, namePaint, selected)
                drawTape(scene, pal, tapePaint)
            } else {
                PanoramaUi.lensMarks = emptyList()
                PanoramaUi.flightMarks = emptyList()
            }
            val t1 = System.nanoTime()
            PanoramaUi.drawn((t1 - t0) / 1e6, t1)
        }
        // T327: the edge arrow while the target is off the view (a 48 dp target whose tap turns the view to it).
        val target = PanoramaUi.targets.target
        val dir = PanoramaUi.targetDir
        val arrow = if (scene != null && target != null && dir != null) {
            val m = EDGE_MARGIN_DP * d
            io.github.jasondorjeshort.dystopo.horizon.OffView.of(
                scene.lens, dir.first, dir.second, target.name,
                io.github.jasondorjeshort.dystopo.horizon.RimBox(m, scene.tape.box.bottom + m, wPx - m, hPx - m),
                follow = aim?.follow == true, // T330: by the screen's axes in Follow
            )
        } else null
        PanoramaUi.arrow = arrow
        val sign = aim?.follow == true // T331 (choice (i)): in Follow the arrow is a sign, no edge and no tap
        PanoramaUi.arrowClickable = arrow != null && !sign
        if (arrow == null) PanoramaUi.arrowPx = null
        else EdgeArrow(arrow, pal, wPx, hPx, corner, sign = sign, topPx = PanoramaScene.TAPE_DP * d + EDGE_MARGIN_DP * d, avoid = correctionBox)
        // T331: the correction's line and Reset (choice (f)).
        if (correctionBox == null || correction == null) { PanoramaUi.correctionPx = null; PanoramaUi.resetPx = null }
        else CorrectionPlate(correctionBox, correction, pal)
        // T330: "Follow phone" (choice (g)): one label with an on and off state, 48 dp tall, above the zoom buttons; flips the setting.
        if (followBox == null) PanoramaUi.followPx = null else FollowButton(followBox, following, pal)
        // The zoom buttons (48 dp each, stacked at the lower right: PanoramaScene.zoomButtons), the pinch's one-finger equal.
        if (aim != null) for ((i, b) in PanoramaScene.zoomButtons(wPx, hPx, d).withIndex()) {
            val inward = i == 0
            val label = if (inward) PanoramaStrings.ZOOM_IN else PanoramaStrings.ZOOM_OUT
            Box(
                Modifier.offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }.size(PanoramaScene.ZOOM_BUTTON_DP.dp)
                    .testTag(if (inward) "panoramaZoomIn" else "panoramaZoomOut")
                    .background(pal.card.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).border(1.dp, pal.line, RoundedCornerShape(12.dp))
                    .semantics { contentDescription = label }
                    .clickable(role = Role.Button) { PanoramaUi.steer { a, (w, h, _) -> a.zoom(inward, w, h) } },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(if (inward) "+" else "−", style = TextStyle(color = pal.ink, fontSize = with(density) { 24.dp.toSp() }, fontWeight = FontWeight.Medium))
            }
        }
        // While nothing is ready: the reason, or that the terrain is being read or the skyline worked out (T340).
        if (scene == null) {
            BasicText(
                PanoramaData.waiting(work),
                Modifier.align(Alignment.Center).padding(16.dp).testTag("panoramaWaiting"),
                style = TextStyle(color = pal.muted, fontSize = 15.sp),
            )
        }
    }
    SideEffect {
        // The list's bold rows read the names the plot drew (T325's parts read HorizonUi.namedKeys).
        val keys = scene?.names?.mapNotNull { (it.label.ref as? PeakView)?.let { p -> HorizonUi.keyOf(p) } }?.toSet() ?: emptySet()
        if (keys != HorizonUi.namedKeys) HorizonUi.namedKeys = keys
        if (scene != null && PanoramaUi.firstSkylineMs == null && PanoramaUi.openedAtMs > 0) {
            PanoramaUi.firstSkylineMs = SystemClock.elapsedRealtime() - PanoramaUi.openedAtMs
        }
        PanoramaUi.wanted = wanted
    }
}

/**
 * The sky: the paper's light at the level line to a cool tone 60 degrees up, along the centre column. T330: a rolled
 * Follow aim runs the gradient between where the level and 60 degrees up fall on [lens] (straight up the sky, at any roll).
 * T331: as a brush for a plot [width] by [height] px, its gradient moved down by [dy] px, so the strip under the status
 * bar above the plot ([dy] its height) carries the plot's sky on upward.
 */
internal fun skyBrush(aim: PanoramaAim?, tints: PanoramaTints, lens: io.github.jasondorjeshort.dystopo.horizon.LensView?, width: Double, height: Double, dy: Float = 0f): Brush {
    if (aim == null) return androidx.compose.ui.graphics.SolidColor(tints.skyLow)
    val flat = androidx.compose.ui.graphics.SolidColor(if (aim.tiltDeg > 30) tints.skyHigh else tints.skyLow)
    if (aim.follow && lens != null && aim.rollDeg != 0.0) {
        val a = lens.project(0.0, aim.bearingDeg)
        val b = lens.project(60.0, aim.bearingDeg)
        return if (a != null && b != null && hypot(a.x - b.x, a.y - b.y) > 1) {
            Brush.linearGradient(listOf(tints.skyLow, tints.skyHigh), start = Offset(a.x.toFloat(), a.y.toFloat() + dy), end = Offset(b.x.toFloat(), b.y.toFloat() + dy))
        } else flat
    }
    val f = PanoramaAim.focal(width, aim.fovDeg)
    fun rowOf(el: Double): Float? = (el - aim.tiltDeg).takeIf { abs(it) < 85 }?.let { (height / 2 - f * kotlin.math.tan(it * Math.PI / 180)).toFloat() }
    val y0 = rowOf(0.0)
    val y60 = rowOf(60.0)
    return if (y0 != null && y60 != null && abs(y0 - y60) > 1) Brush.verticalGradient(listOf(tints.skyHigh, tints.skyLow), startY = y60 + dy, endY = y0 + dy)
    else flat
}

/** The ground: the layers far to near, the ridges, the level line. */
private fun DrawScope.drawGround(scene: PanoramaScene, tints: PanoramaTints, pal: GnssPalette, count: Int) {
    val dp = density
    for (layer in scene.layers) {
        val tint = tints.layer(layer.index, count)
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        for (ring in layer.rings) {
            path.moveTo(ring[0].toFloat(), ring[1].toFloat())
            for (i in 1 until ring.size / 2) path.lineTo(ring[2 * i].toFloat(), ring[2 * i + 1].toFloat())
            path.close()
        }
        drawPath(path, tint)
        val ridge = androidx.compose.ui.graphics.lerp(tint, Color.Black, 0.35f)
        for (r in layer.ridges) {
            val p = Path()
            p.moveTo(r[0].toFloat(), r[1].toFloat())
            for (i in 1 until r.size / 2) p.lineTo(r[2 * i].toFloat(), r[2 * i + 1].toFloat())
            drawPath(p, ridge, style = Stroke(1f * dp))
        }
    }
    scene.level?.let { l ->
        drawLine(pal.ink.copy(alpha = 0.6f), Offset(l[0].toFloat(), l[1].toFloat()), Offset(l[2].toFloat(), l[3].toFloat()), 1.2f * dp,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * dp, 4f * dp)))
    }
}

/** The bearing tape: a plate along the top, its ticks and labels (drawn last, over every layer). */
private fun DrawScope.drawTape(scene: PanoramaScene, pal: GnssPalette, tapePaint: android.graphics.Paint) {
    val dp = density
    val tape = scene.tape
    drawRect(pal.card.copy(alpha = 0.85f), Offset(tape.box.left.toFloat(), tape.box.top.toFloat()), Size((tape.box.right - tape.box.left).toFloat(), (tape.box.bottom - tape.box.top).toFloat()))
    for (t in tape.ticks) drawLine(pal.ink, Offset(t.x.toFloat(), t.top.toFloat()), Offset(t.x.toFloat(), t.bottom.toFloat()), 1f * dp)
    tapePaint.color = pal.ink.toArgb()
    for (l in tape.labels) drawContext.canvas.nativeCanvas.drawText(l.text, l.box.left.toFloat(), l.baseline.toFloat(), tapePaint)
}

/**
 * The peaks: the candidates not named as small triangles, then each named one's triangle (solid in sight; T327: hollow, an
 * outline with no fill, just out of sight, so it never shows as a pale patch on the ground), its leader, and its name on a
 * plate. T327: the plate is the card's colour with a thin edge, so a name over the ground reads as a label, not as a pale
 * blob lying over the ridge (T326's frame facing south on its side).
 */
private fun DrawScope.drawNames(scene: PanoramaScene, pal: GnssPalette, namePaint: android.graphics.Paint, selected: String?) {
    val dp = density
    // The candidates not named: small triangles.
    val s = 5f * dp
    for (m in scene.marks) if (!m.named) drawPath(triangle(m.x.toFloat(), m.y.toFloat(), s * 0.55f), pal.muted.copy(alpha = 0.7f))
    for (n in scene.names) n.placed.leader?.let { l ->
        drawLine(pal.ink.copy(alpha = 0.55f), Offset(l[0].x.toFloat(), l[0].y.toFloat()), Offset(l[1].x.toFloat(), l[1].y.toFloat()), 1f * dp)
    }
    for (n in scene.names) {
        val key = (n.label.ref as? PeakView)?.let { HorizonUi.keyOf(it) }
        val colour = if (key != null && key == selected) pal.link else pal.ink
        val tri = triangle(n.markX.toFloat(), n.markY.toFloat(), s)
        if (n.label.outOfSight) drawPath(tri, colour, style = Stroke(1.5f * dp))
        else drawPath(tri, colour)
    }
    for (n in scene.names) {
        val p = n.placed
        val key = (n.label.ref as? PeakView)?.let { HorizonUi.keyOf(it) }
        val chosen = key != null && key == selected
        val at = Offset(p.box.left.toFloat(), p.box.top.toFloat())
        val size = Size((p.box.right - p.box.left).toFloat(), (p.box.bottom - p.box.top).toFloat())
        drawRoundRect(pal.card.copy(alpha = 0.92f), at, size, CornerRadius(3f * dp))
        drawRoundRect(if (chosen) pal.link else pal.line, at, size, CornerRadius(3f * dp), style = Stroke(1f * dp))
        namePaint.isFakeBoldText = chosen
        namePaint.color = (if (chosen) pal.link else pal.ink).toArgb()
        namePaint.textAlign = android.graphics.Paint.Align.CENTER
        drawContext.canvas.nativeCanvas.drawText(p.label.name, p.x.toFloat(), p.y.toFloat(), namePaint)
    }
    namePaint.isFakeBoldText = false
    namePaint.textAlign = android.graphics.Paint.Align.LEFT
}

private fun triangle(x: Float, y: Float, s: Float) = Path().apply { moveTo(x, y - s); lineTo(x + s, y + s * 0.7f); lineTo(x - s, y + s * 0.7f); close() }

/** T327: the edge arrow stands this far inside the plot's edges and under the tape, dp. */
private const val EDGE_MARGIN_DP = 6.0

/**
 * T327: the Sun, Satellites and Flights through the scene's lens, under the peaks' names (the round plot's order: the
 * flights under the names), writing what they drew (`GET /panorama` `layers`) and their marks for the taps.
 */
private fun DrawScope.drawLensLayers(scene: PanoramaScene, ll: LensLayers?, pal: GnssPalette, namePaint: android.graphics.Paint, nameText: PanoramaText, paints: LensPaints) {
    val marks = ArrayList<LensMark>()
    val drawn = LinkedHashMap<String, Any?>()
    var flights: List<io.github.jasondorjeshort.dystopo.app.horizon.FlightMark> = emptyList()
    if (ll != null) {
        if (ll.sunDay != null || ll.sunNow != null) drawn["sun"] = drawSunOnLens(scene.lens, ll.sunDay, ll.sunNow, pal, ll.night, marks, paints)
        if (ll.satellitesOn) drawn["satellites"] = drawSatellitesOnLens(scene.lens, ll.sats, pal, marks, paints)
        ll.flights?.let { f ->
            val names = placeFlightNames(scene, f.list, ll.pickedFlight, nameText, if (PanoramaUi.gesturing) PanoramaUi.flightNames else null)
            PanoramaUi.flightNames = names
            val (fm, info) = drawFlightsOnLens(scene.lens, f, names, ll.pickedFlight, namePaint, pal)
            flights = fm
            drawn["flights"] = info
        }
    }
    if (ll?.flights == null) PanoramaUi.flightNames = emptyList()
    namePaint.color = pal.ink.toArgb()
    PanoramaUi.lensMarks = marks
    PanoramaUi.flightMarks = flights
    PanoramaUi.layersDrawn = drawn
}

/**
 * T327: the edge arrow ([OffView]'s point and line, T324): a plate with the arrow and its line ("Sun, 40° right") at the
 * edge the target is off by, at least 48 dp tall, kept off the zoom buttons' corner; a tap turns the view to the target.
 */
@Composable
private fun EdgeArrow(
    a: io.github.jasondorjeshort.dystopo.horizon.OffViewArrow, pal: GnssPalette, wPx: Double, hPx: Double, zoom: io.github.jasondorjeshort.dystopo.horizon.RimBox,
    /** T331 (choice (i)): in Follow a sign: no edge, no click action, its line still spoken. */
    sign: Boolean = false,
    /** T331: the plate's top stays at or under this (px: under the tape; T330's note: it rose over the tape at an edge). */
    topPx: Double = 0.0,
    /** T331: a box the plate keeps off (the correction's line at the lower left): moved up above it. */
    avoid: io.github.jasondorjeshort.dystopo.horizon.RimBox? = null,
) {
    val side = a.side
    val glyph = when (side) {
        io.github.jasondorjeshort.dystopo.horizon.OffSide.LEFT -> "◀"
        io.github.jasondorjeshort.dystopo.horizon.OffSide.RIGHT -> "▶"
        io.github.jasondorjeshort.dystopo.horizon.OffSide.UP -> "▲"
        io.github.jasondorjeshort.dystopo.horizon.OffSide.DOWN -> "▼"
    }
    val style = TextStyle(color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    androidx.compose.foundation.layout.Row(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (wPx * 0.7).roundToInt().coerceAtLeast(1)))
                var x = when (side) {
                    io.github.jasondorjeshort.dystopo.horizon.OffSide.RIGHT -> a.x - p.width
                    io.github.jasondorjeshort.dystopo.horizon.OffSide.LEFT -> a.x
                    else -> a.x - p.width / 2.0
                }.coerceIn(0.0, maxOf(0.0, wPx - p.width))
                var y = when (side) {
                    io.github.jasondorjeshort.dystopo.horizon.OffSide.UP -> a.y
                    io.github.jasondorjeshort.dystopo.horizon.OffSide.DOWN -> a.y - p.height
                    else -> a.y - p.height / 2.0
                }.coerceIn(minOf(topPx, maxOf(0.0, hPx - p.height)), maxOf(0.0, hPx - p.height))
                // T331: off the correction's line: up above it.
                if (avoid != null && x < avoid.right && x + p.width > avoid.left && y < avoid.bottom && y + p.height > avoid.top) y = maxOf(minOf(topPx, avoid.top), avoid.top - p.height - 4.0 * density)
                // Off the zoom buttons' corner: up above it at the right edge, else to its left; T331: up above it too when
                // the plate is wider than the room to its left (a two-line plate at font 2.0 lay under "Follow phone").
                if (x + p.width > zoom.left && y + p.height > zoom.top) {
                    if (side == io.github.jasondorjeshort.dystopo.horizon.OffSide.RIGHT || zoom.left - p.width < 0) y = maxOf(topPx, zoom.top - p.height)
                    else x = zoom.left - p.width
                }
                layout(constraints.maxWidth, constraints.maxHeight) { p.place(x.roundToInt(), y.roundToInt()) }
            }
            .onGloballyPositioned { c ->
                val at = c.positionInRoot()
                PanoramaUi.arrowPx = listOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height).map { Math.round(it) }
            }
            .testTag("panoramaArrow")
            .heightIn(min = 48.dp)
            .background(pal.card.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
            .then(if (sign) Modifier else Modifier.border(1.5.dp, pal.link, RoundedCornerShape(12.dp)))
            .semantics(mergeDescendants = true) { contentDescription = a.text }
            .then(if (sign) Modifier else Modifier.clickable(role = Role.Button) { PanoramaUi.turnToTarget() })
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        // The line takes what the arrow leaves (at large text it wraps to two lines; the arrow is never pushed out).
        val right = side == io.github.jasondorjeshort.dystopo.horizon.OffSide.RIGHT
        if (!right) BasicText(glyph, Modifier.clearAndSetSemantics { }, style = style.copy(color = pal.link, fontSize = 16.sp))
        BasicText(a.text, Modifier.weight(1f, fill = false).clearAndSetSemantics { }, style = style, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (right) BasicText(glyph, Modifier.clearAndSetSemantics { }, style = style.copy(color = pal.link, fontSize = 16.sp))
    }
}

// T330
/** "Follow phone"'s text size (sp) and its inner padding at each side (dp). */
private const val FOLLOW_TEXT_SP = 14
private const val FOLLOW_PAD_DP = 12.0

/** "Follow phone"'s box (plot px): [widthPx] wide, 48 dp tall, right-aligned with the zoom buttons and a margin above them. */
internal fun followButtonBox(width: Double, height: Double, dp: Double, widthPx: Double): io.github.jasondorjeshort.dystopo.horizon.RimBox {
    val zin = PanoramaScene.zoomButtons(width, height, dp)[0]
    val bottom = zin.top - PanoramaScene.ZOOM_MARGIN_DP * dp
    return io.github.jasondorjeshort.dystopo.horizon.RimBox(zin.right - widthPx, bottom - PanoramaScene.ZOOM_BUTTON_DP * dp, zin.right, bottom)
}

/**
 * T330: the plot's third button (wave 46's choice (g)): "Follow phone", a switch whose on state is drawn filled in the link
 * colour; a tap flips the synced setting `panoramaSteering` (the row in Settings › Navigation and tracking is its other
 * place). Upright in its corner at any roll (it is not drawn through the lens).
 */
@Composable
private fun FollowButton(b: io.github.jasondorjeshort.dystopo.horizon.RimBox, on: Boolean, pal: GnssPalette) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
            .size(with(density) { (b.right - b.left).toFloat().toDp() }, PanoramaScene.ZOOM_BUTTON_DP.dp)
            .testTag("panoramaFollow")
            .onGloballyPositioned { c ->
                val at = c.positionInRoot()
                PanoramaUi.followPx = listOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height).map { Math.round(it) }
            }
            .background(if (on) pal.link else pal.card.copy(alpha = 0.9f), shape).border(1.dp, if (on) pal.link else pal.line, shape)
            .toggleable(value = on, role = Role.Switch) { setSteering(if (it) PanoramaSteering.FOLLOW else PanoramaSteering.DRAG) },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            PanoramaStrings.FOLLOW_PHONE, maxLines = 1,
            style = TextStyle(color = if (on) pal.card else pal.ink, fontSize = FOLLOW_TEXT_SP.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/** T330: sets `panoramaSteering` as its row does (off the main thread, then the screens read it again). */
internal fun setSteering(to: PanoramaSteering) {
    Thread({
        io.github.jasondorjeshort.dystopo.app.SettingsRoutes(io.github.jasondorjeshort.dystopo.app.DystopoApp.instance)
            .apply(io.github.jasondorjeshort.dystopo.app.SettingsRoutes.Parsed.Ok.of(io.github.jasondorjeshort.dystopo.app.AppSettings.PANORAMA_STEERING, to))
        io.github.jasondorjeshort.dystopo.app.DystopoApp.instance.map.onMain { io.github.jasondorjeshort.dystopo.app.SettingsUi.revision++ }
    }, "dystopo-panorama-steering").start()
}
// end T330

// T331
/** The correction's line: its text size (sp), the plate's inner padding (dp), the gap before Reset (dp), Reset's padding (dp). */
private const val LINE_TEXT_SP = 13
private const val LINE_PAD_DP = 10.0
private const val LINE_GAP_DP = 6.0
private const val RESET_TEXT_SP = 14
private const val RESET_PAD_DP = 12.0

/** The widest line the plate is sized for, so its box stays put as the degrees change. */
private val WIDEST_LINE = io.github.jasondorjeshort.dystopo.horizon.HeadingCorrection.line(-180.0)

/** The plate's size (px): the widest line (wrapping to two lines in at most 3/4 of the plot's width less Reset) and Reset, 48 dp tall at least. */
internal data class PlateSize(val width: Double, val height: Double, val textWidth: Double, val resetWidth: Double)

private fun correctionPlateSize(measurer: androidx.compose.ui.text.TextMeasurer, density: androidx.compose.ui.unit.Density, wPx: Double): PlateSize {
    val d = density.density.toDouble()
    val resetW = measurer.measure(PanoramaStrings.RESET, TextStyle(fontSize = RESET_TEXT_SP.sp, fontWeight = FontWeight.Medium)).size.width + 2 * RESET_PAD_DP * d
    // At most 3/4 of the plot's width, and clear of the zoom buttons' column at the right.
    val maxPlate = minOf(wPx * 0.75, wPx - (2 * EDGE_MARGIN_DP + PanoramaScene.ZOOM_MARGIN_DP + PanoramaScene.ZOOM_BUTTON_DP) * d)
    val maxText = (maxPlate - resetW - (2 * LINE_PAD_DP + LINE_GAP_DP) * d).coerceAtLeast(40 * d)
    val t = measurer.measure(
        WIDEST_LINE, TextStyle(fontSize = LINE_TEXT_SP.sp), maxLines = 2,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxText.roundToInt()),
    )
    val textW = t.size.width.toDouble()
    val h = maxOf(PanoramaScene.ZOOM_BUTTON_DP * d, t.size.height + 8 * d)
    return PlateSize(LINE_PAD_DP * d + textW + LINE_GAP_DP * d + resetW + 2 * d, h, textW, resetW)
}

/**
 * The plate's box (plot px): at the lower left, [EDGE_MARGIN_DP] in from the edges, over the near ground where no name
 * stands while the phone points at the land (at the upper left a dominant name forced to its place could fall under it);
 * above the flights' credit ([creditDp] of room) while that shows.
 */
internal fun correctionBoxAt(hPx: Double, d: Double, s: PlateSize, creditDp: Double): io.github.jasondorjeshort.dystopo.horizon.RimBox {
    val left = EDGE_MARGIN_DP * d
    val bottom = hPx - (EDGE_MARGIN_DP + creditDp) * d
    return io.github.jasondorjeshort.dystopo.horizon.RimBox(left, bottom - s.height, left + s.width, bottom)
}

/** The room the flights' credit takes at the plot's lower left (two 12 dp lines on a plate, 6 dp in), dp. */
internal const val CREDIT_ROOM_DP = 34.0

/**
 * T331: the heading correction's line (wave 46's choice (f): "Compass adjusted 4° east", [HeadingCorrection.line]) with
 * "Reset" (a 48 dp button that drops the correction), on a plate at the lower left of the plot, upright at any roll.
 */
@Composable
private fun CorrectionPlate(b: io.github.jasondorjeshort.dystopo.horizon.RimBox, deg: Double, pal: GnssPalette) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(12.dp)
    androidx.compose.foundation.layout.Row(
        Modifier.offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
            .size(with(density) { (b.right - b.left).toFloat().toDp() }, with(density) { (b.bottom - b.top).toFloat().toDp() })
            .testTag("panoramaCorrection")
            .onGloballyPositioned { c ->
                val at = c.positionInRoot()
                PanoramaUi.correctionPx = listOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height).map { Math.round(it) }
            }
            .background(pal.card.copy(alpha = 0.92f), shape).border(1.dp, pal.line, shape)
            .padding(start = LINE_PAD_DP.dp, end = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        BasicText(
            io.github.jasondorjeshort.dystopo.horizon.HeadingCorrection.line(deg), Modifier.weight(1f, fill = false).padding(end = LINE_GAP_DP.dp),
            style = TextStyle(color = pal.ink, fontSize = LINE_TEXT_SP.sp), maxLines = 2,
        )
        Box(
            Modifier.heightIn(min = PanoramaScene.ZOOM_BUTTON_DP.dp)
                .testTag("panoramaCorrectionReset")
                .onGloballyPositioned { c ->
                    val at = c.positionInRoot()
                    PanoramaUi.resetPx = listOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height).map { Math.round(it) }
                }
                .clickable(role = Role.Button) { PanoramaFollow.correct(PanoramaCorrection.Reset) }
                .padding(horizontal = RESET_PAD_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(PanoramaStrings.RESET, style = TextStyle(color = pal.link, fontSize = RESET_TEXT_SP.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}
// end T331
