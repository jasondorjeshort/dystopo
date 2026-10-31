package io.github.jasondorjeshort.dystopo.app.print

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import io.github.jasondorjeshort.dystopo.render.BoxPx
import io.github.jasondorjeshort.dystopo.render.PageLayout
import io.github.jasondorjeshort.dystopo.render.SheetCanvas
import io.github.jasondorjeshort.dystopo.render.SheetFont

/**
 * T183: `shared`'s [SheetCanvas] on an `android.graphics.Canvas` in page pixels (the PDF page's canvas scaled by
 * 72 / dpi, so text stays vector text in the PDF), with the phone's sans serif at the page's dpi. Strokes are square
 * capped and mitred, as devtool's AWT strokes are, so the two sheets draw the same lines.
 */
class AndroidSheetCanvas(private val canvas: Canvas, private val layout: PageLayout) : SheetCanvas {
    private val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val fonts = HashMap<SheetFont, Paint>()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Texts drawn (the margin's lines; `POST /print` reports the count). */
    var textsDrawn = 0
        private set

    private fun font(f: SheetFont): Paint = fonts.getOrPut(f) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = if (f.bold) bold else regular
            textSize = (f.sizePt * layout.dpi / 72).toFloat()
        }
    }

    private fun bounds(p: Paint, text: String): Rect = Rect().also { p.getTextBounds(text, 0, text.length, it) }

    override fun fontPx(font: SheetFont): Double = font(font).textSize.toDouble()

    override fun advance(text: String, font: SheetFont): Double = font(font).measureText(text).toDouble()

    override fun measure(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean): BoxPx {
        val r = bounds(font(font), text)
        val shift = -r.width() * align
        return place(x, y, r.left + shift, r.top.toDouble(), r.right + shift, r.bottom.toDouble(), rotate)
    }

    override fun text(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean, color: Int): BoxPx {
        val p = font(font)
        p.color = color
        p.style = Paint.Style.FILL
        val shift = -p.measureText(text) * align
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        if (rotate) canvas.rotate(-90f)
        canvas.drawText(text, shift.toFloat(), 0f, p)
        canvas.restore()
        textsDrawn++
        val r = bounds(p, text)
        val b = place(x, y, r.left + shift, r.top.toDouble(), r.right + shift, r.bottom.toDouble(), rotate)
        return BoxPx(b.x - 2, b.y - 2, b.w + 4, b.h + 4)
    }

    override fun haloText(text: String, font: SheetFont, x: Double, y: Double, align: Double, color: Int, halo: Int) {
        val p = Paint(font(font))
        val shift = (-p.measureText(text) * align).toFloat()
        p.style = Paint.Style.STROKE
        p.strokeWidth = p.textSize * 0.25f
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = Paint.Cap.ROUND
        p.color = halo
        canvas.drawText(text, x.toFloat() + shift, y.toFloat(), p)
        p.style = Paint.Style.FILL
        p.color = color
        canvas.drawText(text, x.toFloat() + shift, y.toFloat(), p)
        textsDrawn++
    }

    override fun line(x0: Double, y0: Double, x1: Double, y1: Double, color: Int, width: Double) {
        stroke.color = color
        stroke.strokeWidth = width.toFloat()
        canvas.drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), stroke)
    }

    override fun polyline(xy: DoubleArray, dx: Double, dy: Double, color: Int, width: Double) {
        if (xy.size < 4) return
        val path = Path()
        path.moveTo((dx + xy[0]).toFloat(), (dy + xy[1]).toFloat())
        for (i in 1 until xy.size / 2) path.lineTo((dx + xy[2 * i]).toFloat(), (dy + xy[2 * i + 1]).toFloat())
        stroke.color = color
        stroke.strokeWidth = width.toFloat()
        canvas.drawPath(path, stroke)
    }

    override fun rect(x: Double, y: Double, w: Double, h: Double, fill: Int?, stroke: Int?, width: Double) {
        val l = x.toFloat()
        val t = y.toFloat()
        val r = (x + w).toFloat()
        val b = (y + h).toFloat()
        fill?.let {
            this.fill.color = it
            canvas.drawRect(l, t, r, b, this.fill)
        }
        stroke?.let {
            this.stroke.color = it
            this.stroke.strokeWidth = width.toFloat()
            canvas.drawRect(l, t, r, b, this.stroke)
        }
    }

    override fun polygon(xy: DoubleArray, color: Int) {
        if (xy.size < 6) return
        val path = Path()
        path.moveTo(xy[0].toFloat(), xy[1].toFloat())
        for (i in 1 until xy.size / 2) path.lineTo(xy[2 * i].toFloat(), xy[2 * i + 1].toFloat())
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    override fun clip(x: Double, y: Double, w: Double, h: Double) {
        canvas.save()
        canvas.clipRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
    }

    override fun unclip() {
        canvas.restore()
    }

    companion object {
        /**
         * The page box of a text's local box ([l], [t], [r], [b] from its baseline origin, alignment applied) drawn at
         * ([x], [y]), turned a quarter left when [rotate]d: local (u, v) lands on (x + v, y - u). Pure, tested.
         */
        fun place(x: Double, y: Double, l: Double, t: Double, r: Double, b: Double, rotate: Boolean): BoxPx =
            if (!rotate) BoxPx(x + l, y + t, r - l, b - t) else BoxPx(x + t, y - r, b - t, r - l)
    }
}
