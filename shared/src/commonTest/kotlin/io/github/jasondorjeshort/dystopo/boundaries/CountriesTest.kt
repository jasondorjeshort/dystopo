package io.github.jasondorjeshort.dystopo.boundaries

import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CountriesTest {
    private val all = Countries.all

    private fun signedArea(r: List<AreaPoint>): Double {
        var s = 0.0
        for (i in 1 until r.size) s += r[i - 1].lon * r[i].lat - r[i].lon * r[i - 1].lat
        return s / 2
    }

    private fun inside(p: AreaPoint, ring: List<AreaPoint>): Boolean {
        var inside = false
        for (i in 1 until ring.size) {
            val a = ring[i - 1]
            val b = ring[i]
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < a.lon + (p.lat - a.lat) / (b.lat - a.lat) * (b.lon - a.lon)) inside = !inside
        }
        return inside
    }

    @Test
    fun theSetIsWithinItsBandAndTheResourceUnder700Kb() {
        // 240 of Natural Earth's 242 rows at 1:50m (Ashmore and Cartier Is. under the floor, Siachen Glacier by code).
        assertTrue(all.size in 235..245, "${all.size} countries")
        assertEquals(all.size, all.map { it.code }.toSet().size, "codes are unique")
        val bytes = CountriesData.TEXT.encodeToByteArray().size
        assertTrue(bytes < 700_000, "resource $bytes bytes")
    }

    @Test
    fun everyRingIsClosedInRangeAndNeverCrossesTheAntimeridian() {
        for (c in all) {
            assertTrue(c.rings.isNotEmpty(), c.code)
            assertTrue(c.name.isNotBlank() && c.nameLong.isNotBlank() && c.continent.isNotBlank(), c.code)
            for (r in c.rings) {
                assertTrue(r.size >= 4 && r.first() == r.last(), "${c.code}: a ring is closed")
                for (p in r) assertTrue(abs(p.lat) <= 90.0 && abs(p.lon) <= 180.0, "${c.code}: $p in range")
                for (i in 1 until r.size) assertTrue(abs(r[i].lon - r[i - 1].lon) <= 180.0, "${c.code}: longitudes ${r[i - 1].lon} then ${r[i].lon}")
            }
            assertTrue(c.vertexCount <= 2010, "${c.code}: ${c.vertexCount} vertices over the writer budget")
            assertTrue(c.toleranceM >= 0.0, c.code)
            if (c.vertexCount < 1900) assertEquals(0.0, c.toleranceM, "${c.code}: not simplified, no tolerance")
        }
    }

    @Test
    fun outerRingsAreCounterClockwiseAndHolesClockwiseInsideAnOuterRing() {
        var holes = 0
        for (c in all) {
            val outer = c.rings.filter { signedArea(it) > 0 }
            assertTrue(outer.isNotEmpty(), "${c.code} has an outer ring")
            for (r in c.rings) {
                val a = signedArea(r)
                assertTrue(a != 0.0, "${c.code}: a ring has area")
                if (a < 0) {
                    holes++
                    // A hole's vertices lie inside (or on) one of the country's outer rings.
                    val probe = AreaPoint(r.sumOf { it.lat } / (r.size), r.sumOf { it.lon } / (r.size))
                    assertTrue(outer.any { inside(probe, it) }, "${c.code}: a clockwise ring is a hole inside an outer ring")
                }
            }
        }
        assertTrue(holes > 0, "the set has holes (Lesotho in South Africa, San Marino and the Vatican in Italy)")
    }

    @Test
    fun boxesAndTheAntimeridian() {
        val fiji = assertNotNull(Countries.byCode("FJI"))
        assertTrue(fiji.crossesAntimeridian && fiji.west > fiji.east, "Fiji's box runs across 180")
        assertTrue(fiji.rings.count { signedArea(it) > 0 } >= 2)
        assertTrue(fiji.rings.any { r -> r.all { it.lon > 0 } } && fiji.rings.any { r -> r.all { it.lon < 0 } }, "Fiji has parts either side")
        val che = assertNotNull(Countries.byCode("CHE"))
        assertTrue(!che.crossesAntimeridian && che.west in 5.0..6.5 && che.east in 10.0..11.0 && che.south in 45.5..46.5 && che.north in 47.5..48.0)
        for (c in all) for (r in c.rings) for (p in r) {
            assertTrue(p.lat >= c.south - 1e-9 && p.lat <= c.north + 1e-9, c.code)
            if (!c.crossesAntimeridian) assertTrue(p.lon >= c.west - 1e-9 && p.lon <= c.east + 1e-9, c.code)
        }
    }

    @Test
    fun findAndByCode() {
        assertEquals("CHE", Countries.find("swit").first().code)
        assertEquals("CIV", Countries.find("Côte").first().code)
        assertEquals("CHE", Countries.find("CHE").first().code)
        assertEquals("CIV", Countries.find("cote d’ivoire").first().code)
        assertEquals("CIV", Countries.find("CÔTE").first().code)
        assertTrue(Countries.find("land").any { it.code == "CHE" }, "a substring finds Switzerland")
        assertEquals(emptyList(), Countries.find("  "))
        assertEquals(emptyList(), Countries.find("no such place"))
        assertEquals("Switzerland", Countries.byCode("che")?.name)
        assertEquals("SAH", Countries.byCode("ESH")?.code, "ISO_A3_EH looks up a row keyed by another ADM0_A3")
        assertNull(Countries.byCode("XXX"))
        assertNotNull(Countries.byCode("ATA"), "Antarctica is in the set")
        assertNull(Countries.byCode("KAS"), "Siachen Glacier is left out")
        assertNull(Countries.byCode("ATC"), "Ashmore and Cartier Is. is under the floor")
        for (code in listOf("KOS", "TWN", "SAH", "PSX", "ISR", "GRL", "PRI", "HKG", "KAZ", "CUB")) assertNotNull(Countries.byCode(code), code)
    }

    @Test
    fun theTextFormParses() {
        val text = "# comment\nAAA\t\tA land\tThe A land\tEurope\t1,2,3,4\t12\t" + RingCodec.encode(
            listOf(AreaPoint(2.0, 1.0), AreaPoint(2.0, 3.0), AreaPoint(4.0, 3.0), AreaPoint(2.0, 1.0)),
        ) + "\n"
        val c = Countries.parse(text).single()
        assertEquals("AAA", c.code)
        assertNull(c.iso3)
        assertEquals(12.0, c.toleranceM)
        assertEquals(listOf(AreaPoint(2.0, 1.0), AreaPoint(2.0, 3.0), AreaPoint(4.0, 3.0), AreaPoint(2.0, 1.0)), c.rings.single())
        assertEquals(3, c.vertexCount)
        assertEquals("AAA", Countries.find("a land", listOf(c)).single().code)
    }
}

