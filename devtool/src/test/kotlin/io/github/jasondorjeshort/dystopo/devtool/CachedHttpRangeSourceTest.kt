package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CachedHttpRangeSourceTest {
    private val dir: File = Files.createTempDirectory("cached-range-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /**
     * Many threads read the same cold range at once (parallel tile renders do this). Each fake fetch
     * returns different bytes and they all finish together, so every thread races to move its
     * `.part` file onto the same cache file. Nobody may fail, the first writer's bytes stay on disk,
     * and every caller gets exactly those bytes.
     */
    @Test
    fun concurrentColdReadsOfOneRangeAgree() {
        val threads = 16
        repeat(20) { round ->
            val roundDir = File(dir, "round$round")
            val barrier = CyclicBarrier(threads)
            val calls = AtomicInteger()
            val source = CachedHttpRangeSource("http://example.invalid/x.tif", roundDir) { _, _, length ->
                val n = calls.incrementAndGet()
                barrier.await() // everyone has missed the cache before anyone writes it
                ByteArray(length) { n.toByte() }
            }
            val results = Collections.synchronizedList(ArrayList<ByteArray>())
            val errors = Collections.synchronizedList(ArrayList<Throwable>())
            val workers = (0 until threads).map {
                thread {
                    try {
                        results += source.read(1000, 64)
                    } catch (e: Throwable) {
                        errors += e
                    }
                }
            }
            workers.forEach { it.join() }
            assertEquals(emptyList(), errors.map { "${it::class.simpleName}: ${it.message}" }, "round $round")
            assertEquals(threads, calls.get())
            val onDisk = File(roundDir, "1000-64.bin").readBytes()
            for (r in results) assertContentEquals(onDisk, r, "every caller returns the cached bytes")
            assertTrue(roundDir.listFiles()!!.none { it.name.endsWith(".part") }, "no temp files left behind")
            // Warm read: from disk, no fetch.
            assertContentEquals(onDisk, source.read(1000, 64))
            assertEquals(threads, calls.get())
        }
    }
}
