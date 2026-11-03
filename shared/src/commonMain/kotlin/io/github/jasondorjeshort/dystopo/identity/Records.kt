package io.github.jasondorjeshort.dystopo.identity

/**
 * The identity records of ADR 0051 (stage 1a), DER-encoded so the bytes signed are unambiguous. Each starts with its
 * type, its version ([VERSION]) and the platform tag; a reader that meets a newer version refuses with
 * [AttestationFormatException] ("This phone runs an older Dystopo; update both", ADR 0033 §3).
 *
 * - [IdentityRecord]: the identity key's attestation chain (leaf first). The identity key is the attested leaf key;
 *   pins bind to its id ([Sha.keyId]).
 * - [CertificationRecord]: a 30-day signing key certified by the identity key. Records (positions, notes) are signed
 *   by a signing key, so making one needs no provisioning.
 * - [RotationRecord]: a new identity key, signed by the old identity key and by the new one, so pins and mutes
 *   follow an identity across a re-key ([PinSet.applyRotation]). The new key's own chain is judged by the policy as
 *   any identity's.
 *
 * Record signatures are ECDSA P-256 with SHA-256 ([Oids.ECDSA_SHA256]), DER-encoded.
 */
object Records {
    const val VERSION = 1L
    const val MAX_SIZE = 64 * 1024

    internal fun header(type: String, platform: String) = listOf(Der.utf8(type), Der.integer(VERSION), Der.utf8(platform))

    /** Reads the type, version and platform; returns the platform and leaves [r] at the body. */
    internal fun readHeader(r: DerReader, type: String): String {
        val t = r.next().asText("record type", 64)
        if (t != type) malformed("expected a $type record, found '$t'")
        val v = r.next().asLong("record version")
        if (v != VERSION) malformed("$type record version $v; this phone reads $VERSION (update both)")
        return r.next().asText("platform", 64)
    }

    internal fun open(bytes: ByteArray, what: String): DerReader {
        if (bytes.size > MAX_SIZE) malformed("$what of ${bytes.size} bytes, at most $MAX_SIZE")
        return DerReader.single(bytes, what).expectSequence(what).reader()
    }
}

class IdentityRecord(val platform: String, val chain: List<ByteArray>) {
    /** The attested identity key (the leaf's SubjectPublicKeyInfo). */
    val identityKey: ByteArray get() = X509Cert.parse(chain.first()).publicKey

    fun encode(): ByteArray = Der.sequence(Records.header(TYPE, platform) + Der.sequence(chain))

    companion object {
        const val TYPE = "dystopo-identity"

        fun decode(bytes: ByteArray): IdentityRecord {
            val r = Records.open(bytes, "identity record")
            val platform = Records.readHeader(r, TYPE)
            val certs = r.next().expectSequence("chain").children()
            r.finish("identity record")
            if (certs.isEmpty() || certs.size > AttestationPolicy.MAX_CHAIN) malformed("identity record with ${certs.size} certificates")
            return IdentityRecord(platform, certs.map { it.expectSequence("certificate").encoded() })
        }
    }
}

class CertificationRecord(
    val platform: String,
    /** The certifying identity key's id ([Sha.keyId]). */
    val identityKeyId: String,
    /** The certified signing key, DER SubjectPublicKeyInfo. */
    val signingKey: ByteArray,
    val validFrom: Long,
    val validTo: Long,
    val signature: ByteArray,
) {
    fun encode(): ByteArray = Der.sequence(tbs(platform, identityKeyId, signingKey, validFrom, validTo), Der.octets(signature))

    /** Null when the record holds for [identityKey] at [now]; else why not. */
    fun check(identityKey: ByteArray, verifier: Verifier, now: Long): String? = when {
        platform !in Platform.KNOWN -> "platform '$platform'"
        identityKeyId != Sha.keyId(identityKey) -> "certified by another identity key"
        validTo <= validFrom || validTo - validFrom > MAX_VALIDITY -> "validity of ${(validTo - validFrom) / DAY} days, at most ${MAX_VALIDITY / DAY}"
        !verifier.verify(identityKey, Oids.ECDSA_SHA256, tbs(platform, identityKeyId, signingKey, validFrom, validTo), signature) -> "bad signature"
        now < validFrom -> "not valid until ${Der.iso(validFrom)}"
        now > validTo -> "ended ${Der.iso(validTo)}"
        else -> null
    }

    companion object {
        const val TYPE = "dystopo-certification"

        /** 30-day signing keys (ADR 0051), with a day's slack for clocks. */
        const val MAX_VALIDITY = 31 * DAY

        private fun tbs(platform: String, identityKeyId: String, signingKey: ByteArray, from: Long, to: Long) =
            Der.sequence(Records.header(TYPE, platform) + listOf(Der.utf8(identityKeyId), Der.octets(signingKey), Der.integer(from), Der.integer(to)))

        fun create(identity: RecordSigner, signingKey: ByteArray, validFrom: Long, validTo: Long, platform: String = Platform.ANDROID_KEY_ATTESTATION_V1): CertificationRecord {
            val id = Sha.keyId(identity.publicKey)
            return CertificationRecord(platform, id, signingKey, validFrom, validTo, identity.sign(tbs(platform, id, signingKey, validFrom, validTo)))
        }

        fun decode(bytes: ByteArray): CertificationRecord {
            val r = Records.open(bytes, "certification record")
            val t = r.next().expectSequence("certification").reader()
            val platform = Records.readHeader(t, TYPE)
            val id = t.next().asText("identity key id", 64)
            val key = t.next().asOctets("signing key", 1024)
            val from = t.next().asLong("valid from")
            val to = t.next().asLong("valid to")
            t.finish("certification")
            val sig = r.next().asOctets("signature", 256)
            r.finish("certification record")
            return CertificationRecord(platform, id, key, from, to, sig)
        }
    }
}

