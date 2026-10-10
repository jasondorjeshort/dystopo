package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.style.LineTicks

/**
 * Cliff ticks on the downhill side for renderers that cannot ask the DEM (MapLibre, T41). A style can
 * only put cliff ticks on one side of a line (the right, the OSM `natural=cliff` convention), so the
 * vector tile is rewritten instead: every cliff line is cut into pieces whose ground falls to their
 * right, reversing the pieces where the DEM says the left is lower. The decision is the devtool
 * renderer's ([LineTicks.downhillTicks]): segment by segment, a segment that cannot tell (flat, no
 * data) follows the whole line, and a line that cannot tell keeps its direction.
 */
object CliffSides {
    const val LAYER = "natural_line"

    /** What [orient] did. */
    class Result(
        val tile: VectorTile,
        /** Cliff lines looked at. */
        val lines: Int,
        /** Pieces written reversed (their left was downhill). */
        val reversedPieces: Int,
        /** Segments whose ground falls to the left as drawn in the source. */
        val segmentsLeft: Int,
        /** Segments the DEM could not decide on their own. */
        val segmentsUndecided: Int,
    ) {
        val changed: Boolean get() = reversedPieces > 0
    }

    private val LAYER_BYTES = LAYER.encodeToByteArray()
    private val CLIFF_BYTES = "cliff".encodeToByteArray()

    /** Cheap pre-check on raw tile bytes: the cliff layer name and the class value both occur. */
    fun mayHaveCliffs(bytes: ByteArray): Boolean = indexOf(bytes, LAYER_BYTES) >= 0 && indexOf(bytes, CLIFF_BYTES) >= 0

    fun isCliff(f: Feature): Boolean = (f.properties["class"] as? Value.StringValue)?.value == "cliff"

    /**
     * Rewrites the cliff lines of [tile] so the downhill side is on their right. [elevation] gives metres
     * at a position in the layer's tile units (x right, y down; NaN for no data), [offset] is how far to
     * each side it is sampled and [step] the spacing of samples along a segment, both in tile units.
     */
    fun orient(tile: VectorTile, elevation: (Double, Double) -> Float, offset: Double, step: Double, minDrop: Double = 1.0): Result {
        var lines = 0
        var reversed = 0
        var left = 0
        var undecided = 0
        val layers = tile.layers.map { layer ->
            if (layer.name != LAYER) return@map layer
            var touched = false
            val features = layer.features.map { f ->
                val g = f.geometry
                if (g !is Geometry.Lines || !isCliff(f)) return@map f
                val pieces = ArrayList<List<TilePoint>>()
                for (line in g.lines) {
                    lines++
                    val o = orientLine(line, elevation, offset, step, minDrop)
                    reversed += o.reversed
                    left += o.segmentsLeft
                    undecided += o.segmentsUndecided
                    pieces += o.pieces
                }
                if (pieces == g.lines) f else f.copy(geometry = Geometry.Lines(pieces)).also { touched = true }
            }
            if (touched) Layer(layer.name, features, layer.extent, layer.version) else layer
        }
        return Result(if (reversed > 0) VectorTile(layers) else tile, lines, reversed, left, undecided)
    }

    /** One line cut into downhill-right pieces. */
    class Oriented(val pieces: List<List<TilePoint>>, val reversed: Int, val segmentsLeft: Int, val segmentsUndecided: Int)

    fun orientLine(line: List<TilePoint>, elevation: (Double, Double) -> Float, offset: Double, step: Double, minDrop: Double = 1.0): Oriented {
        if (line.size < 2) return Oriented(listOf(line), 0, 0, 0)
        val n = line.size - 1
        val sides = arrayOfNulls<Boolean>(n)
        for (i in 0 until n) {
            val a = line[i]
            val b = line[i + 1]
            if (a == b) continue
            sides[i] = LineTicks.rightIsDownhill(doubleArrayOf(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble()), offset, elevation, step, minDrop)
        }
        val whole = DoubleArray(line.size * 2).also { arr -> line.forEachIndexed { i, p -> arr[2 * i] = p.x.toDouble(); arr[2 * i + 1] = p.y.toDouble() } }
        val fallback = if (sides.all { it != null }) true else LineTicks.rightIsDownhill(whole, offset, elevation, step, minDrop) ?: true
        val right = BooleanArray(n) { sides[it] ?: fallback }
        val undecided = sides.indices.count { sides[it] == null && line[it] != line[it + 1] }
        val leftCount = sides.indices.count { !right[it] && line[it] != line[it + 1] }
        if (right.all { it }) return Oriented(listOf(line), 0, leftCount, undecided)
        val pieces = ArrayList<List<TilePoint>>()
        var reversed = 0
        var start = 0
        while (start < n) {
            var end = start
            while (end + 1 < n && right[end + 1] == right[start]) end++
            val piece = line.subList(start, end + 2)
            if (right[start]) pieces += piece else { pieces += piece.reversed(); reversed++ }
            start = end + 1
        }
        return Oriented(pieces, reversed, leftCount, undecided)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
