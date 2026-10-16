package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T111: the browser's Trips box (tools/demo/trips.js) follows the shared routing code where it repeats it: its mode
 * select offers every [TravelMode] (T108's crow included) and so does `demo-check.py --route-mode`, its line under
 * the select for crow is [TravelMode.CROW_NOTICE] word for word, a crow route's bearing uses [Directions.compass]'s
 * words, and its directions list counts the priorities up to [Directions.DEFAULT_LEVEL], as the phone's route card does.
 */
class BrowserTripsTextTest {
    private val trips = File("../tools/demo/trips.js").readText() // tests run in devtool/
    private val check = File("../tools/demo-check.py").readText()

    @Test
    fun theTripsBoxOffersEveryModeWithThePhonesCrowLine() {
        val options = Regex("""<option value="(\w+)">""").findAll(Regex("""<select id="trip-mode">(.*?)</select>""").find(trips)!!.groupValues[1])
            .map { it.groupValues[1] }.toList()
        assertEquals(TravelMode.entries.map { it.label }, options)
        val choices = Regex("""--route-mode", default="", choices=\[(.*?)]""").find(check)!!.groupValues[1]
        assertEquals(listOf("") + TravelMode.entries.map { it.label }, Regex(""""(\w*)"""").findAll(choices).map { it.groupValues[1] }.toList())
        val crow = Regex("""const CROW_NOTICE = "((?:[^"\\]|\\.)*)";""").find(trips)!!.groupValues[1]
        assertEquals(TravelMode.CROW_NOTICE, crow)
    }

    @Test
    fun theCrowBearingsCompassWordsAreThePhones() {
        // trips.js: COMPASS[Math.floor((((deg % 360) + 360) % 360 + 22.5) / 45) % 8], the crow card's "Bearing 18° (north)".
        val words = Regex(""""(\w+)"""").findAll(Regex("""const COMPASS = \[(.*?)];""").find(trips)!!.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(8, words.size)
        for (d in -720..720) {
            val js = words[Math.floorDiv(((d % 360 + 360) % 360 * 10 + 225), 450) % 8]
            assertEquals(Directions.compass(d.toDouble()), js, "bearing $d")
        }
    }

    @Test
    fun theDirectionsListUsesThePhonesDefaultLevel() {
        val rule = Regex("""\(i\.priority == null \? (\d) : i\.priority\) <= (\d)""").find(trips) ?: error("no priority filter in trips.js")
        assertEquals(Directions.DEFAULT_LEVEL, rule.groupValues[2].toInt())
        assertTrue(rule.groupValues[1].toInt() <= Directions.DEFAULT_LEVEL, "an instruction without a priority is shown")
    }
}
