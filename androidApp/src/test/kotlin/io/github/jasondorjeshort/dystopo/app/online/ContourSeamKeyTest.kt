package io.github.jasondorjeshort.dystopo.app.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T88: the cached view-edge seam. A contour tile at the view's edge is made with its out-of-view halo tiles left
 * out; it must not be cached under the key a whole tile uses, or the half-cell gap stays after a pan makes the
 * neighbour ([OnlineTiles.contourKey]).
 */
class ContourSeamKeyTest {
    private val key = "sources/contours-v9-ft-512@best-v2/12/662/1442.pbf"

    @Test
    fun aWholeTileKeepsThePlainKey() {
        assertEquals(key, OnlineTiles.contourKey(key, emptyList()))
    }

    @Test
    fun aTileMadeWithoutHaloTilesNamesThem() {
        val partial = OnlineTiles.contourKey(key, listOf(Triple(12, 661, 1442)))
        assertNotEquals(key, partial)
        assertTrue(partial.endsWith(".pbf") && "12-661-1442" in partial, partial)
        // Once a missing tile is made, the tile is looked up with fewer missing tiles: a different key, so it is made again.
        val two = OnlineTiles.contourKey(key, listOf(Triple(12, 661, 1442), Triple(12, 662, 1441)))
        assertNotEquals(partial, two)
        assertNotEquals(key, two)
    }

    @Test
    fun theKeyDoesNotDependOnOrderOrRepeats() {
        val a = OnlineTiles.contourKey(key, listOf(Triple(12, 662, 1441), Triple(12, 661, 1442)))
        val b = OnlineTiles.contourKey(key, listOf(Triple(12, 661, 1442), Triple(12, 662, 1441), Triple(12, 661, 1442)))
        assertEquals(a, b)
    }
}
