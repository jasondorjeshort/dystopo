package io.github.jasondorjeshort.dystopo.app.identity

import io.github.jasondorjeshort.dystopo.identity.AttestationPolicy
import io.github.jasondorjeshort.dystopo.identity.KeyDescription
import io.github.jasondorjeshort.dystopo.identity.PolicyLists
import io.github.jasondorjeshort.dystopo.identity.Reason
import io.github.jasondorjeshort.dystopo.identity.Refusal
import io.github.jasondorjeshort.dystopo.identity.TrustAnchors
import io.github.jasondorjeshort.dystopo.identity.Verdict

/**
 * T216: the phone half's rules that need no Android (ADR 0051, stage 1a), so the unit tests run them on the JVM:
 * the debug policy, when keys are renewed, when the revocation list is refetched, and the Share screen's self-check
 * line. T220: [shown] is how the screen draws that line: its sentence and its lift in view, the reasons behind "Why?".
 */
object IdentityRules {
    const val DAY = 86_400_000L

    /** Signing keys live 30 days (ADR 0051) and are renewed a day before they end. */
    const val SIGNING_VALIDITY = 30 * DAY
    const val SIGNING_RENEW_BEFORE = DAY

    /** The successor identity is made when the identity chain ends within 14 days (ADR 0051), at most once a day. */
    const val SUCCESSOR_WINDOW = 14 * DAY
    const val ROTATION_MIN_INTERVAL = DAY

    /** The cached revocation list is refetched once a day while online (strangers' notes need one under 30 days). */
    const val REVOCATION_REFRESH = DAY

    fun signingKeyDue(validTo: Long?, now: Long): Boolean = validTo == null || now >= validTo - SIGNING_RENEW_BEFORE

    fun successorDue(chainEnd: Long?, now: Long, lastRotationAt: Long?): Boolean =
        chainEnd != null && chainEnd - now <= SUCCESSOR_WINDOW && (lastRotationAt == null || now - lastRotationAt >= ROTATION_MIN_INTERVAL)

    fun revocationDue(fetchedAt: Long?, now: Long): Boolean = fetchedAt == null || now - fetchedAt >= REVOCATION_REFRESH || now < fetchedAt

    /**
     * The debug policy (debug builds only, ADR 0051: "debug builds alone add the debug signer and a test root"): the
     * release lists plus this build's own signer digests, read from the running app at run time, so the release lists
     * never hold a debug key (`ReleaseListsTest`).
     */
    fun debugPolicy(ownSigners: List<String>, lists: PolicyLists = PolicyLists.release): AttestationPolicy =
        AttestationPolicy(lists.packages.toSet(), (lists.signers.map { it.sha256 } + ownSigners.map { it.lowercase() }).toSet())

    /** The release roots plus the test root (debug builds); the release roots alone when [testRoot] is null. */
    fun debugAnchors(testRoot: TrustAnchors?, lists: PolicyLists = PolicyLists.release): TrustAnchors =
        if (testRoot == null) lists.anchors else lists.anchors + testRoot

    /**
     * The order in which the self-check line picks the one reason it names. Causes on the phone itself come first,
     * because they explain the rest (a software key's chain never reaches a published root); the app's own signer comes
     * last, since a debug build always fails it. Every reason stays in `GET /identity`.
     */
    val LINE_ORDER: List<Reason> = listOf(
        Reason.MALFORMED, Reason.UNKNOWN_PLATFORM, Reason.NO_ATTESTATION, Reason.SOFTWARE_LEVEL, Reason.NO_ROOT_OF_TRUST,
        Reason.BOOTLOADER_UNLOCKED, Reason.BOOT_NOT_VERIFIED, Reason.REVOKED, Reason.UNKNOWN_ROOT, Reason.BAD_SIGNATURE,
        Reason.EXTENDED_CHAIN, Reason.EXPIRED, Reason.NOT_YET_VALID, Reason.CHALLENGE_MISMATCH, Reason.WRONG_PACKAGE,
        Reason.UNKNOWN_SIGNER, Reason.REVOCATION_LIST_OLD,
    )

    fun primaryReason(reasons: List<Refusal>): Refusal? = reasons.minByOrNull { LINE_ORDER.indexOf(it.reason).let { i -> if (i < 0) LINE_ORDER.size else i } }

    /** True when the only failed check is the app's signer: the hardware policy passes (ADR 0051's first prediction). */
    fun passesWithSignerRelaxed(v: Verdict): Boolean = v.accepted || v.reasons.all { it.reason == Reason.UNKNOWN_SIGNER }

    /** Where the phone's identity stands, as the Share screen's line reads it. */
    enum class Phase(val json: String) {
        NONE("none"),
        CHECKING("checking"),
        OFFLINE("offline"),
        KEYS_UNAVAILABLE("keys-unavailable"),
        ERROR("error"),
        READY("ready"),
    }

