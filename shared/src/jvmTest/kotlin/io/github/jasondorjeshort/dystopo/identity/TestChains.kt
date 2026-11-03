package io.github.jasondorjeshort.dystopo.identity

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Mints synthetic Android key attestation chains for the tests (T211). No certificate builder is in the dependency
 * set and `java.security` verifies but does not issue, so the certificates are written with the package's own
 * [Der] encoder and signed with `java.security.Signature`: no test dependency, and malformed or extended chains
 * (an extension in the wrong certificate, a forged certificate below an attested key) are as easy as good ones.
 *
 * The chains run leaf, intermediate, root, like a phone's (which has one or two more intermediates), under the
 * committed test root (`identity/test-root.json`, its private key public on purpose) or a throwaway root.
 */
object TestChains {
    const val PACKAGE = "io.github.jasondorjeshort.dystopo"
    const val RELEASE_SIGNER = "47313810a4313ca3d679a0a18d09d9f80a74a17c07cf6cb226cf035895e258ad"

    /** The tests' clock: 2026-11-01T00:00:00Z. */
    val NOW: Long = Der.epochMillis(2026, 11, 1, 0, 0, 0)

    val DAY_MS = 86_400_000L

    class Ca(val cert: ByteArray, val keys: KeyPair, val name: String)

