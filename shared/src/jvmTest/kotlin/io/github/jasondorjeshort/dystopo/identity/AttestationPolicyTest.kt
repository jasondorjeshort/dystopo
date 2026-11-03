package io.github.jasondorjeshort.dystopo.identity

import io.github.jasondorjeshort.dystopo.identity.TestChains.NOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T211's Done-when, case by case: synthetic chains under the test root judged by the release policy's rules. */
class AttestationPolicyTest {
    private fun check(name: String) {
        val case = Scenarios.named(name)
        val v = Scenarios.evaluate(case)
        assertEquals(case.expected, v.status, "$name: ${v.toMap()}")
        assertEquals(case.codes.sorted(), v.codes.distinct().sorted(), "$name: reasons ${v.toMap()["reasons"]}")
        for (r in v.reasons) assertTrue(r.reason.lift.isNotBlank() && r.reason.message.isNotBlank(), "$name: every reason has a message and a lift")
    }

    @Test fun teeChainAccepted() {
        check("accept-tee")
        val v = Scenarios.evaluate(Scenarios.named("accept-tee"))
        assertEquals("tee", v.toMap()["securityLevel"])
        assertEquals(3, v.chainLength)
        assertEquals(Sha.keyId(X509Cert.parse(Scenarios.named("accept-tee").chain[0]).publicKey), v.toMap()["identityKeyId"])
    }

    @Test fun strongBoxChainAccepted() = check("accept-strongbox")

    @Test fun chainWithoutItsRootAccepted() = check("accept-root-not-presented")

    @Test fun leafExpiredWithinGraceIsStale() {
        check("stale-leaf-expired-10-days")
        assertEquals(10L, Scenarios.evaluate(Scenarios.named("stale-leaf-expired-10-days")).toMap()["staleDays"])
    }

    @Test fun anotherPackageRefused() = check("refuse-other-package")

    @Test fun unlistedSignerRefused() = check("refuse-unlisted-signer")

    @Test fun softwareLevelRefusedUnderTheReleasePolicy() {
        check("refuse-software-level")
        check("refuse-software-chain-as-emulators-make")
        // A policy that allows software keys (none ships) passes the same chain: the level is the only reason.
        val lax = AttestationPolicy(setOf(TestChains.PACKAGE), setOf(TestChains.RELEASE_SIGNER), requireHardware = false)
        assertEquals(Verdict.Status.ACCEPT, TestChains.evaluate(Scenarios.named("refuse-software-level").chain, policy = lax).status)
    }

    @Test fun unlockedBootloaderRefused() = check("refuse-unlocked-bootloader")

    @Test fun unverifiedBootRefused() {
        check("refuse-unverified-boot")
        check("refuse-self-signed-boot")
    }

    @Test fun unknownRootKeyRefused() = check("refuse-unknown-root")

    @Test fun leafPastGraceRefused() = check("refuse-leaf-past-grace")

    @Test fun notYetValidRefused() = check("refuse-not-yet-valid")

    @Test fun serialRevokedUnderDecimalKeyRefused() = check("refuse-revoked-decimal-key")

    @Test fun serialRevokedUnderHexKeyRefused() = check("refuse-revoked-hex-key")

    @Test fun listedButUnrelatedSerialsDoNotRefuse() = check("accept-not-revoked")

    @Test fun extensionOnlyInLaterCertificateRefused() {
        check("refuse-extension-only-in-later-certificate")
        check("refuse-chain-extended-below-attested-key")
        check("refuse-no-attestation")
    }

