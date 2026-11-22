package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.Peak
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.horizon.HorizonLabels
import io.github.jasondorjeshort.dystopo.horizon.LayeredSkyline
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim
import io.github.jasondorjeshort.dystopo.horizon.PeakRim
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T322: the Panorama golden (`goldens/panorama/`): synthetic ridges at 2, 8, 20 and 36 km north of a point at 45 N,
 * 0.5 E, four named, swept at 3, 10, 30 and 40 km and drawn through a 60 degree lens looking north, 4 degrees up.
 */
object PanoramaGolden {
    const val FILE = "panorama/synthetic-ridges-b0-t4-f60.png"
    private const val LAT = 45.0
    private const val LON = 0.5
    private const val RANGE_M = 40_000.0

    private fun cone(e: Double, n: Double, ce: Double, cn: Double, h: Double, rad: Double) = max(0.0, h * (1 - sqrt((e - ce) * (e - ce) + (n - cn) * (n - cn)) / rad))

    /** Ground metres [e] east and [n] north of the point. */
    fun heightAt(e: Double, n: Double): Double =
        800.0 + cone(e, n, -600.0, 2000.0, 260.0, 900.0) + cone(e, n, 2500.0, 8000.0, 900.0, 3000.0) + cone(e, n, -4000.0, 8500.0, 700.0, 2500.0) +
            cone(e, n, 3000.0, 20_000.0, 1900.0, 6000.0) + cone(e, n, -9000.0, 21_000.0, 1500.0, 5000.0) + cone(e, n, -1000.0, 36_000.0, 3200.0, 9000.0)

    private fun latLon(e: Double, n: Double): Pair<Double, Double> {
        val mpp = WebMercator.metersPerPixel(LAT, 20)
        val gx = WebMercator.lonToX(LON, 20) * 256 + e / mpp
        val gy = WebMercator.latToY(LAT, 20) * 256 - n / mpp
        return WebMercator.yToLat(gy / 256, 20) to WebMercator.xToLon(gx / 256, 20)
    }

    val peaks: List<Peak> = listOf(
        Triple("Near Knoll", -600.0, 2000.0), Triple("East Cone", 2500.0, 8000.0), Triple("West Cone", -4000.0, 8500.0),
        Triple("Tall Dome", 3000.0, 20_000.0), Triple("Far Summit", -1000.0, 36_000.0),
    ).map { (name, e, n) -> latLon(e, n).let { (la, lo) -> Peak(name, "peak", la, lo, heightAt(e, n)) } }

    fun terrain(): Terrain = Terrain.read(Horizon.windowsAround(LAT, LON, 12, RANGE_M)) { w: MercatorWindow ->
        val z = w.zoom
        val m = WebMercator.metersPerPixel(LAT, z)
        val cx = WebMercator.lonToX(LON, z) * 256
        val cy = WebMercator.latToY(LAT, z) * 256
        ElevationGrid(w.width, w.height, m, FloatArray(w.width * w.height) { k ->
            heightAt((w.x0 + k % w.width + 0.5 - cx) * m, -(w.y0 + k / w.width + 0.5 - cy) * m).toFloat()
        })
    }

    fun render(fov: Double = 60.0): PanoramaRender.Drawn {
        val terrain = terrain()
        val skyline = LayeredSkyline.sweep(terrain, LAT, LON, LayeredSkyline.ranges(RANGE_M), 0.5, 1.7)
        val ranked = HorizonLabels.rank(PeakRim.labels(PanoramaRender.peakViews(terrain, skyline, peaks)))
        return PanoramaRender.draw(PanoramaAim(0.0, 4.0, fov), 540.0, 540.0, 1.5, skyline, ranked)
    }

    /** The text's boxes, 2 px wider each side: where the comparison is loose (fonts differ a little by machine). */
    fun mask(d: PanoramaRender.Drawn): BooleanArray {
        val w = d.image.width
        val m = BooleanArray(w * d.image.height)
        for (b in d.textBoxes) for (y in max(0, floor(b.top).toInt() - 2) until minOf(d.image.height, b.bottom.toInt() + 3)) for (x in max(0, floor(b.left).toInt() - 2) until minOf(w, b.right.toInt() + 3)) m[y * w + x] = true
        return m
    }
}

