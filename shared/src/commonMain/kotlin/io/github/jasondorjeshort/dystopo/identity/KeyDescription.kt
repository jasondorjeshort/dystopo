package io.github.jasondorjeshort.dystopo.identity

/**
 * The Android key attestation extension (OID [OID], the `KeyDescription` schema of
 * https://source.android.com/docs/security/features/keystore/attestation#schema), read by our own parser (ADR 0051:
 * no library in the app). Only the fields the policy and the self-check use are kept; every other authorization tag
 * is skipped after its length is checked. Bounded: [MAX_SIZE] for the whole extension, the per-field caps below,
 * and [AttestationFormatException] for anything malformed, never another exception type.
 */
data class KeyDescription(
    val attestationVersion: Int,
    val attestationSecurityLevel: SecurityLevel,
    val keyMintVersion: Int,
    val keyMintSecurityLevel: SecurityLevel,
    val challenge: ByteArray,
    val uniqueId: ByteArray,
    val softwareEnforced: AuthorizationList,
    val hardwareEnforced: AuthorizationList,
) {
    /** The root of trust: from the hardware list (where TEE and StrongBox put it), else the software list. */
    val rootOfTrust: RootOfTrust? get() = hardwareEnforced.rootOfTrust ?: softwareEnforced.rootOfTrust

    /** The application id: the framework fills it in, so it sits in the software list on every phone. */
    val applicationId: ApplicationId? get() = softwareEnforced.applicationId ?: hardwareEnforced.applicationId

    val osVersion: Long? get() = hardwareEnforced.osVersion ?: softwareEnforced.osVersion
    val osPatchLevel: Long? get() = hardwareEnforced.osPatchLevel ?: softwareEnforced.osPatchLevel
    val vendorPatchLevel: Long? get() = hardwareEnforced.vendorPatchLevel ?: softwareEnforced.vendorPatchLevel
    val bootPatchLevel: Long? get() = hardwareEnforced.bootPatchLevel ?: softwareEnforced.bootPatchLevel

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "attestationVersion" to attestationVersion,
        "securityLevel" to attestationSecurityLevel.json,
        "keyMintVersion" to keyMintVersion,
        "keyMintSecurityLevel" to keyMintSecurityLevel.json,
        "challengeBytes" to challenge.size,
        "packages" to applicationId?.packages?.map { linkedMapOf("name" to it.name, "version" to it.version) },
        "signerDigests" to applicationId?.signatureDigests?.map { it.hex() },
        "rootOfTrust" to rootOfTrust?.let {
            linkedMapOf(
                "deviceLocked" to it.deviceLocked,
                "verifiedBootState" to it.verifiedBootState.json,
                "verifiedBootKeySha256" to Sha.sha256(it.verifiedBootKey).hex(),
                "rootOfTrustIn" to if (hardwareEnforced.rootOfTrust != null) "hardware" else "software",
            )
        },
        "osVersion" to osVersion,
        "osPatchLevel" to osPatchLevel,
        "vendorPatchLevel" to vendorPatchLevel,
        "bootPatchLevel" to bootPatchLevel,
    )

    override fun equals(other: Any?): Boolean = other is KeyDescription && toMap() == other.toMap() && challenge.contentEquals(other.challenge)

    override fun hashCode(): Int = toMap().hashCode()

    enum class SecurityLevel(val code: Int, val json: String) {
        SOFTWARE(0, "software"),
        TRUSTED_ENVIRONMENT(1, "tee"),
        STRONG_BOX(2, "strongbox"),
        ;

        val hardware: Boolean get() = this != SOFTWARE

        companion object {
            fun of(code: Long): SecurityLevel = entries.firstOrNull { it.code.toLong() == code } ?: malformed("security level $code")
        }
    }

    enum class VerifiedBootState(val code: Int, val json: String) {
        VERIFIED(0, "verified"),
        SELF_SIGNED(1, "self-signed"),
        UNVERIFIED(2, "unverified"),
        FAILED(3, "failed"),
        ;

        companion object {
            fun of(code: Long): VerifiedBootState = entries.firstOrNull { it.code.toLong() == code } ?: malformed("verified boot state $code")
        }
    }

    class RootOfTrust(val verifiedBootKey: ByteArray, val deviceLocked: Boolean, val verifiedBootState: VerifiedBootState, val verifiedBootHash: ByteArray?)

    class PackageInfo(val name: String, val version: Long)

    class ApplicationId(val packages: List<PackageInfo>, val signatureDigests: List<ByteArray>)

    /** The authorization list fields kept (tags from the schema); the rest are skipped. */
    class AuthorizationList(
        val rootOfTrust: RootOfTrust? = null,
        val applicationId: ApplicationId? = null,
        val osVersion: Long? = null,
        val osPatchLevel: Long? = null,
        val vendorPatchLevel: Long? = null,
        val bootPatchLevel: Long? = null,
        val creationDateTime: Long? = null,
        val algorithm: Long? = null,
        val ecCurve: Long? = null,
        val tags: List<Int> = emptyList(),
    )

    companion object {
        /** The key description extension's OID. */
        const val OID = "1.3.6.1.4.1.11129.2.1.17"

        /** The provisioning information extension of RKP chains (its next certificate leaf-wards holds the key description). */
        const val PROVISIONING_INFO_OID = "1.3.6.1.4.1.11129.2.1.30"

        const val MAX_SIZE = 8 * 1024
        const val MAX_CHALLENGE = 128
        const val MAX_UNIQUE_ID = 64
        const val MAX_PACKAGES = 32
        const val MAX_DIGESTS = 16
        const val MAX_BOOT_KEY = 512
        const val MAX_PACKAGE_NAME = 256

        private const val TAG_ALGORITHM = 2
        private const val TAG_EC_CURVE = 10
        private const val TAG_CREATION_DATE_TIME = 701
        private const val TAG_ROOT_OF_TRUST = 704
        private const val TAG_OS_VERSION = 705
        private const val TAG_OS_PATCH_LEVEL = 706
        private const val TAG_ATTESTATION_APPLICATION_ID = 709
        private const val TAG_VENDOR_PATCH_LEVEL = 718
        private const val TAG_BOOT_PATCH_LEVEL = 719

        /** Parses the extension's value (the OCTET STRING's content). */
        fun parse(value: ByteArray): KeyDescription {
            if (value.size > MAX_SIZE) malformed("key description of ${value.size} bytes, at most $MAX_SIZE")
            val top = DerReader.single(value, "key description").expectSequence("key description").children()
            if (top.size != 8) malformed("key description with ${top.size} fields, expected 8")
            return KeyDescription(
                attestationVersion = top[0].asInt("attestationVersion"),
                attestationSecurityLevel = SecurityLevel.of(top[1].asLong("attestationSecurityLevel")),
                keyMintVersion = top[2].asInt("keyMintVersion"),
                keyMintSecurityLevel = SecurityLevel.of(top[3].asLong("keyMintSecurityLevel")),
                challenge = top[4].asOctets("attestationChallenge", MAX_CHALLENGE),
                uniqueId = top[5].asOctets("uniqueId", MAX_UNIQUE_ID),
                softwareEnforced = authorizationList(top[6], "softwareEnforced"),
                hardwareEnforced = authorizationList(top[7], "hardwareEnforced"),
            )
        }

        private fun authorizationList(e: DerElement, what: String): AuthorizationList {
            var rot: RootOfTrust? = null
            var appId: ApplicationId? = null
            var osVersion: Long? = null
            var osPatch: Long? = null
            var vendorPatch: Long? = null
            var bootPatch: Long? = null
            var created: Long? = null
            var algorithm: Long? = null
            var curve: Long? = null
            val tags = ArrayList<Int>()
            var last = -1
            for (field in e.expectSequence(what).children()) {
                if (field.tagClass != DerElement.CONTEXT || !field.constructed) malformed("$what: expected an explicit tag, found ${field.describe()}")
                // DER orders the tags ascending; a repeated tag is how a forged list would try to shadow a field.
                if (field.tag <= last) malformed("$what: tag ${field.tag} after $last")
                last = field.tag
                tags += field.tag
                val inner = field.reader()
                val v = inner.next()
                inner.finish("$what [${field.tag}]")
                when (field.tag) {
                    TAG_ALGORITHM -> algorithm = v.asLong("algorithm")
                    TAG_EC_CURVE -> curve = v.asLong("ecCurve")
                    TAG_CREATION_DATE_TIME -> created = v.asLong("creationDateTime")
                    TAG_ROOT_OF_TRUST -> rot = rootOfTrust(v)
                    TAG_OS_VERSION -> osVersion = v.asLong("osVersion")
                    TAG_OS_PATCH_LEVEL -> osPatch = v.asLong("osPatchLevel")
                    TAG_ATTESTATION_APPLICATION_ID -> appId = applicationId(v.asOctets("attestationApplicationId", 4096))
                    TAG_VENDOR_PATCH_LEVEL -> vendorPatch = v.asLong("vendorPatchLevel")
                    TAG_BOOT_PATCH_LEVEL -> bootPatch = v.asLong("bootPatchLevel")
                    else -> Unit // other tags: length-checked by the reader, not used
                }
            }
            return AuthorizationList(rot, appId, osVersion, osPatch, vendorPatch, bootPatch, created, algorithm, curve, tags)
        }

        private fun rootOfTrust(e: DerElement): RootOfTrust {
            val f = e.expectSequence("rootOfTrust").children()
            if (f.size < 3 || f.size > 4) malformed("rootOfTrust with ${f.size} fields")
            return RootOfTrust(
                verifiedBootKey = f[0].asOctets("verifiedBootKey", MAX_BOOT_KEY),
                deviceLocked = f[1].asBoolean("deviceLocked"),
                verifiedBootState = VerifiedBootState.of(f[2].asLong("verifiedBootState")),
                verifiedBootHash = f.getOrNull(3)?.asOctets("verifiedBootHash", 64),
            )
        }

        private fun applicationId(bytes: ByteArray): ApplicationId {
            val f = DerReader.single(bytes, "attestationApplicationId").expectSequence("attestationApplicationId").children()
            if (f.size != 2) malformed("attestationApplicationId with ${f.size} fields")
            if (!f[0].isUniversal(DerElement.SET, true) || !f[1].isUniversal(DerElement.SET, true)) malformed("attestationApplicationId: expected two SETs")
            val infos = f[0].children()
            if (infos.size > MAX_PACKAGES) malformed("more than $MAX_PACKAGES packages")
            val packages = infos.map { p ->
                val pf = p.expectSequence("packageInfo").children()
                if (pf.size != 2) malformed("packageInfo with ${pf.size} fields")
                PackageInfo(pf[0].asText("packageName", MAX_PACKAGE_NAME), pf[1].asLong("packageVersion"))
            }
            val digests = f[1].children()
            if (digests.size > MAX_DIGESTS) malformed("more than $MAX_DIGESTS signature digests")
            return ApplicationId(packages, digests.map { it.asOctets("signatureDigest", 64) })
        }
    }
}
