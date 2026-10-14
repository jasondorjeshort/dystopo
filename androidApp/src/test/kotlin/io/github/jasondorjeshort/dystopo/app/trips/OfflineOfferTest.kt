package io.github.jasondorjeshort.dystopo.app.trips

import io.github.jasondorjeshort.dystopo.app.areas.aboutMb
import io.github.jasondorjeshort.dystopo.app.areas.budgetError
import io.github.jasondorjeshort.dystopo.app.areas.corridorMaxZoom
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T95 (the critic's serious objection to ADR 0027): the trip's Offline button shows the size first and refuses over the budget. */
class OfflineOfferTest {
    private val mb = 1024L * 1024L

    @Test
    fun theOfferNamesTheSizeAndTheTiles() {
        assertEquals("This route offline: about 29 MB (240 tiles).", offlineOfferText(30_060_000, 240, null))
        assertEquals("This route offline: about 4.2 MB (61 tiles).", offlineOfferText(4_400_000, 61, null))
    }

    @Test
    fun theOfferAndTheBudgetMessageSayTheSameSize() { // the PR #99 verifier saw "about 28 MB" then "about 29 MB"
        val bytes = (28.3 * mb).toLong()
        val text = offlineOfferText(bytes, 233, budgetError(pinnedBytes = 0, estimateBytes = bytes, alreadyBytes = 0, budgetMb = 16))
        assertTrue(text.startsWith("This route offline: about 28 MB (233 tiles). Offline areas would use about 28 MB, over their 16 MB budget"), text)
        assertEquals("1223 MB", aboutMb(1_282_440_000))
        assertEquals("9.5 MB", aboutMb((9.5 * mb).toLong()))
    }

    @Test
    fun overTheBudgetTheOfferSaysWhyAfterTheSize() {
        val refusal = assertNotNull(budgetError(pinnedBytes = 1900 * mb, estimateBytes = 400 * mb, alreadyBytes = 0, budgetMb = 2048))
        val text = offlineOfferText(400 * mb, 4_321, refusal)
        assertTrue(text.startsWith("This route offline: about 400 MB (4,321 tiles). Offline areas would use about 2300 MB"), text)
        assertTrue(text.endsWith("Settings › Storage."), text)
    }

    /**
     * The sizes the offer shows for a long drive (Rainier to Glacier/Waterton, about 630 km as a straight line, so a
     * lower bound; the critic's case was 570 km) at the drive's default zooms (T91: z8 to z13) and at the walk's (z8 to
     * z15), the buffer the trip card uses (500 m grown by its simplification tolerance) and the server's rule (DEM from
     * z6, vector to z14).
     */
    @Test
    fun aLongDriveAtTheDriveZoomsIsAFractionOfTheWalkZooms() {
        val line = listOf(AreaPoint(46.855, -121.76), AreaPoint(49.0, -113.925))
        fun bytes(maxZoom: Int): Long {
            val area = AreaOfInterest(AreaShape.Corridor(line, 550.0), 8, maxZoom)
            return AreaManifest.tileItems(area, 6..maxZoom, 8..minOf(maxZoom, AreaManifest.OSM_MAX_ZOOM)).sumOf { it.bytes }
        }
        val drive = bytes(corridorMaxZoom("drive"))
        val walk = bytes(corridorMaxZoom("hike"))
        assertTrue(drive in 150 * mb..400 * mb, "drive z8-13: ${drive / mb} MB")
        assertTrue(walk > 3 * drive, "walk zooms z8-15: ${walk / mb} MB against ${drive / mb} MB")
        // The default 2 GB budget takes the drive and refuses the same line at the walk's zooms when 1.5 GB is pinned.
        assertNull(budgetError(pinnedBytes = 0, estimateBytes = drive, alreadyBytes = 0, budgetMb = 2048))
        assertNotNull(budgetError(pinnedBytes = 1536 * mb, estimateBytes = walk, alreadyBytes = 0, budgetMb = 2048))
    }
}
