package io.github.jasondorjeshort.dystopo.identity

/** Whose chain is judged: a group member (any revocation list age, shown) or a stranger's note (a list under 30 days). */
enum class Audience(val json: String) { MEMBER("member"), STRANGER("stranger") }

/**
 * Why a chain is refused, with what the refused phone's owner can do about it (ADR 0051, "every verdict is shown
 * with reasons and a lift"). [message] and [lift] are the plain sentences the phone shows (stage 1a's self-check
 * and the verify route); [code] is the stable key reports and tests use.
 */
enum class Reason(val code: String, val message: String, val lift: String) {
    MALFORMED("malformed", "The phone's proof could not be read.", "Update Dystopo on both phones. If it stays, this phone's maker ships attestation that does not work."),
    UNKNOWN_PLATFORM("platform", "The proof is of a kind this phone does not read.", "This phone runs an older Dystopo; update both."),
    BAD_SIGNATURE("signature", "A certificate in the proof is not signed by the one above it.", "None: the proof was altered."),
    UNKNOWN_ROOT("root", "The proof does not lead to a known attestation root.", "Update Dystopo (its root list may be out of date). Otherwise this phone's maker ships attestation that does not work."),
    EXPIRED("expired", "The proof expired more than 60 days ago.", "Connect to the internet once so the phone renews its identity."),
    NOT_YET_VALID("not-yet-valid", "The proof is not valid yet.", "Check the date and time on both phones."),
    NO_ATTESTATION("no-attestation", "No certificate in the proof carries a key attestation.", "None on this phone: its maker ships attestation that does not work."),
    EXTENDED_CHAIN("extended-chain", "The attestation is not on the proof's last key: the chain was extended past the secure hardware's certificate.", "None: the proof was altered."),
    SOFTWARE_LEVEL("software-level", "The key is not held in secure hardware (software attestation).", "None on this phone: it needs secure hardware (TEE or StrongBox). Emulators always fail this."),
    WRONG_PACKAGE("package", "The proof names another app.", "Install Dystopo from the project's releases."),
    UNKNOWN_SIGNER("signer", "The app is not signed with a listed release key.", "Install the signed release from the project's releases."),
    BOOTLOADER_UNLOCKED("unlocked", "The phone's bootloader is unlocked.", "Relock the bootloader."),
    BOOT_NOT_VERIFIED("boot", "The phone's system is not verified by its maker's boot key.", "Restore the maker's system and relock the bootloader."),
    NO_ROOT_OF_TRUST("no-root-of-trust", "The attestation has no hardware root of trust.", "None on this phone: its maker ships attestation that does not work."),
    REVOKED("revoked", "A certificate in the proof is on Google's revocation list.", "None on this phone: its maker's attestation key was revoked."),
    REVOCATION_LIST_OLD("revocation-list-old", "This phone's copy of the revocation list is missing or older than 30 days.", "Connect to the internet to refresh it."),
    CHALLENGE_MISMATCH("challenge", "The attestation answers another challenge.", "Make the identity again."),
}

class Refusal(val reason: Reason, val detail: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf("code" to reason.code, "message" to reason.message, "lift" to reason.lift, "detail" to detail)
}

/**
 * A chain's verdict: [Status.ACCEPT], [Status.STALE] (every check passed but the chain ended within the grace window:
 * the member is shown stale, not refused) or [Status.REFUSE] with every reason found.
 */
class Verdict(
    val status: Status,
    val reasons: List<Refusal>,
    val description: KeyDescription?,
    /** The attested key (the leaf's SubjectPublicKeyInfo) when the chain could be read. */
    val identityKey: ByteArray?,
    val anchor: RootEntry?,
    val chainLength: Int,
    /** The earliest end among the chain's own certificates (the presented root excluded). */
    val chainEnd: Long?,
    val now: Long,
    val revocation: RevocationList?,
) {
    enum class Status(val json: String) { ACCEPT("accept"), STALE("stale"), REFUSE("refuse") }

    val accepted: Boolean get() = status != Status.REFUSE

    val codes: List<String> get() = reasons.map { it.reason.code }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "status" to status.json,
        "reasons" to reasons.map { it.toMap() },
        "identityKeyId" to identityKey?.let { Sha.keyId(it) },
        "securityLevel" to description?.attestationSecurityLevel?.json,
        "chainLength" to chainLength,
        "anchor" to anchor?.let { linkedMapOf("name" to it.name, "keyId" to it.keyId) },
        "chainEnd" to chainEnd?.let { Der.iso(it) },
        "at" to Der.iso(now),
        "staleDays" to if (status == Status.STALE && chainEnd != null) (now - chainEnd) / DAY else null,
        "revocation" to revocation?.let { linkedMapOf("entries" to it.entries, "ageDays" to it.ageMillis(now)?.let { a -> a / DAY }) },
        "attestation" to description?.toMap(),
    )
}

internal const val DAY = 86_400_000L

