package io.github.jasondorjeshort.dystopo.app.firstrun

import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.OnlineUi
import io.github.jasondorjeshort.dystopo.app.online.OnlineView
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T220 (the survey `first-run-and-share.md`, the critic's objection 16): the first-run card's choices, each with one
 * sentence and one of them primary; the four states' one sentence; the region button's rule while the card is up.
 */
class FirstRunChoicesTest {
    private val mb = 40L * 1024 * 1024

    private fun ids(server: String, direct: Boolean) = firstRunChoices(server, direct, null, ContourUnits.METERS).map { it.id }

    @Test
    fun theChoicesFollowTheState() {
        assertEquals(listOf("download", "look", "import"), ids("", true))
        assertEquals(listOf("download", "look", "server", "import"), ids("http://10.0.0.2:9021", false))
        assertEquals(listOf("download", "import"), ids("", false))
    }

    @Test
    fun exactlyOneIsPrimaryAndEveryOneHasASentence() {
        for ((server, direct) in listOf("" to true, "http://10.0.0.2:9021" to false, "" to false)) {
            val choices = firstRunChoices(server, direct, mb, ContourUnits.METERS)
            assertEquals(listOf("download"), choices.filter { it.weight == ChoiceWeight.FILLED }.map { it.id })
            choices.forEach { c ->
                assertTrue(c.sentence.endsWith("."), c.label)
                assertEquals(c.weight == ChoiceWeight.FILLED, c.toMap()["primary"])
            }
        }
        val weights = firstRunChoices("http://10.0.0.2:9021", false, null, ContourUnits.METERS).associate { it.id to it.weight }
        assertEquals(ChoiceWeight.OUTLINED, weights["look"])
        assertEquals(ChoiceWeight.TEXT, weights["server"])
        assertEquals(ChoiceWeight.TEXT, weights["import"])
    }

    @Test
    fun theDownloadSaysItsSizeOnlyWhenKnownAndNeverANetworkType() {
        val download = { size: Long?, units: ContourUnits -> firstRunChoices("", true, size, units).first().sentence }
        assertEquals("Saves about 16 km around you on this phone so it works without signal.", download(null, ContourUnits.METERS))
        assertEquals("Saves about 10 mi around you on this phone so it works without signal.", download(null, ContourUnits.FEET))
        assertTrue(download(mb, ContourUnits.METERS).endsWith("without signal. Uses about 40 MB of data."), download(mb, ContourUnits.METERS))
        firstRunChoices("", true, mb, ContourUnits.METERS).forEach { c ->
            assertFalse("mobile" in c.sentence.lowercase() || "wi-fi" in c.sentence.lowercase(), c.sentence) // ADR 0023
        }
    }

    @Test
    fun theFourStatesAreOneSentenceEachAndNameNoProvider() {
        val notice = "Online data limit reached for this session"
        assertEquals("Server: http://10.0.0.2:9021", firstRunBody("http://10.0.0.2:9021", false, null))
        assertEquals(notice, firstRunBody("", true, notice)) // ADR 0023's notice keeps its place
        val direct = firstRunBody("", true, null)
        val none = firstRunBody("", false, null)
        for (body in listOf(direct, none)) {
            assertEquals(1, body.count { it == '.' }, body)
            for (provider in listOf("OpenStreetMap", "OpenFreeMap", "Copernicus", "USGS")) assertFalse(provider in body, body)
        }
        assertTrue("Settings › Sources" in direct)
        assertTrue(none.startsWith("No server set")) // tools/android-firstrun.py keys on it
        assertEquals("You can download later from Packs.", NOT_NOW_LINE)
    }

    @Test
    fun theRegionButtonIsNotOfferedWhileTheCardIsUp() {
        val view = OnlineView(46.85, -121.76, 12.0, io.github.jasondorjeshort.dystopo.packs.PackBounds(-121.9, 46.7, -121.5, 47.0), OnlineService.CACHED)
        assertTrue(OnlineUi("online", view, mapOf("state" to "idle"), false).offerDownload)
        assertFalse(OnlineUi("online", view, mapOf("state" to "idle"), false, firstRunCard = true).offerDownload)
    }
}
