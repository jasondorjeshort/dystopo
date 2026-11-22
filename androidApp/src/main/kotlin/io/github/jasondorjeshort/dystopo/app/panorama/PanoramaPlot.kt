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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
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
    /** The layers drawn over the terrain and the names, in order (T327's Sun, Satellites and Flights through the scene's lens). */
    layers: List<DrawScope.(PanoramaScene) -> Unit> = emptyList(),
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
        Modifier.size(plotW, plotH).testTag("panoramaPlot").onGloballyPositioned { c ->
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
                        // The nearest name drawn (its box, or within 24 dp of it) picks its peak; a tap elsewhere lets it go.
                        val names = PanoramaUi.scene?.names.orEmpty()
                        val hit = names.minByOrNull { n -> hypot((n.placed.box.left + n.placed.box.right) / 2 - tap.x, (n.placed.box.top + n.placed.box.bottom) / 2 - tap.y) }
                            ?.takeIf { n -> val b = n.placed.box; val r = 24 * d; tap.x >= b.left - r && tap.x <= b.right + r && tap.y >= b.top - r && tap.y <= b.bottom + r }
                        val key = (hit?.label?.ref as? PeakView)?.let { HorizonUi.keyOf(it) }
                        HorizonUi.selected = key?.takeIf { it != HorizonUi.selected }
                    }
                },
        ) {
            drawSky(aim, tints)
            if (scene != null) {
                drawScene(scene, tints, pal, namePaint, tapePaint, selected, sky!!.profiles.size)
                for (l in layers) l(scene)
            }
        }
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

/** The scene: the layers far to near, the ridges, the level line, the tape, the marks and the names on their plates. */
private fun DrawScope.drawScene(scene: PanoramaScene, tints: PanoramaTints, pal: GnssPalette, namePaint: android.graphics.Paint, tapePaint: android.graphics.Paint, selected: String?, count: Int) {
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
    // The tape: a plate along the top, its ticks and labels.
    val tape = scene.tape
    drawRect(pal.card.copy(alpha = 0.85f), Offset(tape.box.left.toFloat(), tape.box.top.toFloat()), Size((tape.box.right - tape.box.left).toFloat(), (tape.box.bottom - tape.box.top).toFloat()))
    for (t in tape.ticks) drawLine(pal.ink, Offset(t.x.toFloat(), t.top.toFloat()), Offset(t.x.toFloat(), t.bottom.toFloat()), 1f * dp)
    tapePaint.color = pal.ink.toArgb()
    for (l in tape.labels) drawContext.canvas.nativeCanvas.drawText(l.text, l.box.left.toFloat(), l.baseline.toFloat(), tapePaint)
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
        if (n.label.outOfSight) {
            drawPath(tri, pal.sky)
            drawPath(tri, colour, style = Stroke(1.5f * dp))
        } else drawPath(tri, colour)
    }
    for (n in scene.names) {
        val p = n.placed
        val key = (n.label.ref as? PeakView)?.let { HorizonUi.keyOf(it) }
        val chosen = key != null && key == selected
        drawRoundRect(pal.sky.copy(alpha = 0.85f), Offset(p.box.left.toFloat(), p.box.top.toFloat()), Size((p.box.right - p.box.left).toFloat(), (p.box.bottom - p.box.top).toFloat()), CornerRadius(3f * dp))
        namePaint.isFakeBoldText = chosen
        namePaint.color = (if (chosen) pal.link else pal.ink).toArgb()
        namePaint.textAlign = android.graphics.Paint.Align.CENTER
        drawContext.canvas.nativeCanvas.drawText(p.label.name, p.x.toFloat(), p.y.toFloat(), namePaint)
    }
    namePaint.isFakeBoldText = false
    namePaint.textAlign = android.graphics.Paint.Align.LEFT
}

private fun triangle(x: Float, y: Float, s: Float) = Path().apply { moveTo(x, y - s); lineTo(x + s, y + s * 0.7f); lineTo(x - s, y + s * 0.7f); close() }
