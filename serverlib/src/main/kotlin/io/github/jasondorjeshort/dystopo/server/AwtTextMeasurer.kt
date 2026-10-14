package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.render.TextMeasurer
import io.github.jasondorjeshort.dystopo.render.TextSize
import java.awt.Font
import java.awt.font.FontRenderContext

/** Measures text with the same font the renderer draws with. */
object AwtTextMeasurer : TextMeasurer {
    private val frc = FontRenderContext(null, true, true)

    fun font(fontPx: Double): Font = RenderFonts.bold(fontPx) // T50: bundled Noto Sans Bold

    override fun measure(text: String, fontPx: Double): TextSize {
        val bounds = LabelText.bounds(text, font(fontPx), frc) // T92: other scripts through the fallback fonts
        return TextSize(bounds.width, bounds.height)
    }
}
