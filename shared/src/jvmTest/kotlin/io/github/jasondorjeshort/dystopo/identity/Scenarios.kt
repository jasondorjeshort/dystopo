package io.github.jasondorjeshort.dystopo.identity

import io.github.jasondorjeshort.dystopo.identity.KeyDescription.SecurityLevel
import io.github.jasondorjeshort.dystopo.identity.KeyDescription.VerifiedBootState
import io.github.jasondorjeshort.dystopo.identity.TestChains.DAY_MS
import io.github.jasondorjeshort.dystopo.identity.TestChains.NOW
import io.github.jasondorjeshort.dystopo.identity.TestChains.Spec

/**
 * The accept and refuse cases of T211's Done-when, each a chain under the test root (or not), the clock, the
 * revocation list and the expected verdict. [AttestationPolicyTest] checks them; [IdentityFixturesTest] writes them
 * out for `devtool attest --fixtures`.
 */
object Scenarios {
    class Case(
        val name: String,
        val chain: List<ByteArray>,
        val expected: Verdict.Status,
        val codes: List<String> = emptyList(),
        val now: Long = NOW,
        val revoked: Boolean = false,
        val audience: Audience = Audience.MEMBER,
        val revocationFetchedAt: Long? = null,
    )

    /** Intermediate serial revoked under its decimal key; leaf serial revoked under its hex key. */
    const val REVOKED_DECIMAL_SERIAL = 9_000_000_007L
    const val REVOKED_HEX_SERIAL = 0x7ab3c0ffeeL

    /** The revocation list the revoked cases use, keyed as Google's live file is: one decimal, one hex, and noise. */
    val revocationJson: String = """
        {
          "entries": {
            "$REVOKED_DECIMAL_SERIAL": { "status": "REVOKED", "reason": "KEY_COMPROMISE" },
            "${REVOKED_HEX_SERIAL.toString(16)}": { "status": "SUSPENDED", "reason": "SOFTWARE_FLAW" },
            "c35747a084470c3135aeefe2b8d40cd6": { "status": "REVOKED" },
            "6681152659205225093": { "status": "REVOKED" }
          }
        }
    """.trimIndent()

    val all: List<Case> by lazy { build() }

    fun named(name: String): Case = all.first { it.name == name }

    private fun build(): List<Case> {
        val out = ArrayList<Case>()
        fun add(name: String, chain: TestChains.Chain, expected: Verdict.Status, vararg codes: String) = out.add(Case(name, chain.certs, expected, codes.toList()))

        add("accept-tee", TestChains.chain(), Verdict.Status.ACCEPT)
        add("accept-strongbox", TestChains.chain(Spec(level = SecurityLevel.STRONG_BOX)), Verdict.Status.ACCEPT)
        add("accept-root-not-presented", TestChains.chain(includeRoot = false), Verdict.Status.ACCEPT)
        add("stale-leaf-expired-10-days", TestChains.chain(leafNotAfter = NOW - 10 * DAY_MS), Verdict.Status.STALE)
        add("refuse-other-package", TestChains.chain(Spec(packages = listOf("com.example.other"))), Verdict.Status.REFUSE, "package")
        add("refuse-unlisted-signer", TestChains.chain(Spec(digests = listOf("ab".repeat(32)))), Verdict.Status.REFUSE, "signer")
        add("refuse-software-level", TestChains.chain(Spec(level = SecurityLevel.SOFTWARE)), Verdict.Status.REFUSE, "software-level")
        add(
            "refuse-software-chain-as-emulators-make",
            TestChains.chain(Spec(level = SecurityLevel.SOFTWARE, rootOfTrustInHardware = false, locked = false, boot = VerifiedBootState.UNVERIFIED)),
            Verdict.Status.REFUSE, "software-level", "no-root-of-trust",
        )
        add("refuse-unlocked-bootloader", TestChains.chain(Spec(locked = false)), Verdict.Status.REFUSE, "unlocked")
        add("refuse-unverified-boot", TestChains.chain(Spec(boot = VerifiedBootState.UNVERIFIED)), Verdict.Status.REFUSE, "boot")
        add("refuse-self-signed-boot", TestChains.chain(Spec(boot = VerifiedBootState.SELF_SIGNED)), Verdict.Status.REFUSE, "boot")
        add("refuse-unknown-root", TestChains.chain(root = TestChains.otherRoot()), Verdict.Status.REFUSE, "root")
        add("refuse-leaf-past-grace", TestChains.chain(leafNotAfter = NOW - 61 * DAY_MS), Verdict.Status.REFUSE, "expired")
        add("refuse-not-yet-valid", TestChains.chain(leafNotBefore = NOW + 3 * DAY_MS), Verdict.Status.REFUSE, "not-yet-valid")
        out += Case("refuse-revoked-decimal-key", TestChains.chain(interSerial = REVOKED_DECIMAL_SERIAL).certs, Verdict.Status.REFUSE, listOf("revoked"), revoked = true)
        out += Case("refuse-revoked-hex-key", TestChains.chain(leafSerial = REVOKED_HEX_SERIAL).certs, Verdict.Status.REFUSE, listOf("revoked"), revoked = true)
        out += Case("accept-not-revoked", TestChains.chain().certs, Verdict.Status.ACCEPT, revoked = true)
        add("refuse-extension-only-in-later-certificate", TestChains.chain(leafHasAttestation = false, interExtensions = listOf(TestChains.Ext(KeyDescription.OID, TestChains.keyDescription(Spec())))), Verdict.Status.REFUSE, "extended-chain")
        add("refuse-chain-extended-below-attested-key", TestChains.extendedChain(), Verdict.Status.REFUSE, "extended-chain")
        add("refuse-no-attestation", TestChains.chain(leafHasAttestation = false), Verdict.Status.REFUSE, "no-attestation")
        add(
            "accept-rkp-provisioning-info-above-leaf",
            TestChains.chain(interExtensions = listOf(TestChains.Ext(KeyDescription.PROVISIONING_INFO_OID, Der.sequence()))),
            Verdict.Status.ACCEPT,
        )
        val tampered = TestChains.chain().certs.toMutableList().also { it[0] = it[0].copyOf().apply { this[size - 5] = (this[size - 5].toInt() xor 0x01).toByte() } }
        out += Case("refuse-bad-signature", tampered, Verdict.Status.REFUSE, listOf("signature"))
        out += Case("refuse-stranger-without-fresh-list", TestChains.chain().certs, Verdict.Status.REFUSE, listOf("revocation-list-old"), revoked = true, audience = Audience.STRANGER, revocationFetchedAt = NOW - 31 * DAY_MS)
        out += Case("accept-stranger-with-fresh-list", TestChains.chain().certs, Verdict.Status.ACCEPT, revoked = true, audience = Audience.STRANGER, revocationFetchedAt = NOW - 2 * DAY_MS)
        out += Case("refuse-garbage", listOf(byteArrayOf(0x30, 0x03, 0x02, 0x01)), Verdict.Status.REFUSE, listOf("malformed"))
        return out
    }

    fun revocation(case: Case): RevocationList? =
        if (case.revoked) RevocationList.parse(revocationJson, case.revocationFetchedAt ?: (NOW - DAY_MS)) else null

    fun evaluate(case: Case): Verdict = TestChains.evaluate(case.chain, now = case.now, revocation = revocation(case), audience = case.audience)
}
