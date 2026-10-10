package io.github.jasondorjeshort.dystopo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkTest {
    @Test
    fun fullLink() {
        assertEquals(DeepLink.Target(46.853, -121.76, 13.5, 90.0), DeepLink.target(46.853, -121.76, 13.5, 90.0))
    }

    @Test
    fun defaultsZoomAndBearing() {
        assertEquals(DeepLink.Target(46.853, -121.76, DeepLink.DEFAULT_ZOOM, 0.0), DeepLink.target(46.853, -121.76, null, null))
    }

    @Test
    fun bearingWraps() {
        assertEquals(350.0, DeepLink.target(0.0, 0.0, 5.0, -10.0)!!.bearing)
    }

    @Test
    fun rejectsMissingOrOutOfRange() {
        assertNull(DeepLink.target(null, -121.76, 12.0, null))
        assertNull(DeepLink.target(46.8, null, 12.0, null))
        assertNull(DeepLink.target(89.0, 0.0, 12.0, null))
        assertNull(DeepLink.target(0.0, 181.0, 12.0, null))
        assertNull(DeepLink.target(0.0, 0.0, 23.0, null))
        assertNull(DeepLink.target(Double.NaN, 0.0, 12.0, null))
        assertNull(DeepLink.target(0.0, 0.0, Double.POSITIVE_INFINITY, null))
    }
}
