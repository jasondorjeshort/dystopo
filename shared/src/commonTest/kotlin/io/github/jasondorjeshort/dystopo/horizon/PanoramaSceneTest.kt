package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.TerrainFixture
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T324: the Panorama's scene ([PanoramaScene]): its outlines against the per-ray answer, the tape, the names. */
class PanoramaSceneTest {
    private val w = 540.0
    private val h = 405.0
    private val dp = 1.3125
    private val text = PanoramaText({ s -> 6.0 * dp * s.length }, -10.0 * dp, 3.0 * dp)

    private fun cone(e: Double, n: Double, ce: Double, cn: Double, hM: Double, rad: Double) = max(0.0, hM * (1 - sqrt((e - ce) * (e - ce) + (n - cn) * (n - cn)) / rad))

    /** Cones at 2, 8, 20 and 45 km on rolling ground near Paradise (a public place), as `LensViewTest`'s. */
    private val skyline: LayeredSkyline by lazy {
        val fx = TerrainFixture(46.786, -121.735) { e, n ->
            1600.0 + 20 * kotlin.math.sin(e / 900) * kotlin.math.cos(n / 1300) +
                cone(e, n, 2000.0, 0.0, 300.0, 800.0) + cone(e, n, 0.0, 8000.0, 900.0, 2500.0) + cone(e, n, 600.0, 1500.0, 250.0, 500.0) +
                cone(e, n, -20_000.0, -3000.0, 1800.0, 5000.0) + cone(e, n, 30_000.0, 33_000.0, 2500.0, 8000.0)
        }
        LayeredSkyline.sweep(fx.terrain(Horizon.windowsAround(fx.lat, fx.lon, 12, 60_000.0)), fx.lat, fx.lon, LayeredSkyline.ranges(60_000.0), 0.5, 1.7)
    }

    /** A crater: the rim 45 degrees up all round, for the view straight up (the ground round the outside of the skyline). */
    private val crater: LayeredSkyline by lazy {
        val fx = TerrainFixture(46.786, -121.735) { e, n ->
            val d = sqrt(e * e + n * n)
            1000.0 + if (d < 1000) 0.0 else min(1500.0, (d - 1000) * 3.0)
        }
        LayeredSkyline.sweep(fx.terrain(Horizon.windowsAround(fx.lat, fx.lon, 12, 5_000.0)), fx.lat, fx.lon, listOf(3_000.0, 5_000.0), 1.0, 1.7)
    }

    /** The scene's rings filled far to near (even-odd, at pixel centres): each pixel's layer index, -1 the sky. */
    private fun raster(scene: PanoramaScene): IntArray {
        val iw = scene.width.toInt()
        val ih = scene.height.toInt()
        val out = IntArray(iw * ih) { -1 }
        for (layer in scene.layers) {
            for (y in 0 until ih) {
                val yc = y + 0.5
                val xs = ArrayList<Double>()
                for (ring in layer.rings) {
                    val k = ring.size / 2
                    for (i in 0 until k) {
                        val j = (i + 1) % k
                        val ay = ring[2 * i + 1]; val by = ring[2 * j + 1]
                        if ((ay > yc) != (by > yc)) xs += ring[2 * i] + (yc - ay) / (by - ay) * (ring[2 * j] - ring[2 * i])
                    }
                }
                xs.sort()
                for (p in 0 until xs.size / 2) {
                    val x0 = max(0, ceil(xs[2 * p] - 0.5).toInt())
                    val x1 = min(iw - 1, floor(xs[2 * p + 1] - 0.5).toInt())
                    for (x in x0..x1) out[y * iw + x] = layer.index
                }
            }
        }
        return out
    }