class PanoramaCommandTest {
    private val strict = Tolerance(maxMeanAbs = 0.25, pixelThreshold = 12, maxDifferingFraction = 0.002)
    private val labels = Tolerance(maxMeanAbs = 12.0, pixelThreshold = 12, maxDifferingFraction = 0.30)

    @Test
    fun theSyntheticPanoramaMatchesItsGolden() {
        val d = PanoramaGolden.render()
        val actual = d.image
        val expected = javaClass.getResourceAsStream("/goldens/${PanoramaGolden.FILE}")?.use { ImageIO.read(it) }
            ?: fail("no golden goldens/${PanoramaGolden.FILE}; actual at ${write(actual, "actual")}. Run ./gradlew updateGoldens and view it.")
        if (expected.width != actual.width || expected.height != actual.height) fail("size ${actual.width}x${actual.height}, golden ${expected.width}x${expected.height}")
        val diff = ImageDiff(expected, actual, PanoramaGolden.mask(d), 12)
        if (!diff.outside.within(strict) || !diff.inside.within(labels)) {
            write(actual, "actual")
            fail(
                "panorama differs from goldens/${PanoramaGolden.FILE}:\n  outside text: ${diff.outside.describe(strict)}\n  text: ${diff.inside.describe(labels)}\n" +
                    "  ${write(diff.diffImage(), "diff")}\nIf the change is intended, run ./gradlew updateGoldens, view the new image, and commit it.",
            )
        }
    }

    @Test
    fun theNamesStandOnTheViewAndANarrowerViewSpreadsThem() {
        val wide = PanoramaGolden.render(60.0)
        val names = wide.placed.map { it.label.name }
        assertTrue("Far Summit" in names && "Near Knoll" in names, "named: $names")
        for (p in wide.placed) assertTrue(p.box.left >= 0 && p.box.right <= 540 && p.box.top >= 0 && p.box.bottom <= 540, "${p.label.name} on the view")
        // The same names at 30 degrees stand farther apart on the screen.
        val narrow = PanoramaGolden.render(30.0)
        val a = wide.placed.associateBy { it.label.name }
        val b = narrow.placed.associateBy { it.label.name }
        val both = a.keys.intersect(b.keys).toList()
        assertTrue(both.size >= 2, "named at both: $both")
        fun spread(m: Map<String, HorizonLabels.Placed>) = both.maxOf { m[it]!!.x } - both.minOf { m[it]!!.x }
        assertTrue(spread(b) > spread(a))
        assertEquals(LayeredSkyline.ranges(40_000.0), listOf(3_000.0, 10_000.0, 30_000.0, 40_000.0))
    }

    @Test
    fun aTargetBehindGetsItsEdgeArrowAndLineAndTheSceneIsCheap() {
        val terrain = PanoramaGolden.terrain()
        val skyline = LayeredSkyline.sweep(terrain, 45.0, 0.5, LayeredSkyline.ranges(40_000.0), 0.5, 1.7)
        val d = PanoramaRender.draw(PanoramaAim(0.0, 4.0, 60.0), 540.0, 405.0, 1.5, skyline, emptyList(), Triple("Sun", 10.0, 200.0))
        val a = d.arrow ?: fail("no arrow for a target behind")
        assertEquals("Sun, 160° left", a.text)
        assertTrue(a.x < 540.0 / 2)
        assertEquals(null, PanoramaRender.draw(PanoramaAim(0.0, 4.0, 60.0), 540.0, 405.0, 1.5, skyline, emptyList(), Triple("Ahead", 2.0, 5.0)).arrow)
        val cost = PanoramaRender.sceneCost(d, builds = 5)
        assertTrue(cost.placedMs < 200 && cost.heldMs < 200, "scene ${cost.placedMs} ms")
    }

    private fun write(image: BufferedImage, kind: String): String {
        val f = File(Goldens.diffDir.absoluteFile, PanoramaGolden.FILE.replace('/', '-').removeSuffix(".png") + "-$kind.png")
        f.parentFile.mkdirs()
        ImageIO.write(image, "png", f)
        return f.path
    }
}
