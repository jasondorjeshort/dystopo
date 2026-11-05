package io.github.jasondorjeshort.dystopo.app.settings

import io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals

/** T223: the accuracy limit's choices as the row shows them; what is stored stays in metres. */
class AccuracyChoicesTest {
    private fun shown(units: ContourUnits) = RecordingProfiles.ACCURACY_CHOICES.map { RecordingProfiles.accuracyLabel(it, units) }

    @Test
    fun feetAreRoundNumbers() = assertEquals(listOf("Off", "65 ft", "165 ft", "330 ft", "650 ft"), shown(ContourUnits.FEET))

    @Test
    fun metresAreAsStored() = assertEquals(listOf("Off", "20 m", "50 m", "100 m", "200 m"), shown(ContourUnits.METERS))

    @Test
    fun theStoredValuesAreUnchanged() = assertEquals(listOf(null, 20.0, 50.0, 100.0, 200.0), RecordingProfiles.ACCURACY_CHOICES)

    @Test
    fun theRuleTurnsAt500Feet() {
        assertEquals("495 ft", RecordingProfiles.accuracyLabel(151.0, ContourUnits.FEET)) // 495.4
        assertEquals("500 ft", RecordingProfiles.accuracyLabel(152.5, ContourUnits.FEET)) // 500.3, the nearest 50
    }
}
