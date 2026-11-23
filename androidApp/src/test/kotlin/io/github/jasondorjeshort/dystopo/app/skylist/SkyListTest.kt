package io.github.jasondorjeshort.dystopo.app.skylist

import io.github.jasondorjeshort.dystopo.app.about.CreditLine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T344: the List view holds every section and line of the Panorama's pane as it stood before (nothing lost), in the pane's
 * order, and the Panorama no longer hosts them. Read from the sources (comments skipped), as `HelpRegistryTest` reads them.
 */
class SkyListTest {
    private val src = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app")

    private fun code(f: File): String =
        f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")

    private val list by lazy { code(File(src, "skylist/SkyListScreen.kt")).substringAfter("fun SkyListScreen()") }
    private val panorama by lazy { code(File(src, "panorama/PanoramaScreen.kt")).substringAfter("fun PanoramaScreen()").substringBefore("private fun FollowEffects") }

    /** The pane's parts as the Panorama hosted them before T344 (T326, T327, T340), in its order. */
    private val pane = listOf(
        "PlaceRow(", "SkyViewHeading(", "PickedPeak(", "LayerToggles(", "TerrainLine(", "FlightsSection(", "PeaksSection(", "SunSection(",
        "SatellitesSection(", "HorizonText.NO_LAYERS", "FlightCardHost(",
    )

    @Test
    fun everyPartOfThePaneIsInTheListInItsOrder() {
        var at = -1
        for (p in pane) {
            val i = list.indexOf(p)
            assertTrue(i >= 0, "the List has no $p")
            assertTrue(i > at, "$p out of the pane's order in the List")
            at = i
        }
        // The parts SkyList.PARTS names (GET /skylist and the docs read it) are those, in the same order.
        assertEquals(pane.dropLast(1).map { it.removeSuffix("(").removePrefix("HorizonText.") }, SkyList.PARTS)
        // Each section's help moved with it; the sun's line, a peak's row and a satellite's row show the Panorama.
        for (k in listOf("\"panorama.flights\"", "\"panorama.peaks\"", "\"panorama.sun\"")) assertTrue(k in list, k)
        assertTrue(Regex("""onRow\s*=\s*\{\s*key,\s*p\s*->\s*SkyList\.show""").containsMatchIn(list), "a peak's row shows the Panorama")
        assertTrue(Regex("""onNow\s*=\s*\{\s*SkyList\.show""").containsMatchIn(list), "the sun's line shows the Panorama")
        assertTrue(Regex("""onRow\s*=\s*\{\s*s\s*->\s*SkyList\.show""").containsMatchIn(list), "a satellite's row shows the Panorama")
    }

    @Test
    fun thePanoramaKeepsItsPlaceLineHeadingAndPickAndLosesTheList() {
        for (p in listOf("PlaceRow(", "SkyViewHeading(", "PickedPeak(", "FlightCardHost(", "ListButton(")) assertTrue(p in panorama, "the Panorama lost $p")
        for (p in listOf("LayerToggles(", "TerrainLine(", "FlightsSection(", "PeaksSection(", "SunSection(", "SatellitesSection(", "verticalScroll(")) {
            assertFalse(p in panorama, "the Panorama still hosts $p")
        }
    }

    @Test
    fun theCreditPutsTheFlightsNoticeFirstOnlyWhenTheCapCuts() {
        val notice = "Flights: ADSB.lol contributors (ODbL)"
        val line = "© OpenStreetMap contributors | USGS 3DEP | $notice"
        assertEquals(line, CreditLine.noticeFirst(line, notice, overflowed = false))
        assertEquals("$notice | © OpenStreetMap contributors | USGS 3DEP", CreditLine.noticeFirst(line, notice, overflowed = true))
        assertEquals(notice, CreditLine.noticeFirst(notice, notice, overflowed = true))
        assertEquals("© OpenStreetMap contributors", CreditLine.noticeFirst("© OpenStreetMap contributors", null, overflowed = true))
        assertEquals("© OpenStreetMap contributors", CreditLine.noticeFirst("© OpenStreetMap contributors", notice, overflowed = true))
    }
}
