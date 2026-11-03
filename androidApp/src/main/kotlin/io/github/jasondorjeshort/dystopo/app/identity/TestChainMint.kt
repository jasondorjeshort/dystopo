package io.github.jasondorjeshort.dystopo.app.identity

import io.github.jasondorjeshort.dystopo.identity.Der
import io.github.jasondorjeshort.dystopo.identity.DerReader
import io.github.jasondorjeshort.dystopo.identity.KeyDescription
import io.github.jasondorjeshort.dystopo.identity.Oids
import io.github.jasondorjeshort.dystopo.identity.RootEntry
import io.github.jasondorjeshort.dystopo.identity.TrustAnchors
import io.github.jasondorjeshort.dystopo.identity.X509Cert
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * T216: synthetic Android key attestation chains for `POST /identity/test-chain` (debug builds). The accept paths of
 * ADR 0051 cannot be reached from an emulator's own software chain, so a debug phone mints a leaf, an intermediate
 * and the root under the **test root** (`shared/src/jvmTest/resources/identity/test-root.json`, copied into the debug
 * build's assets only; its private key is public on purpose and never in a release list). Two debug phones share
 * that root, so a chain minted on one is accepted or refused on the other by the debug policy
 * ([IdentityRules.debugPolicy]). The same shape as T211's `TestChains` (the package's own [Der] encoder,
 * `java.security` signatures). No key material lives in this file: a release build has no test root to sign with.
 */
object TestChainMint {
    /** The test root: its certificate and key pair. */
    class Root(val cert: ByteArray, val keys: KeyPair) {
        val publicKey: ByteArray get() = X509Cert.parse(cert).publicKey
        val anchors: TrustAnchors get() = TrustAnchors(listOf(RootEntry(NAME, publicKey, "test-root.json (debug build asset)")))
    }

    const val NAME = "Dystopo test attestation root"

    /** Reads the test root's JSON (`certificate` and `privateKeyPkcs8`, base64). */
    fun root(json: String): Root {
        val m = MiniJson.parseObject(json)
        val cert = b64(m["certificate"] as String)
        val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(b64(m["privateKeyPkcs8"] as String)))
        val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(X509Cert.parse(cert).publicKey))
        return Root(cert, KeyPair(public, private))
    }

    /** What the minted attestation says; the defaults pass the debug policy of a phone built with the same debug key. */
    data class Spec(
        val level: KeyDescription.SecurityLevel = KeyDescription.SecurityLevel.TRUSTED_ENVIRONMENT,
        val packages: List<String>,
        val digests: List<String>,
        val locked: Boolean = true,
        val boot: KeyDescription.VerifiedBootState = KeyDescription.VerifiedBootState.VERIFIED,
        val notBefore: Long,
        /** The leaf's end; a past end makes an expired chain (stale within 60 days, refused after). */
        val notAfter: Long,
        /** False: a throwaway root no phone trusts (the unknown-root refusal). */
        val testRoot: Boolean = true,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "level" to level.json,
            "packages" to packages,
            "signerDigests" to digests,
            "deviceLocked" to locked,
            "verifiedBootState" to boot.json,
            "notBefore" to Der.iso(notBefore),
            "notAfter" to Der.iso(notAfter),
            "root" to if (testRoot) "test" else "throwaway",
        )
    }

    /** Leaf (carrying the key description), intermediate, root, as a phone presents them. */
    fun mint(spec: Spec, root: Root, now: Long, random: SecureRandom = SecureRandom()): List<ByteArray> {
        val ca = if (spec.testRoot) root else throwawayRoot(now)
        val interKeys = ecKeys()
        val leafKeys = ecKeys()
        val inter = cert(random.nextLong() and Long.MAX_VALUE, NAME, "Test attestation intermediate", interKeys.public, ca.keys.private, now - 365 * DAY, now + 3650 * DAY)
        val leaf = cert(
            random.nextLong() and Long.MAX_VALUE, "Test attestation intermediate", "Android Keystore Key", leafKeys.public, interKeys.private,
            spec.notBefore, spec.notAfter, listOf(KeyDescription.OID to keyDescription(spec, now)),
        )
        return listOf(leaf, inter, ca.cert)
    }

    private fun throwawayRoot(now: Long): Root {
        val keys = ecKeys()
        return Root(cert(1, "Unknown root", "Unknown root", keys.public, keys.private, now - 3650 * DAY, now + 3650 * DAY), keys)
    }

    private const val DAY = 86_400_000L

    internal fun b64(text: String): ByteArray = java.util.Base64.getMimeDecoder().decode(text)

    private fun ecKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun name(cn: String): ByteArray = Der.sequence(Der.tlv(0x31, Der.sequence(Der.oid("2.5.4.3"), Der.utf8(cn))))

    private fun cert(
        serial: Long,
        issuer: String,
        subject: String,
        subjectKey: PublicKey,
        issuerKey: PrivateKey,
        notBefore: Long,
        notAfter: Long,
        extensions: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val alg = Der.sequence(Der.oid(Oids.ECDSA_SHA256))
        val exts = if (extensions.isEmpty()) emptyList() else listOf(Der.explicit(3, Der.sequence(extensions.map { (oid, v) -> Der.sequence(Der.oid(oid), Der.octets(v)) })))
        val tbs = Der.sequence(
            listOf(Der.explicit(0, Der.integer(2)), Der.integer(serial), alg, name(issuer), Der.sequence(Der.time(notBefore), Der.time(notAfter)), name(subject), subjectKey.encoded) + exts,
        )
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(issuerKey)
            update(tbs)
            sign()
        }
        return Der.sequence(tbs, alg, Der.bitString(sig))
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The key description as KeyMint writes it (schema version 300); the root of trust in the hardware list for TEE and StrongBox. */
    internal fun keyDescription(spec: Spec, now: Long): ByteArray {
        val appId = Der.sequence(
            Der.setOf(spec.packages.map { Der.sequence(Der.octets(it.encodeToByteArray()), Der.integer(1)) }),
            Der.setOf(spec.digests.map { Der.octets(hex(it)) }),
        )
        val rot = Der.sequence(Der.octets(ByteArray(32) { 7 }), Der.boolean(spec.locked), Der.enumerated(spec.boot.code.toLong()), Der.octets(ByteArray(32) { 9 }))
        val hw = ArrayList<ByteArray>()
        val sw = ArrayList<ByteArray>()
        hw += Der.explicit(1, Der.setOf(listOf(Der.integer(2), Der.integer(3)))) // purpose: sign, verify
        hw += Der.explicit(2, Der.integer(3)) // algorithm EC
        hw += Der.explicit(3, Der.integer(256))
        hw += Der.explicit(5, Der.setOf(listOf(Der.integer(4)))) // digest SHA-256
        hw += Der.explicit(10, Der.integer(1)) // P-256
        hw += Der.explicit(503, Der.nullValue()) // noAuthRequired
        sw += Der.explicit(701, Der.integer(now))
        (if (spec.level.hardware) hw else sw) += Der.explicit(704, rot)
        sw += Der.explicit(709, Der.octets(appId))
        fun sorted(list: List<ByteArray>) = list.sortedBy { DerReader.single(it, "tag").tag }
        return Der.sequence(
            Der.integer(300),
            Der.enumerated(spec.level.code.toLong()),
            Der.integer(300),
            Der.enumerated(spec.level.code.toLong()),
            Der.octets("dystopo-test-chain".encodeToByteArray()),
            Der.octets(ByteArray(0)),
            Der.sequence(sorted(sw)),
            Der.sequence(sorted(hw)),
        )
    }
}
