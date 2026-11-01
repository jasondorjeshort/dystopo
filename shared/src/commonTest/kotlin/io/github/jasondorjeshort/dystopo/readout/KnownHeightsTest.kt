package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.dem.Peak
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T197: the candidates a pin can come from (peaks, saddles, passes near the fix) and the words around it. */
class KnownHeightsTest {
    // Near Paradise (a public place); 0.0009 degrees of latitude is about 100 m.
    private val lat = 46.7860
    private val lon = -121.7350

    @Test
    fun candidatesAreNamedTaggedPeaksSaddlesAndPassesWithinAHundredMetresAndNearTheDem() {
        val peaks = listOf(
            Peak("Near Summit", "peak", lat + 0.0004, lon, 1700.0), // 44 m
            Peak("Near Saddle", "saddle", lat - 0.0006, lon, 1640.0), // 67 m
            Peak("Too Far", "peak", lat + 0.0012, lon, 1720.0), // 133 m
            Peak("No Height", "pass", lat, lon + 0.0005, null),
            Peak("Feet As Metres", "peak", lat, lon - 0.0005, 5577.0), // the DEM says about 1700
            Peak("A Ridge", "ridge", lat, lon + 0.0002, 1690.0),
        )
        val dem = ElevationSampler { _, _ -> 1690.0 }
        val f = KnownHeights.near(peaks, lat, lon, dem)
        assertEquals(listOf("Near Summit", "Near Saddle"), f.candidates.map { it.name })
        assertEquals(3.0, f.candidates[0].sigmaM)
        assertEquals(5.0, f.candidates[1].sigmaM)
        assertEquals(mapOf("No Height" to "noEle", "Feet As Metres" to "offDem", "A Ridge" to "class"), f.rejected.associate { it.first.name to it.second })
        // With no DEM the tag is taken as it is.
        assertEquals(3, KnownHeights.near(peaks, lat, lon, null).candidates.size)
        // The press picks the nearest within its radius.
        assertEquals("Near Saddle", KnownHeights.pick(f.candidates, lat - 0.0006, lon, 100.0)?.name)
        assertNull(KnownHeights.pick(f.candidates, lat + 0.01, lon, 100.0))
    }

    @Test
    fun readTakesSaddlesAndPassesFromTheLayerAtZoomThirteen() {
        val z = 13
        val extent = 4096
        val tiles = HashMap<Triple<Int, Int, Int>, MutableList<Feature>>()
        fun point(plat: Double, plon: Double, vararg kv: Pair<String, Any>) {
            val fx = WebMercator.lonToX(plon, z)
            val fy = WebMercator.latToY(plat, z)
            val x = floor(fx).toInt()
            val y = floor(fy).toInt()
            val props = kv.associate { (k, v) -> k to if (v is String) Value.StringValue(v) else Value.IntValue((v as Int).toLong()) }
            tiles.getOrPut(Triple(z, x, y)) { ArrayList() } +=
                Feature(null, props, Geometry.Points(listOf(TilePoint(((fx - x) * extent).toInt(), ((fy - y) * extent).toInt()))))
        }
        point(lat + 0.0003, lon, "class" to "saddle", "name" to "Test Saddle", "ele" to 1650)
        point(lat - 0.0003, lon, "class" to "pass", "name" to "Test Pass", "ele" to 1660)
        point(lat, lon + 0.0003, "class" to "cliff", "name" to "Test Cliff", "ele" to 1670)
        val encoded = tiles.mapValues { (_, fs) -> VectorTile(listOf(Layer("mountain_peak", fs, extent))).encode() }
        val f = KnownHeights.read({ tz, tx, ty -> encoded[Triple(tz, tx, ty)] }, lat, lon, null)
        assertEquals(setOf("Test Saddle", "Test Pass"), f.candidates.map { it.name }.toSet())
        // The viewshed's list (the default classes) still has neither.
        assertEquals(0, io.github.jasondorjeshort.dystopo.dem.Viewshed.readPeaks({ tz, tx, ty -> encoded[Triple(tz, tx, ty)] }, lat, lon, 100.0, zoom = 13).size)
    }

    @Test
    fun wordsInTheUsersUnits() {
        val k = KnownHeight("Alta Vista", "peak", lat, lon, 1810.0, 3.0, 20.0, null)
        assertEquals("I am at Alta Vista, 5938 ft", KnownHeights.menuLabel(k, ContourUnits.FEET))
        assertEquals("I am at Alta Vista, 1810 m", KnownHeights.menuLabel(k, ContourUnits.METERS))
        assertEquals("Height set from Alta Vista", KnownHeights.confirmation("Alta Vista", 1810.0, ContourUnits.FEET))
        assertEquals("Height set to 5420 ft", KnownHeights.confirmation(null, 5420 / 3.28084, ContourUnits.FEET))
        assertEquals("Set at Alta Vista", KnownHeights.markLabel("Alta Vista"))
        assertEquals("Set by you", KnownHeights.markLabel(null))
        assertEquals("Set by you, 10:42, 1652 m", KnownHeights.markDetail(null, "10:42", 1652.0, ContourUnits.METERS))
    }

    /** The idea's figure: how long a pin holds against the station prior (Paradise's pressure, about 826 hPa). */
    @Test
    fun howLongAPinHoldsAgainstTheStationPrior() {
        assertEquals(64.8, KnownHeights.markHoldS(3.0, 3.0) / 60, 0.05)
        assertEquals(180.0, KnownHeights.markHoldS(5.0, 5.0) / 60, 0.1)
        val near = KnownHeights.holdAgainstStationS(3.0, 0.0, 826.0)!! / 60.0
        val far = KnownHeights.holdAgainstStationS(3.0, 50_000.0, 826.0)!! / 60.0
        val sign = KnownHeights.holdAgainstStationS(5.0, 0.0, 826.0)
        val signFar = KnownHeights.holdAgainstStationS(5.0, 50_000.0, 826.0)!! / 60.0
        println("T197 against the station prior (${KnownHeights.stationSigmaM(0.0, 826.0)} m fresh at the station): summit $near min at 0 km, $far min at 50 km; sign ${sign} s at 0 km, $signFar min at 50 km")
        assertTrue(near in 30.0..45.0, "summit at 0 km $near min")
        assertTrue(far > near)
        assertEquals(0L, sign, "a sign's 5 m is no surer than a fresh station at 0 km")
        assertTrue(signFar > 30)
    }

    @Test
    fun typedHeights() {
        assertEquals(1652.0, KnownHeights.parseTyped("1652", ContourUnits.METERS))
        assertEquals(1652.0, KnownHeights.parseTyped("1,652 m", ContourUnits.FEET))
        assertEquals(5420 * 0.3048, KnownHeights.parseTyped("5,420", ContourUnits.FEET)!!, 1e-9)
        assertEquals(5420 * 0.3048, KnownHeights.parseTyped("5420 ft", ContourUnits.METERS)!!, 1e-9)
        assertEquals(5420 * 0.3048, KnownHeights.parseTyped("5420'", ContourUnits.METERS)!!, 1e-9)
        assertNull(KnownHeights.parseTyped("", ContourUnits.METERS))
        assertNull(KnownHeights.parseTyped("abc", ContourUnits.METERS))
        assertNull(KnownHeights.parseTyped("12000", ContourUnits.METERS)) // out of range
        assertEquals(-50.0, KnownHeights.parseTyped("-50", ContourUnits.METERS))
    }
}
