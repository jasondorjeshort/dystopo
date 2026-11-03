package io.github.jasondorjeshort.dystopo.app.identity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.areas.DownloadWorker
import io.github.jasondorjeshort.dystopo.app.update.Signing
import io.github.jasondorjeshort.dystopo.identity.AttestationPolicy
import io.github.jasondorjeshort.dystopo.identity.Audience
import io.github.jasondorjeshort.dystopo.identity.CertificationRecord
import io.github.jasondorjeshort.dystopo.identity.Der
import io.github.jasondorjeshort.dystopo.identity.JavaSecurityVerifier
import io.github.jasondorjeshort.dystopo.identity.Pem
import io.github.jasondorjeshort.dystopo.identity.PolicyLists
import io.github.jasondorjeshort.dystopo.identity.RecordSigner
import io.github.jasondorjeshort.dystopo.identity.RotationRecord
import io.github.jasondorjeshort.dystopo.identity.Sha
import io.github.jasondorjeshort.dystopo.identity.TrustAnchors
import io.github.jasondorjeshort.dystopo.identity.Verdict
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

/**
 * T216: this install's attested identity (ADR 0051, stage 1a, the phone half; the parser, records and policy are T211's
 * `shared` package `identity`).
 *
 * - **The identity key** is an EC P-256 key made in the Android Keystore with an attestation challenge, in StrongBox
 *   when the phone has one, else in the TEE (an emulator's Keystore is software). It is made on **first use**: the
 *   first open of the Share screen or the first `GET /identity`, and only while online, since phones with Remote Key
 *   Provisioning draw the attestation key from a pool Google's provisioning service fills. Offline it waits and says
 *   so ([IdentityRules.Phase.OFFLINE]); `ERROR_ATTESTATION_KEYS_UNAVAILABLE` (API 33) is reported as its own phase.
 * - **Signing keys** are 30-day Keystore keys certified by the identity key in a [CertificationRecord]; a new one is
 *   made a day before the current one ends. They need no network.
 * - **The successor**: when the identity chain ends within 14 days, the next use while online makes a new identity key
 *   and a [RotationRecord] signed by both, then drops the old key ([rotate]; `POST /identity/rotate` forces it).
 * - **The self-check** judges this phone's own chain by the **release** policy over the bundled lists, with the cached
 *   revocation list ([RevocationCache]); the Share screen shows its line ([IdentityRules.line]).
 *
 * Nothing is sent anywhere but the revocation list's GET. The policy-file fetch of the proposal is deferred: the
 * lists ship in the APK and update with it (ADR 0051's dated line of 2026-11-03). State: `files/identity/identity.json`
 * (aliases, dates, the challenge, the latest records); the keys stay in the Keystore.
 */
class InstallIdentity private constructor(private val context: Context) {
    private val dir = File(context.filesDir, "identity")
    private val stateFile = File(dir, "identity.json")
    val revocation = RevocationCache(dir)

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    @Volatile var phase: IdentityRules.Phase = IdentityRules.Phase.NONE
        private set

    @Volatile private var error: Map<String, Any?>? = null
    @Volatile private var lastEnsure: Map<String, Any?>? = null
    @Volatile private var lastVerdict: Verdict? = null

    private var state: MutableMap<String, Any?> = load()

    private val alias: String? get() = state["alias"] as? String

    /** The identity key's attestation chain, leaf first, as the Keystore returns it; null before the key exists. */
    fun chain(): List<ByteArray>? = alias?.let { a -> runCatching { keyStore.getCertificateChain(a)?.map { it.encoded } }.getOrNull() }