    @Test fun provisioningInfoRightAboveTheLeafAccepted() {
        check("accept-rkp-provisioning-info-above-leaf")
        // The provisioning extension vouches for the certificate right after it: below the attested key it fails.
        val base = TestChains.chain(interExtensions = listOf(TestChains.Ext(KeyDescription.PROVISIONING_INFO_OID, Der.sequence())))
        val extraKeys = TestChains.ecKeys()
        val extra = TestChains.cert(
            5, "Android Keystore Key", "Forged key", extraKeys.public, base.leafKeys.private, NOW - TestChains.DAY_MS, NOW + 30 * TestChains.DAY_MS,
            listOf(TestChains.Ext(KeyDescription.OID, TestChains.keyDescription(TestChains.Spec()))),
        )
        assertEquals(listOf("extended-chain"), TestChains.evaluate(listOf(extra) + base.certs).codes)
    }

    @Test fun badSignatureRefused() = check("refuse-bad-signature")

    @Test fun strangerNeedsAFreshRevocationList() {
        check("refuse-stranger-without-fresh-list")
        check("accept-stranger-with-fresh-list")
        // A member is accepted with any age, or none.
        assertEquals(Verdict.Status.ACCEPT, TestChains.evaluate(TestChains.chain().certs, revocation = null, audience = Audience.MEMBER).status)
        val noList = TestChains.evaluate(TestChains.chain().certs, revocation = null, audience = Audience.STRANGER)
        assertEquals(listOf("revocation-list-old"), noList.codes)
    }

    @Test fun garbageRefusedAsMalformed() = check("refuse-garbage")

    @Test fun everyCaseIsChecked() {
        // Every scenario the fixtures carry is asserted somewhere above or here.
        for (case in Scenarios.all) check(case.name)
    }

    @Test fun challengeMismatchRefused() {
        val chain = TestChains.chain().certs
        val v = TestChains.releasePolicy.evaluate(chain, TestChains.testAnchors, JavaSecurityVerifier, NOW, null, expectedChallenge = "other".encodeToByteArray())
        assertEquals(listOf("challenge"), v.codes)
        val ok = TestChains.releasePolicy.evaluate(chain, TestChains.testAnchors, JavaSecurityVerifier, NOW, null, expectedChallenge = "dystopo-identity".encodeToByteArray())
        assertEquals(Verdict.Status.ACCEPT, ok.status)
    }

    @Test fun releaseAnchorsRefuseTheTestRoot() {
        val v = TestChains.evaluate(TestChains.chain().certs, anchors = PolicyLists.release.anchors, policy = AttestationPolicy.release())
        // An unanchored root is not stripped, so its own dates count too (the test root starts 2026-11-03).
        assertEquals(Verdict.Status.REFUSE, v.status)
        assertTrue("root" in v.codes, "${v.codes}")
    }

    @Test fun identityRecordCarriesItsPlatform() {
        val chain = TestChains.chain()
        val rec = IdentityRecord.decode(IdentityRecord(Platform.ANDROID_KEY_ATTESTATION_V1, chain.certs).encode())
        assertEquals(Platform.ANDROID_KEY_ATTESTATION_V1, rec.platform)
        assertTrue(rec.identityKey.contentEquals(chain.leafKeys.public.encoded))
        val ok = TestChains.releasePolicy.evaluate(rec, TestChains.testAnchors, JavaSecurityVerifier, NOW, null)
        assertEquals(Verdict.Status.ACCEPT, ok.status)
        val ios = TestChains.releasePolicy.evaluate(IdentityRecord("apple-app-attest-v1", chain.certs), TestChains.testAnchors, JavaSecurityVerifier, NOW, null)
        assertEquals(listOf("platform"), ios.codes)
        assertNotNull(ios.reasons.single().reason.lift)
    }

    @Test fun verdictJsonNamesReasonsAndLifts() {
        val m = Scenarios.evaluate(Scenarios.named("refuse-unlocked-bootloader")).toMap()
        assertEquals("refuse", m["status"])
        @Suppress("UNCHECKED_CAST")
        val r = (m["reasons"] as List<Map<String, Any?>>).single()
        assertEquals("unlocked", r["code"])
        assertEquals("Relock the bootloader.", r["lift"])
    }
}