/**
 * The Android key attestation policy (ADR 0051; platform [Platform.ANDROID_KEY_ATTESTATION_V1]). The lists are data
 * ([PolicyLists]); the trust anchors are a parameter of [evaluate], so the release roots, a debug build's test root
 * and tests share one code path.
 *
 * A chain (leaf first, as the Keystore returns it) passes when:
 * - every certificate is signed by the next, up to the first certificate whose public key is an anchor (that
 *   presented root and anything after it are stripped: its dates are ignored and its own signature unread), or, with
 *   no root presented, the last certificate is signed by an anchor key;
 * - its certificates are within their dates; a chain that ended at most [graceMillis] ago is [Verdict.Status.STALE];
 * - the key attestation extension's first occurrence counted from the root (Google: "only the first occurrence of
 *   the extension in the chain can be trusted"; with RKP's provisioning extension, in the certificate right after
 *   it) is in the leaf, so a chain extended below an attested key is refused;
 * - the attestation is TEE or StrongBox (both security levels), names only listed packages and only listed signer
 *   digests, has a hardware root of trust with a locked bootloader and a verified boot;
 * - no certificate's serial is on the revocation list, and for a stranger the list is younger than
 *   [strangerRevocationMaxAgeMillis].
 * Every failed check adds a [Refusal]; a parse failure anywhere is [Reason.MALFORMED], never an acceptance.
 */