    /**
     * First use and upkeep: makes the identity key when there is none (online only), renews the signing key, makes the
     * successor when the chain ends within 14 days, refreshes the revocation list once a day, then runs the self-check.
     * Blocking (key generation takes up to a few seconds in StrongBox); call it off the main thread.
     */
    @Synchronized fun ensure(trigger: String, refreshRevocation: Boolean = false): Map<String, Any?> {
        val t0 = System.nanoTime()
        val online = DownloadWorker.networkUsable(context)
        val steps = ArrayList<String>()
        if (alias == null || chain().isNullOrEmpty()) {
            if (!online) {
                phase = IdentityRules.Phase.OFFLINE
                steps += "identity: waiting for a network"
            } else {
                phase = IdentityRules.Phase.CHECKING
                val made = makeIdentity(generation = ((state["generation"] as? Number)?.toInt() ?: 0) + 1)
                steps += if (made) "identity: made (${state["requested"]})" else "identity: failed (${error?.get("code")})"
            }
        }
        if (alias != null && chain() != null) {
            phase = IdentityRules.Phase.READY
            error = null
            val now = System.currentTimeMillis()
            val check = selfCheck(now)
            if (online && IdentityRules.successorDue(check.chainEnd, now, (state["lastRotationAt"] as? Number)?.toLong())) {
                steps += "successor: " + (runCatching { rotate("successor").let { "made" } }.getOrElse { "failed: ${it.message}" })
            }
            if (IdentityRules.signingKeyDue((signing()?.get("validTo") as? Number)?.toLong(), now)) {
                steps += "signing key: " + (runCatching { newSigningKey(now); "made" }.getOrElse { "failed: ${it.message}" })
            }
        }
        steps += "revocation list: " + revocation.refreshIfDue(online, force = refreshRevocation)
        if (phase == IdentityRules.Phase.READY) selfCheck(System.currentTimeMillis())
        lastEnsure = linkedMapOf("trigger" to trigger, "at" to Der.iso(System.currentTimeMillis()), "online" to online, "steps" to steps, "ms" to (System.nanoTime() - t0) / 1_000_000)
        return lastEnsure!!
    }

    /** Judges this phone's chain by the release policy (the self-check of the Share screen and `GET /identity`). */
    fun selfCheck(now: Long = System.currentTimeMillis()): Verdict {
        val c = chain().orEmpty()
        val v = AttestationPolicy.release().evaluate(c, PolicyLists.release.anchors, JavaSecurityVerifier, now, revocation.current(), Audience.MEMBER, challenge())
        lastVerdict = v
        return v
    }

    /** The Share screen's line for the current state. */
    fun line(): IdentityRules.Line = IdentityRules.line(phase, lastVerdict, error?.get("message") as? String)

    private fun challenge(): ByteArray? = (state["challenge"] as? String)?.let { Base64.getDecoder().decode(it) }

    private fun signing(): Map<*, *>? = state["signing"] as? Map<*, *>

    /** Makes the identity key under a new alias; true when it was made. On failure the phase and [error] say why. */
    private fun makeIdentity(generation: Int): Boolean {
        val newAlias = "$ALIAS_PREFIX$generation"
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return try {
            val requested = generate(newAlias, challenge)
            state["alias"] = newAlias
            state["generation"] = generation
            state["created"] = System.currentTimeMillis()
            state["requested"] = requested
            state["challenge"] = Base64.getEncoder().encodeToString(challenge)
            state.remove("signing")
            save()
            newSigningKey(System.currentTimeMillis())
            true
        } catch (e: Exception) {
            runCatching { keyStore.deleteEntry(newAlias) }
            val unavailable = keysUnavailable(e)
            phase = if (unavailable != null) IdentityRules.Phase.KEYS_UNAVAILABLE else IdentityRules.Phase.ERROR
            error = linkedMapOf(
                "code" to if (unavailable != null) "ERROR_ATTESTATION_KEYS_UNAVAILABLE" else e.javaClass.simpleName,
                "message" to (if (unavailable != null) "no attestation keys" else e.message ?: e.javaClass.simpleName),
                "transient" to unavailable,
                "at" to Der.iso(System.currentTimeMillis()),
            )
            Log.w(TAG, "identity key not made: ${error}", e)
            false
        }
    }

    /** Generates the attested key: StrongBox when the phone has it, else the TEE. Returns which was asked for. */
    private fun generate(alias: String, challenge: ByteArray): String {
        val strongBox = Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (strongBox) {
            try {
                generate(alias, challenge, strongBox = true)
                return "strongbox"
            } catch (e: Exception) {
                if (e !is StrongBoxUnavailableException) Log.w(TAG, "StrongBox key failed, using the TEE", e)
                runCatching { keyStore.deleteEntry(alias) }
            }
        }
        generate(alias, challenge, strongBox = false)
        return "tee"
    }

