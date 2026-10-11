package io.github.jasondorjeshort.dystopo.server

import kotlin.test.Test
import kotlin.test.assertEquals

/** The live box's source labels for the Canadian and New Zealand readers (the readers' own tests moved to shared, T80). */
class LiveLabelsTest {
    @Test
    fun canadaAndNewZealandHaveLiveLabels() {
        assertEquals("live-hrdem", LiveTiles.labelFor(listOf("hrdem", "cgg2013a", "egm2008")))
        assertEquals("live-linz", LiveTiles.labelFor(listOf("linz-dem", "nzgeoid2016", "egm2008")))
    }
}
