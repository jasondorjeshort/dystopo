package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.render.SlopeShading
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T107: the browser demo's slope shadings (`SLOPE_BANDS`, `SLOPE_TEXT` and `SLOPE_SAFETY` in tools/demo/index.html)
 * must match T104's: every band's label and colour in [SlopeShading.bands], and the legend's words as the phone's Map
 * options show them (`options/MapOptionsSection.kt`, read as text: the app module is not on this classpath).
 */
class SlopeLegendTest {
    private val html = File("../tools/demo/index.html").readText() // tests run in devtool/
    private val phone = File("../androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/options/MapOptionsSection.kt").readText()

    /** A JS string literal's text: `°` escapes decoded. */
    private fun js(s: String) = Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    /** The quoted pieces of a `"..." + "..."` expression, joined. */
    private fun joined(expr: String) = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(expr).joinToString("") { js(it.groupValues[1]) }

    @Test
    fun theDemoPagesSlopeBandsAndLegendAreThePhones() {
        val block = Regex("""const SLOPE_BANDS = \{(.*?)\n\};""", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        for (shading in SlopeShading.entries.filter { it != SlopeShading.OFF }) {
            val line = Regex("""\b${shading.key}: \[(.*)\],""").find(block)?.groupValues?.get(1) ?: error("no ${shading.key} in SLOPE_BANDS")
            val demo = Regex("""\["([^"]+)", "#([0-9a-f]{6})"\]""").findAll(line).map { it.groupValues[1] to it.groupValues[2].toInt(16) }.toList()
            assertEquals(shading.bands.map { it.label to it.rgb }, demo, "${shading.key} bands")
        }
        val texts = Regex("""const SLOPE_TEXT = \{(.*?)\n\};""", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        for (key in listOf("avalanche", "steepness")) {
            val expr = Regex("""\b$key: ((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+),""").find(texts)?.groupValues?.get(1) ?: error("no $key in SLOPE_TEXT")
            val text = joined(expr)
            assertTrue(text.length > 20, text)
            // The phone's words, also as concatenated literals.
            val upper = key.uppercase()
            val phoneExpr = Regex("""SlopeShading\.$upper -> ((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)""").find(phone)?.groupValues?.get(1) ?: error("no $upper legend in the phone's options")
            assertEquals(joined(phoneExpr), text, "$key legend")
        }
        val safety = Regex("""const SLOPE_SAFETY = "([^"]+)";""").find(html)!!.groupValues[1]
        val phoneSafety = Regex("""SAFETY_LINE = "([^"]+)"""").find(phone)!!.groupValues[1]
        assertEquals(phoneSafety, safety)
    }
}
