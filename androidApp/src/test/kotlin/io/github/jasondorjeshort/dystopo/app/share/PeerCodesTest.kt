package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.share.PeerCodes.Outcome
import io.github.jasondorjeshort.dystopo.app.share.PeerCodes.Phone
import io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T96: which codes a phone sends unasked, against a real [ShareGate] (five wrong codes replace its code). The T90
 * verifier's scenario: B keeps peer A and three more codes, A reopens its Share screen (a new code), B relaunches (the
 * launch retry) and opens its Share screen (discovery). On T90 that spent five failures and rotated A's code.
 */
class PeerCodesTest {
    private val kept = "7K3MQW2D9XHPT4CA" // A's code before it reopened its screen
    private val others = listOf("1111111111111111", "2222222222222222", "3333333333333333")
    private val nowCode = "9ZZZZZZZZZZZZZZZ" // what A's screen shows now

    /** Phone A as the sharing side: a real gate whose code is [nowCode] (it would show a new one after five failures). */
    private fun sharer(): ShareGate {
        var n = 0
        return ShareGate(hello = { mapOf("name" to "A") }, sync = { null }, newCode = { if (n++ == 0) nowCode else "8888888888888888" })
    }

    /** One sealed hello with [code], as PeerSources.add sends it; the gate's answer as an [Outcome]. */
    private fun send(gate: ShareGate, code: String): Outcome {
        val frame = SealedLink.sealRequest(code, SealedLink.Request("POST", "/peer/hello", ByteArray(0)), ShareGate::randomBytes)
        val r = gate.handle(Request("POST", ShareGate.SEALED_PATH, emptyMap(), frame, loopback = false))
        return if (r.status == 200) Outcome.OPENED else Outcome.WRONG.also { assertEquals(403, r.status) }
    }

    private val addrA = "10.0.2.23:8841"
    private val keptA = Phone(addrA, "Dystopo sdk_gphone64_x86_64 4af9", "4af9c0de") // the kept peer, as PeerSources.Peer.phone
    private val foundA = Phone(addrA, "Dystopo sdk_gphone64_x86_64 4af9", "4af9c0de") // the same phone, as discovery resolves it

    /** B after a relaunch: the four codes from the file (A's last), A's refusals as kept. */
    private fun relaunched(codes: List<String> = others + kept, refused: Map<String, List<String>> = emptyMap()) = PeerCodes().apply { restore(codes, refused) }

    @Test
    fun aRelaunchWithFourKnownCodesSpendsAtMostOneOfTheSharersFailures() {
        val gate = sharer()
        val b = relaunched()
        // The launch retry: A's kept code, once.
        assertNull(b.attempt(keptA, listOf(kept)) { send(gate, it) })
        assertEquals(1, gate.failures)
        // B opens its Share screen; discovery resolves A (and resolves it again a moment later).
        repeat(2) { assertNull(b.attempt(foundA, b.plan(foundA, own = kept, bound = emptySet())) { send(gate, it) }) }
        assertEquals(1, gate.failures, "the retry's wrong code counts: discovery sends neither it nor another stale code")
        assertEquals(0, gate.rotations)
        assertEquals(nowCode, gate.code, "A still shows the code its user read out")
        assertEquals(1, b.sent)

        // Relaunched again and again with the refusals kept (PeerStore): A is a kept peer, so it gets only its own code
        // from before, which it refused. Nothing more.
        val (codes, refusals) = relaunchMany(b, gate, 5)
        assertEquals(1, gate.failures)
        assertEquals(0, gate.rotations)

        // The user enters the code A shows now: fresh, it is tried at once and opens, with no failure spent.
        val last = relaunched(codes, refusals)
        last.learn(nowCode)
        assertEquals(nowCode, last.attempt(foundA, last.plan(foundA, own = kept, bound = emptySet())) { send(gate, it) })
        assertEquals(1, gate.failures)
        assertEquals(0, gate.rotations)
    }

