package io.github.jasondorjeshort.dystopo.app.identity

import io.github.jasondorjeshort.dystopo.identity.AttestationPolicy
import io.github.jasondorjeshort.dystopo.identity.JavaSecurityVerifier
import io.github.jasondorjeshort.dystopo.identity.KeyDescription
import io.github.jasondorjeshort.dystopo.identity.PolicyLists
import io.github.jasondorjeshort.dystopo.identity.Reason
import io.github.jasondorjeshort.dystopo.identity.Refusal
import io.github.jasondorjeshort.dystopo.identity.Verdict
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T216: the phone half's rules (ADR 0051, stage 1a) and the cross-phone accept and refuse paths on the JVM: a chain
 * minted under the debug build's test root ([TestChainMint], as `POST /identity/test-chain` does) judged by the debug
 * policy another phone would use ([IdentityRules.debugPolicy]) and by the release policy.
 */
class IdentityRulesTest {
    private val day = IdentityRules.DAY
    private val now = 1_793_000_000_000L // 2026-10-26

    /** The debug build's asset is this file (androidApp/build.gradle.kts adds its folder to the debug assets). */
    private val root = TestChainMint.root(File("../shared/src/jvmTest/resources/identity/test-root.json").readText())
    private val pkg = PolicyLists.release.packages.single()
    private val debugSigner = "d".repeat(64)
    private val release = PolicyLists.release.signers.single().sha256

    private fun spec(
        level: KeyDescription.SecurityLevel = KeyDescription.SecurityLevel.TRUSTED_ENVIRONMENT,
        packages: List<String> = listOf(pkg),
        digests: List<String> = listOf(debugSigner),
        locked: Boolean = true,
        boot: KeyDescription.VerifiedBootState = KeyDescription.VerifiedBootState.VERIFIED,
        notAfter: Long = now + 365 * day,
        testRoot: Boolean = true,
    ) = TestChainMint.Spec(level, packages, digests, locked, boot, minOf(now, notAfter) - 30 * day, notAfter, testRoot)

    private fun debugVerdict(s: TestChainMint.Spec): Verdict =
        IdentityRules.debugPolicy(listOf(debugSigner)).evaluate(TestChainMint.mint(s, root, now), IdentityRules.debugAnchors(root.anchors), JavaSecurityVerifier, now, null)

    @Test fun mintedChainsAcceptAndRefuseUnderTheDebugPolicy() {
        assertEquals(Verdict.Status.ACCEPT, debugVerdict(spec()).status)
        assertEquals(Verdict.Status.ACCEPT, debugVerdict(spec(level = KeyDescription.SecurityLevel.STRONG_BOX)).status)
        assertEquals(Verdict.Status.STALE, debugVerdict(spec(notAfter = now - 10 * day)).status)
        val refusals = mapOf(
            "software-level" to spec(level = KeyDescription.SecurityLevel.SOFTWARE),
            "unlocked" to spec(locked = false),
            "boot" to spec(boot = KeyDescription.VerifiedBootState.UNVERIFIED),
            "package" to spec(packages = listOf("org.example.other")),
            "signer" to spec(digests = listOf("e".repeat(64))),
            "root" to spec(testRoot = false),
            "expired" to spec(notAfter = now - 90 * day),
        )
        for ((code, s) in refusals) {
            val v = debugVerdict(s)
            assertEquals(Verdict.Status.REFUSE, v.status, code)
            assertTrue(code in v.codes, "$code: ${v.codes}")
            v.reasons.forEach { assertTrue(it.reason.lift.isNotBlank()) }
        }
    }

    @Test fun releasePolicyRefusesTheTestRootAndTheDebugSigner() {
        val chain = TestChainMint.mint(spec(), root, now)
        val v = AttestationPolicy.release().evaluate(chain, PolicyLists.release.anchors, JavaSecurityVerifier, now, null)
        assertEquals(Verdict.Status.REFUSE, v.status)
        assertTrue("root" in v.codes && "signer" in v.codes, "${v.codes}")
        // With the release signer and the test anchors (what devtool attest --anchors test reads), the release rules accept.
        val withRelease = TestChainMint.mint(spec(digests = listOf(release)), root, now)
        assertEquals(Verdict.Status.ACCEPT, AttestationPolicy.release().evaluate(withRelease, root.anchors, JavaSecurityVerifier, now, null).status)
    }

    @Test fun debugPolicyAddsTheOwnSignerAtRunTimeAndLeavesTheReleaseListsAlone() {
        val p = IdentityRules.debugPolicy(listOf(debugSigner.uppercase()))
        assertTrue(debugSigner in p.signers && release in p.signers)
        assertEquals(listOf(release), PolicyLists.release.signers.map { it.sha256 })
        assertFalse(debugSigner in AttestationPolicy.release().signers)
        val anchors = IdentityRules.debugAnchors(root.anchors)
        assertEquals(PolicyLists.release.anchors.keys.size + 1, anchors.keys.size)
        assertFalse(root.anchors.keyIds.single() in PolicyLists.release.anchors.keyIds)
        assertEquals(PolicyLists.release.anchors.keyIds, IdentityRules.debugAnchors(null).keyIds)
    }

