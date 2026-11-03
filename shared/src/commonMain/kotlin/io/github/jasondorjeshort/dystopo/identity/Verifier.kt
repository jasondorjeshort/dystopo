package io.github.jasondorjeshort.dystopo.identity

import io.github.jasondorjeshort.dystopo.packs.Sha256

/**
 * The one platform piece the attestation code needs (ADR 0051): signature verification. commonMain has no elliptic
 * curve or RSA code; the JVM and Android implementation is `JavaSecurityVerifier` in jvmCommonMain (`java.security`),
 * an iOS one would follow. Chain walking, anchors, dates and the policy stay in commonMain on top of this.
 */
interface Verifier {
    /**
     * True when [signature] over [data] verifies under [publicKey] (a DER SubjectPublicKeyInfo) with the X.509
     * signature algorithm [algorithmOid] (ECDSA with SHA-256/384/512, RSA PKCS#1 v1.5 with SHA-256/384/512). False for
     * a bad signature, an unsupported algorithm, a key that does not match the algorithm or a malformed key; never
     * throws.
     */
    fun verify(publicKey: ByteArray, algorithmOid: String, data: ByteArray, signature: ByteArray): Boolean
}

/** What signs a record: an EC P-256 key (the identity key or a signing key, in the Keystore on the phone). */
interface RecordSigner {
    /** The key's SubjectPublicKeyInfo, DER. */
    val publicKey: ByteArray

    /** An ECDSA P-256 SHA-256 signature over [data], DER-encoded as the Keystore and Java produce it. */
    fun sign(data: ByteArray): ByteArray
}

/** OIDs the package names. */
object Oids {
    const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    const val ECDSA_SHA384 = "1.2.840.10045.4.3.3"
    const val ECDSA_SHA512 = "1.2.840.10045.4.3.4"
    const val RSA_SHA256 = "1.2.840.113549.1.1.11"
    const val RSA_SHA384 = "1.2.840.113549.1.1.12"
    const val RSA_SHA512 = "1.2.840.113549.1.1.13"
    const val EC_PUBLIC_KEY = "1.2.840.10045.2.1"
    const val RSA_ENCRYPTION = "1.2.840.113549.1.1.1"
    const val P256 = "1.2.840.10045.3.1.7"
}

/** SHA-256 for key ids and digests (the pure-Kotlin [Sha256] of the packs code). */
object Sha {
    fun sha256(bytes: ByteArray): ByteArray = Sha256().update(bytes).digest()

    /** A key's id: the SHA-256 of its SubjectPublicKeyInfo, lowercase hex. Pins bind to this. */
    fun keyId(publicKey: ByteArray): String = sha256(publicKey).hex()
}
