package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.share.PeerCodes.Outcome
import io.github.jasondorjeshort.dystopo.app.share.PeerCodes.Phone
import io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T106: the T90 and T96 verifiers' share follow-ups. The sharer's gate forgets wrong codes after ten minutes; a request
 * that ends in an [Error] does not confirm a code; within a second of an answer at most four requests go out unprobed
 * (a code a third phone rotated meanwhile costs at most four failures); a request waiting for another's answer gives up
 * at its own deadline; discovery reaching a peer added by hand keeps it one.
 */
class ShareGateFollowupsTest {
    private val code = "7K3MQW2D9XHPT4CA"
    private val phone = Phone("10.0.2.23:8841", "Dystopo sdk_gphone64_x86_64 4af9", "4af9c0de")

    /** One sealed hello with [c] to [gate]; its answer as an [Outcome]. */
    private fun hello(gate: ShareGate, c: String): Outcome {
        val frame = SealedLink.sealRequest(c, SealedLink.Request("POST", "/peer/hello", ByteArray(0)), ShareGate::randomBytes)
        val r = gate.handle(Request("POST", ShareGate.SEALED_PATH, emptyMap(), frame, loopback = false))
        return if (r.status == 200) Outcome.OPENED else Outcome.WRONG.also { assertEquals(403, r.status) }
    }

    @Test
    fun theGateForgetsWrongCodesAfterItsWindow() {
        var now = 1_000_000L
        val gate = ShareGate(hello = { emptyMap() }, sync = { null }, newCode = { code }, clock = { now })
        // Four phones with stale codes, one wrong code each, over the first minutes the screen is open.
        repeat(4) { now += 60_000; assertEquals(Outcome.WRONG, hello(gate, "1111111111111111")) }
        assertEquals(4, gate.failures)
        // Ten minutes after each, it no longer counts: four more later do not replace the code (on T96 they did, at the fifth).
        now += ShareGate.FAILURE_WINDOW_MS
        assertEquals(0, gate.failures)
        repeat(4) { assertEquals(Outcome.WRONG, hello(gate, "2222222222222222")) }
        assertEquals(0, gate.rotations)
        assertEquals(4, gate.failures)
        assertEquals(4L, gate.failuresAged)
        // Five within the window still replace it.
        assertEquals(Outcome.WRONG, hello(gate, "3333333333333333"))
        assertEquals(1, gate.rotations)
        assertEquals(0, gate.failures)
        assertEquals(8L + 1, gate.refused)
    }

    /** Blocks the request that went first until [n] others wait for its answer (or 10 s): load-proof bursts. */
    private fun awaitWaiting(b: PeerCodes, n: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (b.waited < n && System.currentTimeMillis() < deadline) Thread.sleep(2)
    }

    /** [n] requests at once through [b].send; their outcomes. */
    private fun burst(b: PeerCodes, c: String, n: Int, waitMs: Long = 10_000, call: () -> Unit): List<Result<Unit>> {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val futures = (1..n).map { pool.submit<Result<Unit>> { start.await(); runCatching { b.send(phone, c, waitMs) { call() } } } }
        start.countDown()
        val out = futures.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        return out
    }

    @Test
    fun aCodeRotatedByAThirdPhoneRightAfterASuccessCostsAtMostFourFailures() {
        var n = 0
        val gate = ShareGate(hello = { emptyMap() }, sync = { null }, newCode = { if (n++ == 0) code else "9ZZZZZZZZZZZZZZZ" })
        val b = PeerCodes(confirmMs = 5_000)
        // B is connected: its code just answered.
        b.send(phone, code, waitMs = 1_000) { assertEquals(Outcome.OPENED, hello(gate, code)) }
        // A third phone spends A's five failures: A shows a new code. B's map asks for sixteen tiles within the second.
        gate.rotate()
        assertEquals(1, gate.rotations)
        val sent = AtomicInteger()
        val results = burst(b, code, 16) {
            sent.incrementAndGet()
            Thread.sleep(50) // the answers take a moment: the others arrive meanwhile
            if (hello(gate, code) == Outcome.WRONG) throw ShareClient.WrongCodeException("wrong code")
        }
        assertTrue(sent.get() <= PeerCodes.MAX_UNANSWERED, "${sent.get()} sent before the first answer came back (T96: all sixteen)")
        assertTrue(gate.failures <= 4)
        assertEquals(1, gate.rotations, "B did not make A replace its code again")
        assertTrue(results.all { it.exceptionOrNull() is ShareClient.WrongCodeException })
        assertTrue(b.isRefused(phone, code))
    }

