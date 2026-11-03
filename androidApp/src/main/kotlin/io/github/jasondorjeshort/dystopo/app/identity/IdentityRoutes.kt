package io.github.jasondorjeshort.dystopo.app.identity

import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.update.Signing
import io.github.jasondorjeshort.dystopo.identity.AttestationFormatException
import io.github.jasondorjeshort.dystopo.identity.AttestationPolicy
import io.github.jasondorjeshort.dystopo.identity.Audience
import io.github.jasondorjeshort.dystopo.identity.IdentityRecord
import io.github.jasondorjeshort.dystopo.identity.JavaSecurityVerifier
import io.github.jasondorjeshort.dystopo.identity.KeyDescription
import io.github.jasondorjeshort.dystopo.identity.Pem
import io.github.jasondorjeshort.dystopo.identity.PolicyLists
import io.github.jasondorjeshort.dystopo.identity.Sha
import io.github.jasondorjeshort.dystopo.identity.TrustAnchors
import io.github.jasondorjeshort.dystopo.identity.Verdict
import io.github.jasondorjeshort.dystopo.identity.X509Cert
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * T216 debug routes for the attested install identity (ADR 0051, stage 1a; [InstallIdentity]). Debug builds only, on
 * the debug port, like every non-map route.
 * - `GET /identity[?pem=1][&refresh=1]`: the identity's first use (makes the key when there is none and the phone is
 *   online) and its state: `phase` (`none`, `checking`, `offline`, `keys-unavailable`, `error`, `ready`), `line` (the
 *   Share screen's self-check line), `error` (`code` is `ERROR_ATTESTATION_KEYS_UNAVAILABLE` when the key service had
 *   no keys, with `transient`), `identity` (`keyId`, `created`, `requested` strongbox or tee, the attested
 *   `securityLevel`, `chainLength`, `chainEnd`, `successorDue`, `deviceLocked`, `verifiedBootState`), `signing` (the
 *   30-day key's id, dates and its certification record's check), `rotations`, `selfCheck` (the verdict by the
 *   **release** policy: `status`, `reasons` each with `code`, `message`, `lift` and `detail`, `securityLevel`,
 *   `passesWithSignerRelaxed`, the attestation's fields), `lists` (the bundled lists' versions; `policyFile` says the
 *   fetch is deferred), `ownSigners`, `revocation` (`entries`, `fetchedAt`, `ageDays`, `lastFetch`), `lastEnsure`
 *   (what this call did), `verified` (counts of `POST /identity/verify` by status and reason). `pem=1` adds the chain as
 *   PEM (for `devtool attest --chain`); `refresh=1` refetches the revocation list now.
 * - `POST /identity/verify[?policy=debug|release][&audience=member|stranger][&at=<ISO instant>]`: a chain in (PEM
 *   certificates leaf first, a JSON body `{"pem": ...}` or `{"record": <base64 identity record>}`, or the record's raw
 *   bytes), the verdict out (`verdict` as `devtool attest` prints it). `debug` (the default in debug builds) is the
 *   release lists plus this build's own signer and the test root ([IdentityRules.debugPolicy]); `release` is the
 *   bundled lists alone. The revocation list is the phone's cached copy.
 * - `POST /identity/rotate`: makes the successor identity key now and the rotation record signed by both keys, drops
 *   the old key; answers the old and new key ids and the record's check (409 before the first `GET /identity`).
 * - `POST /identity/test-chain[?level=tee|strongbox|software][&locked=1|0][&boot=verified|self-signed|unverified|failed]
 *   [&package=<name>][&signer=own|release|<sha256 hex>][&root=test|other][&expiredDays=<n>]`: debug builds; a
 *   synthetic chain under the test root ([TestChainMint]) as PEM, with this phone's own verdict on it under the debug
 *   policy (`here`). The defaults (TEE, this package, this build's signer, locked, verified, a year's validity) pass
 *   the debug policy of any phone built with the same debug key; each parameter makes one refusal (`expiredDays` under
 *   60 makes it stale). Post its `pem` to another phone's `/identity/verify` for the cross-phone check.
 * Nothing here sends anything off the phone but the revocation list's GET ([RevocationCache]).
 */
