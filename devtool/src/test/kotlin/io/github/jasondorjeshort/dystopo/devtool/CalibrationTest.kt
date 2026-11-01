package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.UsageError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** T197: `devtool readout --calibrate <ele>@<time>`'s forms. */
class CalibrationTest {
    private val first = 1_755_189_000_000L // 2025-08-14T16:30:00Z

    @Test
    fun formsOfTheHeightAndTheTime() {
        val a = Calibration.parse("2147@2025-08-14T18:17:26Z", "High point", null, first)
        assertEquals(2147.0, a.eleM)
        assertEquals(1_755_195_446_000L, a.tMs)
        assertEquals("peak", a.kind)
        assertEquals(3.0, a.sigmaM)
        val b = Calibration.parse("5433ft@+90min", null, null, first)
        assertEquals(5433 * 0.3048, b.eleM, 1e-9)
        assertEquals(first + 90 * 60_000L, b.tMs)
        assertNull(b.name)
        assertEquals("sign", b.kind)
        assertEquals(5.0, b.sigmaM)
        assertEquals(first + 30_000L, Calibration.parse("1656@+30", null, "saddle", first).tMs)
        assertEquals(5.0, Calibration.parse("1656@+30s", "A Saddle", "saddle", first).sigmaM)
        assertEquals(first, Calibration.parse("1656@$first", null, null, first).tMs)
    }

    @Test
    fun refusals() {
        assertFailsWith<UsageError> { Calibration.parse("2147", null, null, first) }
        assertFailsWith<UsageError> { Calibration.parse("abc@+0", null, null, first) }
        assertFailsWith<UsageError> { Calibration.parse("20000@+0", null, null, first) }
        assertFailsWith<UsageError> { Calibration.parse("2147@soon", null, null, first) }
    }
}
