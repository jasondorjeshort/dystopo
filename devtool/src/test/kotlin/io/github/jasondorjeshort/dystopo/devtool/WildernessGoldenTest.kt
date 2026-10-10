package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T58: the OSM golden (`lake-cliff-osm-z15`) carries a wilderness polygon ([OsmFixture.LAKE_CLIFF]: the
 * `park` feature of class `wilderness`, image x 214 and east), so the golden guards the wilderness fill
 * (T50) as well as its dotted boundary. Rendering the same case with the fill layer removed from the
 * style must change only the pixels inside that polygon, and there toward the fill's teal.
 */
class WildernessGoldenTest {
    @Test
    fun theOsmGoldenShowsTheWildernessFill() {
        val case = Goldens.cases.single { it.osm != null }
        assertEquals("lake-cliff-osm-z15", case.name)
        val fillLayer = Regex("""\{"id": "wilderness-fill".*?"opacity": [0-9.]+\},\s*""", RegexOption.DOT_MATCHES_ALL)
        assertTrue(fillLayer.containsMatchIn(DefaultOsmStyle.JSON), "the style has a wilderness-fill layer")
        val dir = Files.createTempDirectory("wilderness").toFile()
        try {
            val noFill = File(dir, "style.json").apply { writeText(fillLayer.replace(DefaultOsmStyle.JSON, "")) }
            val withFill = Goldens.render(case).image
            val window = case.osm!!.window(case)
            val style = styleOptions(*(case.args + listOf("--osm", case.osm.file(case).path, "--osm-style", noFill.path)).toTypedArray())
            val (without, _) = MapRenderer.render(case.grid(), case.zoom, style, Timings(), window = window)

            var changedOutside = 0
            var inside = 0
            var redDrop = 0.0
            for (y in 0 until withFill.height) for (x in 0 until withFill.width) {
                val a = withFill.getRGB(x, y)
                val b = without.getRGB(x, y)
                if (x < 213) {
                    if (a != b) changedOutside++
                } else if (x >= 216) {
                    inside++
                    // The fill (#1f7a6e at 7 %) darkens red most: paper and terrain are warm.
                    redDrop += ((b shr 16) and 0xff) - ((a shr 16) and 0xff)
                }
            }
            assertEquals(0, changedOutside, "pixels west of the wilderness edge changed")
            val meanRedDrop = redDrop / inside
            println("wilderness fill: $inside pixels east of the edge, mean red drop $meanRedDrop; $changedOutside changed west of it")
            assertTrue(meanRedDrop > 4.0, "wilderness fill too faint or missing: mean red drop $meanRedDrop")
        } finally {
            dir.deleteRecursively()
        }
    }
}
