package io.github.jasondorjeshort.dystopo.identity

import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bundled release lists (ADR 0051: "debug builds alone add the debug signer and a test root; a test asserts
 * release lists hold neither").
 */
class ReleaseListsTest {
    private val lists = PolicyLists.release

    @Test fun releaseListsHoldNeitherTheTestRootNorADebugSigner() {
        val testRootKey = Sha.keyId(X509Cert.parse(TestChains.testRoot.cert).publicKey)
        assertFalse(testRootKey in lists.anchors.keyIds, "the test root is in roots.json")
        // The signer list is the release signer and nothing else (the second signer's line is a placeholder).
        assertEquals(listOf(TestChains.RELEASE_SIGNER), lists.signers.map { it.sha256 })
        // The SDK's debug key, where this machine has one (every machine's differs, so the list must hold only the
        // release digest above; this checks the one at hand as well).
        val debugStore = File(System.getProperty("user.home"), ".android/debug.keystore")
        if (debugStore.isFile) {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { debugStore.inputStream().use { load(it, "android".toCharArray()) } }
            for (alias in ks.aliases()) {
                val digest = MessageDigest.getInstance("SHA-256").digest(ks.getCertificate(alias).encoded).hex()
                assertFalse(digest in lists.signers.map { it.sha256 }, "the debug signer $alias is in signers.json")
            }
        }
        // And the policy built from them refuses a chain under the test root.
        val v = AttestationPolicy.release().evaluate(TestChains.chain().certs, lists.anchors, JavaSecurityVerifier, TestChains.NOW, null)
        assertEquals(Verdict.Status.REFUSE, v.status)
        assertTrue("root" in v.codes, "${v.codes}")
    }

    @Test fun rootsAreTheFivePublishedCertificatesOnTwoKeys() {
        assertEquals(5, lists.roots.size)
        assertEquals(2, lists.anchors.keys.size)
        assertEquals(1, lists.rootsVersion)
        assertEquals(Platform.ANDROID_KEY_ATTESTATION_V1, lists.platform)
        for (r in lists.roots) {
            assertTrue("android.googleapis.com/attestation/root" in r.origin || "developer.android.com" in r.origin, r.origin)
            val alg = if (r.name.contains("EC P-384")) "EC" else "RSA"
            val key = KeyFactory.getInstance(alg).generatePublic(X509EncodedKeySpec(r.publicKey))
            when (key) {
                is RSAPublicKey -> assertEquals(4096, key.modulus.bitLength())
                is ECPublicKey -> assertEquals(384, key.params.curve.field.fieldSize)
            }
        }
        assertEquals(listOf(TestChains.PACKAGE), lists.packages)
        assertTrue(lists.signers.all { it.origin.isNotBlank() })
    }

    @Test fun placeholderSignerIsSkippedAndMalformedListsThrow() {
        val roots = """{"version":1,"platform":"p","roots":[]}"""
        val signers = """{"version":1,"platform":"p","packages":["a"],"signers":[{"name":"x","sha256":null,"origin":"placeholder"}]}"""
        assertEquals(0, PolicyLists.parse(roots, signers).signers.size)
        kotlin.test.assertFailsWith<AttestationFormatException> { PolicyLists.parse(roots, signers.replace("null", "\"12\"")) }
        kotlin.test.assertFailsWith<AttestationFormatException> { PolicyLists.parse(roots, signers.replace("\"p\"", "\"q\"")) }
        kotlin.test.assertFailsWith<AttestationFormatException> { PolicyLists.parse("{", signers) }
    }
}