    /** The share of pixels whose filled layer is not the layer of their ray. */
    private fun differing(aim: PanoramaAim, s: LayeredSkyline = skyline): Pair<Double, Set<Int>> {
        val scene = PanoramaScene.build(aim, w, h, dp, s, emptyList(), text, text)
        val got = raster(scene)
        val iw = w.toInt()
        var bad = 0
        val layersSeen = HashSet<Int>()
        for (k in got.indices) {
            val (el, az) = scene.lens.ray(k % iw + 0.5, k / iw + 0.5)
            val want = s.layerAt(el, az)
            layersSeen += want
            if (got[k] != want) bad++
        }
        return bad.toDouble() / got.size to layersSeen
    }

    @Test
    fun theOutlinesFilledFarToNearGiveEachPixelTheLayerOfItsRay() {
        val cases = listOf(
            "opening, north across the wrap" to PanoramaAim.opening(w, h, 0.0),
            "opening, east" to PanoramaAim.opening(w, h, 85.0),
            "10 degrees on the north cone" to PanoramaAim(0.0, 6.0, 10.0),
            "30 degrees on the far cone" to PanoramaAim(42.0, 1.0, 30.0),
            "100 degrees" to PanoramaAim(300.0, 5.0, 100.0),
            "tilt -45" to PanoramaAim(20.0, -45.0, 60.0),
            "tilt 60" to PanoramaAim(20.0, 60.0, 100.0),
        )
        val all = HashSet<Int>()
        for ((name, aim) in cases) {
            val (share, seen) = differing(aim)
            assertTrue(share < 0.01, "$name: ${share * 100}% of pixels differ")
            all += seen
            println("T324 outlines against rays, $name: ${share * 100}% differ, layers $seen")
        }
        assertEquals(setOf(-1, 0, 1, 2, 3), all, "every layer and the sky are tested")
        // Straight up from the crater's floor: the rim stands round the view's corners, the ground outside the skyline's loop.
        val (share, seen) = differing(PanoramaAim(0.0, 90.0, 100.0), crater)
        assertTrue(share < 0.01, "straight up: ${share * 100}%")
        println("T324 outlines against rays, straight up in a crater: ${share * 100}% differ, layers $seen")
        val up = PanoramaScene.build(PanoramaAim(0.0, 90.0, 100.0), w, h, dp, crater, emptyList(), text, text)
        assertTrue(up.layers.any { it.rings.size == 2 }, "the skyline's loop wholly in front: the view less the loop, a hole")
        assertTrue(seen.size >= 2, "the rim and the sky: $seen")
    }

    @Test
    fun allGroundAndAllSky() {
        val down = PanoramaScene.build(PanoramaAim(0.0, -45.0, 10.0), w, h, dp, skyline, emptyList(), text, text)
        for (layer in down.layers) {
            assertEquals(1, layer.rings.size, "looking down every layer's ground covers the view")
            assertEquals(8, layer.rings[0].size)
            assertTrue(layer.ridges.isEmpty())
        }
        val up = PanoramaScene.build(PanoramaAim(0.0, 80.0, 10.0), w, h, dp, skyline, emptyList(), text, text)
        assertTrue(up.layers.all { it.rings.isEmpty() }, "all sky")
        assertNull(up.level)
    }

    @Test
    fun theLevelLineAndTheLayersOrder() {
        val aim = PanoramaAim.opening(w, h, 0.0)
        val s = PanoramaScene.build(aim, w, h, dp, skyline, emptyList(), text, text)
        val level = assertNotNull(s.level)
        assertTrue(kotlin.math.abs(level[1] - h * 2 / 3) < 1e-6)
        assertEquals(listOf(3, 2, 1, 0), s.layers.map { it.index }, "far to near")
        assertTrue(s.layers.first().ridges.isNotEmpty())
    }