class RotationRecord(
    val platform: String,
    val oldKey: ByteArray,
    val newKey: ByteArray,
    val issuedAt: Long,
    val oldSignature: ByteArray,
    val newSignature: ByteArray,
) {
    fun encode(): ByteArray = Der.sequence(tbs(platform, oldKey, newKey, issuedAt), Der.octets(oldSignature), Der.octets(newSignature))

    /** Null when both keys signed this rotation; else why not. */
    fun check(verifier: Verifier): String? {
        val tbs = tbs(platform, oldKey, newKey, issuedAt)
        return when {
            platform !in Platform.KNOWN -> "platform '$platform'"
            oldKey.contentEquals(newKey) -> "the old and new keys are the same"
            !verifier.verify(oldKey, Oids.ECDSA_SHA256, tbs, oldSignature) -> "not signed by the old identity key"
            !verifier.verify(newKey, Oids.ECDSA_SHA256, tbs, newSignature) -> "not signed by the new identity key"
            else -> null
        }
    }

    companion object {
        const val TYPE = "dystopo-rotation"

        private fun tbs(platform: String, oldKey: ByteArray, newKey: ByteArray, issuedAt: Long) =
            Der.sequence(Records.header(TYPE, platform) + listOf(Der.octets(oldKey), Der.octets(newKey), Der.integer(issuedAt)))

        fun create(old: RecordSigner, new: RecordSigner, issuedAt: Long, platform: String = Platform.ANDROID_KEY_ATTESTATION_V1): RotationRecord {
            val tbs = tbs(platform, old.publicKey, new.publicKey, issuedAt)
            return RotationRecord(platform, old.publicKey, new.publicKey, issuedAt, old.sign(tbs), new.sign(tbs))
        }

        fun decode(bytes: ByteArray): RotationRecord {
            val r = Records.open(bytes, "rotation record")
            val t = r.next().expectSequence("rotation").reader()
            val platform = Records.readHeader(t, TYPE)
            val old = t.next().asOctets("old key", 1024)
            val new = t.next().asOctets("new key", 1024)
            val at = t.next().asLong("issued at")
            t.finish("rotation")
            val so = r.next().asOctets("old signature", 256)
            val sn = r.next().asOctets("new signature", 256)
            r.finish("rotation record")
            return RotationRecord(platform, old, new, at, so, sn)
        }
    }
}

/**
 * Pins (a group member, a contact, a mute) bound to identity key ids, never to signing keys (ADR 0051). A verified
 * rotation record moves a pin from the old key to the new one.
 */
class PinSet {
    private val pins = LinkedHashMap<String, String>()

    /** Pins [identityKey] with [label] (a member's name, "muted"). */
    fun pin(identityKey: ByteArray, label: String) {
        pins[Sha.keyId(identityKey)] = label
    }

    fun label(identityKey: ByteArray): String? = pins[Sha.keyId(identityKey)]

    val size: Int get() = pins.size

    enum class Rotation { MOVED, NOT_PINNED, REFUSED }

    /** Moves the old key's pin to the new key when [record] verifies; returns what happened and why when refused. */
    fun applyRotation(record: RotationRecord, verifier: Verifier): Pair<Rotation, String?> {
        val why = record.check(verifier)
        if (why != null) return Rotation.REFUSED to why
        val oldId = Sha.keyId(record.oldKey)
        val label = pins.remove(oldId) ?: return Rotation.NOT_PINNED to null
        pins[Sha.keyId(record.newKey)] = label
        return Rotation.MOVED to null
    }
}
