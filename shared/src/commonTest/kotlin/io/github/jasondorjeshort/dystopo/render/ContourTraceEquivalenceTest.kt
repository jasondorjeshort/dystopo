package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Contours.trace] against the tracer it replaced (T35): the old HashMap-based marching squares, kept
 * here as [LegacyTracer]. Both must produce the same set of lines with exactly the same points; only
 * the order of lines, the start of rings and the direction of lines may differ (the old order came
 * from HashMap iteration). Compared after [canonical] normalises those.
 */
class ContourTraceEquivalenceTest {
    private fun grid(w: Int, h: Int, f: (Int, Int) -> Float) =
        ElevationGrid(w, h, 1.0, FloatArray(w * h) { f(it % w, it / w) })

    private fun assertSameGeometry(g: ElevationGrid, levels: List<Double>) {
        val expected = LegacyTracer.trace(g, levels).map(::canonical).sorted()
        val actual = Contours.trace(g, levels).map(::canonical).sorted()
        assertEquals(expected.size, actual.size, "line count")
        assertEquals(expected, actual)
    }

    @Test
    fun smoothTerrain() {
        val g = grid(97, 83) { x, y -> (1000 + 300 * sin(x / 9.0) * sin(y / 13.0) + 2.0 * x).toFloat() }
        assertSameGeometry(g, Contours.levels(g.min().toDouble(), g.max().toDouble(), 25.0))
    }

    @Test
    fun noiseWithSaddles() {
        val r = Random(35)
        val g = grid(64, 64) { _, _ -> r.nextFloat() * 100 }
        assertSameGeometry(g, Contours.levels(0.0, 100.0, 7.0))
    }

    @Test
    fun samplesExactlyOnLevels() {
        // Integer samples and integer levels: many samples equal a level (the >= rule decides).
        val r = Random(7)
        val g = grid(50, 40) { _, _ -> r.nextInt(0, 6).toFloat() * 10 }
        assertSameGeometry(g, listOf(10.0, 20.0, 30.0, 40.0))
    }

    @Test
    fun noDataHoles() {
        val r = Random(11)
        val g = grid(60, 45) { x, y ->
            if ((x - 30) * (x - 30) + (y - 20) * (y - 20) < 60 || r.nextInt(50) == 0) Float.NaN
            else (x * 3 + y * 2 + r.nextFloat() * 5)
        }
        assertSameGeometry(g, Contours.levels(0.0, 300.0, 10.0))
    }

    @Test
    fun unsortedLevelsAndOneRowGrids() {
        val g = grid(30, 2) { x, y -> (x * 4 + y * 7).toFloat() }
        assertSameGeometry(g, listOf(50.0, 10.0, 90.0))
        val tall = grid(2, 30) { x, y -> (y * 4 + x * 7).toFloat() }
        assertSameGeometry(tall, listOf(33.0, 5.0))
    }

    @Test
    fun outputOrderIsByLevelThenOpenBeforeClosed() {
        // A cone cut by the grid edge: high levels are closed rings, low ones open arcs.
        val g = grid(80, 80) { x, y -> (1000 - ((x - 60) * (x - 60) + (y - 40) * (y - 40)) * 0.2).toFloat() }
        val lines = Contours.trace(g, listOf(990.0, 700.0, 900.0))
        assertEquals(lines.map { it.level }.sorted(), lines.map { it.level })
        for (level in listOf(700.0, 900.0, 990.0)) {
            val of = lines.filter { it.level == level }
            val firstClosed = of.indexOfFirst { it.closed }
            assertTrue(firstClosed < 0 || of.drop(firstClosed).all { it.closed }, "open lines precede rings at $level")
        }
        assertTrue(lines.single { it.level == 990.0 }.closed)
        assertTrue(lines.filter { it.level == 700.0 }.none { it.closed })
    }

    /** Lines as text with rings rotated to their smallest point and the smaller direction chosen. */
    private fun canonical(line: ContourLine): String {
        val pts = (0 until line.pointCount).map { line.points[2 * it] to line.points[2 * it + 1] }
        val cmp = compareBy<Pair<Double, Double>>({ it.first }, { it.second })
        val listCmp = Comparator<List<Pair<Double, Double>>> { a, b ->
            for (i in a.indices) {
                val c = cmp.compare(a[i], b[i])
                if (c != 0) return@Comparator c
            }
            0
        }
        val candidates = if (!line.closed) listOf(pts, pts.reversed()) else {
            val rev = pts.reversed()
            listOf(pts, rev).flatMap { seq -> seq.indices.map { k -> seq.drop(k) + seq.take(k) } }
        }
        val best = candidates.minWith(listCmp)
        return "${line.level} ${line.closed} " + best.joinToString(" ") { "${it.first},${it.second}" }
    }
}

