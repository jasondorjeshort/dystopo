package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T384: the four coordinate formats at Paradise (Mount Rainier, public), the equator, near a pole and the antimeridian. */
class CoordinateFormatTest {
    private val lat = 46.786
    private val lon = -121.735

    @Test
    fun paradiseInEachFormat() {
        assertEquals("46.78600° N, 121.73500° W", CoordinateFormat.DECIMAL.format(lat, lon))
        assertEquals("46° 47.160' N, 121° 44.100' W", CoordinateFormat.DEGREES_MINUTES.format(lat, lon))
        assertEquals("46° 47' 09.6\" N, 121° 44' 06.0\" W", CoordinateFormat.DMS.format(lat, lon))
        val utm = CoordinateFormat.UTM.format(lat, lon)
        val m = Regex("""^(\d+)([C-X]) (\d+) (\d+)$""").matchEntire(utm) ?: error("not UTM: $utm")
        assertEquals("10", m.groupValues[1])
        assertEquals("T", m.groupValues[2])
        // The text reads back to Paradise within a metre (Utm's own inverse).
        val (la, lo) = Utm.inverse(m.groupValues[3].toDouble(), m.groupValues[4].toDouble(), 10, true)
        assertTrue(abs(la - lat) * 111_000 < 1.0 && abs(lo - lon) * 76_000 < 1.0, "$utm reads back as $la, $lo")
        // The default, and the keys a setting stores.
        assertEquals(CoordinateFormat.DECIMAL, CoordinateFormat.DEFAULT)
        for (f in CoordinateFormat.entries) assertEquals(f, CoordinateFormat.of(f.key))
        assertNull(CoordinateFormat.of("mgrs"))
    }

    @Test
    fun theEquatorAndTheHemispheres() {
        assertEquals("0.00000° N, 0.00000° E", CoordinateFormat.DECIMAL.format(0.0, 0.0))
        assertEquals("0° 00.000' N, 0° 00.000' E", CoordinateFormat.DEGREES_MINUTES.format(0.0, 0.0))
        assertEquals("0° 00' 00.0\" N, 0° 00' 00.0\" E", CoordinateFormat.DMS.format(0.0, 0.0))
        assertEquals("31N 166021 0", CoordinateFormat.UTM.format(0.0, 0.0))
        // A value that rounds to zero is not "S" or "W".
        assertEquals("0.00000° N, 0.00000° E", CoordinateFormat.DECIMAL.format(-0.000001, -0.000001))
        // The southern hemisphere: the band letter and the false northing.
        assertEquals("0.50000° S, 0.50000° W", CoordinateFormat.DECIMAL.format(-0.5, -0.5))
        val south = CoordinateFormat.UTM.format(-0.5, -0.5)
        assertTrue(south.startsWith("30M ") && south.split(" ")[2].toLong() in 9_900_000..9_999_999, south)
    }

    @Test
    fun nearAPole() {
        // Rounding carries upwards: 89.999999° is 90° 00' 00.0", never 89° 59' 60.0".
        assertEquals("90.00000° N, 45.00000° E", CoordinateFormat.DECIMAL.format(89.999999, 45.0))
        assertEquals("90° 00.000' N, 45° 00.000' E", CoordinateFormat.DEGREES_MINUTES.format(89.999999, 45.0))
        assertEquals("90° 00' 00.0\" N, 45° 00' 00.0\" E", CoordinateFormat.DMS.format(89.999999, 45.0))
        assertEquals("89° 59' 59.6\" S, 45° 00' 00.0\" E", CoordinateFormat.DMS.format(-89.9998889, 45.0))
        // UTM stops at 84° N and 80° S: the position reads in decimal degrees there.
        assertNull(CoordinateText.utm(84.5, 10.0))
        assertNull(CoordinateText.utm(-80.5, 10.0))
        assertEquals("85.00000° N, 10.00000° E", CoordinateFormat.UTM.format(85.0, 10.0))
        assertEquals('X', CoordinateText.band(84.0))
        assertEquals('C', CoordinateText.band(-80.0))
    }

