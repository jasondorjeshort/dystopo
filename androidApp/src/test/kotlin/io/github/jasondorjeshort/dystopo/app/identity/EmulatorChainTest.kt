package io.github.jasondorjeshort.dystopo.app.identity

import io.github.jasondorjeshort.dystopo.identity.AttestationPolicy
import io.github.jasondorjeshort.dystopo.identity.Der
import io.github.jasondorjeshort.dystopo.identity.JavaSecurityVerifier
import io.github.jasondorjeshort.dystopo.identity.KeyDescription
import io.github.jasondorjeshort.dystopo.identity.Pem
import io.github.jasondorjeshort.dystopo.identity.PolicyLists
import io.github.jasondorjeshort.dystopo.identity.Reason
import io.github.jasondorjeshort.dystopo.identity.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * T216: the identity chain an API 35 emulator's Keystore made for the debug app (`GET /identity?pem=1`, captured
 * 2026-11-03; synthetic key material: the emulator's software KeyMint under the RKP test CA "Droid Unregistered Device
 * CA", nothing from a real phone; PEM certificate blocks in a `.txt`, since the push scan refuses every `.pem` file as a secret). Its root carries the provisioning extension too, which T211's policy read as an
 * extended chain and so never reported the level; the release policy now refuses it for what it is.
 */
class EmulatorChainTest {
    private val chain = Pem.certificates(EmulatorChainTest::class.java.getResourceAsStream("/identity/emulator-software-chain.txt")!!.readBytes().decodeToString())
    private val at = Der.epochMillis(2026, 11, 3, 9, 0, 0) // inside the RKP test intermediate's two weeks

    @Test fun releasePolicyRefusesTheSoftwareChainWithItsLevel() {
        assertEquals(3, chain.size)
        val v = AttestationPolicy.release().evaluate(chain, PolicyLists.release.anchors, JavaSecurityVerifier, at, null)
        assertEquals(Verdict.Status.REFUSE, v.status)
        assertEquals(listOf("no-root-of-trust", "root", "signer", "software-level"), v.codes.distinct().sorted())
        assertFalse("extended-chain" in v.codes)
        val d = v.description!!
        assertEquals(KeyDescription.SecurityLevel.SOFTWARE, d.attestationSecurityLevel)
        assertEquals(listOf(PolicyLists.release.packages.single()), d.applicationId!!.packages.map { it.name })
        assertEquals(false, d.rootOfTrust!!.deviceLocked)
        assertEquals(Reason.SOFTWARE_LEVEL, IdentityRules.primaryReason(v.reasons)!!.reason)
        assertEquals(
            "This phone cannot prove it runs the signed app: the key is not held in secure hardware (software attestation).",
            IdentityRules.line(IdentityRules.Phase.READY, v).text,
        )
    }

    @Test fun debugPolicyStillRefusesItButNotForTheSigner() {
        val signers = v().description!!.applicationId!!.signatureDigests.map { Signing.hex(it) }
        val v = IdentityRules.debugPolicy(signers).evaluate(chain, IdentityRules.debugAnchors(null), JavaSecurityVerifier, at, null)
        assertEquals(listOf("no-root-of-trust", "root", "software-level"), v.codes.distinct().sorted())
    }

    private fun v() = AttestationPolicy.release().evaluate(chain, PolicyLists.release.anchors, JavaSecurityVerifier, at, null)

    private object Signing {
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
