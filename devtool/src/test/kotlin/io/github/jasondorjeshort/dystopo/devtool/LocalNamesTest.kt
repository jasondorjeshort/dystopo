package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.server.AwtTextMeasurer
import io.github.jasondorjeshort.dystopo.server.LabelText
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.style.Placement
import io.github.jasondorjeshort.dystopo.style.SourceTile
import io.github.jasondorjeshort.dystopo.style.StyleLayer
import io.github.jasondorjeshort.dystopo.style.TextTemplate
import java.awt.font.FontRenderContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T92: the PNG renderer's second name line in another script, by the phone style's rule. */
class LocalNamesTest {
    private fun props(vararg kv: Pair<String, String>) = kv.associate { (k, v) -> k to (Value.StringValue(v) as Value) }

    @Test
    fun theSecondLineFollowsThePhoneRule() {
        // English first, the Hebrew name on the second line (an outdoors pack: name and name_en only).
        assertEquals("עין גדי", LocalNames.localLine(props("name" to "עין גדי", "name_en" to "Ein Gedi")))
        // OpenMapTiles: name:nonlatin wins over name.
        assertEquals("Ἄθως", LocalNames.localLine(props("name" to "Athos", "name:en" to "Mount Athos", "name:nonlatin" to "Ἄθως")))
        // No English or Latin name: the local name is already the first line.
        assertNull(LocalNames.localLine(props("name" to "הר ישי")))
        // Latin names (Vietnamese in Latin Extended Additional included) have no second line.
        assertNull(LocalNames.localLine(props("name" to "Chamonix-Mont-Blanc", "name:en" to "Chamonix")))
        assertNull(LocalNames.localLine(props("name" to "Ḥaḍramawt", "name:en" to "Hadhramaut")))
        // A script the bundled fonts cannot draw is left out (the phone's is-supported-script): no boxes.
        assertNull(LocalNames.localLine(props("name" to "सगरमाथा", "name:en" to "Everest")))
        assertNull(LocalNames.localLine(props("name" to "富士山", "name:en" to "Mount Fuji")))
        for (s in listOf("עין גדי", "البحر الميت", "ดอยอินทนนท์", "მყინვარწვერი", "Эльбрус", "Όλυμπος")) assertTrue(LabelText.supported(s), s)
    }

    @Test
    fun pointLabelsGetTheLineAndCurvedLabelsTheVisualOrder() {
        val style = LocalNames.style(DefaultOsmStyle.style)
        val symbols = style.layers.filterIsInstance<StyleLayer.Symbol>().filter { "{name}" in DefaultOsmStyle.style.layers.first { o -> o.id == it.id }.let { o -> (o as StyleLayer.Symbol).text } }
        assertTrue(symbols.isNotEmpty())
        for (s in symbols) when (s.placement) {
            Placement.POINT -> assertTrue("{name}\n{${LocalNames.KEY}}" in s.text, s.text)
            Placement.LINE -> assertEquals(false, "{name}" in s.text, s.text)
        }
        val peak = props("name" to "הר צרויה", "name_en" to "Har Tseruya", "ele" to "212")
        val feature = Feature(1, peak, Geometry.Points(listOf(TilePoint(10, 10))))
        val tile = VectorTile(listOf(Layer("mountain_peak", listOf(feature))))
        val (tiles, n) = LocalNames.withLocalNames(listOf(SourceTile(TileId(13, 4900, 3300), tile)))
        assertEquals(1, n)
        val f = tiles.single().tile.layers.single().features.single()
        val peakLayer = style.layers.filterIsInstance<StyleLayer.Symbol>().first { it.placement == Placement.POINT && "{elevation}" in it.text }
        assertEquals(listOf("Har Tseruya", "הר צרויה", "694 ft"), TextTemplate.render(peakLayer.text, f.properties + ("ele" to Value.DoubleValue(211.6)), "ft"))
        // Hebrew in visual order for one-character-at-a-time drawing: the logical string reversed.
        assertEquals("םדק לחנ", LabelText.visualOrder("נחל קדם"))
        assertEquals("Nahal Kedem", LabelText.visualOrder("Nahal Kedem"))
        assertEquals("ם 2 לחנ", LabelText.visualOrder("נחל 2 ם"))
    }

    @Test
    fun latinTextIsMeasuredExactlyAsBefore() {
        val frc = FontRenderContext(null, true, true)
        val font = AwtTextMeasurer.font(14.0)
        for (s in listOf("Ein Gedi", "Chamonix-Mont-Blanc", "Mount Rainier 14,411 ft")) {
            assertEquals(font.createGlyphVector(frc, s).visualBounds, LabelText.bounds(s, font, frc))
            assertSame(font, LabelText.fontFor(s.substring(0, 1), font))
        }
        // Hebrew is drawn (non-empty ink) by a fallback face, not the label font's missing-glyph boxes.
        val ink = LabelText.ink("עין גדי", font, frc)
        assertTrue(ink.bounds.width > 20 && ink.bounds.height > 5, "${ink.bounds}")
        assertFalse(font.canDisplay('ע'.code))
        assertTrue(LabelText.fontFor("ע", font).canDisplay('ע'.code))
    }
}
