package io.github.jasondorjeshort.dystopo.app.share

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * T57: finds a QR code in a grey image (a camera frame's luminance plane, or a screenshot) and reads it:
 * the in-app scanner's decoder, pure Kotlin with no dependency (ML Kit is proprietary; CameraX only delivers
 * the frames). Steps: binarize (a local mean threshold from an integral image, then a global Otsu threshold
 * as a second try), find finder patterns (runs of dark:light:dark:light:dark in a 1:1:3:1:1 ratio along rows,
 * confirmed down the column and along the row again), pick the three that form the code's right-angled
 * corner, estimate the version from their spacing, look for the bottom-right alignment pattern (versions 2+),
 * map module centres into the image with a perspective transform through four points, sample, and hand the
 * matrix to [QrMatrixDecoder]. Each candidate version near the estimate is tried. Unit-tested on rendered,
 * rotated, skewed, blurred and noisy codes, and on screenshots of the Share screen through `POST /share/scan`.
 */
object QrDecoder {
    class Result(val text: String, val version: Int, val level: Char, val corrected: Int, val finders: Int, val threshold: String)

    /** Why the last [decode] found nothing (for `/share/scan` and the scanner's status line). */
    data class Miss(val finders: Int, val triples: Int, val attempts: Int, val lastError: String?)

    /** Decodes [lum] (w x h, row-major, 0..255); null when no code was read ([lastMiss] says how far it got). */
    fun decode(lum: IntArray, w: Int, h: Int): Result? {
        require(lum.size >= w * h && w > 0 && h > 0)
        var finders = 0
        var triples = 0
        var attempts = 0
        var lastError: String? = null
        for ((name, bin) in listOf("local" to { localThreshold(lum, w, h) }, "global" to { globalThreshold(lum, w, h) })) {
            val img = Bits(w, h, bin())
            val found = findFinders(img)
            finders = maxOf(finders, found.size)
            for (t in triples(found)) {
                triples++
                for (version in candidateVersions(t, img)) {
                    attempts++
                    try {
                        val matrix = sample(img, t, version) ?: continue
                        val r = QrMatrixDecoder.decode(matrix)
                        return Result(r.text, r.version, r.level, r.correctedErrors, found.size, name)
                    } catch (e: QrMatrixDecoder.DecodeException) {
                        lastError = e.message
                    }
                }
            }
        }
        lastMiss = Miss(finders, triples, attempts, lastError)
        return null
    }

    @Volatile var lastMiss: Miss? = null
        private set

    /** Luminance from ARGB pixels (a Bitmap's getPixels), for screenshots. */
    fun luminance(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        ((p ushr 16 and 0xFF) * 77 + (p ushr 8 and 0xFF) * 150 + (p and 0xFF) * 29) ushr 8
    }

    // ---- Binarization ----

    class Bits(val w: Int, val h: Int, val dark: BooleanArray) {
        operator fun get(x: Int, y: Int): Boolean = x in 0 until w && y in 0 until h && dark[y * w + x]
    }

