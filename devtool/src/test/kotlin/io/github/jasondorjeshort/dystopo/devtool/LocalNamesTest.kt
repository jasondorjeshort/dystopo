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
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
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
    fun arabicIsJoinedBeforeItIsDrawnOneCharacterAtATime() {
        // T107: a curved label draws each character alone, so the joined forms are written out first.
        // "nahr" (river, noon heh reh): noon initial, heh medial, reh final; in visual order the final reh comes first.
        val nahr = "نهر"
        assertEquals("ﻧﻬﺮ", LabelText.shapeArabic(nahr))
        assertEquals("ﺮﻬﻧ", LabelText.visualOrder(nahr))
        // "wadi" (waw alef dal yeh): waw, alef and dal join nothing after them, so every letter stands alone.
        assertEquals("ﻭﺍﺩﻱ", LabelText.shapeArabic("وادي"))
        // "jabal" (mountain, jeem beh lam) twice: jeem initial, beh medial, lam final; the space ends the word.
        assertEquals("ﺟﺒﻞ ﺟﺒﻞ", LabelText.shapeArabic("جبل جبل"))
        // Lam then alef is one ligature: isolated at the start of a word, final after a joining letter ("salam": seen
        // initial, lam-alef final, meem isolated since alef does not join forward).
        assertEquals("ﻻ", LabelText.shapeArabic("لا"))
        assertEquals("ﺳﻼﻡ", LabelText.shapeArabic("سلام"))
        // A mark (fatha) on a letter does not break the join: beh, fatha, reh is beh initial + fatha + reh final.
        assertEquals("ﺑَﺮ", LabelText.shapeArabic("بَر"))
        // Text without Arabic letters comes back unchanged; Persian letters have forms too (gaf initial before lam).
        assertEquals("Wadi Rum 2", LabelText.shapeArabic("Wadi Rum 2"))
        assertEquals("נחל קדם", LabelText.shapeArabic("נחל קדם"))
        assertEquals('ﮔ', LabelText.shapeArabic("گل")[0])
        // Every form drawn is in a bundled face (the fallback a curved label picks per character).
        val font = AwtTextMeasurer.font(14.0)
        val phrase = "سلام عليكم $nahr الأردن"
        for (c in LabelText.visualOrder(phrase)) if (c != ' ') assertTrue(LabelText.fontFor(c.toString(), font).canDisplay(c), "U+%04X".format(c.code))
    }

    @Test
    fun aCurvedArabicRiverNameIsDrawnJoined() {
        // T107: a river with only an Arabic name ("nahr al-wadi", river of the valley; synthetic) over the golden
        // fixture's window, rendered as `render --osm` would: its curved label's glyphs are the joined forms, in
        // visual order. The image goes to build/ to look at (not a golden).
        val name = "نهر الوادي"
        val fixture = OsmFixture { c ->
            c.line("waterway", listOf(10.0 to 200.0, 70.0 to 170.0, 130.0 to 160.0, 190.0 to 130.0, 250.0 to 70.0), "class" to "river", "name" to name)
        }
        val r = Goldens.render(GoldenCase(SyntheticTerrain.CONE, zoom = 15, sizePx = 256, centerX = 2500.0, osm = fixture))
        javax.imageio.ImageIO.write(r.image, "png", java.io.File("build/arabic-curved-label.png"))
        val curved = r.osmLabels.filter { it.glyphs != null }
        assertEquals(1, curved.size, "one curved label: ${r.osmLabels.map { it.lines }}")
        val drawn = curved.single().glyphs!!.joinToString("") { it.text }
        assertEquals(LabelText.visualOrder(name), drawn)
        assertEquals("ﻱﺩﺍﻮﻟﺍ ﺮﻬﻧ", drawn)
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