    /** [n] relaunches of a phone with [b]'s codes and refusals (as PeerStore keeps them), each resolving A; the last state. */
    private fun relaunchMany(b: PeerCodes, gate: ShareGate, n: Int): Pair<List<String>, Map<String, List<String>>> {
        var codes = b.codes
        var refusals = b.refusals(codes.toSet() + kept)
        repeat(n) {
            val next = relaunched(codes, refusals)
            assertTrue(next.isRefused(keptA, kept), "the kept code's refusal survives the restart: the peer comes back codeChanged")
            assertEquals(emptyList(), next.plan(foundA, own = kept, bound = emptySet()))
            next.attempt(foundA, next.plan(foundA, own = kept, bound = emptySet())) { send(gate, it) }
            codes = next.codes
            refusals = next.refusals(codes.toSet() + kept)
        }
        return codes to refusals
    }

    /**
     * The same, but B was still running when A reopened: its fetcher asked A for a tile with the old code (one 403, the
     * peer `codeChanged`, the refusal written down), then B relaunched. The relaunch sends nothing.
     */
    @Test
    fun theFirstRefusalBeforeARelaunchIsTheOnlyOne() {
        val gate = sharer()
        val running = relaunched()
        assertEquals(Outcome.WRONG, send(gate, kept)) // PeerSources.request: a tile, refused
        running.refuse(Phone(addrA, keptA.nsdName, keptA.device), kept)
        assertEquals(1, gate.failures)
        relaunchMany(running, gate, 5)
        assertEquals(1, gate.failures)
        assertEquals(0, gate.rotations)
        assertEquals(nowCode, gate.code)
    }

    @Test
    fun theKeptCodeGoesFirstAndOpensWithNoFailure() {
        val gate = ShareGate(hello = { emptyMap() }, sync = { null }, newCode = { kept }) // A did not reopen: the kept code still opens
        val b = relaunched()
        assertEquals(kept, b.attempt(foundA, b.plan(foundA, own = kept, bound = emptySet())) { send(gate, it) })
        assertEquals(0, gate.failures)
        assertEquals(1, b.sent)
    }

    @Test
    fun aKeptPeersCodeIsNotTriedOnAnotherPhone() {
        val b = relaunched()
        val other = Phone("10.0.2.21:8841", "Dystopo Pixel 7 a698", "a698aa00")
        // A is kept with its code; a phone discovery finds that is not A gets only codes no kept peer holds, one stale.
        val plan = b.plan(other, own = null, bound = setOf(kept))
        assertFalse(kept in plan)
        val sent = ArrayList<String>()
        b.attempt(other, plan) { sent += it; Outcome.WRONG }
        assertEquals(listOf(others.last()), sent, "one code kept from before per phone per session, the newest")
    }

    @Test
    fun aPhoneThisOneDoesNotKnowGetsEachStaleCodeOnceOnePerRelaunch() {
        val gate = sharer() // another phone, sharing with a code this one does not have
        val other = Phone("10.0.2.21:8841", "Dystopo Pixel 7 a698", "a698aa00")
        var codes = others + kept
        var refusals = emptyMap<String, List<String>>()
        val failures = ArrayList<Int>()
        repeat(6) {
            val b = relaunched(codes, refusals)
            b.attempt(other, b.plan(other, own = null, bound = setOf(kept))) { send(gate, it) }
            failures += gate.failures
            codes = b.codes
            refusals = b.refusals(codes.toSet())
        }
        assertEquals(listOf(1, 2, 3, 3, 3, 3), failures, "the three unbound codes once each, one per relaunch; the kept peer's never")
        assertEquals(0, gate.rotations)
    }