    @Test
    fun theTapesLabelsNeverTouch() {
        val wide = 1080.0
        val dpPhone = 2.625
        val t = PanoramaText({ s -> 5.6 * dpPhone * s.length }, -9.3 * dpPhone, 2.4 * dpPhone)
        for (fov in listOf(10.0, 60.0, 100.0)) for (tilt in listOf(0.0, 30.0, 60.0)) for (b in listOf(0.0, 22.0, 180.0)) {
            val lens = PanoramaAim(b, tilt, fov).lens(wide, wide * 0.75, dpPhone)
            val tape = PanoramaScene.tape(lens, t)
            assertTrue(tape.labels.size >= 2, "fov $fov tilt $tilt bearing $b: ${tape.labels.map { it.text }}")
            for ((a, c) in tape.labels.zipWithNext()) assertTrue(c.box.left - a.box.right >= PanoramaScene.TAPE_GAP_DP * dpPhone - 1e-9, "fov $fov: ${a.text} and ${c.text} touch")
            for (l in tape.labels) assertTrue(l.box.left >= 0 && l.box.right <= wide)
        }
        // At 60 degrees looking north: N is labelled, with degrees between the compass points.
        val tape = PanoramaScene.tape(PanoramaAim(0.0, 0.0, 60.0).lens(wide, wide * 0.75, dpPhone), t)
        assertTrue("N" in tape.labels.map { it.text })
        assertTrue(tape.labels.any { it.text.endsWith("°") })
        val n = tape.ticks.first { it.bearingDeg == 0.0 }
        assertTrue(kotlin.math.abs(n.x - wide / 2) < 1e-6)
        assertEquals("NE", PanoramaScene.tapeText(45.0))
        assertEquals("15°", PanoramaScene.tapeText(15.0))
    }

    @Test
    fun aForcedNameNearAnEdgeStandsWhollyInsideAndOneOffTheLensIsReported() {
        val aim = PanoramaAim(90.0, 0.0, 60.0)
        val lens = aim.lens(w, h, dp)
        // The dominant peak's mark 1 px from the right edge, and the closest under the tape: both forced.
        val (elR, azR) = lens.ray(w - 1.0, h / 2)
        val (elT, azT) = lens.ray(w / 2, 5.0)
        val edge = HorizonLabel("A Long Dominant Name", LabelKind.PEAK, azR, elR, 20_000.0, 1.0, 4000.0, dominant = true)
        val top = HorizonLabel("Closest", LabelKind.PEAK, azT, elT, 300.0, 1.0, 1000.0)
        val behind = HorizonLabel("Behind", LabelKind.PEAK, 270.0, 1.0, 5000.0, 1.0, 2000.0)
        val beside = HorizonLabel("Beside", LabelKind.PEAK, 150.0, 1.0, 5000.0, 1.0, 2000.0)
        val ranked = HorizonLabels.rank(listOf(edge, top, behind, beside))
        val s = PanoramaScene.build(aim, w, h, dp, skyline, ranked, text, text)
        val names = s.names.associateBy { it.label.name }
        for (n in listOf("A Long Dominant Name", "Closest")) {
            val b = assertNotNull(names[n], "$n named").placed.box
            assertTrue(s.keepIn.holds(b), "$n wholly inside the view and under no tape: $b")
        }
        val left = s.leftOut.associate { it.label.name to it.reason }
        assertEquals(PanoramaScene.REASON_BEHIND, left["Behind"])
        assertEquals(PanoramaScene.REASON_OFF_VIEW, left["Beside"])
        assertEquals(2, s.marks.size)
        // Held through a drag: each name keeps its offset from its mark.
        val turned = aim.drag(-40.0, 0.0, w)
        val held = PanoramaScene.build(turned, w, h, dp, skyline, ranked, text, text, held = s.names)
        val before = names.getValue("Closest")
        val after = held.names.first { it.label.name == "Closest" }
        assertTrue(kotlin.math.abs((after.placed.x - after.markX) - (before.placed.x - before.markX)) < 1e-9)
        assertTrue(after.markX < before.markX - 30, "the mark moved left with the drag")
    }

