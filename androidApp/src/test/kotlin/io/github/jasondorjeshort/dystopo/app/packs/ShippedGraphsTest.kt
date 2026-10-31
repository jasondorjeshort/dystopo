package io.github.jasondorjeshort.dystopo.app.packs

import io.github.jasondorjeshort.dystopo.app.packs.ShippedGraphs.WaitRule
import io.github.jasondorjeshort.dystopo.app.packs.ShippedGraphs.WaitRule.Next
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T189 (ADR 0049's B): the planner's wait for a shipped graph, and the graph's files on the device. */
class ShippedGraphsTest {
    private val dir: File = Files.createTempDirectory("shipped-graphs").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val stall = 120_000L

    @Test
    fun waitsWhileTheDownloadProgressesAndBuildsWhenItStallsOrEnds() {
        // Queued behind another download, or arriving: wait.
        assertEquals(Next.WAIT, WaitRule.next("pending", "queued", 0, stall))
        assertEquals(Next.WAIT, WaitRule.next("downloading", "running", 119_999, stall))
        assertEquals(Next.WAIT, WaitRule.next("verifying", "running", 10, stall))
        // In place: take it, whatever the job says.
        assertEquals(Next.TAKE, WaitRule.next("done", "done", 0, stall))
        assertEquals(Next.TAKE, WaitRule.next("done", "running", 0, stall))
        // No new bytes for the whole wait: build.
        assertEquals(Next.BUILD, WaitRule.next("downloading", "running", stall, stall))
        assertEquals(Next.BUILD, WaitRule.next("pending", "queued", stall + 1, stall))
        // Not taken (a mismatch, a wrong hash, a 404) or the job ended without it: build at once.
        for (s in listOf("skipped", "failed", "missing")) assertEquals(Next.BUILD, WaitRule.next(s, "running", 0, stall), s)
        assertEquals(Next.BUILD, WaitRule.next("downloading", "failed", 0, stall))
        assertEquals(Next.BUILD, WaitRule.next("pending", "cancelled", 0, stall))
        assertEquals(Next.BUILD, WaitRule.next(null, "running", 0, stall), "a job without a graph")
    }

    @Test
    fun graphFilesAreKnownByHeaderAndLeftoversGoWithThePack() {
        val graph = File(dir, "imported.idx").apply { writeBytes(byteArrayOf(0x44, 0x59, 0x53, 0x47, 6, 0, 0, 0, 4)) }
        val archive = File(dir, "x-dem.pmtiles").apply { writeBytes("PMTiles".toByteArray() + ByteArray(120)) }
        assertTrue(PackManager.isGraphFile(graph))
        assertFalse(PackManager.isGraphFile(archive))
        assertFalse(PackManager.isGraphFile(File(dir, "missing")))

        val vector = File(dir, "x-outdoors.pmtiles").apply { writeBytes(ByteArray(8)) }
        assertEquals(emptyList(), PackManager.graphLeftovers(vector))
        val part = File(dir, "x-outdoors-graph.idx.part").apply { writeBytes(ByteArray(3)) }
        val tmp = File(dir, "x-outdoors-graph.idx.tmp").apply { writeBytes(ByteArray(3)) }
        assertEquals(setOf(part.name, tmp.name), PackManager.graphLeftovers(vector).map { it.name }.toSet())
    }
}