    @Test
    fun freshCodesAreTriedOnceEachAfterTheStaleOne() {
        val b = relaunched()
        val other = Phone("10.0.2.21:8841", "Dystopo Pixel 7 a698", "a698aa00")
        b.learn("4444444444444444")
        b.learn("5555555555555555")
        val sent = ArrayList<String>()
        repeat(3) { b.attempt(other, b.plan(other, own = null, bound = setOf(kept))) { sent += it; Outcome.WRONG } }
        assertEquals(listOf("5555555555555555", "4444444444444444", others.last()), sent, "newest fresh first, then one stale; nothing twice")
        // A code learned after the phone was resolved is tried on it at once (T90), and only once.
        b.learn("6666666666666666")
        b.attempt(other, b.plan(other, own = null, bound = setOf(kept))) { sent += it; Outcome.WRONG }
        b.attempt(other, b.plan(other, own = null, bound = setOf(kept))) { sent += it; Outcome.WRONG }
        assertEquals("6666666666666666", sent.last())
        assertEquals(4, sent.size)
    }

    @Test
    fun aTryThatNeverConnectedLeavesTheStaleSlotFree() {
        val b = relaunched()
        val sent = ArrayList<String>()
        assertNull(b.attempt(foundA, listOf(kept)) { sent += it; Outcome.UNREACHABLE })
        assertFalse(b.isRefused(foundA, kept))
        assertEquals(kept, b.attempt(foundA, b.plan(foundA, own = kept, bound = emptySet())) { sent += it; Outcome.OPENED })
        assertEquals(listOf(kept, kept), sent)
    }

    @Test
    fun aRefusalIsKeptByAnyKeyOfThePhoneAndClearedWhenTheCodeOpens() {
        val b = PeerCodes()
        b.learn(kept)
        b.refuse(Phone(addr = addrA), kept)
        assertTrue(b.isRefused(Phone(addrA, "whatever", null), kept), "the same address")
        assertFalse(b.isRefused(Phone("10.0.2.30:8841", "Dystopo Pixel 7 a698", "a698aa00"), kept))
        b.refuse(Phone("10.0.2.30:8841", null, "a698aa00"), kept)
        assertTrue(b.isRefused(Phone("10.0.2.31:8841", null, "a698aa00"), kept), "the same device at a new address")
        // It opened there after all (another phone had that address): the refusal under those keys goes.
        b.opened(Phone(addr = addrA), kept)
        assertFalse(b.isRefused(Phone(addr = addrA), kept))
        // Forget retires the code: its refusals go with it, and it is no longer tried anywhere.
        assertTrue(b.retire(kept))
        assertEquals(emptyList(), b.codes)
        assertEquals(emptyMap(), b.refusals(setOf(kept)))
        assertEquals(emptyList(), b.plan(Phone(addr = "10.0.2.40:8841"), own = null, bound = emptySet()))
    }