    @Test
    fun t329TheOutlinesHoldRolledAndLookingFarDown() {
        val cases = listOf(
            "roll 30" to PanoramaAim.follow(10.0, 3.0, 30.0, 60.0),
            "roll 30, east, wide" to PanoramaAim.follow(85.0, 8.0, 30.0, 100.0),
            "roll -90" to PanoramaAim.follow(0.0, 2.0, -90.0, 60.0),
            "roll -90, narrow on the far cone" to PanoramaAim.follow(42.0, 1.0, -90.0, 30.0),
            "tilt -75" to PanoramaAim.follow(20.0, -75.0, 0.0, 100.0),
            "tilt -75, roll 40" to PanoramaAim.follow(200.0, -75.0, 40.0, 100.0),
        )
        val all = HashSet<Int>()
        for ((name, aim) in cases) {
            val (share, seen) = differing(aim)
            assertTrue(share < 0.01, "$name: ${share * 100}% of pixels differ")
            all += seen
            println("T329 outlines against rays, $name: ${share * 100}% differ, layers $seen")
        }
        assertTrue(all.containsAll(listOf(-1, 0, 1)), "the sky and the near layers are tested rolled: $all")
        // Through the crater's zenith rolled: the hole of the skyline's loop.
        val (share, _) = differing(PanoramaAim.follow(0.0, 80.0, 65.0, 100.0), crater)
        assertTrue(share < 0.01, "near straight up, rolled: ${share * 100}%")
    }

    @Test
    fun t329StraightDownIsAllGroundAndStraightUpAllSky() {
        for (roll in listOf(0.0, 35.0, -120.0)) {
            val down = PanoramaScene.build(PanoramaAim.follow(30.0, -90.0, roll, 60.0), w, h, dp, skyline, emptyList(), text, text)
            for (layer in down.layers) {
                assertEquals(1, layer.rings.size, "roll $roll: straight down every layer's ground covers the view")
                assertEquals(8, layer.rings[0].size)
            }
            assertNull(down.level)
            val up = PanoramaScene.build(PanoramaAim.follow(30.0, 90.0, roll, 60.0), w, h, dp, skyline, emptyList(), text, text)
            assertTrue(up.layers.all { it.rings.isEmpty() && it.ridges.isEmpty() }, "roll $roll: straight up all sky")
            assertNull(up.level)
        }
    }

    @Test
    fun t329TheLevelLineAtARollLiesOnTheLevel() {
        for ((tilt, roll) in listOf(0.0 to 20.0, 10.0 to -35.0, -20.0 to 70.0, 0.0 to 90.0)) {
            val aim = PanoramaAim.follow(100.0, tilt, roll, 60.0)
            val lens = aim.lens(w, h, dp)
            val l = assertNotNull(PanoramaScene.level(lens), "tilt $tilt roll $roll")
            for (s in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                val (el, _) = lens.ray(l[0] + s * (l[2] - l[0]), l[1] + s * (l[3] - l[1]))
                assertTrue(kotlin.math.abs(el) < 1e-6, "tilt $tilt roll $roll at $s: elevation $el")
            }
            assertTrue(kotlin.math.hypot(l[2] - l[0], l[3] - l[1]) > 100, "it crosses the view")
            // Its slope is the roll's: a clockwise phone sees the level rise to the right.
            if (roll != 90.0) assertEquals(-kotlin.math.tan(roll * kotlin.math.PI / 180), (l[3] - l[1]) / (l[2] - l[0]), 1e-6)
        }
        // At no roll the Follow aim's line is the Drag aim's.
        val drag = assertNotNull(PanoramaScene.level(PanoramaAim(40.0, 7.0, 60.0).lens(w, h, dp)))
        val follow = assertNotNull(PanoramaScene.level(PanoramaAim.follow(40.0, 7.0, 0.0, 60.0).lens(w, h, dp)))
        for (i in 0 until 4) assertEquals(drag[i], follow[i], 1e-9)
    }

