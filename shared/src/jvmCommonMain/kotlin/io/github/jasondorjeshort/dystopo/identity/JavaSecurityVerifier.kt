package io.github.jasondorjeshort.dystopo.identity

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * The [Verifier] of the JVM and Android (ADR 0051): `java.security` signatures over keys given as DER
 * SubjectPublicKeyInfo. ECDSA (P-256 and P-384 keys, the attestation chains and records) and RSA PKCS#1 v1.5 (Google's
 * RSA root); every failure is `false`.
 */
object JavaSecurityVerifier : Verifier {
    private val algorithms = mapOf(
        Oids.ECDSA_SHA256 to ("EC" to "SHA256withECDSA"),
        Oids.ECDSA_SHA384 to ("EC" to "SHA384withECDSA"),
        Oids.ECDSA_SHA512 to ("EC" to "SHA512withECDSA"),
        Oids.RSA_SHA256 to ("RSA" to "SHA256withRSA"),
        Oids.RSA_SHA384 to ("RSA" to "SHA384withRSA"),
        Oids.RSA_SHA512 to ("RSA" to "SHA512withRSA"),
    )

    override fun verify(publicKey: ByteArray, algorithmOid: String, data: ByteArray, signature: ByteArray): Boolean {
        val (keyAlgorithm, signatureAlgorithm) = algorithms[algorithmOid] ?: return false
        return try {
            val key: PublicKey = KeyFactory.getInstance(keyAlgorithm).generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance(signatureAlgorithm).run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        } catch (e: Exception) {
            false
        }
    }
}