    @Test
    fun oneTryOfACodeOnAPhoneAtATime() {
        val b = PeerCodes()
        b.learn(nowCode)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        var sends = 0
        val first = pool.submit {
            b.attempt(foundA, listOf(nowCode)) { synchronized(this) { sends++ }; inside.countDown(); release.await(5, TimeUnit.SECONDS); Outcome.WRONG }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        // A second resolve of the same phone while the first try is out: nothing sent.
        b.attempt(foundA, listOf(nowCode)) { synchronized(this) { sends++ }; Outcome.WRONG }
        release.countDown()
        first.get(5, TimeUnit.SECONDS)
        pool.shutdown()
        assertEquals(1, sends)
        assertEquals(1, b.held)
    }

    /**
     * T106: blocks the request that went first until [n] others are waiting for its answer (or 10 s), so a burst test does
     * not depend on every thread arriving within a sleep: under load (several agents running `check`) late threads found
     * the answer already there and never waited (3 of 15 seen once).
     */
    private fun awaitWaiting(b: PeerCodes, n: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (b.waited < n && System.currentTimeMillis() < deadline) Thread.sleep(2)
    }

    /** [n] requests at once through [b].send to [phone] with [code], each [call]ing after a start line; their outcomes. */
    private fun burst(b: PeerCodes, phone: Phone, code: String, n: Int, call: () -> Unit): List<Result<Unit>> {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val futures = (1..n).map { pool.submit<Result<Unit>> { start.await(); runCatching { b.send(phone, code, waitMs = 10_000) { call() } } } }
        start.countDown()
        val out = futures.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        return out
    }

    /**
     * T96 verifier: a map source's Share screen was reopened, then a cold map view asked it for a burst of tiles with the
     * old code; T96's first round sent them all (seven failures and a new code). Now one goes, the rest fail unsent.
     */
    @Test
    fun aBurstWithAChangedCodeSendsOneWrongCode() {
        val gate = sharer() // A shows nowCode; B still has the kept one
        val b = PeerCodes(confirmMs = 50)
        // B was connected: its code answered (long enough ago that the next burst asks first).
        b.send(foundA, kept, waitMs = 1_000) { }
        Thread.sleep(100)
        val sends = java.util.concurrent.atomic.AtomicInteger()
        val results = burst(b, foundA, kept, 16) {
            sends.incrementAndGet()
            awaitWaiting(b, 15) // the answer takes a while: the others arrive meanwhile (T106: all of them, however loaded the host)
            if (send(gate, kept) == Outcome.WRONG) throw ShareClient.WrongCodeException("wrong code")
        }
        assertEquals(1, sends.get(), "one request with the old code")
        assertEquals(1, gate.failures)
        assertEquals(0, gate.rotations)
        assertTrue(results.all { it.exceptionOrNull() is ShareClient.WrongCodeException }, "every request fails as a wrong code")
        assertEquals(15, b.spared, "the other fifteen waited for its answer and were not sent")
        assertTrue(b.isRefused(foundA, kept))
        // Eight pieces at once afterwards (the verifier's second case): none sent.
        assertTrue(burst(b, foundA, kept, 8) { sends.incrementAndGet() }.all { it.exceptionOrNull() is ShareClient.WrongCodeException })
        assertEquals(1, sends.get())
        assertEquals(1, gate.failures)
    }

    @Test
    fun aBurstWithAWorkingCodeAsksOnceThenGoesTogether() {
        val gate = ShareGate(hello = { emptyMap() }, sync = { null }, newCode = { kept })
        val b = PeerCodes(confirmMs = 5_000)
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val results = burst(b, foundA, kept, 16) {
            most.accumulateAndGet(inFlight.incrementAndGet()) { x, y -> maxOf(x, y) }
            if (first.getAndSet(false)) awaitWaiting(b, 15) else Thread.sleep(50) // T106: the first answers once all wait
            assertEquals(Outcome.OPENED, send(gate, kept))
            inFlight.decrementAndGet()
        }
        assertTrue(results.all { it.isSuccess })
        assertEquals(16L, gate.served)
        assertEquals(0, gate.failures)
        assertEquals(15, b.waited, "the first asked alone")
        assertTrue(most.get() > 1, "then they went together (at most ${most.get()} at once)")
    }

    @Test
    fun aBurstToAPhoneThatIsGoneFailsAtOnceAfterOneTry() {
        val b = PeerCodes(confirmMs = 50)
        val sends = java.util.concurrent.atomic.AtomicInteger()
        val t0 = System.currentTimeMillis()
        val results = burst(b, foundA, kept, 8) {
            sends.incrementAndGet()
            Thread.sleep(300) // a connect timeout, shortened
            throw LanConnectException("10.0.2.23:8841: SocketTimeoutException", java.io.IOException("timeout"))
        }
        assertEquals(1, sends.get(), "the others take the first one's failure instead of each trying in turn")
        assertTrue(results.all { it.exceptionOrNull() is LanConnectException })
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        assertFalse(b.isRefused(foundA, kept), "no connection is not a refusal")
    }

    @Test
    fun atMostMaxCodesAreKnown() {
        val b = PeerCodes()
        (0 until PeerSources.MAX_CODES + 2).forEach { b.learn("$it".repeat(16)) }
        assertEquals(PeerSources.MAX_CODES, b.codes.size)
        assertEquals("5".repeat(16), b.codes.last())
        assertTrue(PeerSources.MAX_CODES < 5, "under the five failures that replace another phone's code")
    }
}
