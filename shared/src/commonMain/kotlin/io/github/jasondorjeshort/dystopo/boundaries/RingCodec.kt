package io.github.jasondorjeshort.dystopo.boundaries

import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import kotlin.math.roundToLong

/**
 * Rings as text: the polyline encoding's fixed point and 5-bit chunks (1e-5 degrees, latitude then longitude,
 * each value the zigzag-coded difference from the previous point, written in 5-bit groups with a continuation
 * bit, every group offset by 63 so the text uses only the printable characters `?` to `~`). The first point is
 * the difference from (0, 0); the closing point is not written, [decode] repeats the first point.
 */
object RingCodec {
    const val SCALE = 1e5

    /** Degrees to the integer grid the text stores. */
    fun quantize(deg: Double): Long = (deg * SCALE).roundToLong()

    /** [ring] (closed or not) as text; the closing point, when present, is dropped. */
    fun encode(ring: List<AreaPoint>): String {
        val n = if (ring.size > 1 && ring.first() == ring.last()) ring.size - 1 else ring.size
        val sb = StringBuilder(n * 6)
        var lat = 0L
        var lon = 0L
        for (i in 0 until n) {
            val la = quantize(ring[i].lat)
            val lo = quantize(ring[i].lon)
            put(sb, la - lat)
            put(sb, lo - lon)
            lat = la
            lon = lo
        }
        return sb.toString()
    }

    /** The closed ring of [text] (first point repeated at the end). */
    fun decode(text: String): List<AreaPoint> {
        val out = ArrayList<AreaPoint>(text.length / 4 + 1)
        var i = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var shift = 0
            var v = 0L
            while (true) {
                require(i < text.length) { "ring text ends inside a value" }
                val b = text[i++].code - 63
                require(b in 0..63) { "not a ring character: ${text[i - 1]}" }
                v = v or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (v and 1L != 0L) (v shr 1).inv() else v shr 1
        }
        while (i < text.length) {
            lat += next()
            lon += next()
            out += AreaPoint(lat / SCALE, lon / SCALE)
        }
        if (out.isNotEmpty()) out += out[0]
        return out
    }

    private fun put(sb: StringBuilder, delta: Long) {
        var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }
}