    @Test
    fun aBurstWithAWorkingCodeStillGoesFourAtATime() {
        val gate = ShareGate(hello = { emptyMap() }, sync = { null }, newCode = { code })
        val b = PeerCodes(confirmMs = 5_000)
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val results = burst(b, code, 16) {
            most.accumulateAndGet(inFlight.incrementAndGet()) { x, y -> maxOf(x, y) }
            if (first.getAndSet(false)) awaitWaiting(b, 15) else Thread.sleep(30)
            assertEquals(Outcome.OPENED, hello(gate, code))
            inFlight.decrementAndGet()
        }
        assertTrue(results.all { it.isSuccess })
        assertEquals(16L, gate.served)
        assertTrue(most.get() in 2..PeerCodes.MAX_UNANSWERED, "together, at most four at once (saw ${most.get()})")
    }

    @Test
    fun anErrorIsNoAnswer() {
        val b = PeerCodes(confirmMs = 5_000)
        b.send(phone, code, waitMs = 1_000) { } // answered: confirmed
        // A request that dies with an Error (here an assertion; out of memory on a phone) does not confirm the code again.
        assertTrue(runCatching { b.send(phone, code, waitMs = 1_000) { throw AssertionError("boom") } }.exceptionOrNull() is AssertionError)
        // So the next burst asks first: one request, the others wait for its answer (T96 let four out at once).
        val waitedBefore = b.waited
        val sent = AtomicInteger()
        assertTrue(burst(b, code, 8) { if (sent.getAndIncrement() == 0) awaitWaiting(b, waitedBefore + 7) }.all { it.isSuccess })
        assertEquals(7, b.waited - waitedBefore, "all but the first waited for its answer")
    }

    @Test
    fun aProbeThatDiesWithAnErrorFailsItsWaitersWithAnIoException() {
        val b = PeerCodes(confirmMs = 5_000)
        val first = AtomicInteger()
        val results = burst(b, code, 6) {
            if (first.getAndIncrement() == 0) {
                awaitWaiting(b, 5) // every other request waits for this one's answer
                throw OutOfMemoryError("simulated")
            }
        }
        assertEquals(1, first.get(), "only the probe ran")
        assertEquals(1, results.count { it.exceptionOrNull() is OutOfMemoryError })
        assertEquals(5, results.count { it.exceptionOrNull() is IOException })
        assertFalse(b.isRefused(phone, code))
    }

    @Test
    fun aWaiterGivesUpAtItsOwnDeadline() {
        val b = PeerCodes(confirmMs = 5_000)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        // The first request asks and its answer is slow (a connect timeout on a phone that went away).
        val probe = pool.submit<Result<Unit>> { runCatching { b.send(phone, code, waitMs = 10_000) { inside.countDown(); release.await(5, TimeUnit.SECONDS); Unit } } }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val t0 = System.currentTimeMillis()
        val waited = runCatching { b.send(phone, code, waitMs = 300) { error("not sent: it waits for the first") } }
        val ms = System.currentTimeMillis() - t0
        val e = waited.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("no answer to the request this one waited for"), "$e")
        assertTrue(ms in 250..2_000, "gave up after $ms ms")
        release.countDown()
        assertTrue(probe.get(5, TimeUnit.SECONDS).isSuccess)
        pool.shutdown()
        // The probe answered: the code is confirmed, and the next request goes at once.
        assertEquals(Unit, b.send(phone, code, waitMs = 100) { })
    }

    @Test
    fun discoveryKeepsAPeerAddedByHandOne() {
        assertTrue(staysAuto(existing = null, replaced = emptyList(), auto = true), "found by discovery, new")
        assertFalse(staysAuto(existing = null, replaced = emptyList(), auto = false), "added by hand")
        assertFalse(staysAuto(existing = false, replaced = emptyList(), auto = true), "by hand at this address (T96)")
        // T106: added by hand at another address (an adb forward, a typed address); discovery resolved it at its own.
        assertFalse(staysAuto(existing = null, replaced = listOf(false), auto = true))
        assertTrue(staysAuto(existing = true, replaced = listOf(true), auto = true))
    }
}
