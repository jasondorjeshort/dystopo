package io.github.jasondorjeshort.dystopo.identity

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fuzzing the key description parser and the chain judge (ADR 0051: untrusted input from strangers; one exception
 * type; never an acceptance from bytes that do not say what an accepted chain says). Fixed seeds, so a failure
 * reproduces.
 */
class AttestationFuzzTest {
    private val good = TestChains.keyDescription(TestChains.Spec())
    private val goodParsed = KeyDescription.parse(good)

    /** Parses [bytes], allowing only [AttestationFormatException]. */
    private fun parseOrNull(bytes: ByteArray): KeyDescription? = try {
        KeyDescription.parse(bytes)
    } catch (e: AttestationFormatException) {
        null
    } catch (e: Throwable) {
        fail("${e::class.simpleName} (${e.message}) from ${bytes.hex()}")
    }

    /** What the policy reads from a description; an accepted fuzz case must say exactly this. */
    private fun policyFields(d: KeyDescription) = listOf(
        d.attestationSecurityLevel, d.keyMintSecurityLevel,
        d.applicationId?.packages?.map { it.name }, d.applicationId?.signatureDigests?.map { it.hex() },
        d.hardwareEnforced.rootOfTrust?.deviceLocked, d.hardwareEnforced.rootOfTrust?.verifiedBootState,
    )

    @Test fun everyTruncationThrowsTheOneException() {
        for (n in 0 until good.size) assertFailsWith<AttestationFormatException> { KeyDescription.parse(good.copyOf(n)) }
    }

    @Test fun randomBytesThrowTheOneException() {
        val rnd = Random(51)
        var parsed = 0
        repeat(20_000) {
            val b = rnd.nextBytes(rnd.nextInt(0, 300))
            if (rnd.nextBoolean() && b.isNotEmpty()) b[0] = 0x30
            if (parseOrNull(b) != null) parsed++
        }
        assertEquals(0, parsed, "random bytes never form a key description")
    }

    @Test fun mutatedExtensionsNeverAcceptWhatTheOriginalDidNotSay() {
        val rnd = Random(1051)
        var thrown = 0
        var refused = 0
        var accepted = 0
        repeat(1_500) {
            val m = good.copyOf()
            repeat(rnd.nextInt(1, 4)) {
                val i = rnd.nextInt(m.size)
                m[i] = if (rnd.nextBoolean()) (m[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() else rnd.nextInt(256).toByte()
            }
            val d = parseOrNull(m)
            if (d == null) thrown++
            // The mutated extension in a correctly signed chain: the judge must never throw, and accept only when
            // the mutation left every field the policy reads as it was.
            val v = try {
                TestChains.evaluate(TestChains.chain(leafExtension = m).certs)
            } catch (e: Throwable) {
                fail("evaluate threw ${e::class.simpleName}: ${e.message}")
            }
            if (v.accepted) {
                accepted++
                assertTrue(d != null && policyFields(d) == policyFields(goodParsed), "accepted a mutation that changed the policy's fields: ${v.toMap()}")
            } else {
                refused++
                if (d == null) assertTrue("malformed" in v.codes, "an unreadable extension is refused as malformed: ${v.codes}")
            }
        }
        assertTrue(thrown > 500, "most mutations break the encoding ($thrown thrown, $refused refused, $accepted accepted)")
        println("fuzz: 1500 mutated extensions, $thrown thrown, $refused refused, $accepted accepted with the policy's fields intact")
    }

    @Test fun mutatedCertificatesNeverAccept() {
        val rnd = Random(2051)
        val chain = TestChains.chain().certs
        repeat(1_500) {
            val which = rnd.nextInt(2) // the leaf or the intermediate; the presented root is stripped by design
            val c = chain[which].copyOf()
            val i = rnd.nextInt(c.size)
            c[i] = (c[i].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            val mutated = chain.toMutableList().also { it[which] = c }
            val v = try {
                TestChains.evaluate(mutated)
            } catch (e: Throwable) {
                fail("evaluate threw ${e::class.simpleName}: ${e.message}")
            }
            assertTrue(!v.accepted, "a flipped bit at $i of certificate $which was accepted")
        }
    }

    @Test fun deepNestingAndHugeLengthsThrowTheOneException() {
        var nested = Der.nullValue()
        repeat(100) { nested = Der.sequence(nested) }
        assertFailsWith<AttestationFormatException> { KeyDescription.parse(nested) }
        assertFailsWith<AttestationFormatException> { KeyDescription.parse(byteArrayOf(0x30, 0x84.toByte(), 0x7f, 0, 0, 0)) }
        assertFailsWith<AttestationFormatException> { KeyDescription.parse(byteArrayOf(0x30, 0x80.toByte(), 0, 0)) }
        assertFailsWith<AttestationFormatException> { X509Cert.parse(ByteArray(X509Cert.MAX_SIZE + 1)) }
        assertFailsWith<AttestationFormatException> { KeyDescription.parse(ByteArray(KeyDescription.MAX_SIZE + 1) { 0x30 }) }
        assertEquals(Verdict.Status.REFUSE, TestChains.evaluate(List(11) { TestChains.testRoot.cert }).status)
        assertEquals(Verdict.Status.REFUSE, TestChains.evaluate(emptyList()).status)
    }

    @Test fun repeatedTagInAnAuthorizationListThrows() {
        // A second rootOfTrust after the first, the shape a forged list would use to shadow the hardware one.
        val rot = Der.explicit(704, Der.sequence(Der.octets(ByteArray(32)), Der.boolean(true), Der.enumerated(0), Der.octets(ByteArray(32))))
        val list = Der.sequence(rot, rot)
        val d = Der.sequence(Der.integer(300), Der.enumerated(1), Der.integer(300), Der.enumerated(1), Der.octets(ByteArray(0)), Der.octets(ByteArray(0)), Der.sequence(), list)
        assertFailsWith<AttestationFormatException> { KeyDescription.parse(d) }
    }
}
