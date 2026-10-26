package io.github.jasondorjeshort.dystopo.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T144: the tile service is closed by reference counting with a deferred close ([RefCounted]): a reload drops the owner's
 * reference and the last reader closes the old packs; every acquiring path of the loopback server ([LocalServer.leased]
 * around the handler, [Leases]) releases on every exit.
 */
class TileServiceRefCountTest {
    /** A stand-in for a tile service: its references and whether its packs closed. */
    private class Service(val name: String) {
        val closes = AtomicInteger()
        val refs = RefCounted { closes.incrementAndGet() }
    }

    @Test
    fun theOldServiceClosesOnlyAfterItsLastReaderReleases() {
        val old = Service("old")
        assertTrue(old.refs.acquire(), "reader 1")
        assertTrue(old.refs.acquire(), "reader 2")
        assertTrue(old.refs.releaseOwner(), "readers were still in it: the close is deferred")
        assertEquals(0, old.closes.get(), "not closed under a read")
        assertTrue(old.refs.isOpen)
        old.refs.release()
        assertEquals(0, old.closes.get(), "one reader still reads")
        old.refs.release()
        assertEquals(1, old.closes.get(), "the last reader closed it")
        assertFalse(old.refs.acquire(), "a closed service is never handed out again")
        assertFalse(old.refs.releaseOwner(), "the owner's reference goes once")
        assertEquals(1, old.closes.get())
        assertFailsWith<IllegalStateException> { old.refs.release() }
    }

    @Test
    fun withNoReaderTheOwnerClosesAtOnce() {
        val s = Service("s")
        assertFalse(s.refs.releaseOwner())
        assertEquals(1, s.closes.get())
        assertEquals(0, s.refs.refs)
    }

    @Test
    fun aReaderOnAnotherThreadKeepsTheOldServiceOpenAcrossAReload() {
        val published = AtomicReference(Service("first"))
        val leases = Leases({ published.get() }, { it.refs.acquire() }, { it.refs.release() })
        val reading = CountDownLatch(1)
        val reloaded = CountDownLatch(1)
        val seen = AtomicReference<String>()
        val reader = thread {
            leases.lease().use {
                reading.countDown()
                reloaded.await(5, TimeUnit.SECONDS)
                seen.set(leases.held()!!.name) // the request keeps reading the service it started with
            }
        }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        val old = published.get()
        published.set(Service("second")) // reload: publish, then drop the owner's reference
        old.refs.releaseOwner()
        assertEquals(0, old.closes.get(), "open while the request reads it")
        reloaded.countDown()
        reader.join(5_000)
        assertEquals("first", seen.get())
        assertEquals(1, old.closes.get(), "closed by the request's release")
        assertEquals(1, published.get().refs.refs, "the new one holds only the owner's reference")
    }

    @Test
    fun aLeaseTakesTheNewServiceWhenTheOneItReadClosedInBetween() {
        val first = Service("first")
        val second = Service("second")
        val reads = AtomicInteger()
        // The first read of `current` returns the old service, which closes before the acquire: the lease reads again.
        val leases = Leases({ if (reads.getAndIncrement() == 0) first.also { it.refs.releaseOwner() } else second }, { it.refs.acquire() }, { it.refs.release() })
        leases.lease().use { assertSame(second, leases.held()) }
        assertEquals(1, second.refs.refs)
        assertNull(leases.held())
    }

    @Test
    fun aRequestThatReloadsReadsTheNewServiceAndReleasesBoth() {
        val published = AtomicReference(Service("first"))
        val leases = Leases({ published.get() }, { it.refs.acquire() }, { it.refs.release() })
        val old = published.get()
        leases.lease().use {
            val next = Service("second")
            published.set(next)
            leases.swap(next) // what DystopoApp.reload does on a thread holding a lease
            old.refs.releaseOwner()
            assertSame(next, leases.held())
            assertEquals(1, old.closes.get(), "the swap released the old one")
            leases.lease().use { assertSame(next, leases.held(), "a nested lease shares the hold") }
            assertEquals(2, next.refs.refs)
        }
        assertEquals(1, published.get().refs.refs)
        assertEquals(0, published.get().closes.get())
    }

    /** Every exit of the server's handler call releases the lease: a response, an exception, an error; a failed acquire releases nothing. */
    @Test
    fun everyAcquiringPathOfTheServerReleasesOnEveryExit() {
        val s = Service("s")
        val leases = Leases({ s }, { it.refs.acquire() }, { it.refs.release() })
        val lease = { leases.lease() }
        val ok = LocalServer.leased(lease) {
            assertEquals(2, s.refs.refs, "held while the handler runs")
            Response.text("ok")
        }
        assertEquals(200, ok.status)
        assertEquals(1, s.refs.refs)
        assertFailsWith<IllegalStateException> { LocalServer.leased(lease) { throw IllegalStateException("handler failed") } }
        assertEquals(1, s.refs.refs, "released after an exception")
        assertFailsWith<OutOfMemoryError> { LocalServer.leased(lease) { throw OutOfMemoryError("heap") } }
        assertEquals(1, s.refs.refs, "released after an error")
        assertNull(leases.held())
        val released = AtomicInteger()
        assertFailsWith<RuntimeException> {
            LocalServer.leased({ throw RuntimeException("no service") }) { released.incrementAndGet(); Response.text("never") }
        }
        assertEquals(0, released.get(), "a lease that failed runs nothing")
        assertEquals(1, s.refs.refs)
        assertEquals(0, s.closes.get())
    }

    @Test
    fun manyReadersAndReloadsLeaveOneServiceOpen() {
        val all = java.util.concurrent.ConcurrentLinkedQueue<Service>()
        val published = AtomicReference(Service("0").also { all += it })
        val leases = Leases({ published.get() }, { it.refs.acquire() }, { it.refs.release() })
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        val readClosed = AtomicInteger()
        val readers = (1..8).map {
            thread {
                while (!stop.get()) leases.lease().use { if (leases.held()!!.closes.get() > 0) readClosed.incrementAndGet() }
            }
        }
        repeat(200) { n ->
            val next = Service("${n + 1}").also { all += it }
            val old = published.getAndSet(next)
            old.refs.releaseOwner()
        }
        stop.set(true)
        readers.forEach { it.join(5_000) }
        assertEquals(0, readClosed.get(), "no read ever saw closed packs")
        assertEquals(1, all.count { it.closes.get() == 0 }, "exactly the published service is open")
        assertTrue(all.all { it.closes.get() <= 1 }, "each closed once")
    }
}
