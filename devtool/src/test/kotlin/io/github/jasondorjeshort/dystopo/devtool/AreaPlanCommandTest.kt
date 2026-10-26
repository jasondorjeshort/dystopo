package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.UsageError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** T140: `devtool area-plan`'s radius forms. */
class AreaPlanCommandTest {
    @Test
    fun radiusTakesMetresKilometresAndMiles() {
        assertEquals(5000.0, AreaPlanCommand.radiusM("5km"))
        assertEquals(5000.0, AreaPlanCommand.radiusM("5000"))
        assertEquals(800.0, AreaPlanCommand.radiusM("800m"))
        assertEquals(3 * 1609.344, AreaPlanCommand.radiusM("3mi"), 1e-9)
        assertFailsWith<UsageError> { AreaPlanCommand.radiusM("far") }
    }
}
