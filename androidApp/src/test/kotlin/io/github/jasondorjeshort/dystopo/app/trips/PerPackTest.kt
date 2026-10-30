package io.github.jasondorjeshort.dystopo.app.trips

import io.github.jasondorjeshort.dystopo.app.Pack
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import java.io.File
import java.nio.channels.ClosedChannelException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * T175: the crow route's DEM sampler ([io.github.jasondorjeshort.dystopo.app.trips.Trips.demSampler]) must follow a pack
 * reload. The sampler was cached by the pack's file path, so after a reload (the same file opened as a new [Pack], the old
 * one closed) it read the closed archive and `POST /route?mode=crow` answered 500 `ClosedChannelException`.
 */
class PerPackTest {
    private val dir: File = Files.createTempDirectory("perpack").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** Larger than the head the reader keeps in memory, so a tile read goes to the file. */
    private val tileBytes = ByteArray(64 * 1024) { (it % 251).toByte() }

    /** A one-tile archive, `rainier-dem.pmtiles` by name only. */
    private fun archive(): File {
        val w = PmtilesWriter(TileType.PNG)
        w.addTile(10, 164, 357, tileBytes)
        val f = File(dir, "rainier-dem.pmtiles")
        f.outputStream().use { out -> w.finish(ByteSink { out.write(it) }) }
        return f
    }

    @Test
    fun aReopenedPackGetsANewValueAndTheOldClosedOneIsNeverReadAgain() {
        val file = archive()
        val cache = PerPack { p -> { p.tile(10, 164, 357) } } // what Trips makes: a reader over the pack's tiles
        val first = Pack("dem", file)
        val read1 = cache.get(first)!!
        assertContentEquals(tileBytes, read1())
        assertSame(read1, cache.get(first), "the same pack keeps its value")

        // DystopoApp.reload: the same file opened again, the old pack closed (its last reader released it).
        val second = Pack("dem", file)
        first.close()
        assertFailsWith<ClosedChannelException> { read1() } // what the path-keyed cache handed out after a reload
        val read2 = cache.get(second)!!
        assertNotSame(read1, read2)
        assertContentEquals(tileBytes, read2(), "the reopened pack is read, no ClosedChannelException")
        second.close()
    }

    @Test
    fun noPackNoValue() {
        assertNull(PerPack<String> { "x" }.get(null))
    }
}
