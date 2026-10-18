package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.parse
import io.github.jasondorjeshort.dystopo.model.RouteEditor
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.SavedDirections
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.server.Gzip
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T116: `sync-sim --bundle` carries a browser-saved trip (a `.dsync` file: a gzipped bundle of route ops with its
 * `directions`) from the first peer to every other one over the simulated network, into SQL stores shaped like the
 * phone's, and its report says each peer holds the trip with the directions saved for its path; a peer that holds the
 * path without them fails the check.
 */
class SyncSimBundleTest {
    private val dir: File = Files.createTempDirectory("sync-sim-bundle").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val id = "0b6f1c2e-1d2a-4c5b-9e8f-112233445566"

    /** A browser's trip: a path through three points (straight legs: no graph here) with its directions, as ops of one device. */
    private fun browserTrip(): Pair<File, RoutePlan> {
        val browser = InMemoryTrackStore("5f0e6a52-3a6b-4b8e-9a51-0c1d2e3f4a5b") { 1_790_000_000_000L }
        val ed = RouteEditor(null)
        val path = ed.create(listOf(46.7864 to -121.7366, 46.7960 to -121.7262, 46.8024 to -121.7289))
        val plan = RoutePlan(id, "Panorama Point", path, type = path.mode, directions = SavedDirections.of(ed.directions(path), path))
        browser.putRoutePlan(plan, 1_790_000_000_000L)
        val f = File(dir, "trips.dsync")
        f.writeBytes(Gzip.compress(browser.bundle().encode()))
        return f to plan
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(vararg args: String): Map<String, Any?> {
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf, true, "UTF-8"))
        try {
            SyncSimCommand().parse(args.toList())
        } finally {
            System.setOut(old)
        }
        return MiniJson.parseObject(buf.toString("UTF-8"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aBrowserTripReachesEveryPeerWithItsDirections() {
        val (file, plan) = browserTrip()
        val saved = plan.savedDirections!!
        assertTrue(saved.size >= 3, saved.toString()) // the frame: each straight leg's start (the via point's) and the arrival
        for (store in listOf("sql", "memory")) {
            val r = run("--bundle", file.path, "--peers", "3", "--ops", "0", "--partitions", "0", "--store", store, "--reorder")
            assertEquals(true, r["converged"], "$store: $r")
            val b = r["bundle"] as Map<String, Any?>
            assertEquals(true, b["ok"], "$store: $b")
            assertEquals(1, (b["trips"] as Number).toInt())
            val trip = (b["list"] as List<Map<String, Any?>>).single()
            assertEquals(saved.size, (trip["directions"] as Number).toInt())
            val peers = trip["peers"] as List<Map<String, Any?>>
            assertEquals(3, peers.size)
            assertTrue(peers.all { it["samePath"] == true && it["sameDirections"] == true && (it["directions"] as Number).toInt() == saved.size }, "$store: $peers")
        }
        // With the simulator's own changes racing: still converged, the trip still checked.
        val busy = run("--bundle", file.path, "--peers", "3", "--ops", "300", "--store", "sql", "--seed", "7")
        assertEquals(true, busy["converged"], busy.toString())
        assertEquals(true, (busy["bundle"] as Map<String, Any?>)["ok"])
    }

    @Test
    fun aPeerWithThePathButNotItsDirectionsFails() {
        val (file, plan) = browserTrip()
        val bundle = SyncSimCommand.BundleCheck.read(file.readBytes())
        val good = InMemoryTrackStore("00000000-0000-4000-8000-0000000000a1").also { it.apply(bundle, 0) }
        val bare = InMemoryTrackStore("00000000-0000-4000-8000-0000000000b2").also { it.putRoutePlan(plan.copy(directions = null), 0) }
        assertEquals(true, SyncSimCommand.BundleCheck.json(file.path, bundle, listOf(good))["ok"])
        assertEquals(false, SyncSimCommand.BundleCheck.json(file.path, bundle, listOf(good, bare))["ok"])
        assertEquals(false, SyncSimCommand.BundleCheck.json(file.path, bundle, listOf(InMemoryTrackStore()))["ok"]) // reached no peer
    }
}
