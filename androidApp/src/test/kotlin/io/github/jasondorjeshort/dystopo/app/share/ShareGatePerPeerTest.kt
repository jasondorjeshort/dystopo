package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T110 (T106's follow-up): the share gate counts wrong codes per address. Phones holding stale codes no longer add up to
 * a lockout of anyone else: two phones failing three times each lock out neither, while a third phone's five lock out that
 * phone only, and the code stays, so the phone using it keeps going. Fifty within the window from any addresses still
 * replace the code.
 */
class ShareGatePerPeerTest {
    private val code = "7K3MQW2D9XHPT4CA"
    private val stale = "1111111111111111"

    /** One sealed hello from [peer] with [c]; the HTTP status of the answer. */
    private fun hello(gate: ShareGate, peer: String?, c: String): Int {
        val frame = SealedLink.sealRequest(c, SealedLink.Request("GET", "/share/hello", ByteArray(0)), ShareGate::randomBytes)
        return gate.handle(Request("POST", ShareGate.SEALED_PATH, emptyMap(), frame, loopback = false, peer = peer)).status
    }

    @Test
    fun twoPhonesFailingThreeTimesEachLockOutNeitherWhileAThirdsFiveLockOutOnlyIt() {
        var now = 5_000_000L
        val gate = ShareGate(hello = { mapOf("device" to "a") }, sync = { null }, newCode = { code }, clock = { now })
        val a = "192.168.1.21"
        val b = "192.168.1.22"
        val c = "192.168.1.23"
        repeat(3) {
            now += 1_000
            assertEquals(403, hello(gate, a, stale))
            assertEquals(403, hello(gate, b, stale))
        }
        // Six wrong codes in all: on T106 the fifth replaced the code and the sixth went against the new one.
        assertEquals(6, gate.failures)
        assertEquals(0, gate.rotations)
        assertFalse(gate.lockedOut(a))
        assertFalse(gate.lockedOut(b))
        // Both still get in with the code on the screen.
        assertEquals(200, hello(gate, a, code))
        assertEquals(200, hello(gate, b, code))
        // A third phone guesses five times: it is locked out, and even the right code from it is refused unopened.
        repeat(5) { assertEquals(403, hello(gate, c, "22222222222222${it}2")) }
        assertTrue(gate.lockedOut(c))
        assertEquals(429, hello(gate, c, code))
        assertEquals(1L, gate.lockedRefused)
        assertEquals(5, gate.failuresFrom(c))
        // The code stayed: the first two are not locked out and still connect.
        assertEquals(0, gate.rotations)
        assertEquals(code, gate.code)
        assertFalse(gate.lockedOut(a))
        assertFalse(gate.lockedOut(b))
        assertEquals(200, hello(gate, a, code))
        assertEquals(200, hello(gate, b, code))
        // The lockout ends when its failures age out of the window.
        now += ShareGate.FAILURE_WINDOW_MS
        assertFalse(gate.lockedOut(c))
        assertEquals(200, hello(gate, c, code))
        val j = gate.json()
        assertEquals(0, j["addressesLockedOut"])
        assertEquals(11L, j["refused"])
    }

    @Test
    fun aNewCodeLiftsEveryLockout() {
        val gate = ShareGate(hello = { emptyMap() }, sync = { null }, newCode = ShareGate::randomCode)
        repeat(5) { assertEquals(403, hello(gate, "10.0.0.9", stale)) }
        assertTrue(gate.lockedOut("10.0.0.9"))
        assertEquals(1, gate.json()["addressesLockedOut"])
        gate.rotate() // the Share screen reopened
        assertFalse(gate.lockedOut("10.0.0.9"))
        assertEquals(200, hello(gate, "10.0.0.9", gate.code))
    }

    @Test
    fun fiftyFromManyAddressesStillReplaceTheCode() {
        var now = 1_000L
        val gate = ShareGate(hello = { emptyMap() }, sync = { null }, newCode = ShareGate::randomCode, clock = { now })
        val first = gate.code
        // A guesser spreading over 25 addresses, two guesses each: no address reaches five, but fifty in all do.
        for (i in 0 until ShareGate.GLOBAL_FAILURES) {
            now += 100
            assertEquals(403, hello(gate, "10.0.1.${i % 25}", stale))
        }
        assertEquals(1, gate.rotations)
        assertTrue(gate.code != first)
        assertEquals(0, gate.failures)
    }
}