    @Test
    fun theAntimeridian() {
        // 180° and -180° are one meridian: read as 180° W, zone 1.
        assertEquals("0.00000° N, 180.00000° W", CoordinateFormat.DECIMAL.format(0.0, 180.0))
        assertEquals("0.00000° N, 180.00000° W", CoordinateFormat.DECIMAL.format(0.0, -180.0))
        assertEquals("0.00000° N, 179.50000° E", CoordinateFormat.DECIMAL.format(0.0, 179.5))
        assertEquals("0.00000° N, 179.50000° W", CoordinateFormat.DECIMAL.format(0.0, 180.5))
        assertEquals("0° 00' 00.0\" N, 179° 59' 59.6\" E", CoordinateFormat.DMS.format(0.0, 179.9998889))
        assertTrue(CoordinateFormat.UTM.format(0.0, 180.0).startsWith("1N 166021 "), CoordinateFormat.UTM.format(0.0, 180.0))
        assertTrue(CoordinateFormat.UTM.format(0.0, 179.9).startsWith("60N "), CoordinateFormat.UTM.format(0.0, 179.9))
    }

    // T388
    /**
     * T388: [CoordinateFormat.line] breaks only between latitude and longitude (a UTM text before its northing), and reads
     * as [CoordinateFormat.format] with the spaces put back; at Paradise, in the southern hemisphere (Aconcagua, an
     * `areas.json` area) and beside the antimeridian (Taveuni, on both sides of it).
     */
    @Test
    fun aLineBreaksOnlyBetweenLatitudeAndLongitude() {
        val places = listOf(lat to lon, -32.653 to -70.011, -16.84 to 179.95, -16.84 to -179.95)
        for ((la, lo) in places) for (f in CoordinateFormat.entries) {
            val line = f.line(la, lo)
            assertEquals(f.format(la, lo), line.replace(CoordinateText.NBSP, ' '), "$f at $la, $lo")
            val breaks = line.count { it == ' ' }
            assertEquals(1, breaks, "one place to break: '$line'")
            if (f != CoordinateFormat.UTM) assertTrue(", " in line, line) else assertTrue(Regex("^\\d+[C-X]" + CoordinateText.NBSP + "\\d+ \\d+$").matches(line), line)
        }
        assertEquals("46°_47'_09.6\"_N, 121°_44'_06.0\"_W", CoordinateFormat.DMS.line(lat, lon).replace(CoordinateText.NBSP, '_'))
        // The southern hemisphere and the antimeridian read with their letters and zones.
        assertEquals("32.65300° S, 70.01100° W", CoordinateFormat.DECIMAL.format(-32.653, -70.011))
        assertTrue(CoordinateFormat.UTM.format(-32.653, -70.011).startsWith("19H "), CoordinateFormat.UTM.format(-32.653, -70.011))
        assertEquals("16° 50.400' S, 179° 57.000' E", CoordinateFormat.DEGREES_MINUTES.format(-16.84, 179.95))
        assertEquals("16° 50' 24.0\" S, 179° 57' 00.0\" W", CoordinateFormat.DMS.format(-16.84, -179.95))
        assertTrue(CoordinateFormat.UTM.format(-16.84, 179.95).startsWith("60K "), CoordinateFormat.UTM.format(-16.84, 179.95))
        assertTrue(CoordinateFormat.UTM.format(-16.84, -179.95).startsWith("1K "), CoordinateFormat.UTM.format(-16.84, -179.95))
        // Outside UTM the decimal fallback breaks at its comma too.
        assertEquals("85.00000°_N, 10.00000°_E", CoordinateFormat.UTM.line(85.0, 10.0).replace(CoordinateText.NBSP, '_'))
        assertEquals("10T_597271 5181898".substring(0, 4), CoordinateFormat.UTM.line(lat, lon).replace(CoordinateText.NBSP, '_').substring(0, 4))
    }
    // end T388
}