class RingCodecTest {
    @Test
    fun thePolylineReferenceExample() {
        // The encoding's published example: three points, 1e-5 degrees, latitude first.
        val pts = listOf(AreaPoint(38.5, -120.2), AreaPoint(40.7, -120.95), AreaPoint(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", RingCodec.encode(pts))
        assertEquals(pts + pts[0], RingCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
    }

    @Test
    fun roundTripsAtTheExtremes() {
        val ring = listOf(AreaPoint(-90.0, -180.0), AreaPoint(-90.0, 180.0), AreaPoint(85.05112, 179.99999), AreaPoint(0.00001, -0.00001), AreaPoint(-90.0, -180.0))
        val text = RingCodec.encode(ring)
        assertTrue(text.all { it.code in 63..126 }, text)
        assertEquals(ring, RingCodec.decode(text))
    }
}

class TextFoldTest {
    @Test
    fun foldsCaseAndAccents() {
        assertEquals("cote d'ivoire", TextFold.fold("Côte d’Ivoire"))
        assertEquals("sao tome and principe", TextFold.fold("São Tomé and Príncipe"))
        assertEquals("curacao", TextFold.fold("CURAÇAO"))
        assertEquals("aland", TextFold.fold("Åland"))
        assertEquals("strasse", TextFold.fold("Straße"))
    }
}
