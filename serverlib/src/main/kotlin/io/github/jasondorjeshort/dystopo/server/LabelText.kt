package io.github.jasondorjeshort.dystopo.server

import java.awt.Font
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.Rectangle2D
import java.text.AttributedString

/**
 * T92: label text in the rendered PNGs, including the local name in another script on a second line (as the
 * phone style draws it, `MapLibreStyle.LABEL_NAME`). Text the label font draws whole goes through
 * [Font.createGlyphVector] exactly as before (the goldens are Latin and stay byte-identical); text with
 * characters it lacks is laid out by [TextLayout] with each run in the first bundled script font that has it
 * ([RenderFonts.scriptFallbacks]: Hebrew, Arabic, Thai, Georgian; Greek and Cyrillic are in Noto Sans), which
 * shapes Arabic and Thai and orders right-to-left runs. [supported] says whether a text can be drawn at all
 * (the second line is left out otherwise, like the phone's `is-supported-script`: no boxes for CJK or
 * Devanagari).
 */
object LabelText {
    /** The outline of [text] in [font] with its origin at the left end of the baseline, and its visual bounds. */
    class Ink(val outline: Shape, val bounds: Rectangle2D)

    fun ink(text: String, font: Font, frc: FontRenderContext): Ink {
        if (font.canDisplayUpTo(text) == -1) {
            val gv = font.createGlyphVector(frc, text)
            return Ink(gv.outline, gv.visualBounds)
        }
        val layout = TextLayout(attributed(text, font).iterator, frc)
        return Ink(layout.getOutline(null), layout.bounds)
    }

    fun bounds(text: String, font: Font, frc: FontRenderContext): Rectangle2D =
        if (font.canDisplayUpTo(text) == -1) font.createGlyphVector(frc, text).visualBounds else TextLayout(attributed(text, font).iterator, frc).bounds

    /**
     * The font that draws [glyph] (one character of a curved label): [font] when it has it, else the first script
     * fallback that has it, at [font]'s size and transform.
     */
    fun fontFor(glyph: String, font: Font): Font {
        if (glyph.isEmpty() || font.canDisplayUpTo(glyph) == -1) return font
        val fb = RenderFonts.scriptFallbacks.firstOrNull { it.canDisplayUpTo(glyph) == -1 } ?: return font
        return fb.deriveFont(font.transform).deriveFont(font.size2D)
    }

    /**
     * [text] in visual order (left to right on the page): right-to-left runs reversed and the runs reordered by
     * the Unicode bidi algorithm, for curved labels, which draw one character at a time from the left. Hebrew
     * reads right; Arabic letters keep their isolated forms (no joining one character at a time).
     */
    fun visualOrder(text: String): String {
        val bidi = java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        if (bidi.isLeftToRight) return text
        val n = bidi.runCount
        val levels = ByteArray(n) { bidi.getRunLevel(it).toByte() }
        val runs = Array<Any>(n) { i ->
            val run = text.substring(bidi.getRunStart(i), bidi.getRunLimit(i))
            if (levels[i].toInt() and 1 == 1) StringBuilder(run).reverse().toString() else run
        }
        java.text.Bidi.reorderVisually(levels, 0, runs, 0, n)
        return runs.joinToString("")
    }

    /** Every character of [text] is in the label font or one of the script fallbacks. */
    fun supported(text: String, font: Font = RenderFonts.bold): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!font.canDisplay(cp) && RenderFonts.scriptFallbacks.none { it.canDisplay(cp) }) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /** [text] with a font per run: [font] where it has the character, else the first fallback that has it (derived to [font]'s size and transform). */
    private fun attributed(text: String, font: Font): AttributedString {
        val s = AttributedString(text)
        val derived = HashMap<Font, Font>()
        fun pick(cp: Int): Font {
            if (font.canDisplay(cp)) return font
            val fb = RenderFonts.scriptFallbacks.firstOrNull { it.canDisplay(cp) } ?: return font
            return derived.getOrPut(fb) { fb.deriveFont(font.transform).deriveFont(font.size2D) }
        }
        var start = 0
        var current: Font? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            // Spaces, digits and marks stay in the run they are in (an Arabic name keeps its spaces in the Arabic font).
            val f = if (current != null && (Character.isWhitespace(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt())) current else pick(cp)
            if (current != null && f != current) {
                s.addAttribute(TextAttribute.FONT, current, start, i)
                start = i
            }
            current = f
            i += Character.charCount(cp)
        }
        if (current != null) s.addAttribute(TextAttribute.FONT, current, start, text.length)
        return s
    }
}