object IdentityRoutes {
    private val verifiedByStatus = ConcurrentHashMap<String, AtomicInteger>()
    private val verifiedByReason = ConcurrentHashMap<String, AtomicInteger>()

    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/identity" && !r.path.startsWith("/identity/")) return null
        val identity = InstallIdentity.get(app)
        return when (r.method to r.path) {
            "GET" to "/identity" -> {
                identity.ensure("GET /identity", refreshRevocation = r.query["refresh"] == "1")
                Response.json(identity.json(includePem = r.query["pem"] == "1") + linkedMapOf("verified" to verifiedJson()))
            }
            "POST" to "/identity/verify" -> verify(app, r)
            "POST" to "/identity/rotate" -> try {
                Response.json(identity.rotate("POST /identity/rotate") + linkedMapOf("identity" to identity.json()["identity"]))
            } catch (e: IllegalStateException) {
                Response.json(mapOf("error" to e.message), 409)
            } catch (e: Exception) {
                Response.json(mapOf("error" to (e.message ?: e.javaClass.simpleName)), 500)
            }
            "POST" to "/identity/test-chain" -> testChain(app, r)
            else -> Response.notFound()
        }
    }

    private fun verify(app: DystopoApp, r: Request): Response {
        val which = r.query["policy"] ?: if (BuildConfig.DEBUG_ENDPOINTS) "debug" else "release"
        if (which != "debug" && which != "release") return bad("policy must be debug or release")
        val audience = when (r.query["audience"] ?: "member") {
            "member" -> Audience.MEMBER
            "stranger" -> Audience.STRANGER
            else -> return bad("audience must be member or stranger")
        }
        val now = r.query["at"]?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() ?: return bad("at: '$it' is not an ISO instant (2026-11-01T00:00:00Z)") }
            ?: System.currentTimeMillis()
        val (format, chain, platform) = try {
            readChain(r.body) ?: return bad("give PEM certificates, {\"pem\": ...}, {\"record\": <base64>} or an identity record's bytes")
        } catch (e: AttestationFormatException) {
            return bad("unreadable: ${e.message}")
        }
        val (policy, anchors) = policy(app, which)
        val revocation = InstallIdentity.get(app).revocation.current()
        val v = if (platform != null) {
            policy.evaluate(IdentityRecord(platform, chain), anchors, JavaSecurityVerifier, now, revocation, audience)
        } else {
            policy.evaluate(chain, anchors, JavaSecurityVerifier, now, revocation, audience)
        }
        count(v)
        return Response.json(
            linkedMapOf(
                "policy" to which,
                "format" to format,
                "audience" to audience.json,
                "anchors" to anchors.keys.map { linkedMapOf("name" to it.name, "keyId" to it.keyId) },
                "signers" to policy.signers.sorted(),
                "verdict" to v.toMap(),
            ),
        )
    }

    /** The format, the chain and, for an identity record, its platform tag; null when the body holds none of them. */
    private fun readChain(body: ByteArray): Triple<String, List<ByteArray>, String?>? {
        if (body.isEmpty()) return null
        if (body[0].toInt() == 0x30) {
            val rec = IdentityRecord.decode(body)
            return Triple("identity-record", rec.chain, rec.platform)
        }
        val text = body.decodeToString().trim()
        if (text.startsWith("{")) {
            val m = runCatching { MiniJson.parseObject(text) }.getOrNull() ?: return null
            (m["pem"] as? String)?.let { return Triple("pem", Pem.certificates(it).ifEmpty { return null }, null) }
            (m["record"] as? String)?.let { b ->
                val bytes = runCatching { Base64.getMimeDecoder().decode(b) }.getOrNull() ?: return null
                val rec = IdentityRecord.decode(bytes)
                return Triple("identity-record", rec.chain, rec.platform)
            }
            return null
        }
        return Pem.certificates(text).takeIf { it.isNotEmpty() }?.let { Triple("pem", it, null) }
    }

    private fun policy(app: DystopoApp, which: String): Pair<AttestationPolicy, TrustAnchors> =
        if (which == "release" || !BuildConfig.DEBUG_ENDPOINTS) {
            AttestationPolicy.release() to PolicyLists.release.anchors
        } else {
            IdentityRules.debugPolicy(runCatching { Signing.own(app) }.getOrDefault(emptyList())) to InstallIdentity.debugAnchors(app)
        }

    private fun testChain(app: DystopoApp, r: Request): Response {
        if (!BuildConfig.DEBUG_ENDPOINTS) return Response.notFound()
        val root = InstallIdentity.testRoot(app) ?: return Response.json(mapOf("error" to "no test root in this build's assets (test-root.json)"), 500)
        val q = r.query
        val level = when (q["level"] ?: "tee") {
            "tee" -> KeyDescription.SecurityLevel.TRUSTED_ENVIRONMENT
            "strongbox" -> KeyDescription.SecurityLevel.STRONG_BOX
            "software" -> KeyDescription.SecurityLevel.SOFTWARE
            else -> return bad("level must be tee, strongbox or software")
        }
        val boot = when (q["boot"] ?: "verified") {
            "verified" -> KeyDescription.VerifiedBootState.VERIFIED
            "self-signed" -> KeyDescription.VerifiedBootState.SELF_SIGNED
            "unverified" -> KeyDescription.VerifiedBootState.UNVERIFIED
            "failed" -> KeyDescription.VerifiedBootState.FAILED
            else -> return bad("boot must be verified, self-signed, unverified or failed")
        }
        val own = runCatching { Signing.own(app) }.getOrDefault(emptyList())
        val digests = when (val s = q["signer"] ?: "own") {
            "own" -> own.ifEmpty { return Response.json(mapOf("error" to "this build's signer could not be read"), 500) }
            "release" -> PolicyLists.release.signers.map { it.sha256 }
            else -> if (s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }) listOf(s) else return bad("signer must be own, release or a lowercase SHA-256")
        }
        val now = System.currentTimeMillis()
        val expiredDays = q["expiredDays"]?.let { it.toLongOrNull()?.takeIf { d -> d >= 0 } ?: return bad("expiredDays must be a whole number >= 0") }
        val notAfter = if (expiredDays != null) now - expiredDays * IdentityRules.DAY - 60_000 else now + 365 * IdentityRules.DAY
        val spec = TestChainMint.Spec(
            level = level,
            packages = listOf(q["package"] ?: app.packageName),
            digests = digests,
            locked = q["locked"] != "0",
            boot = boot,
            notBefore = minOf(now, notAfter) - 30 * IdentityRules.DAY,
            notAfter = notAfter,
            testRoot = (q["root"] ?: "test") != "other",
        )
        val chain = TestChainMint.mint(spec, root, now)
        val (policy, anchors) = policy(app, "debug")
        val here: Verdict = policy.evaluate(chain, anchors, JavaSecurityVerifier, now, InstallIdentity.get(app).revocation.current())
        return Response.json(
            linkedMapOf(
                "spec" to spec.toMap(),
                "chainLength" to chain.size,
                "identityKeyId" to Sha.keyId(X509Cert.parse(chain.first()).publicKey),
                "here" to linkedMapOf("policy" to "debug", "status" to here.status.json, "codes" to here.codes),
                "pem" to Pem.encode(chain),
            ),
        )
    }

    private fun count(v: Verdict) {
        verifiedByStatus.getOrPut(v.status.json) { AtomicInteger() }.incrementAndGet()
        v.codes.distinct().forEach { verifiedByReason.getOrPut(it) { AtomicInteger() }.incrementAndGet() }
    }

    private fun verifiedJson(): Map<String, Any?> = linkedMapOf(
        "byStatus" to verifiedByStatus.mapValues { it.value.get() }.toSortedMap(),
        "byReason" to verifiedByReason.mapValues { it.value.get() }.toSortedMap(),
    )

    private fun bad(message: String) = Response.json(mapOf("error" to message), 400)
}
