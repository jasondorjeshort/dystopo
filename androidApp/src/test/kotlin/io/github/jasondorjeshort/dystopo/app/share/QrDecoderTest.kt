package io.github.jasondorjeshort.dystopo.app.share

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrDecoderTest {
    private class Case(val level: Char, val version: Int, val mask: Int, val text: String, val matrix: Array<BooleanArray>)

    /** `qr/decode-reference.txt`: matrices by python-qrcode 8.2 (BSD), versions 1 to 10, every level, numeric, alphanumeric and byte modes. */
    private val cases: List<Case> by lazy {
        val lines = javaClass.classLoader!!.getResource("qr/decode-reference.txt")!!.readText(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        val out = ArrayList<Case>()
        var i = 0
        while (i < lines.size) {
            val head = lines[i].split('|', limit = 4)
            val size = 17 + 4 * head[1].toInt()
            val m = Array(size) { y -> BooleanArray(size) { x -> lines[i + 1 + y][x] == '#' } }
            out += Case(head[0][0], head[1].toInt(), head[2].toInt(), head[3], m)
            i += 1 + size
        }
        out
    }

    @Test
    fun readsReferenceMatricesAtEveryVersionAndLevel() {
        assertEquals(13, cases.size)
        for (c in cases) {
            val r = QrMatrixDecoder.decode(c.matrix)
            assertEquals(c.text, r.text, "v${c.version}-${c.level}")
            assertEquals(c.version, r.version)
            assertEquals(c.level, r.level)
            assertEquals(c.mask, r.mask)
            assertEquals(0, r.correctedErrors)
        }
    }

    @Test
    fun readsOurOwnEncoder() {
        for (text in listOf("dystopo://share?addr=192.168.1.23:8841&code=123456", "dystopo://share?addr=10.0.2.2:8841&code=000042", "x")) {
            for (mask in 0..7) assertEquals(text, QrMatrixDecoder.decode(matrix(QrCode.encode(text, mask))).text)
        }
    }

    @Test
    fun correctsDamagedModules() {
        val rnd = Random(7)
        for (c in cases) {
            val function = QrMatrixDecoder.functionMap(c.version)
            val m = Array(c.matrix.size) { c.matrix[it].copyOf() }
            // A few flipped data modules: each hits at most one codeword, well within every level's capacity.
            val flips = when (c.level) { 'L' -> 2; 'M' -> 4; 'Q' -> 6; else -> 8 }
            var done = 0
            while (done < flips) {
                val x = rnd.nextInt(m.size)
                val y = rnd.nextInt(m.size)
                if (function[y][x]) continue
                m[y][x] = !m[y][x]
                done++
            }
            val r = QrMatrixDecoder.decode(m)
            assertEquals(c.text, r.text, "v${c.version}-${c.level} with $flips flips")
            assertTrue(r.correctedErrors in 1..flips, "corrected ${r.correctedErrors}")
        }
    }

    @Test
    fun tooMuchDamageFailsCleanly() {
        val c = cases.first { it.level == 'L' && it.version == 1 }
        val m = Array(c.matrix.size) { y -> BooleanArray(c.matrix.size) { x -> if (x > 9 && y > 9) !c.matrix[y][x] else c.matrix[y][x] } }
        val r = runCatching { QrMatrixDecoder.decode(m) }
        assertTrue(r.isFailure || r.getOrNull()?.text != c.text)
    }

    // ---- Images ----

    private fun matrix(q: QrCode) = Array(q.size) { y -> BooleanArray(q.size) { x -> q[x, y] } }

    /**
     * Renders [m] into a w x h grey image: [toImage] maps module coordinates (quiet zone included, so 0..size+8)
     * to pixels; each pixel is the average of 3x3 subsamples (antialiasing), then [light] / [dark] levels, a
     * left-to-right lighting gradient and noise.
     */
    private fun render(
        m: Array<BooleanArray>, w: Int, h: Int, fromImage: (Double, Double) -> Pair<Double, Double>,
        dark: Int = 20, light: Int = 235, gradient: Int = 0, noise: Int = 0, seed: Int = 1,
    ): IntArray {
        val rnd = Random(seed)
        val size = m.size
        val out = IntArray(w * h)
        for (py in 0 until h) for (px in 0 until w) {
            var sum = 0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                val (mx, my) = fromImage(px + (sx + 0.5) / 3, py + (sy + 0.5) / 3)
                val x = floor(mx).toInt() - 4
                val y = floor(my).toInt() - 4
                val isDark = x in 0 until size && y in 0 until size && m[y][x]
                sum += if (isDark) dark else light
            }
            val v = sum / 9 - gradient * px / w + (if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0)
            out[py * w + px] = v.coerceIn(0, 255)
        }
        return out
    }

    private val link = "dystopo://share?addr=192.168.1.23:8841&code=123456"

    @Test
    fun decodesAnUprightRendering() {
        val m = matrix(QrCode.encode(link))
        val scale = 9.0
        val r = QrDecoder.decode(render(m, 400, 400, { x, y -> (x - 20) / scale to (y - 20) / scale }), 400, 400)
        assertNotNull(r, "${QrDecoder.lastMiss}")
        assertEquals(link, r.text)
    }

    @Test
    fun decodesRotatedSkewedBlurredAndNoisyRenderings() {
        val m = matrix(QrCode.encode(link))
        val total = m.size + 8.0
        val w = 640
        val h = 480
        for (angle in listOf(0.0, 17.0, 45.0, 90.0, 133.0, 200.0, 290.0)) {
            for (persp in listOf(0.0, 0.0006)) {
                val a = angle * PI / 180
                val scale = 300.0 / total
                // Image -> module: undo a perspective (a true projective map: a camera tilted about the vertical
                // axis), a rotation about the image centre and the scale.
                val img = render(m, w, h, { px, py ->
                    val k = 1 - persp * (px - w / 2.0)
                    val x = (px - w / 2.0) / k
                    val y = (py - h / 2.0) / k
                    val rx = x * cos(-a) - y * sin(-a)
                    val ry = x * sin(-a) + y * cos(-a)
                    rx / scale + total / 2 to ry / scale + total / 2
                }, dark = 60, light = 200, gradient = 70, noise = 25, seed = angle.toInt())
                val r = QrDecoder.decode(img, w, h)
                assertNotNull(r, "angle $angle perspective $persp: ${QrDecoder.lastMiss}")
                assertEquals(link, r.text)
            }
        }
    }

    @Test
    fun decodesLargerVersionsFromImages() {
        for (c in cases.filter { it.version >= 5 }) {
            val total = c.matrix.size + 8.0
            val scale = 560.0 / total
            val a = 9 * PI / 180
            val img = render(c.matrix, 640, 640, { px, py ->
                val x = px - 320.0
                val y = py - 320.0
                (x * cos(-a) - y * sin(-a)) / scale + total / 2 to (x * sin(-a) + y * cos(-a)) / scale + total / 2
            }, noise = 10)
            val r = QrDecoder.decode(img, 640, 640)
            assertNotNull(r, "v${c.version}-${c.level}: ${QrDecoder.lastMiss}")
            assertEquals(c.text, r.text)
        }
    }

    @Test
    fun aSmallCodeInABigScreenshotLikeImage() {
        // A phone screenshot: 1080 x 2400, the code 200 px wide among text-like clutter.
        val m = matrix(QrCode.encode("dystopo://share?addr=10.0.2.16:8841&code=482913"))
        val w = 1080
        val h = 2400
        val scale = 200.0 / m.size
        val img = render(m, w, h, { px, py -> (px - 440) / scale + 4 to (py - 900) / scale + 4 }, dark = 0, light = 255)
        // Clutter: dark bars like lines of text above and below.
        val rnd = Random(3)
        for (line in 0 until 30) {
            val y0 = if (line < 15) 200 + line * 40 else 1250 + (line - 15) * 40
            var x = 60
            while (x < 1000) {
                val len = rnd.nextInt(10, 60)
                for (y in y0 until y0 + 18) for (xx in x until minOf(w, x + len)) img[y * w + xx] = 30
                x += len + rnd.nextInt(8, 20)
            }
        }
        val r = QrDecoder.decode(img, w, h)
        assertNotNull(r, "${QrDecoder.lastMiss}")
        assertEquals("dystopo://share?addr=10.0.2.16:8841&code=482913", r.text)
    }

    @Test
    fun noCodeNoResult() {
        val rnd = Random(5)
        val img = IntArray(320 * 240) { rnd.nextInt(256) }
        assertNull(QrDecoder.decode(img, 320, 240))
        assertNull(QrDecoder.decode(IntArray(100 * 100) { 255 }, 100, 100))
    }

    @Test
    fun shareLinksParse() {
        assertEquals("10.0.2.2:8841" to "123456", ShareLink.parse("dystopo://share?addr=10.0.2.2:8841&code=123456"))
        assertEquals("192.168.1.5:8841" to "000001", ShareLink.parse("dystopo://share?addr=192.168.1.5&code=000001"))
        assertNull(ShareLink.parse("dystopo://share?addr=10.0.2.2:8841&code=12345"))
        assertNull(ShareLink.parse("dystopo://share?code=123456"))
        assertNull(ShareLink.parse("dystopo://map?lat=1&lon=2"))
        assertNull(ShareLink.parse("http://10.0.2.2:8841/?code=123456"))
        assertNull(ShareLink.parse("dystopo://share?addr=evil.example/x?y&code=123456"))
    }
}
