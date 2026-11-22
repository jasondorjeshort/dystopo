package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.dem.TerrainFixture
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T334: "Can I see it from here?" on synthetic terrain: clear, a ridge between, a higher ridge beyond, out of range, no elevation. */
class SightLineTest {
    // Paradise on Rainier (a public place in areas.json); the ground around it is synthetic.
    private val lat = 46.786
    private val lon = -121.735
    private val zoom = 13

    /** The point [m] metres from the observer toward [deg] (flat-earth step). */
    private fun toward(m: Double, deg: Double): Pair<Double, Double> {
        val r = deg * PI / 180
        return (lat + m * cos(r) / 111_195.0) to (lon + m * sin(r) / (111_195.0 * cos(lat * PI / 180)))
    }

    /** A round hill [h] metres high at [north] metres north, [r] metres across. */
    private fun hill(east: Double, north: Double, atNorth: Double, h: Double, r: Double = 400.0): Double {
        val dn = north - atNorth
        return h * exp(-(east * east + dn * dn) / (r * r))
    }

    /** An east-west ridge [h] metres high at [atNorth] metres north, [r] metres across. */
    private fun ridge(north: Double, atNorth: Double, h: Double, r: Double = 200.0): Double {
        val dn = north - atNorth
        return h * exp(-dn * dn / (r * r))
    }

    private fun terrain(rangeM: Double = Horizon.DEFAULT_RANGE_M, f: (Double, Double) -> Double): Terrain {
        val fx = TerrainFixture(lat, lon, f)
        return fx.terrain(Horizon.windowsAround(lat, lon, zoom, rangeM))
    }

    /** Both ways, the ray alone and the kept horizon first: the same kind. */
    private fun both(t: Terrain, place: Pair<Double, Double>, tagM: Double? = null): Pair<Sight, Sight> {
        val byRay = SightLine.of(null, t, lat, lon, place.first, place.second, tagM)
        val profile = Horizon.sweep(t, lat, lon, observerHeightM = Horizon.EYE_HEIGHT_M)
        val byHorizon = SightLine.of(profile, t, lat, lon, place.first, place.second, tagM)
        return byRay to byHorizon
    }

    @Test
    fun clear() {
        val t = terrain { e, n -> hill(e, n, 5_000.0, 200.0) }
        val (ray, kept) = both(t, toward(5_000.0, 0.0))
        assertEquals(SightKind.IN_VIEW, ray.kind)
        assertEquals("ray", ray.by)
        assertEquals(SightKind.IN_VIEW, kept.kind)
        assertEquals("horizon", kept.by, "the hill is the horizon on its bearing: the lookup answers")
        assertEquals("In view, 5.00 km N", ray.text(ContourUnits.METERS))
        assertEquals("In view, 3.1 mi N", ray.text(ContourUnits.FEET))
        assertEquals(200.0, ray.placeEleM!!, 20.0)
        assertNull(ray.ridgeM)
    }

    @Test
    fun aRidgeBetween() {
        // A 300 m ridge 2 km north; the place on flat ground 6 km north.
        val t = terrain { _, n -> ridge(n, 2_000.0, 300.0) }
        val (ray, kept) = both(t, toward(6_000.0, 0.0))
        for (s in listOf(ray, kept)) {
            assertEquals(SightKind.HIDDEN, s.kind, s.toString())
            assertEquals(2_000.0, s.ridgeM!!, 150.0)
            // The line of sight over the ridge's top stands some 900 m above the flat ground at three times the distance.
            assertTrue(s.belowM!! in 800.0..950.0, s.toString())
        }
        assertEquals("ray", ray.by)
        assertEquals("horizon", kept.by, "the ridge is the horizon and nearer than the place: the lookup answers")
        val text = ray.text(ContourUnits.METERS)!!
        assertTrue(text.matches(Regex("Hidden by a ridge [12]\\.\\d\\d km away, \\d+ m below the line of sight")), text)
        assertTrue(ray.text(ContourUnits.FEET)!!.matches(Regex("Hidden by a ridge 1\\.\\d mi away, \\d+ ft below the line of sight")))
    }