    @Test
    fun t329TheTapeRolledAndItsOneFacingMark() {
        val wide = 1080.0
        val dpPhone = 2.625
        val t = PanoramaText({ s -> 5.6 * dpPhone * s.length }, -9.3 * dpPhone, 2.4 * dpPhone)
        // Rolled 20 degrees: the ticks still cross the top edge, the labels upright boxes that never touch.
        val rolled = PanoramaScene.tape(PanoramaAim.follow(0.0, 0.0, 20.0, 60.0).lens(wide, wide * 0.75, dpPhone), t)
        assertNull(rolled.facing)
        assertTrue(rolled.ticks.size >= PanoramaScene.TAPE_MIN_TICKS && rolled.labels.size >= 2, "${rolled.labels.map { it.text }}")
        for ((a, c) in rolled.labels.zipWithNext()) assertTrue(c.box.left - a.box.right >= PanoramaScene.TAPE_GAP_DP * dpPhone - 1e-9)
        // On its side unturned (roll 90) the top edge runs up and down one bearing: no tick crosses it, the one mark stands.
        val side = PanoramaAim.follow(347.0, 0.0, 90.0, 60.0).lens(wide, wide * 0.75, dpPhone)
        val tape = PanoramaScene.tape(side, t)
        assertTrue(tape.ticks.size < PanoramaScene.TAPE_MIN_TICKS)
        assertTrue(tape.ticks.isEmpty() && tape.labels.isEmpty())
        val mark = assertNotNull(tape.facing)
        assertEquals("Facing 347°", mark.text)
        assertEquals(wide / 2, mark.x, 1e-9)
        assertTrue(mark.box.left >= 0 && mark.box.right <= wide && mark.box.bottom <= tape.box.bottom)
        assertEquals("Facing N", PanoramaScene.facingText(0.4))
        // Today's aims never give the mark.
        assertNull(PanoramaScene.tape(PanoramaAim(0.0, 90.0, 10.0).lens(wide, wide * 0.75, dpPhone), t).facing)
    }

    @Test
    fun t329NamesKeepOffEveryBoxTheCallerGives() {
        val aim = PanoramaAim(90.0, 0.0, 60.0)
        val lens = aim.lens(w, h, dp)
        val peaks = (0 until 12).map { i ->
            val (el, az) = lens.ray(40.0 + i * 40.0, h / 2 + (i % 3) * 20)
            HorizonLabel("Peak $i", LabelKind.PEAK, az, el, 5000.0 + i * 100, 1.0, 2000.0 + i)
        }
        val ranked = HorizonLabels.rank(peaks)
        val boxes = listOf(RimBox(0.0, h / 2 - 120, w / 2, h / 2), RimBox(w - 80, h - 80, w, h))
        val s = PanoramaScene.build(aim, w, h, dp, skyline, ranked, text, text, boxes)
        assertTrue(s.names.isNotEmpty())
        // A forced name (the dominant, the closest) may be moved into the view over a box when it has no free place; the rest never are.
        val closest = peaks.minBy { it.distanceM }
        val free = s.names.filter { !it.label.dominant && it.label != closest }
        assertTrue(free.isNotEmpty())
        for (n in free) for (b in boxes) assertTrue(!n.placed.box.overlaps(b), "${n.label.name} over $b")
        assertTrue(boxes.all { it in s.blocked })
    }

    @Test
    fun theSweepsStepsCoarseToFine() {
        assertEquals(listOf(1.0, 0.1), PanoramaSweep.steps(10.0, 1080.0))
        assertEquals(listOf(1.0, 0.5), PanoramaSweep.steps(60.0, 1080.0))
        assertEquals(listOf(1.0), PanoramaSweep.steps(100.0, 1080.0))
        for (fov in listOf(10.0, 15.0, 30.0, 60.0, 100.0)) {
            val s = PanoramaSweep.steps(fov, 1080.0)
            assertEquals(1.0, s.first())
            assertTrue(s.zipWithNext().all { (a, b) -> b < a })
        }
    }
}
