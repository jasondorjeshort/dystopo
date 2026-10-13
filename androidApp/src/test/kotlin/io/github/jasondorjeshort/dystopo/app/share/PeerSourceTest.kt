package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.packs.PackDownload
import io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T78: the peer-source routes behind the share gate (only sealed, under the code; plain 426) and a nearby phone's
 * archive read in pieces as the pack downloader sees it (ranged answer, resume, a piece that does not follow).
 */
class PeerSourceTest {
    private val code = "7K3MQW2D9XHPT4CA"

    private fun sealed(gate: ShareGate, c: String, method: String, target: String): SealedLink.Response? {
        val frame = SealedLink.sealRequest(c, SealedLink.Request(method, target, ByteArray(0)), ShareGate::randomBytes)
        val r = gate.handle(Request("POST", ShareGate.SEALED_PATH, emptyMap(), frame, loopback = false))
        return if (r.status == 200) SealedLink.openResponse(c, frame, r.body) else null.also { assertEquals(403, r.status) }
    }

    @Test
    fun peerRoutesOnlyThroughTheSealedLink() {
        val seen = ArrayList<Request>()
        val gate = ShareGate(hello = { mapOf("name" to "A") }, sync = { null }, serve = { r ->
            seen += r
            if (r.path == "/peer/catalog") Response.json(mapOf("version" to 1, "packs" to emptyList<Any>())) else null
        }, newCode = { code })
        val ok = sealed(gate, code, "GET", "/peer/catalog?by=bbbbbbbb")!!
        assertEquals(200, ok.status)
        assertTrue(ok.body.decodeToString().contains("\"packs\""))
        assertEquals("bbbbbbbb", seen.single().query["by"])
        assertEquals(false, seen.single().loopback)
        // Not served by the peer routes: a sealed 404, as before.
        assertEquals(404, sealed(gate, code, "GET", "/tracks")!!.status)
        // A wrong code reaches nothing; plain requests for the peer routes are refused like the sync routes.
        assertEquals(null, sealed(gate, "7K3MQW2D9XHPT4CB", "GET", "/peer/catalog"))
        assertEquals(2, seen.size)
        assertEquals(426, gate.handle(Request("GET", "/peer/catalog", emptyMap(), ByteArray(0), loopback = false)).status)
        assertEquals(426, gate.handle(Request("GET", "/peer/file/rainier-dem.pmtiles", mapOf("from" to "0"), ByteArray(0), loopback = false)).status)
        assertEquals(2L, gate.plainRefused)
        assertEquals(1L, gate.refused)
        // Without peer routes (a phone sharing sync only) the same request is a sealed 404.
        val syncOnly = ShareGate(hello = { emptyMap() }, sync = { null }, newCode = { code })
        assertEquals(404, sealed(syncOnly, code, "GET", "/peer/catalog")!!.status)
    }

    /** A stand-in for [PeerServe]'s file route over [file]. */
    private fun server(file: ByteArray, chunk: Int, calls: MutableList<Long>): (Long, Int) -> PeerFiles.Piece = { from, len ->
        assertEquals(chunk, len, "the reader asks for the piece size it was opened with")
        synchronized(calls) { calls += from }
        if (from >= file.size) PeerFiles.Piece(416, from, file.size.toLong(), ByteArray(0))
        else PeerFiles.Piece(206, from, file.size.toLong(), file.copyOfRange(from.toInt(), minOf(file.size, from.toInt() + chunk)))
    }

    @Test
    fun archiveInPiecesReadsLikeARangedDownload() {
        val file = Random(78).nextBytes(10_000_003)
        val calls = ArrayList<Long>()
        val conn = PeerFiles.openWith(0, PeerFiles.CHUNK, server(file, PeerFiles.CHUNK, calls))
        assertEquals(206, conn.status)
        assertEquals("bytes 0-10000002/10000003", conn.header("Content-Range"))
        assertEquals(10_000_003L, conn.contentLength)
        assertEquals(PackDownload.Plan.Append(10_000_003L), PackDownload.plan(0, conn.status, conn.header("Content-Range"), conn.contentLength))
        val out = ByteArrayOutputStream()
        conn.body().use { it.copyTo(out, 65536) }
        assertContentEquals(file, out.toByteArray())
        assertEquals(listOf(0L, PeerFiles.CHUNK.toLong(), 2L * PeerFiles.CHUNK), calls.sorted())

        // Resumed from a part file: the plan appends from there.
        val resumed = PeerFiles.openWith(6_500_000, PeerFiles.CHUNK, server(file, PeerFiles.CHUNK, ArrayList()))
        assertEquals(PackDownload.Plan.Append(10_000_003L), PackDownload.plan(6_500_000, resumed.status, resumed.header("Content-Range"), resumed.contentLength, 10_000_003L))
        assertContentEquals(file.copyOfRange(6_500_000, file.size), resumed.body().readBytes())

        // Already complete: 416 with the size, which the plan reads as complete.
        val done = PeerFiles.openWith(file.size.toLong(), PeerFiles.CHUNK, server(file, PeerFiles.CHUNK, ArrayList()))

        // After a cut reply the peer's piece size is halved: the same file in 256 KiB pieces.
        val small = ArrayList<Long>()
        val again = PeerFiles.openWith(0, PeerFiles.MIN_CHUNK, server(file, PeerFiles.MIN_CHUNK, small))
        assertContentEquals(file, again.body().readBytes())
        assertEquals((file.size + PeerFiles.MIN_CHUNK - 1) / PeerFiles.MIN_CHUNK, small.size)
        assertEquals(PackDownload.Plan.Complete, PackDownload.plan(file.size.toLong(), done.status, done.header("Content-Range"), done.contentLength, file.size.toLong()))
    }

    @Test
    fun aPieceThatDoesNotFollowFailsTheRead() {
        val file = Random(1).nextBytes(PeerFiles.CHUNK + 9_000)
        var n = 0
        val conn = PeerFiles.openWith(0) { from, _ ->
            n++
            if (n == 1) PeerFiles.Piece(206, from, file.size.toLong(), file.copyOfRange(0, PeerFiles.CHUNK))
            else PeerFiles.Piece(206, from + 10, file.size.toLong(), file.copyOfRange(from.toInt() + 10, file.size)) // the other phone skipped bytes
        }
        assertEquals(206, conn.status)
        assertFailsWith<IOException> { conn.body().readBytes() }
    }
}
