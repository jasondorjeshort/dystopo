package io.github.jasondorjeshort.dystopo.identity

import io.github.jasondorjeshort.dystopo.identity.TestChains.DAY_MS
import io.github.jasondorjeshort.dystopo.identity.TestChains.NOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The certification and rotation records and the pins they move (ADR 0051, stage 1a). */
class RecordsTest {
    private val identity = TestChains.signer(TestChains.ecKeys())
    private val successor = TestChains.signer(TestChains.ecKeys())
    private val signingKey = TestChains.ecKeys().public.encoded

    @Test fun rotationRecordMovesAPin() {
        val pins = PinSet()
        pins.pin(identity.publicKey, "Alex")
        val rec = RotationRecord.decode(RotationRecord.create(identity, successor, NOW).encode())
        assertEquals(PinSet.Rotation.MOVED to null, pins.applyRotation(rec, JavaSecurityVerifier))
        assertEquals("Alex", pins.label(successor.publicKey))
        assertNull(pins.label(identity.publicKey))
        assertEquals(1, pins.size)
    }

    @Test fun rotationNeedsBothKeys() {
        val pins = PinSet()
        pins.pin(identity.publicKey, "Alex")
        val stranger = TestChains.signer(TestChains.ecKeys())
        // Signed by a stranger claiming to be the old key: the old signature fails.
        val forged = RotationRecord.create(identity, successor, NOW).let {
            val byStranger = RotationRecord.create(stranger, successor, NOW)
            RotationRecord(it.platform, identity.publicKey, successor.publicKey, NOW, byStranger.oldSignature, it.newSignature)
        }
        assertEquals(PinSet.Rotation.REFUSED to "not signed by the old identity key", pins.applyRotation(forged, JavaSecurityVerifier))
        // The new key did not sign: someone moving a pin to a key they do not hold.
        val unsigned = RotationRecord.create(identity, successor, NOW).let { RotationRecord(it.platform, it.oldKey, stranger.publicKey, NOW, it.oldSignature, it.newSignature) }
        assertEquals(PinSet.Rotation.REFUSED, pins.applyRotation(unsigned, JavaSecurityVerifier).first)
        assertEquals("Alex", pins.label(identity.publicKey))
        // An unpinned identity's rotation verifies but moves nothing.
        assertEquals(PinSet.Rotation.NOT_PINNED to null, pins.applyRotation(RotationRecord.create(stranger, successor, NOW), JavaSecurityVerifier))
    }

    @Test fun certificationRecordCertifiesASigningKey() {
        val rec = CertificationRecord.decode(CertificationRecord.create(identity, signingKey, NOW, NOW + 30 * DAY_MS).encode())
        assertNull(rec.check(identity.publicKey, JavaSecurityVerifier, NOW + DAY_MS))
        assertTrue(rec.signingKey.contentEquals(signingKey))
        assertEquals("ended 2026-12-01T00:00:00Z", rec.check(identity.publicKey, JavaSecurityVerifier, NOW + 31 * DAY_MS))
        assertEquals("certified by another identity key", rec.check(successor.publicKey, JavaSecurityVerifier, NOW))
        val long = CertificationRecord.create(identity, signingKey, NOW, NOW + 90 * DAY_MS)
        assertNotNull(long.check(identity.publicKey, JavaSecurityVerifier, NOW))
        val tampered = CertificationRecord(rec.platform, rec.identityKeyId, successor.publicKey, rec.validFrom, rec.validTo, rec.signature)
        assertEquals("bad signature", tampered.check(identity.publicKey, JavaSecurityVerifier, NOW))
    }

    @Test fun newerRecordVersionsAreRefusedWithTheUpdateMessage() {
        val bytes = CertificationRecord.create(identity, signingKey, NOW, NOW + DAY_MS).encode()
        // The version INTEGER 1 follows the type string; make it 2.
        val type = Der.utf8(CertificationRecord.TYPE)
        val at = indexOf(bytes, type + Der.integer(1)) + type.size
        val newer = bytes.copyOf().also { it[at + 2] = 2 }
        val e = assertFailsWith<AttestationFormatException> { CertificationRecord.decode(newer) }
        assertTrue(e.message!!.contains("update both"), e.message)
        assertFailsWith<AttestationFormatException> { RotationRecord.decode(bytes) }
        assertFailsWith<AttestationFormatException> { IdentityRecord.decode(bytes) }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int =
        (0..hay.size - needle.size).first { i -> needle.indices.all { hay[i + it] == needle[it] } }
}
