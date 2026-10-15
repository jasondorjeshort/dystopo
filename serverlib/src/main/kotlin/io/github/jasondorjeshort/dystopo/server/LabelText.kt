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
     * reads right. T107: Arabic letters are first put in their joined forms ([shapeArabic]), so a character drawn
     * alone is still the right initial, medial or final form (before, every letter drew isolated, unjoined).
     */
    fun visualOrder(text: String): String = reorder(shapeArabic(text))

    private fun reorder(text: String): String {
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

    /**
     * T107: [text] (logical order) with each Arabic letter replaced by its contextual presentation form (U+FB50..U+FBFF,
     * U+FE70..U+FEFF): isolated, final, initial or medial by whether it joins the letter before and the one after
     * (marks are skipped when looking for neighbours), and lam followed by alef as one lam-alef ligature. A curved
     * label draws one character at a time, so the font's own shaping never sees the word; with the forms written out,
     * each character is already the glyph the joined word uses. The bundled Noto Sans Arabic has these forms. Text
     * without Arabic letters comes back unchanged.
     */
    fun shapeArabic(text: String): String {
        if (text.none { it in 'ؠ'..'ۿ' }) return text
        val f = ArabicForms
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val forms = f.forms[c]
            if (forms == null) { out.append(c); i++; continue }
            var p = i - 1
            while (p >= 0 && f.transparent(text[p])) p--
            var n = i + 1
            while (n < text.length && f.transparent(text[n])) n++
            val joinsBefore = p >= 0 && f.joinsNext(text[p]) && f.joinsPrevious(c)
            val next = text.getOrNull(n)
            val ligature = if (c == LAM && next != null) f.lamAlef[next] else null
            if (ligature != null) {
                // Lam-alef: one glyph, final when the letter before joins it; the marks on the lam follow it.
                out.appendCodePoint(ligature[if (joinsBefore && ligature[1] != 0) 1 else 0])
                out.append(text, i + 1, n)
                i = n + 1
                continue
            }
            val joinsAfter = next != null && f.joinsNext(c) && f.joinsPrevious(next)
            val form = when {
                joinsBefore && joinsAfter -> 3
                joinsBefore -> 1
                joinsAfter -> 2
                else -> 0
            }
            out.appendCodePoint(forms[form].takeIf { it != 0 } ?: forms[if (form == 3) 1 else 0].takeIf { it != 0 } ?: c.code)
            i++
        }
        return out.toString()
    }

    private const val LAM = 'ل'

    /**
     * The presentation forms of each Arabic letter, read once from the JDK's Unicode data: every code point of
     * Arabic Presentation Forms-A (the letters, U+FB50..U+FBFF) and -B whose name ends in ISOLATED, FINAL, INITIAL or
     * MEDIAL FORM, keyed by its compatibility decomposition (the base letter; lam + alef for the ligatures).
     */
    private object ArabicForms {
        /** Base letter -> [isolated, final, initial, medial] code points (0: the letter has no such form). */
        val forms = HashMap<Char, IntArray>()
        /** Alef (any of its forms) -> the lam-alef ligature's [isolated, final]. */
        val lamAlef = HashMap<Char, IntArray>()
        private val FORMS = listOf("ISOLATED", "FINAL", "INITIAL", "MEDIAL")

        init {
            for (cp in (0xFB50..0xFBFF) + (0xFE70..0xFEFC)) {
                val name = Character.getName(cp) ?: continue
                val form = FORMS.indexOfFirst { name.endsWith(" $it FORM") }.takeIf { it >= 0 } ?: continue
                val base = java.text.Normalizer.normalize(String(Character.toChars(cp)), java.text.Normalizer.Form.NFKC)
                when {
                    base.length == 1 && base[0] in 'ؠ'..'ۿ' && Character.isLetter(base[0]) -> forms.getOrPut(base[0]) { IntArray(4) }.let { if (it[form] == 0) it[form] = cp }
                    base.length == 2 && base[0] == LAM && form < 2 -> lamAlef.getOrPut(base[1]) { IntArray(2) }[form] = cp
                }
            }
        }

        /** Marks (harakat, the superscript alef) sit on a letter and are skipped when finding its neighbours. */
        fun transparent(c: Char): Boolean = Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() }

        /** [c] joins the letter after it: a dual-joining letter (it has an initial form), tatweel or a zero-width joiner. */
        fun joinsNext(c: Char): Boolean = c == 'ـ' || c == '‍' || (forms[c]?.get(2) ?: 0) != 0

        /** [c] joins the letter before it: any letter with a final form, tatweel or a zero-width joiner. */
        fun joinsPrevious(c: Char): Boolean = c == 'ـ' || c == '‍' || (forms[c]?.get(1) ?: 0) != 0

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