    /** Dark where a pixel is below its neighbourhood mean (window about an eighth of the short side) by a margin. */
    fun localThreshold(lum: IntArray, w: Int, h: Int): BooleanArray {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += lum[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val r = maxOf(8, minOf(w, h) / 8)
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r)
            val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r)
                val x1 = minOf(w, x + r + 1)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = sum / ((x1 - x0) * (y1 - y0)).toDouble()
                // The pixel itself as a 3x3 mean, which takes the edge off sensor noise.
                val sx0 = maxOf(0, x - 1); val sx1 = minOf(w, x + 2); val sy0 = maxOf(0, y - 1); val sy1 = minOf(h, y + 2)
                val small = integral[sy1 * (w + 1) + sx1] - integral[sy0 * (w + 1) + sx1] - integral[sy1 * (w + 1) + sx0] + integral[sy0 * (w + 1) + sx0]
                out[y * w + x] = small / ((sx1 - sx0) * (sy1 - sy0)).toDouble() < mean - 8
            }
        }
        return out
    }

    /** One threshold for the whole image (Otsu's method over the histogram). */
    fun globalThreshold(lum: IntArray, w: Int, h: Int): BooleanArray {
        val n = w * h
        val hist = IntArray(256)
        for (i in 0 until n) hist[lum[i].coerceIn(0, 255)]++
        var sumAll = 0.0
        for (i in 0..255) sumAll += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = n - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return BooleanArray(n) { lum[it] <= threshold }
    }

    // ---- Finder patterns ----

    /** A finder pattern centre and its module size, with how many scan rows confirmed it. */
    class Finder(var x: Double, var y: Double, var module: Double, var hits: Int = 1)

    private fun ratioOk(runs: IntArray, pattern: IntArray): Boolean {
        val total = runs.sum()
        val units = pattern.sum()
        if (total < units) return false
        val module = total.toDouble() / units
        val tolerance = module * 0.7
        for (i in runs.indices) if (abs(runs[i] - pattern[i] * module) > tolerance * pattern[i].coerceAtMost(2)) return false
        return true
    }

    private val FINDER = intArrayOf(1, 1, 3, 1, 1)

    fun findFinders(img: Bits): List<Finder> {
        val found = ArrayList<Finder>()
        val step = maxOf(1, img.h / 600)
        val starts = IntArray(img.w + 1)
        val lens = IntArray(img.w + 1)
        val darks = BooleanArray(img.w + 1)
        val runs = IntArray(5)
        for (y in 0 until img.h step step) {
            // The row as runs: start, length, dark.
            var n = 0
            var x = 0
            while (x < img.w) {
                val d = img[x, y]
                var e = x
                while (e < img.w && img[e, y] == d) e++
                starts[n] = x; lens[n] = e - x; darks[n] = d; n++
                x = e
            }
            // Five runs from a dark one: dark light dark light dark.
            for (i in 0..n - 5) {
                if (!darks[i]) continue
                for (k in 0 until 5) runs[k] = lens[i + k]
                if (!ratioOk(runs, FINDER)) continue
                val centreX = starts[i + 2] + lens[i + 2] / 2.0
                confirm(img, centreX, y.toDouble(), runs.sum())?.let { add(found, it) }
            }
        }
        return found.filter { it.hits >= 2 || found.size <= 3 }.sortedByDescending { it.hits }
    }

    /** Runs through (cx, cy) along (dx, dy), both ways from the centre: the five-run pattern or null. */
    internal fun crossRuns(img: Bits, cx: Int, cy: Int, dx: Int, dy: Int, maxRun: Int): IntArray? {
        if (!img[cx, cy]) return null
        val r = IntArray(5)
        var x = cx
        var y = cy
        while (img[x, y] && r[2] <= maxRun) { r[2]++; x -= dx; y -= dy }
        while (!img[x, y] && inside(img, x, y) && r[1] <= maxRun) { r[1]++; x -= dx; y -= dy }
        while (img[x, y] && r[0] <= maxRun) { r[0]++; x -= dx; y -= dy }
        x = cx + dx
        y = cy + dy
        while (img[x, y] && r[2] <= 2 * maxRun) { r[2]++; x += dx; y += dy }
        while (!img[x, y] && inside(img, x, y) && r[3] <= maxRun) { r[3]++; x += dx; y += dy }
        while (img[x, y] && r[4] <= maxRun) { r[4]++; x += dx; y += dy }
        if (r.any { it == 0 }) return null
        return r
    }

    private fun inside(img: Bits, x: Int, y: Int) = x in 0 until img.w && y in 0 until img.h

    /** Confirms a row candidate down its column and along its row again; the refined finder or null. */
    private fun confirm(img: Bits, cx: Double, cy: Double, rowTotal: Int): Finder? {
        val maxRun = rowTotal
        val col = crossRuns(img, cx.roundToInt(), cy.roundToInt(), 0, 1, maxRun) ?: return null
        if (!ratioOk(col, FINDER) || abs(col.sum() - rowTotal) > rowTotal * 0.6) return null
        val vy = verticalCentre(img, cx.roundToInt(), cy.roundToInt(), col)
        val row = crossRuns(img, cx.roundToInt(), vy.roundToInt(), 1, 0, maxRun) ?: return null
        if (!ratioOk(row, FINDER)) return null
        val hx = horizontalCentre(img, cx.roundToInt(), vy.roundToInt(), row)
        val module = (row.sum() + col.sum()) / 14.0
        return Finder(hx, vy, module)
    }

    private fun verticalCentre(img: Bits, x: Int, y: Int, r: IntArray): Double {
        var up = 0
        while (img[x, y - up - 1]) up++
        val start = y - up
        return start + r[2] / 2.0
    }

    private fun horizontalCentre(img: Bits, x: Int, y: Int, r: IntArray): Double {
        var left = 0
        while (img[x - left - 1, y]) left++
        val start = x - left
        return start + r[2] / 2.0
    }

    private fun add(list: MutableList<Finder>, f: Finder) {
        for (g in list) {
            if (abs(g.x - f.x) <= g.module * 2 && abs(g.y - f.y) <= g.module * 2 && abs(g.module - f.module) <= g.module * 0.5) {
                val n = g.hits
                g.x = (g.x * n + f.x) / (n + 1)
                g.y = (g.y * n + f.y) / (n + 1)
                g.module = (g.module * n + f.module) / (n + 1)
                g.hits = n + 1
                return
            }
        }
        list.add(f)
    }

    // ---- The three corners ----

    /** Top-left (the right angle), top-right and bottom-left finders. */
    class Triple(val tl: Finder, val tr: Finder, val bl: Finder) {
        val module: Double get() = (tl.module + tr.module + bl.module) / 3
    }

    /** Plausible corner triples, best first: legs of similar length, a right angle, similar module sizes. */
    fun triples(finders: List<Finder>): List<Triple> {
        val fs = finders.take(12)
        val scored = ArrayList<Pair<Double, Triple>>()
        for (i in fs.indices) for (j in i + 1 until fs.size) for (k in j + 1 until fs.size) {
            val f = listOf(fs[i], fs[j], fs[k])
            val ms = f.map { it.module }
            if (ms.max() > ms.min() * 1.6) continue
            // The corner is the finder opposite the longest side.
            val d = listOf(dist(f[1], f[2]), dist(f[0], f[2]), dist(f[0], f[1]))
            val c = d.indices.maxBy { d[it] }
            val tl = f[c]
            val (p, q) = f.filterIndexed { idx, _ -> idx != c }
            val l1 = dist(tl, p)
            val l2 = dist(tl, q)
            if (l1 < 7 * tl.module || l2 < 7 * tl.module) continue
            val legRatio = maxOf(l1, l2) / minOf(l1, l2)
            if (legRatio > 1.4) continue
            val cos = ((p.x - tl.x) * (q.x - tl.x) + (p.y - tl.y) * (q.y - tl.y)) / (l1 * l2)
            if (abs(cos) > 0.35) continue
            // Orientation: with y down, top-right x bottom-left (from the corner) is positive.
            val cross = (p.x - tl.x) * (q.y - tl.y) - (p.y - tl.y) * (q.x - tl.x)
            val t = if (cross > 0) Triple(tl, p, q) else Triple(tl, q, p)
            scored.add((legRatio - 1) + abs(cos) + (ms.max() / ms.min() - 1) to t)
        }
        return scored.sortedBy { it.first }.map { it.second }.take(4)
    }

    private fun dist(a: Finder, b: Finder) = hypot(a.x - b.x, a.y - b.y)

    /** The module size of finder [a] measured through its centre along the direction to [b] (its 1:1:3:1:1 runs). */
    fun moduleAlong(img: Bits, a: Finder, b: Finder): Double {
        val d = dist(a, b)
        if (d == 0.0) return a.module
        val ux = (b.x - a.x) / d
        val uy = (b.y - a.y) / d
        fun dark(t: Double) = img[(a.x + ux * t).roundToInt(), (a.y + uy * t).roundToInt()]
        val limit = a.module * 6
        // From the centre: the core, the light ring, the outer ring, both ways.
        fun extent(sign: Int): Double {
            var t = 0.0
            var phase = 0 // 0 core (dark), 1 ring (light), 2 outer (dark)
            while (t < limit) {
                val isDark = dark(sign * t)
                if (phase == 0 && !isDark) phase = 1
                else if (phase == 1 && isDark) phase = 2
                else if (phase == 2 && !isDark) return t
                t += 0.5
            }
            return Double.NaN
        }
        val total = extent(1) + extent(-1)
        return if (total.isFinite() && total > 0) total / 7 else a.module
    }

    /**
     * Versions to try: the one the finder spacing suggests first, then its neighbours. The module size is
     * measured along the lines between the finders ([moduleAlong]), since the row and column runs read a
     * rotated finder too wide (by up to the square root of 2 at 45 degrees).
     */
    fun candidateVersions(t: Triple, img: Bits? = null): List<Int> {
        fun m(a: Finder, b: Finder) = img?.let { moduleAlong(it, a, b) } ?: a.module
        val top = dist(t.tl, t.tr) / ((m(t.tl, t.tr) + m(t.tr, t.tl)) / 2)
        val left = dist(t.tl, t.bl) / ((m(t.tl, t.bl) + m(t.bl, t.tl)) / 2)
        val modules = (top + left) / 2 + 7
        val v = ((modules - 17) / 4).roundToInt()
        return listOf(v, v - 1, v + 1, v - 2, v + 2).filter { it in 1..QrMatrixDecoder.MAX_VERSION }.distinct()
    }

    // ---- Sampling ----

    /** Samples the code of [version] whose finders are [t]; null when the transform degenerates. */
    fun sample(img: Bits, t: Triple, version: Int): Array<BooleanArray>? {
        val size = 17 + 4 * version
        val tl = t.tl
        val tr = t.tr
        val bl = t.bl
        // The bottom-right reference: the alignment pattern (versions 2+) at module (size - 6.5), else the
        // parallelogram's fourth corner at the bottom-right finder position (size - 3.5).
        val ux = (tr.x - tl.x) / (size - 7)
        val uy = (tr.y - tl.y) / (size - 7)
        val vx = (bl.x - tl.x) / (size - 7)
        val vy = (bl.y - tl.y) / (size - 7)
        var brModule = size - 3.5
        var brX = tr.x + bl.x - tl.x
        var brY = tr.y + bl.y - tl.y
        if (version >= 2) {
            val a = size - 6.5 - 3.5 // module offset from the top-left finder centre
            val ex = tl.x + (ux + vx) * a
            val ey = tl.y + (uy + vy) * a
            val module = (moduleAlong(img, tl, tr) + moduleAlong(img, tl, bl)) / 2
            val found = findAlignment(img, ex, ey, module)
            if (found != null) {
                brModule = size - 6.5
                brX = found.first
                brY = found.second
            }
        }
        val transform = Perspective.fromQuad(
            3.5, 3.5, size - 3.5, 3.5, 3.5, size - 3.5, brModule, brModule,
            tl.x, tl.y, tr.x, tr.y, bl.x, bl.y, brX, brY,
        ) ?: return null
        val out = Array(size) { BooleanArray(size) }
        val p = DoubleArray(2)
        for (y in 0 until size) for (x in 0 until size) {
            transform.map(x + 0.5, y + 0.5, p)
            out[y][x] = img[p[0].roundToInt(), p[1].roundToInt()]
        }
        return out
    }

    /**
     * An alignment pattern line through its centre: the light ring, the dark centre and the light ring are
     * one module each; the outer dark ring often runs into dark data modules, so only its presence counts.
     */
    private fun alignOk(r: IntArray, module: Double): Boolean {
        val inner = r[1] + r[2] + r[3]
        if (inner < module * 1.5 || inner > module * 5) return false
        val m = inner / 3.0
        for (i in 1..3) if (abs(r[i] - m) > m * 0.6) return false
        return r[0] >= m * 0.5 && r[4] >= m * 0.5
    }

    /**
     * The alignment pattern near ([ex], [ey]): candidates are dark runs whose row and column through the
     * centre read 1:1:1:1:1; each is re-centred and scored by how symmetric its four lines through the centre
     * are (row, column and both diagonals: a line off the centre of concentric squares is lopsided) plus its
     * distance from the estimate. Null when none is found.
     */
    fun findAlignment(img: Bits, ex: Double, ey: Double, module: Double): Pair<Double, Double>? {
        var best: Pair<Double, Double>? = null
        var bestScore = Double.MAX_VALUE
        val radius = (module * 10).roundToInt().coerceAtLeast(8)
        val maxRun = (module * 2.5).roundToInt() + 2
        val seen = HashSet<Long>()
        for (y in (ey.roundToInt() - radius)..(ey.roundToInt() + radius)) {
            for (x in (ex.roundToInt() - radius)..(ex.roundToInt() + radius)) {
                if (!img[x, y] || img[x - 1, y]) continue // only the left edge of a dark run
                var run = 0
                while (img[x + run, y]) run++
                if (run > maxRun || run < module * 0.4) continue
                val cx = x + run / 2
                val r = crossRuns(img, cx, y, 1, 0, maxRun) ?: continue
                if (!alignOk(r, module)) continue
                // Re-centre: column through cx, then the row through that centre, then the column again.
                val c = crossRuns(img, cx, y, 0, 1, maxRun) ?: continue
                if (!alignOk(c, module)) continue
                val ay = verticalCentre(img, cx, y, c)
                val r2 = crossRuns(img, cx, ay.roundToInt(), 1, 0, maxRun) ?: continue
                val ax = horizontalCentre(img, cx, ay.roundToInt(), r2)
                val key = (ax.roundToInt().toLong() shl 32) or (ay.roundToInt().toLong() and 0xFFFFFFFFL)
                if (!seen.add(key)) continue
                val ix = ax.roundToInt()
                val iy = ay.roundToInt()
                var asym = 0.0
                var ok = true
                for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
                    val step = if (dx != 0 && dy != 0) 1.4142 else 1.0
                    val l = crossRuns(img, ix, iy, dx, dy, maxRun)
                    if (l == null || !alignOk(IntArray(5) { (l[it] * step).roundToInt() }, module)) { ok = false; break }
                    val inner = (l[1] + l[2] + l[3]).toDouble()
                    asym += (abs(l[1] - l[3]) + abs(2 * l[2] - l[1] - l[3]) / 2.0) / inner
                }
                if (!ok) continue
                val score = asym * 4 + hypot(ax - ex, ay - ey) / (module * radius / module)
                if (score < bestScore) {
                    bestScore = score
                    best = ax to ay
                }
            }
        }
        return best
    }
}

