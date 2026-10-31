package io.github.jasondorjeshort.dystopo.app.print

import io.github.jasondorjeshort.dystopo.render.BoxPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T183: the pure parts of the phone's sheet: a text box turned a quarter left, the row's scales. */
class PrintSheetTest {
    @Test
    fun textBoxPlacedUprightAndTurned() {
        // Local box: 0..40 wide, 10 above the baseline to 3 below it.
        assertEquals(BoxPx(100.0, 190.0, 40.0, 13.0), AndroidSheetCanvas.place(100.0, 200.0, 0.0, -10.0, 40.0, 3.0, rotate = false))
        // Turned a quarter left about (100, 200): it reads upward, x from 90 to 103, y from 160 to 200.
        assertEquals(BoxPx(90.0, 160.0, 13.0, 40.0), AndroidSheetCanvas.place(100.0, 200.0, 0.0, -10.0, 40.0, 3.0, rotate = true))
    }

    @Test
    fun rowOffersTheSurveyedScales() {
        assertEquals(listOf("1:10,000", "1:25,000", "1:50,000", "Fit the view"), PRINT_SCALES.map { it.first })
        assertEquals(25_000.0, PRINT_SCALES[1].second)
        assertNull(PRINT_SCALES.last().second)
    }
}
