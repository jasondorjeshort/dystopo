package io.github.jasondorjeshort.dystopo.identity

import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Our certificate and extension reader against Java's own certificate parser (the oracle at hand; ADR 0051 allows
 * `android/keyattestation` as one, which T211 did not need: Java parses the same certificates).
 */
class X509CertOracleTest {
    private fun java(der: ByteArray) = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    @Test fun mintedCertificatesReadAsJavaReadsThem() {
        val chain = TestChains.chain(leafSerial = 0x7ab3c0ffeeL, interSerial = 9_000_000_007L)
        for (der in chain.certs) {
            val ours = X509Cert.parse(der)
            val theirs = java(der)
            assertEquals(theirs.serialNumber, BigInteger(1, ours.serial))
            assertEquals(theirs.serialNumber.toString(16), ours.serialHex)
            assertEquals(theirs.notBefore.time, ours.notBefore)
            assertEquals(theirs.notAfter.time, ours.notAfter)
            assertTrue(theirs.publicKey.encoded.contentEquals(ours.publicKey))
            assertTrue(theirs.tbsCertificate.contentEquals(ours.tbs))
            assertTrue(theirs.signature.contentEquals(ours.signature))
            assertEquals(theirs.sigAlgOID, ours.signatureAlgorithm)
            assertTrue(theirs.issuerX500Principal.encoded.contentEquals(ours.issuer))
            assertTrue(theirs.subjectX500Principal.encoded.contentEquals(ours.subject))
            // Java also checks the signatures the same way the policy does.
        }
        val leaf = X509Cert.parse(chain.certs[0])
        val wrapped = java(chain.certs[0]).getExtensionValue(KeyDescription.OID)
        assertTrue(Der.octets(leaf.extension(KeyDescription.OID)!!.value).contentEquals(wrapped))
        java(chain.certs[0]).verify(java(chain.certs[1]).publicKey)
        java(chain.certs[1]).verify(java(chain.certs[2]).publicKey)
    }

    @Test fun keyDescriptionReadsWhatWasWritten() {
        val spec = TestChains.Spec(level = KeyDescription.SecurityLevel.STRONG_BOX, locked = false, boot = KeyDescription.VerifiedBootState.SELF_SIGNED)
        val d = KeyDescription.parse(TestChains.keyDescription(spec))
        assertEquals(300, d.attestationVersion)
        assertEquals(KeyDescription.SecurityLevel.STRONG_BOX, d.attestationSecurityLevel)
        assertEquals(listOf(TestChains.PACKAGE), d.applicationId!!.packages.map { it.name })
        assertEquals(listOf(TestChains.RELEASE_SIGNER), d.applicationId!!.signatureDigests.map { it.hex() })
        assertEquals(false, d.hardwareEnforced.rootOfTrust!!.deviceLocked)
        assertEquals(KeyDescription.VerifiedBootState.SELF_SIGNED, d.rootOfTrust!!.verifiedBootState)
        assertEquals(150000L, d.osVersion)
        assertEquals(202510L, d.osPatchLevel)
        assertEquals(20251001L, d.bootPatchLevel)
        assertEquals(1L, d.hardwareEnforced.ecCurve)
        assertEquals("dystopo-identity", d.challenge.decodeToString())
    }

    @Test fun javaSecurityVerifierChecksEcAndRefusesMismatches() {
        val keys = TestChains.ecKeys()
        val s = TestChains.signer(keys)
        val sig = s.sign("data".encodeToByteArray())
        assertTrue(JavaSecurityVerifier.verify(keys.public.encoded, Oids.ECDSA_SHA256, "data".encodeToByteArray(), sig))
        assertEquals(false, JavaSecurityVerifier.verify(keys.public.encoded, Oids.ECDSA_SHA256, "datA".encodeToByteArray(), sig))
        assertEquals(false, JavaSecurityVerifier.verify(keys.public.encoded, Oids.RSA_SHA256, "data".encodeToByteArray(), sig))
        assertEquals(false, JavaSecurityVerifier.verify(byteArrayOf(1, 2, 3), Oids.ECDSA_SHA256, "data".encodeToByteArray(), sig))
        assertEquals(false, JavaSecurityVerifier.verify(keys.public.encoded, "1.2.3.4", "data".encodeToByteArray(), sig))
        // Google's RSA root key loads (the anchor list's keys are usable).
        val rsa = PolicyLists.release.anchors.keys.first { it.name.contains("RSA") }
        assertEquals(false, JavaSecurityVerifier.verify(rsa.publicKey, Oids.RSA_SHA256, "data".encodeToByteArray(), ByteArray(512)))
    }
}