    @Test fun renewalTimes() {
        assertTrue(IdentityRules.signingKeyDue(null, now))
        assertFalse(IdentityRules.signingKeyDue(now + 2 * day, now))
        assertTrue(IdentityRules.signingKeyDue(now + day, now))
        assertFalse(IdentityRules.successorDue(null, now, null))
        assertFalse(IdentityRules.successorDue(now + 15 * day, now, null))
        assertTrue(IdentityRules.successorDue(now + 14 * day, now, null))
        assertTrue(IdentityRules.successorDue(now - day, now, now - 2 * day))
        assertFalse(IdentityRules.successorDue(now + day, now, now - day / 2)) // at most once a day
        assertTrue(IdentityRules.revocationDue(null, now))
        assertFalse(IdentityRules.revocationDue(now - day / 2, now))
        assertTrue(IdentityRules.revocationDue(now - day, now))
        assertTrue(IdentityRules.revocationDue(now + day, now)) // a clock set back
    }

    @Test fun selfCheckLines() {
        val accept = debugVerdict(spec())
        val ok = IdentityRules.line(IdentityRules.Phase.READY, accept)
        assertTrue(ok.ok)
        assertEquals("This phone can prove it runs the signed app.", ok.text)
        assertEquals("Its key is held in the phone's secure hardware (TEE).", ok.detail)
        assertEquals("Its key is held in a separate security chip (StrongBox).", IdentityRules.line(IdentityRules.Phase.READY, debugVerdict(spec(level = KeyDescription.SecurityLevel.STRONG_BOX))).detail)

        // An emulator-like chain: a software key under an unknown root, signed by a debug key. The line names the
        // software level (the cause), not the root or the signer.
        val soft = AttestationPolicy.release().evaluate(
            TestChainMint.mint(spec(level = KeyDescription.SecurityLevel.SOFTWARE, locked = false), root, now), PolicyLists.release.anchors, JavaSecurityVerifier, now, null,
        )
        assertEquals(Reason.SOFTWARE_LEVEL, IdentityRules.primaryReason(soft.reasons)!!.reason)
        val bad = IdentityRules.line(IdentityRules.Phase.READY, soft)
        assertFalse(bad.ok)
        assertEquals("This phone cannot prove it runs the signed app: the key is not held in secure hardware (software attestation).", bad.text)
        val more = soft.codes.distinct().size - 1
        assertEquals("${Reason.SOFTWARE_LEVEL.lift} ($more more reasons)", bad.detail)

        val stale = IdentityRules.line(IdentityRules.Phase.READY, debugVerdict(spec(notAfter = now - 10 * day)))
        assertEquals("This phone's proof ended 10 days ago.", stale.text)
        assertEquals("Connect to the internet once to set up sharing.", IdentityRules.line(IdentityRules.Phase.OFFLINE, null).text)
        assertTrue(IdentityRules.line(IdentityRules.Phase.KEYS_UNAVAILABLE, null).text.contains("no attestation keys"))
        assertTrue(IdentityRules.line(IdentityRules.Phase.ERROR, null, "boom").text.endsWith(": boom"))
        assertTrue(IdentityRules.line(IdentityRules.Phase.NONE, null).text.startsWith("Checking"))
    }

    /** T220: every verdict the line can read, each lone reason and all of them together. */
    private fun verdicts(): List<Verdict> {
        fun v(status: Verdict.Status, reasons: List<Refusal>, chainEnd: Long? = null) = Verdict(status, reasons, null, null, null, 0, chainEnd, now, null)
        return listOf(v(Verdict.Status.ACCEPT, emptyList()), v(Verdict.Status.STALE, emptyList(), now - 10 * day), v(Verdict.Status.REFUSE, emptyList())) +
            Reason.entries.map { v(Verdict.Status.REFUSE, listOf(Refusal(it, "found ${it.code}"))) } +
            v(Verdict.Status.REFUSE, Reason.entries.map { Refusal(it, "found ${it.code}") })
    }

