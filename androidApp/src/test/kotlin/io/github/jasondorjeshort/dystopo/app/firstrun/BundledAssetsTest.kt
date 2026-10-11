package io.github.jasondorjeshort.dystopo.app.firstrun

import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T80: the glyphs bundled in the APK (androidApp/build.gradle.kts, `bundleMapAssets`): the style's fonts, every
 * range MapLibre may ask for, and each file a non-empty glyph PBF that parses (an empty file is a failed range
 * to MapLibre, and the map then never reports fully loaded).
 */
class BundledAssetsTest {
    private val assets = File(System.getProperty("dystopo.mapAssets") ?: "build/generated/mapAssets")

    private fun list(name: String): List<String> {
        val gradle = listOf(File("build.gradle.kts"), File("androidApp/build.gradle.kts")).first { it.isFile }.readText()
        val body = Regex("""val $name = listOf\(([^)]*)\)""").find(gradle)?.groupValues?.get(1) ?: error("no $name in build.gradle.kts")
        return Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun bundledFontsAreTheStyles() {
        assertEquals(MapLibreStyle.FONTS.toList().sorted(), list("glyphFonts").sorted())
    }

    @Test
    fun everyRangeOfEveryFontIsAParseableGlyphPbf() {
        val expected = (0 until 65536 step 256).map { "$it-${it + 255}" }
        assertTrue(expected.containsAll(MapLibreStyle.GLYPH_RANGES))
        for (font in MapLibreStyle.FONTS) {
            for (range in expected) {
                val f = File(assets, "glyphs/$font/$range.pbf")
                assertTrue(f.isFile && f.length() > 0, "$f missing or empty")
                val stacks = EmptyGlyphs.stacks(f.readBytes())
                assertTrue(stacks.all { it.second == range }, "$f holds ${stacks.map { it.second }}")
            }
        }
    }

    @Test
    fun emptyRangesParse() {
        assertEquals(listOf("Noto Sans Regular" to "5120-5375"), EmptyGlyphs.stacks(EmptyGlyphs.pbf("Noto Sans Regular", "5120-5375")))
    }
}