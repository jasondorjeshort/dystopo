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
    val nameText = remember(namePaint) { namePaint.fontMetrics.let { fm -> PanoramaText({ namePaint.measureText(it).toDouble() }, fm.ascent.toDouble(), fm.descent.toDouble()) } }
    val tapeText = remember(tapePaint) { tapePaint.fontMetrics.let { fm -> PanoramaText({ tapePaint.measureText(it).toDouble() }, fm.ascent.toDouble(), fm.descent.toDouble()) } }

    // The aim at opening, once per opening, when the plot knows its size (choice c).
    val openings = PanoramaUi.openings
    val pending = PanoramaUi.aim == null
    LaunchedEffect(openings, pending, wPx > 0) {
        if (!pending || wPx <= 0) return@LaunchedEffect
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
    val scene = remember(aim, wPx, hPx, sky, ranked, picked, gesturing) {
        if (aim == null || sky == null || wPx <= 0 || hPx <= 0) null else {
            val t0 = System.nanoTime()
            val held = if (gesturing) PanoramaUi.scene?.takeIf { it.lens.width == wPx && it.lens.height == hPx }?.names else null
            val s = PanoramaScene.build(aim, wPx, hPx, d, sky, ranked, nameText, tapeText, zoomCorner, picked = picked, held = held)
            PanoramaUi.sceneBuilt(s, (System.nanoTime() - t0) / 1e6)
            s
        }
    }
    val selected = HorizonUi.selected
    // The spoken label starts with the place line, then the bearing and the field of view, then the names drawn.
    val description = listOfNotNull(
        "${PanoramaStrings.PLACE_LABEL}: $placeText",
        aim?.let { PanoramaStrings.facing(it.bearingDeg, it.fovDeg) },
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
                    customActions = listOf(
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
                        do {
                            val event = awaitPointerEvent()
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            if (!started) {
                                pan += p
                                zoom *= z
                                if (pan.getDistance() > touchSlop || abs(zoom - 1f) > 0.04f) {
                                    started = true
                                    PanoramaUi.gesturing = true
                                }
                            }
                            if (started) {
                                val c = event.calculateCentroid(useCurrent = true)
                                if (p != Offset.Zero) PanoramaUi.steer { a, (w, _, _) -> a.drag(p.x.toDouble(), p.y.toDouble(), w) }
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
            drawSky(aim, tints)
            if (scene != null) {
                drawGround(scene, tints, pal, sky!!.profiles.size)
                drawLensLayers(scene, lensLayers, pal, namePaint, nameText)
                drawNames(scene, pal, namePaint, selected)
                drawTape(scene, pal, tapePaint)
            } else {
                PanoramaUi.lensMarks = emptyList()
                PanoramaUi.flightMarks = emptyList()
            }
        }
        // T327: the edge arrow while the target is off the view (a 48 dp target whose tap turns the view to it).
        val target = PanoramaUi.targets.target
        val dir = PanoramaUi.targetDir
        val arrow = if (scene != null && target != null && dir != null) {
            val m = EDGE_MARGIN_DP * d
            io.github.jasondorjeshort.dystopo.horizon.OffView.of(
                scene.lens, dir.first, dir.second, target.name,
                io.github.jasondorjeshort.dystopo.horizon.RimBox(m, scene.tape.box.bottom + m, wPx - m, hPx - m),
            )
        } else null
        PanoramaUi.arrow = arrow
        if (arrow == null) PanoramaUi.arrowPx = null
        else EdgeArrow(arrow, pal, wPx, hPx, zoomCorner)
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
        // While nothing is ready: the reason, or that it is being worked out.
        if (scene == null) {
            BasicText(
                work?.reason ?: if (work == null) io.github.jasondorjeshort.dystopo.app.gnss.GnssText.NO_POSITION else PanoramaStrings.WORKING,
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

/** The sky: the paper's light at the level line to a cool tone 60 degrees up, along the centre column. */
private fun DrawScope.drawSky(aim: PanoramaAim?, tints: PanoramaTints) {
    if (aim == null) {
        drawRect(tints.skyLow)
        return
    }
    val f = PanoramaAim.focal(size.width.toDouble(), aim.fovDeg)
    fun rowOf(el: Double): Float? = (el - aim.tiltDeg).takeIf { abs(it) < 85 }?.let { (size.height / 2 - f * kotlin.math.tan(it * Math.PI / 180)).toFloat() }
    val y0 = rowOf(0.0)
    val y60 = rowOf(60.0)
    if (y0 != null && y60 != null && abs(y0 - y60) > 1) drawRect(Brush.verticalGradient(listOf(tints.skyHigh, tints.skyLow), startY = y60, endY = y0))
    else drawRect(if (aim.tiltDeg > 30) tints.skyHigh else tints.skyLow)
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
private fun DrawScope.drawLensLayers(scene: PanoramaScene, ll: LensLayers?, pal: GnssPalette, namePaint: android.graphics.Paint, nameText: PanoramaText) {
    val marks = ArrayList<LensMark>()
    val drawn = LinkedHashMap<String, Any?>()
    var flights: List<io.github.jasondorjeshort.dystopo.app.horizon.FlightMark> = emptyList()
    if (ll != null) {
        if (ll.sunDay != null || ll.sunNow != null) drawn["sun"] = drawSunOnLens(scene.lens, ll.sunDay, ll.sunNow, pal, ll.night, marks)
        if (ll.satellitesOn) drawn["satellites"] = drawSatellitesOnLens(scene.lens, ll.sats, pal, marks)
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
private fun EdgeArrow(a: io.github.jasondorjeshort.dystopo.horizon.OffViewArrow, pal: GnssPalette, wPx: Double, hPx: Double, zoom: io.github.jasondorjeshort.dystopo.horizon.RimBox) {
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
                }.coerceIn(0.0, maxOf(0.0, hPx - p.height))
                // Off the zoom buttons' corner: up above it at the right edge, else to its left.
                if (x + p.width > zoom.left && y + p.height > zoom.top) {
                    if (side == io.github.jasondorjeshort.dystopo.horizon.OffSide.RIGHT) y = maxOf(0.0, zoom.top - p.height) else x = maxOf(0.0, zoom.left - p.width)
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
            .border(1.5.dp, pal.link, RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) { contentDescription = a.text }
            .clickable(role = Role.Button) { PanoramaUi.turnToTarget() }
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