class AttestationPolicy(
    val packages: Set<String>,
    /** SHA-256 digests of accepted signing certificates, lowercase hex. */
    val signers: Set<String>,
    val requireHardware: Boolean = true,
    val graceMillis: Long = 60 * DAY,
    val strangerRevocationMaxAgeMillis: Long = 30 * DAY,
    /** How far ahead of a certificate's start a clock may be before the chain is "not valid yet". */
    val clockSkewMillis: Long = DAY,
) {
    val platform: String get() = Platform.ANDROID_KEY_ATTESTATION_V1

    /** Judges an identity record: its platform tag first, then its chain. */
    fun evaluate(record: IdentityRecord, anchors: TrustAnchors, verifier: Verifier, now: Long, revocation: RevocationList?, audience: Audience = Audience.MEMBER, expectedChallenge: ByteArray? = null): Verdict =
        if (record.platform != platform) {
            Verdict(Verdict.Status.REFUSE, listOf(Refusal(Reason.UNKNOWN_PLATFORM, "platform '${record.platform}'")), null, null, null, record.chain.size, null, now, revocation)
        } else {
            evaluate(record.chain, anchors, verifier, now, revocation, audience, expectedChallenge)
        }

    fun evaluate(chain: List<ByteArray>, anchors: TrustAnchors, verifier: Verifier, now: Long, revocation: RevocationList?, audience: Audience = Audience.MEMBER, expectedChallenge: ByteArray? = null): Verdict {
        fun refuse(reason: Reason, detail: String) = Verdict(Verdict.Status.REFUSE, listOf(Refusal(reason, detail)), null, null, null, chain.size, null, now, revocation)
        if (chain.isEmpty()) return refuse(Reason.MALFORMED, "empty chain")
        if (chain.size > MAX_CHAIN) return refuse(Reason.MALFORMED, "${chain.size} certificates, at most $MAX_CHAIN")
        val certs = try {
            chain.map { X509Cert.parse(it) }
        } catch (e: AttestationFormatException) {
            return refuse(Reason.MALFORMED, e.message ?: "unreadable certificate")
        }
        val reasons = ArrayList<Refusal>()

        // 1. Signatures up to an anchor key; the presented root and anything after it are stripped.
        var anchorIndex = -1
        for (i in 1 until certs.size) if (anchors.find(certs[i].publicKey) != null) {
            anchorIndex = i
            break
        }
        val own: List<X509Cert>
        val anchor: RootEntry?
        if (anchorIndex > 0) {
            own = certs.subList(0, anchorIndex)
            anchor = anchors.find(certs[anchorIndex].publicKey)
        } else {
            own = certs
            val last = certs.last()
            anchor = anchors.keys.firstOrNull { verifier.verify(it.publicKey, last.signatureAlgorithm, last.tbs, last.signature) }
            if (anchor == null) reasons += Refusal(Reason.UNKNOWN_ROOT, "the last certificate is not signed by any of ${anchors.keys.size} anchor keys")
        }
        for (j in own.indices) {
            val issuerKey = if (j + 1 < own.size) own[j + 1].publicKey else anchor?.publicKey ?: continue
            if (!verifier.verify(issuerKey, own[j].signatureAlgorithm, own[j].tbs, own[j].signature)) {
                reasons += Refusal(Reason.BAD_SIGNATURE, "certificate $j (serial ${own[j].serialHex}) does not verify under certificate ${j + 1}'s key")
            }
        }

        // 2. Dates of the chain's own certificates.
        val chainEnd = own.minOf { it.notAfter }
        val chainStart = own.maxOf { it.notBefore }
        var stale = false
        if (now + clockSkewMillis < chainStart) reasons += Refusal(Reason.NOT_YET_VALID, "valid from ${Der.iso(chainStart)}")
        if (now > chainEnd) {
            if (now - chainEnd <= graceMillis) stale = true
            else reasons += Refusal(Reason.EXPIRED, "ended ${Der.iso(chainEnd)}, ${(now - chainEnd) / DAY} days ago")
        }

        // 3. The key description: its first occurrence from the root, which must be the leaf.
        var description: KeyDescription? = null
        val extIndex = own.indices.reversed().firstOrNull { own[it].extension(KeyDescription.OID) != null }
        // T216: a self-signed root left in `own` (no anchor matched) is not an attestation key's certificate; the RKP test
        // CA of the API 35 emulator ("Droid Unregistered Device CA") carries the provisioning extension on its root too,
        // which read as an extended chain and hid the attestation's fields.
        val provIndex = own.indices.reversed().firstOrNull { i ->
            own[i].extension(KeyDescription.PROVISIONING_INFO_OID) != null && !(i == own.lastIndex && i > 0 && own[i].issuer.contentEquals(own[i].subject))
        }
        when {
            extIndex == null -> reasons += Refusal(Reason.NO_ATTESTATION, "no certificate carries ${KeyDescription.OID}")
            extIndex != 0 -> reasons += Refusal(Reason.EXTENDED_CHAIN, "the first attestation from the root is in certificate $extIndex, not the leaf")
            provIndex != null && provIndex != extIndex + 1 -> reasons += Refusal(Reason.EXTENDED_CHAIN, "the provisioning information is in certificate $provIndex, not right above the attestation")
            else -> try {
                description = KeyDescription.parse(own[0].extension(KeyDescription.OID)!!.value)
            } catch (e: AttestationFormatException) {
                reasons += Refusal(Reason.MALFORMED, "key description: ${e.message}")
            }
        }

        // 4. The attestation's fields.
        description?.let { d -> reasons += fieldChecks(d, expectedChallenge) }

        // 5. Revocation, over every certificate of the chain's own part.
        val list = revocation ?: RevocationList.NONE
        own.forEachIndexed { i, c -> if (list.isRevoked(c.serialHex)) reasons += Refusal(Reason.REVOKED, "certificate $i, serial ${c.serialHex}") }
        if (audience == Audience.STRANGER) {
            val age = list.ageMillis(now)
            if (age == null || age > strangerRevocationMaxAgeMillis) reasons += Refusal(Reason.REVOCATION_LIST_OLD, if (age == null) "no list" else "${age / DAY} days old")
        }

        val status = when {
            reasons.isNotEmpty() -> Verdict.Status.REFUSE
            stale -> Verdict.Status.STALE
            else -> Verdict.Status.ACCEPT
        }
        return Verdict(status, reasons, description, own[0].publicKey, anchor, certs.size, chainEnd, now, revocation)
    }

    private fun fieldChecks(d: KeyDescription, expectedChallenge: ByteArray?): List<Refusal> {
        val out = ArrayList<Refusal>()
        if (requireHardware && !(d.attestationSecurityLevel.hardware && d.keyMintSecurityLevel.hardware)) {
            out += Refusal(Reason.SOFTWARE_LEVEL, "attestation ${d.attestationSecurityLevel.json}, key ${d.keyMintSecurityLevel.json}")
        }
        val app = d.applicationId
        val names = app?.packages?.map { it.name }.orEmpty()
        if (names.isEmpty() || names.any { it !in packages }) out += Refusal(Reason.WRONG_PACKAGE, if (names.isEmpty()) "no application id" else "packages ${names.joinToString()}")
        val digests = app?.signatureDigests?.map { it.hex() }.orEmpty()
        if (digests.isEmpty() || digests.any { it !in signers }) out += Refusal(Reason.UNKNOWN_SIGNER, if (digests.isEmpty()) "no signer digests" else "signers ${digests.joinToString()}")
        val rot = if (requireHardware) d.hardwareEnforced.rootOfTrust else d.rootOfTrust
        if (rot == null) {
            out += Refusal(Reason.NO_ROOT_OF_TRUST, "no rootOfTrust in the hardware-enforced list")
        } else {
            if (!rot.deviceLocked) out += Refusal(Reason.BOOTLOADER_UNLOCKED, "deviceLocked false")
            if (rot.verifiedBootState != KeyDescription.VerifiedBootState.VERIFIED) out += Refusal(Reason.BOOT_NOT_VERIFIED, "verifiedBootState ${rot.verifiedBootState.json}")
        }
        if (expectedChallenge != null && !expectedChallenge.contentEquals(d.challenge)) out += Refusal(Reason.CHALLENGE_MISMATCH, "challenge differs")
        return out
    }

    companion object {
        const val MAX_CHAIN = 10

        /** The release policy over the bundled lists. */
        fun release(lists: PolicyLists = PolicyLists.release): AttestationPolicy =
            AttestationPolicy(lists.packages.toSet(), lists.signers.map { it.sha256 }.toSet())
    }
}