    /** The self-check line: the sentence, an optional second sentence (the lift) and whether it is good news. */
    data class Line(val text: String, val detail: String?, val ok: Boolean) {
        fun toMap(): Map<String, Any?> = linkedMapOf("text" to text, "detail" to detail, "ok" to ok)
    }

    /** The Share screen's self-check line for [phase] and, when ready, the release policy's [verdict]. */
    fun line(phase: Phase, verdict: Verdict?, error: String? = null): Line = when (phase) {
        Phase.NONE, Phase.CHECKING -> Line("Checking that this phone can prove it runs the signed app...", null, true)
        Phase.OFFLINE -> Line("Connect to the internet once to set up sharing.", "This phone makes its identity key while online.", false)
        Phase.KEYS_UNAVAILABLE -> Line(
            "This phone could not make its identity key: its key service had no attestation keys.",
            "Try again later while online.",
            false,
        )
        Phase.ERROR -> Line("This phone could not make its identity key${error?.let { ": $it" } ?: "."}", "Try again later while online.", false)
        Phase.READY -> when {
            verdict == null -> Line("Checking that this phone can prove it runs the signed app...", null, true)
            verdict.status == Verdict.Status.ACCEPT -> Line("This phone can prove it runs the signed app.", "Its key is held in ${where(verdict.description?.attestationSecurityLevel)}.", true)
            verdict.status == Verdict.Status.STALE -> Line(
                "This phone's proof ended ${verdict.chainEnd?.let { (verdict.now - it) / DAY } ?: 0} days ago.",
                "Connect to the internet so it renews.",
                false,
            )
            else -> {
                val first = primaryReason(verdict.reasons)
                val more = verdict.reasons.map { it.reason }.distinct().size - 1
                val message = first?.reason?.message?.let { it.replaceFirstChar { c -> c.lowercaseChar() } } ?: "the check failed."
                Line(
                    "This phone cannot prove it runs the signed app: $message",
                    listOfNotNull(first?.reason?.lift, if (more > 0) "($more more ${if (more == 1) "reason" else "reasons"})" else null).joinToString(" "),
                    false,
                )
            }
        }
    }

    /**
     * T220: the self-check line as the Share screen draws it: [sentence] and [lift] always in view (ADR 0051: every verdict
     * is shown with reasons and a lift, and the owner reads this line for the ADR's first prediction), [reasons] behind a
     * tap on the same screen. [count] is how many distinct reasons a refusal has. `GET /identity` keeps [line].
     */
    data class Shown(val sentence: String, val lift: String?, val reasons: List<String>, val count: Int, val ok: Boolean) {
        /** The fold's label, or null when there is nothing to fold. */
        val why: String? get() = when {
            reasons.isEmpty() -> null
            count > 1 -> "Why? ($count reasons)"
            else -> "Why?"
        }

        fun toMap(): Map<String, Any?> = linkedMapOf("sentence" to sentence, "lift" to lift, "why" to why, "reasons" to reasons, "ok" to ok)
    }

    /**
     * The line of [phase] and [verdict] split for the screen, from [line] so the two cannot differ. Offline, the sentence is
     * the lift itself and the line's second sentence is the reason; when accepted, the second sentence (where the key is
     * held) is the detail; a refusal's lift is its first reason's, and every reason folds with its message and detail, the
     * first reason first.
     */
    fun shown(phase: Phase, verdict: Verdict?, error: String? = null): Shown {
        val l = line(phase, verdict, error)
        val status = if (phase == Phase.READY) verdict?.status else null
        return when {
            phase == Phase.OFFLINE || status == Verdict.Status.ACCEPT -> Shown(l.text, null, listOfNotNull(l.detail), 0, l.ok)
            status == Verdict.Status.REFUSE -> {
                val lines = reasonLines(verdict!!.reasons)
                Shown(l.text, primaryReason(verdict.reasons)?.reason?.lift, lines, lines.size, l.ok)
            }
            else -> Shown(l.text, l.detail, emptyList(), 0, l.ok)
        }
    }

    /** One line per distinct reason, in [LINE_ORDER]: its message, its lift, what the check found. */
    private fun reasonLines(reasons: List<Refusal>): List<String> =
        reasons.groupBy { it.reason }.entries.sortedBy { LINE_ORDER.indexOf(it.key) }.map { (reason, refusals) ->
            val found = refusals.map { it.detail }.filter { it.isNotBlank() }.distinct().joinToString("; ")
            listOf(reason.message, reason.lift, found).filter { it.isNotBlank() }.joinToString(" ")
        }

    private fun where(level: KeyDescription.SecurityLevel?): String = when (level) {
        KeyDescription.SecurityLevel.STRONG_BOX -> "a separate security chip (StrongBox)"
        else -> "the phone's secure hardware (TEE)"
    }
}