    private fun generate(alias: String, challenge: ByteArray?, strongBox: Boolean, validTo: Long? = null) {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (challenge != null) b.setAttestationChallenge(challenge)
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
        if (validTo != null) b.setKeyValidityEnd(Date(validTo))
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(b.build()) }.generateKeyPair()
    }

    /** `ERROR_ATTESTATION_KEYS_UNAVAILABLE` in [e]'s causes (API 33): whether it is transient; null when it is not that error. */
    private fun keysUnavailable(e: Throwable): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return null
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is android.security.KeyStoreException && t.numericErrorCode == android.security.KeyStoreException.ERROR_ATTESTATION_KEYS_UNAVAILABLE) return t.isTransientFailure
            t = t.cause
        }
        return null
    }

    private fun signer(alias: String): RecordSigner = object : RecordSigner {
        override val publicKey: ByteArray = keyStore.getCertificate(alias).publicKey.encoded
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyStore.getKey(alias, null) as PrivateKey)
            update(data)
            sign()
        }
    }

    /** A 30-day signing key certified by the identity key; the previous one is dropped. */
    private fun newSigningKey(now: Long) {
        val identity = alias ?: return
        val n = ((signing()?.get("n") as? Number)?.toInt() ?: 0) + 1
        val signingAlias = "$identity.signing.$n"
        val to = now + IdentityRules.SIGNING_VALIDITY
        generate(signingAlias, challenge = null, strongBox = false, validTo = to)
        val record = CertificationRecord.create(signer(identity), keyStore.getCertificate(signingAlias).publicKey.encoded, now, to)
        (signing()?.get("alias") as? String)?.let { old -> runCatching { keyStore.deleteEntry(old) } }
        state["signing"] = linkedMapOf("alias" to signingAlias, "n" to n, "validFrom" to now, "validTo" to to, "record" to Base64.getEncoder().encodeToString(record.encode()))
        save()
    }

    /**
     * Makes a new identity key and a rotation record signed by the old and the new key, then drops the old key and
     * certifies a new signing key. Returns the old and new key ids and the record's own check.
     */
    @Synchronized fun rotate(trigger: String): Map<String, Any?> {
        val oldAlias = alias ?: throw IllegalStateException("no identity key yet: GET /identity makes it")
        val oldSigner = signer(oldAlias)
        val oldSigning = signing()?.get("alias") as? String
        val generation = ((state["generation"] as? Number)?.toInt() ?: 0) + 1
        val newAlias = "$ALIAS_PREFIX$generation"
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val requested = generate(newAlias, challenge)
        val now = System.currentTimeMillis()
        val record = RotationRecord.create(oldSigner, signer(newAlias), now)
        val check = record.check(JavaSecurityVerifier)
        @Suppress("UNCHECKED_CAST") val rotations = ((state["rotations"] as? List<String>).orEmpty() + Base64.getEncoder().encodeToString(record.encode())).takeLast(MAX_ROTATIONS)
        state["alias"] = newAlias
        state["generation"] = generation
        state["created"] = now
        state["requested"] = requested
        state["challenge"] = Base64.getEncoder().encodeToString(challenge)
        state["rotations"] = rotations
        state["lastRotationAt"] = now
        state.remove("signing")
        save()
        newSigningKey(now)
        runCatching { keyStore.deleteEntry(oldAlias) }
        oldSigning?.let { runCatching { keyStore.deleteEntry(it) } }
        phase = IdentityRules.Phase.READY
        selfCheck(now)
        return linkedMapOf(
            "trigger" to trigger,
            "oldKeyId" to Sha.keyId(oldSigner.publicKey),
            "newKeyId" to Sha.keyId(record.newKey),
            "rotationRecordBytes" to record.encode().size,
            "rotationCheck" to (check ?: "ok"),
            "at" to Der.iso(now),
        )
    }

    /** `GET /identity`: key ids, dates, level, chain, the self-check with reasons and lifts, the lists, the revocation list. */
    @Synchronized fun json(includePem: Boolean = false): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val c = chain()
        val v = if (c != null) selfCheck(now) else null
        val d = v?.description
        val s = signing()
        val certification = (s?.get("record") as? String)?.let { b ->
            runCatching { CertificationRecord.decode(Base64.getDecoder().decode(b)) }.getOrNull()
        }
        val identityKey = c?.let { runCatching { io.github.jasondorjeshort.dystopo.identity.X509Cert.parse(it.first()).publicKey }.getOrNull() }
        @Suppress("UNCHECKED_CAST") val rotations = (state["rotations"] as? List<String>).orEmpty()
        return linkedMapOf(
            "phase" to phase.json,
            "line" to line().toMap(),
            "error" to error,
            "identity" to if (c == null) null else linkedMapOf(
                "keyId" to identityKey?.let { Sha.keyId(it) },
                "alias" to alias,
                "generation" to state["generation"],
                "created" to (state["created"] as? Number)?.toLong()?.let { Der.iso(it) },
                "requested" to state["requested"],
                "securityLevel" to d?.attestationSecurityLevel?.json,
                "keyMintSecurityLevel" to d?.keyMintSecurityLevel?.json,
                "chainLength" to c.size,
                "chainEnd" to v?.chainEnd?.let { Der.iso(it) },
                "successorDue" to IdentityRules.successorDue(v?.chainEnd, now, (state["lastRotationAt"] as? Number)?.toLong()),
                "deviceLocked" to d?.rootOfTrust?.deviceLocked,
                "verifiedBootState" to d?.rootOfTrust?.verifiedBootState?.json,
            ),
            "signing" to if (s == null) null else linkedMapOf(
                "keyId" to certification?.let { Sha.keyId(it.signingKey) },
                "validFrom" to (s["validFrom"] as? Number)?.toLong()?.let { Der.iso(it) },
                "validTo" to (s["validTo"] as? Number)?.toLong()?.let { Der.iso(it) },
                "certification" to if (certification == null || identityKey == null) "missing" else (certification.check(identityKey, JavaSecurityVerifier, now) ?: "ok"),
            ),
            "rotations" to linkedMapOf("kept" to rotations.size, "lastAt" to (state["lastRotationAt"] as? Number)?.toLong()?.let { Der.iso(it) }),
            "selfCheck" to v?.let {
                linkedMapOf("policy" to "release", "passesWithSignerRelaxed" to IdentityRules.passesWithSignerRelaxed(it)) + it.toMap()
            },
            "lists" to PolicyLists.release.toMap() + linkedMapOf("policyFile" to "deferred: the lists ship in the APK and update with it (ADR 0051, 2026-11-03)"),
            "ownSigners" to runCatching { Signing.own(context) }.getOrNull(),
            "revocation" to revocation.json(),
            "lastEnsure" to lastEnsure,
            "pem" to if (includePem && c != null) Pem.encode(c) else null,
        )
    }

    private fun load(): MutableMap<String, Any?> = runCatching {
        if (stateFile.isFile) LinkedHashMap(MiniJson.parseObject(stateFile.readText())) else LinkedHashMap()
    }.getOrElse { LinkedHashMap() }

    private fun save() {
        dir.mkdirs()
        val tmp = File(dir, "identity.json.tmp")
        tmp.writeText(JsonWriter.write(state))
        if (!tmp.renameTo(stateFile)) {
            stateFile.delete()
            tmp.renameTo(stateFile)
        }
    }

    companion object {
        private const val TAG = "DystopoIdentity"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_PREFIX = "dystopo.identity."
        private const val MAX_ROTATIONS = 4

        @Volatile private var instance: InstallIdentity? = null

        fun get(context: Context): InstallIdentity = instance ?: synchronized(this) {
            instance ?: InstallIdentity(context.applicationContext).also { instance = it }
        }

        /** Debug builds: the test root from the debug build's assets (`test-root.json`); null in release builds. */
        fun testRoot(context: Context): TestChainMint.Root? {
            if (!BuildConfig.DEBUG_ENDPOINTS) return null
            return testRootCache ?: runCatching {
                TestChainMint.root(context.assets.open("test-root.json").use { it.readBytes().decodeToString() })
            }.getOrNull()?.also { testRootCache = it }
        }

        @Volatile private var testRootCache: TestChainMint.Root? = null

        /** The anchors the debug verify uses: the release roots plus, in debug builds, the test root. */
        fun debugAnchors(context: Context): TrustAnchors = IdentityRules.debugAnchors(testRoot(context)?.anchors)
    }
}