    /** The committed test root. */
    val testRoot: Ca by lazy {
        val text = TestChains::class.java.getResourceAsStream("/identity/test-root.json")!!.readBytes().decodeToString()
        val m = MiniJson.parseObject(text)
        val cert = PolicyLists.base64(m["certificate"] as String)
        val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(PolicyLists.base64(m["privateKeyPkcs8"] as String)))
        val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(X509Cert.parse(cert).publicKey))
        Ca(cert, KeyPair(public, private), "Dystopo test attestation root")
    }

    val testAnchors: TrustAnchors by lazy { TrustAnchors(listOf(RootEntry("test root", X509Cert.parse(testRoot.cert).publicKey, "test-root.json"))) }

    /** A throwaway self-signed root no anchor list holds. */
    fun otherRoot(): Ca {
        val keys = ecKeys()
        return Ca(cert(1, "Unknown root", "Unknown root", keys.public, keys.private, NOW - 3650 * DAY_MS, NOW + 3650 * DAY_MS), keys, "Unknown root")
    }

    val releasePolicy: AttestationPolicy get() = AttestationPolicy(setOf(PACKAGE), setOf(RELEASE_SIGNER))

    fun ecKeys(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    fun signer(keys: KeyPair): RecordSigner = object : RecordSigner {
        override val publicKey: ByteArray = keys.public.encoded
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(data)
            sign()
        }
    }

    fun name(cn: String): ByteArray = Der.sequence(Der.tlv(0x31, Der.sequence(Der.oid("2.5.4.3"), Der.utf8(cn))))

    class Ext(val oid: String, val value: ByteArray, val critical: Boolean = false)

    fun cert(
        serial: Long,
        issuer: String,
        subject: String,
        subjectKey: PublicKey,
        issuerKey: PrivateKey,
        notBefore: Long,
        notAfter: Long,
        extensions: List<Ext> = emptyList(),
        serialBytes: ByteArray? = null,
    ): ByteArray {
        val alg = Der.sequence(Der.oid(Oids.ECDSA_SHA256))
        val exts = if (extensions.isEmpty()) emptyList() else listOf(
            Der.explicit(3, Der.sequence(extensions.map { e -> Der.sequence(listOfNotNull(Der.oid(e.oid), if (e.critical) Der.boolean(true) else null, Der.octets(e.value))) })),
        )
        val serialDer = if (serialBytes != null) Der.unsignedInteger(serialBytes) else Der.integer(serial)
        val tbs = Der.sequence(
            listOf(Der.explicit(0, Der.integer(2)), serialDer, alg, name(issuer), Der.sequence(Der.time(notBefore), Der.time(notAfter)), name(subject), subjectKey.encoded) + exts,
        )
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(issuerKey)
            update(tbs)
            sign()
        }
        return Der.sequence(tbs, alg, Der.bitString(sig))
    }

    /** What the attestation says. */
    data class Spec(
        val level: KeyDescription.SecurityLevel = KeyDescription.SecurityLevel.TRUSTED_ENVIRONMENT,
        val keyMintLevel: KeyDescription.SecurityLevel = level,
        val packages: List<String> = listOf(PACKAGE),
        val digests: List<String> = listOf(RELEASE_SIGNER),
        val locked: Boolean = true,
        val boot: KeyDescription.VerifiedBootState = KeyDescription.VerifiedBootState.VERIFIED,
        val challenge: ByteArray = "dystopo-identity".encodeToByteArray(),
        /** Where the root of trust goes: true for the hardware-enforced list (TEE, StrongBox), false for the software one. */
        val rootOfTrustInHardware: Boolean = true,
        val attestationVersion: Int = 300,
    )

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The key description's DER, as KeyMint writes it (schema version 300). */
    fun keyDescription(spec: Spec): ByteArray {
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
        sw += Der.explicit(701, Der.integer(NOW - 1000))
        (if (spec.rootOfTrustInHardware) hw else sw) += Der.explicit(704, rot)
        hw += Der.explicit(705, Der.integer(150000))
        hw += Der.explicit(706, Der.integer(202510))
        sw += Der.explicit(709, Der.octets(appId))
        hw += Der.explicit(718, Der.integer(20251001))
        hw += Der.explicit(719, Der.integer(20251001))
        fun sorted(list: List<ByteArray>) = list.sortedBy { DerReader.single(it, "t").tag }
        return Der.sequence(
            Der.integer(spec.attestationVersion.toLong()),
            Der.enumerated(spec.level.code.toLong()),
            Der.integer(spec.attestationVersion.toLong()),
            Der.enumerated(spec.keyMintLevel.code.toLong()),
            Der.octets(spec.challenge),
            Der.octets(ByteArray(0)),
            Der.sequence(sorted(sw)),
            Der.sequence(sorted(hw)),
        )
    }

    class Chain(val certs: List<ByteArray>, val leafKeys: KeyPair, val serials: List<Long>) {
        val pem: String get() = Pem.encode(certs)
    }

    /**
     * Leaf (the attested key), intermediate, root. [leafExtension] replaces the leaf's key description (fuzzing);
     * [interExtensions] adds extensions to the intermediate; [leafHasAttestation] false leaves it out of the leaf.
     */
    fun chain(
        spec: Spec = Spec(),
        root: Ca = testRoot,
        includeRoot: Boolean = true,
        leafNotAfter: Long = NOW + 365 * DAY_MS,
        interNotAfter: Long = NOW + 3650 * DAY_MS,
        leafNotBefore: Long = NOW - 30 * DAY_MS,
        leafSerial: Long = 0x5eedL,
        interSerial: Long = 7_000_000_001L,
        leafExtension: ByteArray? = null,
        leafHasAttestation: Boolean = true,
        interExtensions: List<Ext> = emptyList(),
    ): Chain {
        val interKeys = ecKeys()
        val leafKeys = ecKeys()
        val inter = cert(interSerial, root.name, "Test attestation intermediate", interKeys.public, root.keys.private, NOW - 365 * DAY_MS, interNotAfter, interExtensions)
        val leafExts = if (leafHasAttestation) listOf(Ext(KeyDescription.OID, leafExtension ?: keyDescription(spec))) else emptyList()
        val leaf = cert(leafSerial, "Test attestation intermediate", "Android Keystore Key", leafKeys.public, interKeys.private, leafNotBefore, leafNotAfter, leafExts)
        val certs = if (includeRoot) listOf(leaf, inter, root.cert) else listOf(leaf, inter)
        return Chain(certs, leafKeys, listOf(leafSerial, interSerial))
    }

    /** The attested chain with one more certificate below its leaf, signed by the attested key and carrying [forged]. */
    fun extendedChain(forged: Spec = Spec()): Chain {
        val base = chain()
        val extraKeys = ecKeys()
        val extra = cert(99, "Android Keystore Key", "Forged key", extraKeys.public, base.leafKeys.private, NOW - DAY_MS, NOW + 365 * DAY_MS, listOf(Ext(KeyDescription.OID, keyDescription(forged))))
        return Chain(listOf(extra) + base.certs, extraKeys, listOf(99L) + base.serials)
    }

    fun evaluate(
        chain: List<ByteArray>,
        anchors: TrustAnchors = testAnchors,
        policy: AttestationPolicy = releasePolicy,
        now: Long = NOW,
        revocation: RevocationList? = null,
        audience: Audience = Audience.MEMBER,
    ): Verdict = policy.evaluate(chain, anchors, JavaSecurityVerifier, now, revocation, audience)
}