/** A plane perspective transform (homography) from module coordinates to image pixels. */
class Perspective private constructor(private val m: DoubleArray) {
    fun map(x: Double, y: Double, out: DoubleArray) {
        val w = m[6] * x + m[7] * y + 1.0
        out[0] = (m[0] * x + m[1] * y + m[2]) / w
        out[1] = (m[3] * x + m[4] * y + m[5]) / w
    }

    companion object {
        /** The transform taking the four source points (sx, sy) to the four destinations (dx, dy); null if degenerate. */
        fun fromQuad(
            sx0: Double, sy0: Double, sx1: Double, sy1: Double, sx2: Double, sy2: Double, sx3: Double, sy3: Double,
            dx0: Double, dy0: Double, dx1: Double, dy1: Double, dx2: Double, dy2: Double, dx3: Double, dy3: Double,
        ): Perspective? {
            val s = doubleArrayOf(sx0, sy0, sx1, sy1, sx2, sy2, sx3, sy3)
            val d = doubleArrayOf(dx0, dy0, dx1, dy1, dx2, dy2, dx3, dy3)
            // Unknowns h0..h7: X = (h0 x + h1 y + h2) / (h6 x + h7 y + 1), Y = (h3 x + h4 y + h5) / (...).
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = s[2 * i]
                val y = s[2 * i + 1]
                val u = d[2 * i]
                val v = d[2 * i + 1]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v, v)
            }
            for (col in 0 until 8) {
                val pivot = (col until 8).maxBy { abs(a[it][col]) }
                if (abs(a[pivot][col]) < 1e-12) return null
                val t = a[col]; a[col] = a[pivot]; a[pivot] = t
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    if (f != 0.0) for (c in col..8) a[r][c] -= f * a[col][c]
                }
            }
            val h = DoubleArray(8) { a[it][8] / a[it][it] }
            if (h.any { !it.isFinite() }) return null
            return Perspective(h)
        }

        @Suppress("unused")
        private fun norm(x: Double, y: Double) = sqrt(x * x + y * y)
    }
}