    @Test fun everyPhaseAndVerdictKeepsItsSentenceAndLiftInView() {
        val cases = IdentityRules.Phase.entries.filter { it != IdentityRules.Phase.READY }.map { Triple(it, null as Verdict?, "boom" as String?) } +
            Triple(IdentityRules.Phase.READY, null, null) + verdicts().map { Triple(IdentityRules.Phase.READY, it, null) }
        assertEquals(6 + Reason.entries.size + 4, cases.size)
        for ((phase, verdict, error) in cases) {
            val line = IdentityRules.line(phase, verdict, error)
            val shown = IdentityRules.shown(phase, verdict, error)
            val where = "$phase ${verdict?.status} ${verdict?.reasons?.map { it.reason }}"
            assertEquals(line.text, shown.sentence, where) // the sentence is the line's, whole
            assertEquals(line.ok, shown.ok, where)
            val inView = listOfNotNull(shown.sentence, shown.lift).joinToString(" ")
            if (verdict != null && verdict.status == Verdict.Status.REFUSE) {
                IdentityRules.primaryReason(verdict.reasons)?.let { assertEquals(it.reason.lift, shown.lift, where) }
            }
            // A second sentence of the line is the lift, unless it is the reason (offline) or the detail (accepted).
            if (phase != IdentityRules.Phase.OFFLINE && verdict?.status != Verdict.Status.ACCEPT && verdict?.status != Verdict.Status.REFUSE) assertEquals(line.detail, shown.lift, where)
            // Every lift the line has is in view; the words are ADR 0051's.
            if (phase == IdentityRules.Phase.OFFLINE) assertTrue("Connect to the internet" in inView, where)
            assertFalse("official" in (inView + shown.reasons).lowercase(), where)
            assertEquals(shown.reasons.isNotEmpty(), shown.why != null, where)
        }
    }

    @Test fun onlyTheReasonsFold() {
        val offline = IdentityRules.shown(IdentityRules.Phase.OFFLINE, null)
        assertEquals("Connect to the internet once to set up sharing.", offline.sentence)
        assertEquals(listOf("This phone makes its identity key while online."), offline.reasons)
        assertEquals("Why?", offline.why)

        val keys = IdentityRules.shown(IdentityRules.Phase.KEYS_UNAVAILABLE, null)
        assertEquals("Try again later while online.", keys.lift)
        assertEquals(null, keys.why)

        val ok = IdentityRules.shown(IdentityRules.Phase.READY, debugVerdict(spec()))
        assertEquals("This phone can prove it runs the signed app.", ok.sentence)
        assertEquals(listOf("Its key is held in the phone's secure hardware (TEE)."), ok.reasons)

        val stale = IdentityRules.shown(IdentityRules.Phase.READY, debugVerdict(spec(notAfter = now - 10 * day)))
        assertEquals("Connect to the internet so it renews.", stale.lift)

        // An emulator-like chain: the first reason's lift in view, every reason behind the tap, in the line's order.
        val soft = AttestationPolicy.release().evaluate(
            TestChainMint.mint(spec(level = KeyDescription.SecurityLevel.SOFTWARE, locked = false), root, now), PolicyLists.release.anchors, JavaSecurityVerifier, now, null,
        )
        val shown = IdentityRules.shown(IdentityRules.Phase.READY, soft)
        assertEquals(Reason.SOFTWARE_LEVEL.lift, shown.lift)
        assertEquals(soft.codes.distinct().size, shown.reasons.size)
        assertEquals("Why? (${shown.reasons.size} reasons)", shown.why)
        assertTrue(shown.reasons.first().startsWith(Reason.SOFTWARE_LEVEL.message), shown.reasons.first())
        assertFalse("more reason" in shown.sentence + shown.lift)

        val one = IdentityRules.shown(IdentityRules.Phase.READY, AttestationPolicy.release().evaluate(TestChainMint.mint(spec(), root, now), root.anchors, JavaSecurityVerifier, now, null))
        assertEquals("Why?", one.why)
        assertEquals(Reason.UNKNOWN_SIGNER.lift, one.lift)
    }

    @Test fun getIdentityKeepsItsLine() {
        // T220: the route's `line` is still text, detail and ok, the refusal's "(n more reasons)" included.
        val soft = AttestationPolicy.release().evaluate(
            TestChainMint.mint(spec(level = KeyDescription.SecurityLevel.SOFTWARE, locked = false), root, now), PolicyLists.release.anchors, JavaSecurityVerifier, now, null,
        )
        val map = IdentityRules.line(IdentityRules.Phase.READY, soft).toMap()
        assertEquals(setOf("text", "detail", "ok"), map.keys)
        assertTrue((map["detail"] as String).endsWith("more reasons)"), map["detail"] as String)
    }

    @Test fun signerOnlyRefusalPassesTheHardwarePolicy() {
        val signerOnly = AttestationPolicy.release().evaluate(TestChainMint.mint(spec(), root, now), root.anchors, JavaSecurityVerifier, now, null)
        assertEquals(listOf("signer"), signerOnly.codes)
        assertTrue(IdentityRules.passesWithSignerRelaxed(signerOnly))
        assertFalse(IdentityRules.passesWithSignerRelaxed(debugVerdict(spec(locked = false))))
    }

    @Test fun everyReasonHasALinePlace() {
        assertEquals(Reason.entries.toSet(), IdentityRules.LINE_ORDER.toSet())
        assertEquals(Reason.entries.size, IdentityRules.LINE_ORDER.size)
        assertNull(IdentityRules.primaryReason(emptyList()))
    }
}
