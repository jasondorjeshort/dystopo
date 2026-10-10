package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.style.Marker
import io.github.jasondorjeshort.dystopo.style.MarkerShape
import io.github.jasondorjeshort.dystopo.style.StyleColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D

/**
 * Point marker drawing shared by the PNG renderer ([OsmLayer]) and the sprite sheet ([SpriteCommand]),
 * so the phone's icons are the renderer's shapes by construction. The browser demo's canvas fallback
 * (`markerPath` in tools/demo/index.html) uses the same vertices.
 */
object MarkerShapes {
    /** Outline of a marker of radius [r] centred at ([cx], [cy]). */
    fun shape(shape: MarkerShape, cx: Double, cy: Double, r: Double): java.awt.Shape = when (shape) {
        MarkerShape.CIRCLE -> Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r)
        MarkerShape.SQUARE -> Rectangle2D.Double(cx - r * 0.9, cy - r * 0.9, 1.8 * r, 1.8 * r)
        MarkerShape.TRIANGLE -> Path2D.Double().apply {
            moveTo(cx, cy - r * 1.1)
            lineTo(cx + r, cy + r * 0.8)
            lineTo(cx - r, cy + r * 0.8)
            closePath()
        }
        MarkerShape.DIAMOND -> Path2D.Double().apply {
            moveTo(cx, cy - r * 1.15)
            lineTo(cx + r * 0.9, cy)
            lineTo(cx, cy + r * 1.15)
            lineTo(cx - r * 0.9, cy)
            closePath()
        }
        MarkerShape.HOUSE -> Path2D.Double().apply {
            // A pitched roof over a square body.
            moveTo(cx, cy - r * 1.1)
            lineTo(cx + r, cy - r * 0.1)
            lineTo(cx + r * 0.75, cy - r * 0.1)
            lineTo(cx + r * 0.75, cy + r * 0.9)
            lineTo(cx - r * 0.75, cy + r * 0.9)
            lineTo(cx - r * 0.75, cy - r * 0.1)
            lineTo(cx - r, cy - r * 0.1)
            closePath()
        }
        MarkerShape.STAR -> Path2D.Double().apply {
            for (k in 0 until 10) {
                val a = -Math.PI / 2 + k * Math.PI / 5
                val rr = if (k % 2 == 0) r * 1.2 else r * 0.5
                val x = cx + rr * Math.cos(a)
                val y = cy + rr * Math.sin(a)
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
            closePath()
        }
    }

    /**
     * Draws [m] centred at ([cx], [cy]), [px] pixels across: a [haloWidth] halo stroke in [halo], the
     * filled shape, and the marker's glyph (P for parking) centred on its ink.
     */
    fun draw(g2: Graphics2D, m: Marker, cx: Double, cy: Double, px: Double, halo: Color, haloWidth: Float = 2.5f) {
        val shape = shape(m.shape, cx, cy, px / 2)
        g2.color = halo
        g2.stroke = BasicStroke(haloWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(shape)
        g2.color = color(m.color)
        g2.fill(shape)
        val text = m.text ?: return
        val gv = AwtTextMeasurer.font(px * 0.8).createGlyphVector(g2.fontRenderContext, text)
        val ink = gv.visualBounds
        g2.color = color(m.textColor)
        g2.fill(gv.getOutline((cx - ink.centerX).toFloat(), (cy - ink.centerY).toFloat()))
    }

    private fun color(c: StyleColor) = Color(c.r, c.g, c.b, c.a)
}