/** The tracer before T35, verbatim apart from names: one HashMap of boxed edge keys per level. */
private object LegacyTracer {
    fun trace(grid: ElevationGrid, levels: List<Double>): List<ContourLine> {
        if (levels.isEmpty() || grid.width < 2 || grid.height < 2) return emptyList()
        val sorted = levels.sorted()
        val tracers = sorted.map { LevelTracer(grid, it) }
        for (y in 0 until grid.height - 1) {
            for (x in 0 until grid.width - 1) {
                val tl = grid[x, y]
                val tr = grid[x + 1, y]
                val br = grid[x + 1, y + 1]
                val bl = grid[x, y + 1]
                val lo = minOf(minOf(tl, tr), minOf(br, bl)).toDouble()
                val hi = maxOf(maxOf(tl, tr), maxOf(br, bl)).toDouble()
                var k = firstAbove(sorted, lo)
                while (k < sorted.size && sorted[k] <= hi) {
                    tracers[k].addCell(x, y, tl, tr, br, bl)
                    k++
                }
            }
        }
        return tracers.flatMap { it.lines() }
    }

    private fun firstAbove(sorted: List<Double>, value: Double): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private class LevelTracer(private val grid: ElevationGrid, private val level: Double) {
        private class Node(var a: Long = NONE, var b: Long = NONE)

        private val nodes = HashMap<Long, Node>()

        private fun hEdge(x: Int, y: Int): Long = (y.toLong() * grid.width + x) * 2
        private fun vEdge(x: Int, y: Int): Long = (y.toLong() * grid.width + x) * 2 + 1

        fun addCell(x: Int, y: Int, tl: Float, tr: Float, br: Float, bl: Float) {
            val top = hEdge(x, y)
            val right = vEdge(x + 1, y)
            val bottom = hEdge(x, y + 1)
            val left = vEdge(x, y)
            val case = (if (tl >= level) 8 else 0) or (if (tr >= level) 4 else 0) or
                (if (br >= level) 2 else 0) or (if (bl >= level) 1 else 0)
            when (case) {
                1, 14 -> link(left, bottom)
                2, 13 -> link(bottom, right)
                3, 12 -> link(left, right)
                4, 11 -> link(top, right)
                6, 9 -> link(top, bottom)
                7, 8 -> link(left, top)
                5, 10 -> {
                    val centerAbove = (tl + tr + br + bl) / 4.0 >= level
                    if ((case == 5) == centerAbove) {
                        link(left, top); link(bottom, right)
                    } else {
                        link(left, bottom); link(top, right)
                    }
                }
            }
        }

        private fun link(p: Long, q: Long) {
            attach(p, q)
            attach(q, p)
        }

        private fun attach(from: Long, to: Long) {
            val node = nodes.getOrPut(from) { Node() }
            if (node.a == NONE) node.a = to else node.b = to
        }

        fun lines(): List<ContourLine> {
            val visited = HashSet<Long>()
            val result = ArrayList<ContourLine>()
            for ((key, node) in nodes) {
                if (node.b == NONE && key !in visited) result += walk(key, visited)
            }
            for (key in nodes.keys) {
                if (key !in visited) result += walk(key, visited)
            }
            return result
        }

        private fun walk(start: Long, visited: HashSet<Long>): ContourLine {
            val coords = ArrayList<Double>()
            var prev = NONE
            var cur = start
            var closed = false
            while (true) {
                visited += cur
                addPoint(cur, coords)
                val node = nodes.getValue(cur)
                val next = if (node.a != prev) node.a else node.b
                if (next == NONE) break
                if (next == start) {
                    closed = true
                    break
                }
                if (next in visited) break
                prev = cur
                cur = next
            }
            return ContourLine(level, coords.toDoubleArray(), closed)
        }

        private fun addPoint(edge: Long, out: MutableList<Double>) {
            val index = edge / 2
            val x = (index % grid.width).toInt()
            val y = (index / grid.width).toInt()
            val v0 = grid[x, y]
            if (edge % 2 == 0L) {
                val t = (level - v0) / (grid[x + 1, y] - v0)
                out += x + t
                out += y.toDouble()
            } else {
                val t = (level - v0) / (grid[x, y + 1] - v0)
                out += x.toDouble()
                out += y + t
            }
        }

        companion object {
            const val NONE = -1L
        }
    }
}
