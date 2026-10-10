package io.github.jasondorjeshort.dystopo.server

import java.awt.Font
import java.awt.geom.AffineTransform

/**
 * The fonts every devtool PNG is drawn with: Noto Sans Regular and Bold (unhinted TTFs from the Noto
 * release, SIL OFL 1.1, `resources/fonts/` with the licence; registry id `noto-sans`), loaded with
 * [Font.createFont] instead of the logical `SansSerif`, which is Arial on Windows, DejaVu or Liberation
 * on Linux and Helvetica on macOS. Label placement depends on text widths, so the golden images only
 * matched on machines with Arial before (T48 installed it in CI); with a bundled font they match anywhere.
 * Italic is the bold face sheared by the same fixed angle everywhere, rather than a third file or the
 * platform's synthetic oblique.
 */
object RenderFonts {
    private fun load(name: String): Font {
        val stream = RenderFonts::class.java.getResourceAsStream("/fonts/$name") ?: error("font resource /fonts/$name missing")
        return stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }

    /** Noto Sans Regular at 1 px; derive the size. */
    val regular: Font by lazy { load("NotoSans-Regular.ttf") }

    /** Noto Sans Bold at 1 px; derive the size. */
    val bold: Font by lazy { load("NotoSans-Bold.ttf") }

    /** Horizontal shear of the italic face (about 11 degrees, close to Noto Sans Italic's slant). */
    const val ITALIC_SHEAR = -0.2

    fun regular(px: Double): Font = regular.deriveFont(px.toFloat())

    fun bold(px: Double): Font = bold.deriveFont(px.toFloat())

    fun boldItalic(px: Double): Font = bold.deriveFont(AffineTransform.getShearInstance(ITALIC_SHEAR, 0.0)).deriveFont(px.toFloat())
}
