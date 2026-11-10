package io.github.jasondorjeshort.dystopo.sources.elevation

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T269: the range-block cache's validator ([CachedRangeSource], [RangeCell]) against a fake server whose file is
 * republished: a 412 on If-Match, a cell cached before T269, the daily HEAD, offline, and a client that reports no
 * headers (the phone's), where a decode error forces the recheck.
 */
class RangeCacheValidationTest {
    /** A server for one file: [file] with [etag] and [lastModified]; [headers] false is a client that reports none. */
    private class FakeServer(var file: ByteArray, var etag: String, var lastModified: String) : SourceHttp {
        var headers = true
        var offline = false
        var heads = 0
        var ranges = 0

        private fun headersFor(from: Long, to: Long): Map<String, String> = if (!headers) emptyMap() else mapOf(
            "etag" to etag, "last-modified" to lastModified, "content-range" to "bytes $from-$to/${file.size}",
        )

        override fun range(url: String, offset: Long, length: Int): HttpReply {
            if (offline) throw IOException("offline")
            ranges++
            val end = minOf(file.size.toLong(), offset + length).toInt()
            return HttpReply(206, file.copyOfRange(offset.toInt(), end), headersFor(offset, end - 1L))
        }

        override fun range(url: String, offset: Long, length: Int, ifMatch: String): HttpReply {
            if (headers && ifMatch != etag) {
                if (offline) throw IOException("offline")
                ranges++
                return HttpReply(412, ByteArray(0))
            }
            return range(url, offset, length)
        }

        override fun get(url: String, timeoutMs: Long): HttpReply = error("not used")

        override fun head(url: String, timeoutMs: Long): HttpReply? {
            if (!headers) return null
            if (offline) throw IOException("offline")
            heads++
            return HttpReply(200, ByteArray(0), mapOf("etag" to etag, "last-modified" to lastModified, "content-length" to "${file.size}"))
        }
    }

    private val url = "https://example.test/dem.tif"
    private lateinit var dir: File
    private lateinit var server: FakeServer
    private var before: SourceHttp? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("t269").toFile()
        server = FakeServer(tiff(version = 0), "\"a\"", "Mon, 01 Jan 2024 00:00:00 GMT")
        before = SourceIo.http
        SourceIo.http = server
        RangeValidation.forgetCells()
    }

    @AfterTest
    fun tearDown() {
        RangeValidation.awaitChecks(5_000)
        SourceIo.http = before!!
        RangeValidation.forgetCells()
        dir.deleteRecursively()
    }

    private val cache get() = dir.path.replace('\\', '/')
    private fun validator() = File(dir, "validator.json").takeIf { it.isFile }?.readBytes()?.let { Validator.parse(it) }
    private fun blocks() = dir.list()!!.filter { it.endsWith(".bin") }.sorted()

    /** A new process: no cell remembered. */
    private fun restart() {
        RangeValidation.awaitChecks(5_000)
        RangeValidation.forgetCells()
    }

    private fun republish() {
        server.file = tiff(version = 1)
        server.etag = "\"b\""
        server.lastModified = "Tue, 01 Oct 2024 00:00:00 GMT"
    }

    private fun counters() = RangeValidation.json()

    private fun delta(before: Map<String, Long>, key: String) = counters().getValue(key) - before.getValue(key)

    @Test
    fun aRepublishedFileAnswers412AndTheCellIsDroppedAndRefetched() {
        assertEquals(1f, RemoteTiff(url, cache).at(0, 0, 0))
        assertEquals("\"a\"", validator()?.etag)
        republish()
        restart()
        val c0 = counters()
        val tiff = RemoteTiff(url, cache) // the cached header: version 0's offsets
        assertEquals(103f, tiff.at(0, 2, 0)) // tile 1 is not cached: If-Match "a" answers 412, the cell drops, the file reopens
        assertEquals(1, delta(c0, "ifMatch412"))
        assertEquals(1, delta(c0, "cellsDropped"))
        assertEquals(1L, tiff.source.ifMatch412)
        assertEquals("\"b\"", validator()?.etag)
        assertEquals(101f, tiff.at(0, 0, 0)) // tile 0 too comes from the new file, not the old block
        assertEquals(blocksOf(tiff(version = 1)), blocks())
    }

    @Test
    fun aCellCachedBeforeT269IsDroppedWhenTheFileIsNewerThanItsOldestBlock() {
        assertEquals(1f, RemoteTiff(url, cache).at(0, 0, 0))
        File(dir, "validator.json").delete()
        dir.listFiles()!!.forEach { it.setLastModified(ms("Fri, 01 Mar 2024 00:00:00 GMT")) }
        republish()
        restart()
        val c0 = counters()
        RemoteTiff(url, cache).at(0, 0, 0) // served from the cache; the check runs behind it
        assertTrue(RangeValidation.awaitChecks(5_000))
        assertEquals(1, server.heads)
        assertEquals(1, delta(c0, "cellsChecked"))
        assertEquals(1, delta(c0, "cellsDropped"))
        assertEquals("\"b\"", validator()?.etag)
        assertOnlyBlocksOf(tiff(version = 1)) // the read behind the check may have refetched some already
        assertEquals(101f, RemoteTiff(url, cache).at(0, 0, 0))
    }

    @Test
    fun aCellCachedBeforeT269AdoptsTheValidatorWhenItsBlocksAreNewerAndIsNotCheckedAgain() {
        assertEquals(1f, RemoteTiff(url, cache).at(0, 0, 0))
        File(dir, "validator.json").delete()
        restart()
        val c0 = counters()
        val kept = blocks()
        RemoteTiff(url, cache).at(0, 0, 0)
        assertTrue(RangeValidation.awaitChecks(5_000))
        assertEquals(1, delta(c0, "cellsChecked"))
        assertEquals(0, delta(c0, "cellsDropped"))
        assertEquals(kept, blocks())
        assertEquals("\"a\"", validator()?.etag)
        restart() // the next run: the validator is a day from due, so no request at all
        val heads = server.heads
        val ranges = server.ranges
        assertEquals(1f, RemoteTiff(url, cache).at(0, 0, 0))
        assertTrue(RangeValidation.awaitChecks(5_000))
        assertEquals(heads, server.heads)
        assertEquals(ranges, server.ranges)
    }

    @Test
    fun aDayOldCheckGetsOneHeadPerProcessAndAMismatchDropsTheCell() {
        assertEquals(1f, RemoteTiff(url, cache).at(0, 0, 0))
        backdate(days = 2)
        restart()
        RemoteTiff(url, cache).at(0, 0, 0)
        RemoteTiff(url, cache).at(0, 0, 0)
        assertTrue(RangeValidation.awaitChecks(5_000))
        assertEquals(1, server.heads)
        assertTrue(System.currentTimeMillis() - validator()!!.checkedAt < 60_000)
        // A wrong stored validator (as if the file changed) is dropped by the next due check.
        File(dir, "validator.json").writeText("""{"etag":"\"wrong\"","checkedAt":0}""")
        restart()
        val c0 = counters()
        RemoteTiff(url, cache).at(0, 0, 0)
        assertTrue(RangeValidation.awaitChecks(5_000))
        assertEquals(1, delta(c0, "cellsDropped"))
        assertEquals("\"a\"", validator()?.etag)
        assertEquals(1f, RemoteTiff(url, cache).at(0, 0, 0))
        assertFalse(blocks().isEmpty())
    }

    @Test
    fun offlineTheCacheIsServedAndTheCheckTimeDoesNotAdvance() {
        assertEquals(1f, RemoteTiff(url, cache).at(0, 0, 0))
        backdate(days = 2)
        val stamp = validator()!!.checkedAt
        server.offline = true
        restart()
        val c0 = counters()
        assertEquals(1f, RemoteTiff(url, cache).at(0, 0, 0))
        assertTrue(RangeValidation.awaitChecks(5_000))
        assertEquals(1, delta(c0, "checkFailures"))
        assertEquals(0, delta(c0, "cellsDropped"))
        assertEquals(stamp, validator()!!.checkedAt)
    }

    @Test
    fun withoutHeadersADecodeErrorComparesTheHeaderBlockDropsTheCellAndReadsAgain() {
        server.headers = false // the phone's client: no headers, no HEAD, no If-Match
        assertEquals(1f, RemoteTiff(url, cache).at(0, 0, 0))
        val stored = validator()!!
        assertNull(stored.etag)
        republish()
        restart()
        val c0 = counters()
        val tiff = RemoteTiff(url, cache)
        // Tile 1 at version 0's offset is garbage in version 1: the inflater fails, the recheck finds the header block changed.
        assertEquals(103f, tiff.at(0, 2, 0))
        assertEquals(1, delta(c0, "cellsChecked"))
        assertEquals(1, delta(c0, "cellsDropped"))
        assertEquals(101f, tiff.at(0, 0, 0))
    }

    @Test
    fun theValidatorRules() {
        val a = Validator("\"a\"", "Mon, 01 Jan 2024 00:00:00 GMT", 10, 0)
        assertEquals(false, a.sameFile(Validator("\"b\"", a.lastModified, 10, 0)))
        assertEquals(true, a.sameFile(Validator(null, a.lastModified, 10, 0)))
        assertEquals(false, a.sameFile(Validator(null, a.lastModified, 11, 0)))
        assertEquals(false, a.sameFile(Validator(null, null, 11, 0)))
        assertNull(a.sameFile(Validator(null, null, -1, 0)))
        val back = Validator.parse(a.json())!!
        assertEquals(a.etag, back.etag)
        assertEquals(a.length, back.length)
        val of = Validator.of(HttpReply(206, ByteArray(4), mapOf("content-range" to "bytes 0-3/455225246", "etag" to "W/\"x\"")), 5)
        assertEquals(455225246L, of.length)
        assertEquals("W/\"x\"", of.etag)
        for (date in listOf("Wed, 13 Aug 2025 20:44:12 GMT", "Tue, 29 Feb 2000 23:59:59 GMT", "Thu, 01 Jan 1970 00:00:00 GMT")) {
            assertEquals(ms(date), Validator.httpDateMs(date), date)
        }
        assertNull(Validator.httpDateMs("yesterday"))
        assertNotEquals(ms("Mon, 01 Jan 2024 00:00:00 GMT"), ms("Tue, 01 Oct 2024 00:00:00 GMT"))
    }

    /** Every cached block holds [file]'s bytes at its range: nothing of another version is left. */
    private fun assertOnlyBlocksOf(file: ByteArray) {
        for (name in blocks()) {
            val (offset, length) = name.removeSuffix(".bin").split('-').map { it.toInt() }
            val expected = file.copyOfRange(offset, minOf(file.size, offset + length))
            assertTrue(File(dir, name).readBytes().contentEquals(expected), name)
        }
    }

    private fun backdate(days: Int) {
        val v = validator()!!
        File(dir, "validator.json").writeBytes(Validator(v.etag, v.lastModified, v.length, System.currentTimeMillis() - days * 86_400_000L).json())
    }

    private fun ms(date: String) = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()

    /** The block files a full read of [file] leaves: the header block and the two tiles. */
    private fun blocksOf(file: ByteArray): List<String> {
        val layout = layout(file)
        return (listOf("0-65536.bin") + layout.map { (o, n) -> "$o-$n.bin" }).sorted()
    }

    private val tileLayouts = HashMap<Int, List<Pair<Long, Int>>>()

    private fun layout(file: ByteArray) = tileLayouts.getValue(if (file.contentEquals(tiff(0))) 0 else 1)

    /**
     * A 4x2 float32 GeoTIFF in two 2x2 deflate tiles: version 0 holds 1..8, version 1 holds 101..108 behind 1000 bytes of
     * padding, so version 0's tile offsets point into other bytes of version 1.
     */
    private fun tiff(version: Int): ByteArray {
        val image = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f).map { it + 100f * version }
        val tiles = listOf(listOf(image[0], image[1], image[4], image[5]), listOf(image[2], image[3], image[6], image[7])).map { deflate(it) }
        val out = Le()
        val tags = 15
        val ifdEnd = 8 + 2 + tags * 12 + 4
        var cursor = ifdEnd
        val data = Le()
        fun place(bytes: ByteArray): Long = cursor.toLong().also { data.bytes(bytes); cursor += bytes.size }
        if (version == 1) place(ByteArray(1000) { 0x55 })
        val t0 = place(tiles[0])
        val t1 = place(tiles[1])
        tileLayouts[version] = listOf(t0 to tiles[0].size, t1 to tiles[1].size)
        val offsetsAt = place(Le().apply { u32(t0); u32(t1) }.toByteArray())
        val countsAt = place(Le().apply { u32(tiles[0].size.toLong()); u32(tiles[1].size.toLong()) }.toByteArray())
        val scaleAt = place(Le().apply { f64(0.25); f64(0.25); f64(0.0) }.toByteArray())
        val tieAt = place(Le().apply { f64(0.0); f64(0.0); f64(0.0); f64(-122.0); f64(47.0); f64(0.0) }.toByteArray())
        val keysAt = place(Le().apply { listOf(1, 1, 0, 1, 1025, 0, 1, 1).forEach { u16(it) } }.toByteArray())
        out.bytes("II".encodeToByteArray()); out.u16(42); out.u32(8)
        out.u16(tags)
        fun short(tag: Int, v: Int) { out.u16(tag); out.u16(3); out.u32(1); out.u16(v); out.u16(0) }
        fun ref(tag: Int, type: Int, count: Long, at: Long) { out.u16(tag); out.u16(type); out.u32(count); out.u32(at) }
        short(256, 4); short(257, 2); short(258, 32); short(259, 8); short(277, 1); short(284, 1); short(317, 1)
        short(322, 2); short(323, 2)
        ref(324, 4, 2, offsetsAt); ref(325, 4, 2, countsAt)
        short(339, 3)
        ref(33550, 12, 3, scaleAt); ref(33922, 12, 6, tieAt)
        ref(34735, 3, 8, keysAt)
        out.u32(0)
        check(out.size == ifdEnd)
        out.bytes(data.toByteArray())
        return out.toByteArray()
    }

    private fun deflate(values: List<Float>): ByteArray {
        val raw = Le().apply { values.forEach { u32(it.toRawBits().toLong() and 0xFFFFFFFFL) } }.toByteArray()
        val d = Deflater()
        d.setInput(raw)
        d.finish()
        val buf = ByteArray(256)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    private class Le {
        private val buf = java.io.ByteArrayOutputStream()
        val size get() = buf.size()
        fun u16(v: Int) { buf.write(v and 0xFF); buf.write((v shr 8) and 0xFF) }
        fun u32(v: Long) { for (i in 0 until 4) buf.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun f64(v: Double) { val b = v.toRawBits(); for (i in 0 until 8) buf.write(((b shr (8 * i)) and 0xFF).toInt()) }
        fun bytes(b: ByteArray) = buf.write(b)
        fun toByteArray(): ByteArray = buf.toByteArray()
    }
}