    @Test
    fun aHigherRidgeBeyondThePlaceReadsInView() {
        // A 200 m hill 4 km north, and a 1500 m ridge 10 km north behind it: the horizon on the bearing is the far ridge,
        // the place stands below it, and nothing between hides it.
        val t = terrain { e, n -> hill(e, n, 4_000.0, 200.0) + ridge(n, 10_000.0, 1_500.0) }
        val (ray, kept) = both(t, toward(4_000.0, 0.0))
        assertEquals(SightKind.IN_VIEW, ray.kind, ray.toString())
        assertEquals(SightKind.IN_VIEW, kept.kind, kept.toString())
        assertEquals("ray", kept.by, "the highest ground lies beyond the place: one ray decides")
        assertTrue(kept.blockDeg!! < kept.angleDeg!!)
    }

    @Test
    fun beyondTheRange() {
        val t = terrain { e, n -> hill(e, n, 5_000.0, 200.0) }
        // Past the range read.
        val far = toward(Horizon.DEFAULT_RANGE_M + 2_000.0, 45.0)
        val (ray, kept) = both(t, far)
        for (s in listOf(ray, kept)) {
            assertEquals(SightKind.CANNOT_TELL, s.kind)
            assertEquals("beyondRange", s.why)
            assertEquals("Cannot tell from here", s.text(ContourUnits.FEET))
        }
        // A terrain read only 3 km out: a place 5 km out is beyond it, though the range is 30 km.
        val short = terrain(3_000.0) { e, n -> hill(e, n, 5_000.0, 200.0) }
        val s = SightLine.of(null, short, lat, lon, toward(5_000.0, 0.0).first, toward(5_000.0, 0.0).second)
        assertEquals(SightKind.CANNOT_TELL, s.kind)
        assertEquals("beyondRange", s.why)
    }

    @Test
    fun noElevation() {
        // No data within 600 m of the place.
        val t = terrain { e, n -> if (abs(e) < 600 && abs(n - 5_000.0) < 600) Double.NaN else 10.0 }
        val (ray, kept) = both(t, toward(5_000.0, 0.0))
        for (s in listOf(ray, kept)) {
            assertEquals(SightKind.CANNOT_TELL, s.kind, s.toString())
            assertEquals("noElevation", s.why)
        }
        // No terrain at all and no tag.
        assertEquals("noElevation", SightLine.of(null, null, lat, lon, toward(5_000.0, 0.0).first, toward(5_000.0, 0.0).second).why)
    }

    @Test
    fun aTagStandsThePlaceUpUnlessTheGroundDisagrees() {
        // The ridge at 2 km hides flat ground 6 km out; a 1200 m tag there (the ground reads 0) is taken for wrong.
        val t = terrain { _, n -> ridge(n, 2_000.0, 300.0) + hill(0.0, n, 6_000.0, 1_100.0) }
        val place = toward(6_000.0, 0.0)
        val tagged = SightLine.of(null, t, lat, lon, place.first, place.second, tagM = 1_150.0)
        assertEquals("tag", tagged.eleFrom)
        assertEquals(SightKind.IN_VIEW, tagged.kind, tagged.toString())
        val wrong = SightLine.of(null, t, lat, lon, place.first, place.second, tagM = 3_000.0)
        assertEquals("dem", wrong.eleFrom)
        assertNotNull(wrong.placeEleM)
    }

    @Test
    fun whereYouStandDrawsNoLine() {
        val t = terrain { _, _ -> 10.0 }
        val s = SightLine.of(null, t, lat, lon, toward(20.0, 90.0).first, toward(20.0, 90.0).second)
        assertEquals(SightKind.HERE, s.kind)
        assertNull(s.text(ContourUnits.METERS))
    }
}
