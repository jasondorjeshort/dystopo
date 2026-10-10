package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LidarSkipTest {
    private val rainier = BoundingBox(-121.86, 46.78, -121.66, 46.93)

    @Test
    fun lidarIsReadFromZoom13AtRainiersLatitude() {
        for (z in 8..12) assertFalse(LidarSource.useful(MercatorWindow.forBbox(rainier, z)), "z$z")
        for (z in 13..16) assertTrue(LidarSource.useful(MercatorWindow.forBbox(rainier, z)), "z$z")
    }

    @Test
    fun anExportsOverviewTargetDecides() {
        // A z12 window near the equator has ~38 m cells, but an export whose finest tile is 15 m
        // decides for every tile, so all tiles of the zoom agree.
        val window = MercatorWindow.forBbox(BoundingBox(10.0, 0.0, 10.1, 0.1), 12)
        assertFalse(LidarSource.useful(window))
        OverviewLevels.withTarget(15.0) { assertTrue(LidarSource.useful(window)) }
        OverviewLevels.withTarget(25.0) { assertFalse(LidarSource.useful(window)) }
    }
}
