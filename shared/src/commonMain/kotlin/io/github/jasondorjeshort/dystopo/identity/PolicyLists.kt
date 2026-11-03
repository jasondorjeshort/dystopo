package io.github.jasondorjeshort.dystopo.identity

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.io.encoding.Base64

/** The platform and chain-format tags records and policies carry from their first field (ADR 0051). */
object Platform {
    const val ANDROID_KEY_ATTESTATION_V1 = "android-key-attestation-v1"

    val KNOWN: Set<String> = setOf(ANDROID_KEY_ATTESTATION_V1)
}

/** One trusted root: its public key (the anchor) and where the entry came from. */
class RootEntry(val name: String, val publicKey: ByteArray, val origin: String) {
    val keyId: String get() = Sha.keyId(publicKey)
}

/** One accepted app signer: the SHA-256 of its signing certificate, lowercase hex. */
class SignerEntry(val name: String, val sha256: String, val origin: String)

/**
 * Trust anchors, by public key (ADR 0051): the policy takes them as a parameter, so the release roots, a debug
 * build's test root and a test's own root all run the same code. Several root certificates sharing one key (Google's
 * RSA root was issued four times) are one anchor.
 */
class TrustAnchors(val roots: List<RootEntry>) {
    private val byId: Map<String, RootEntry> = roots.asReversed().associateBy { it.keyId } // the first entry of a key names it

    val keyIds: Set<String> get() = byId.keys

    fun find(publicKey: ByteArray): RootEntry? = byId[Sha.keyId(publicKey)]

    /** The distinct anchor keys. */
    val keys: List<RootEntry> get() = byId.values.toList()

    operator fun plus(other: TrustAnchors) = TrustAnchors(roots + other.roots)
}

/**
 * The versioned policy lists in `shared`'s resources (`attestation/roots.json`, `attestation/signers.json`, embedded
 * at build time as `AttestationJson`): the release lists only. Debug builds add the debug signer and the test root
 * themselves; `ReleaseListsTest` fails if either reaches these files.
 */
class PolicyLists(
    val platform: String,
    val rootsVersion: Int,
    val signersVersion: Int,
    val roots: List<RootEntry>,
    val signers: List<SignerEntry>,
    val packages: List<String>,
) {
    val anchors: TrustAnchors get() = TrustAnchors(roots)

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "platform" to platform,
        "rootsVersion" to rootsVersion,
        "signersVersion" to signersVersion,
        "roots" to roots.size,
        "anchorKeys" to anchors.keys.size,
        "signers" to signers.map { it.sha256 },
        "packages" to packages,
    )

    companion object {
        /** The bundled release lists. */
        val release: PolicyLists by lazy { parse(AttestationJson.ROOTS, AttestationJson.SIGNERS) }

        fun parse(rootsJson: String, signersJson: String): PolicyLists {
            val r = json(rootsJson, "roots.json")
            val s = json(signersJson, "signers.json")
            val platform = r["platform"] as? String ?: malformed("roots.json: no platform")
            if (s["platform"] != platform) malformed("signers.json: platform ${s["platform"]} differs from roots.json's $platform")
            val roots = (r["roots"] as? List<*> ?: malformed("roots.json: no roots")).map { e ->
                val m = e as? Map<*, *> ?: malformed("roots.json: entry is not an object")
                val key = base64(m["spki"] as? String ?: malformed("roots.json: entry without spki"))
                val claimed = m["spkiSha256"] as? String
                if (claimed != null && claimed != Sha.keyId(key)) malformed("roots.json: spkiSha256 of ${m["name"]} does not match its spki")
                RootEntry(m["name"] as? String ?: "", key, m["origin"] as? String ?: malformed("roots.json: entry without origin"))
            }
            val signers = (s["signers"] as? List<*> ?: malformed("signers.json: no signers")).mapNotNull { e ->
                val m = e as? Map<*, *> ?: malformed("signers.json: entry is not an object")
                val origin = m["origin"] as? String ?: malformed("signers.json: entry without origin")
                // A null digest is a placeholder line (the second release signer, until the owner generates it).
                val sha = (m["sha256"] as? String)?.lowercase() ?: return@mapNotNull null
                if (sha.length != 64 || !sha.all { it in '0'..'9' || it in 'a'..'f' }) malformed("signers.json: '$sha' is not a SHA-256")
                SignerEntry(m["name"] as? String ?: "", sha, origin)
            }
            val packages = (s["packages"] as? List<*> ?: malformed("signers.json: no packages")).map { it as? String ?: malformed("signers.json: package") }
            return PolicyLists(platform, version(r), version(s), roots, signers, packages)
        }

        private fun json(text: String, what: String): Map<String, Any?> = try {
            MiniJson.parseObject(text)
        } catch (e: Exception) {
            malformed("$what: ${e.message}")
        }

        private fun version(m: Map<String, Any?>): Int = (m["version"] as? Number)?.toInt() ?: malformed("policy list without version")

        internal fun base64(text: String): ByteArray = try {
            Base64.Default.decode(text.filterNot { it.isWhitespace() })
        } catch (e: IllegalArgumentException) {
            malformed("bad base64")
        }
    }
}

/** PEM text (one or more `CERTIFICATE` blocks) to DER certificates, in the order given. */
object Pem {
    fun certificates(text: String): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val begin = "-----BEGIN CERTIFICATE-----"
        val end = "-----END CERTIFICATE-----"
        var at = 0
        while (true) {
            val b = text.indexOf(begin, at)
            if (b < 0) break
            val e = text.indexOf(end, b)
            if (e < 0) malformed("PEM block without its end line")
            out += PolicyLists.base64(text.substring(b + begin.length, e))
            at = e + end.length
            if (out.size > AttestationPolicy.MAX_CHAIN) malformed("more than ${AttestationPolicy.MAX_CHAIN} certificates")
        }
        return out
    }

    fun encode(certificates: List<ByteArray>): String = certificates.joinToString("") { der ->
        "-----BEGIN CERTIFICATE-----\n" + Base64.Default.encode(der).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
    }
}
