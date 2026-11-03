package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.identity.AttestationFormatException
import io.github.jasondorjeshort.dystopo.identity.AttestationPolicy
import io.github.jasondorjeshort.dystopo.identity.Audience
import io.github.jasondorjeshort.dystopo.identity.IdentityRecord
import io.github.jasondorjeshort.dystopo.identity.JavaSecurityVerifier
import io.github.jasondorjeshort.dystopo.identity.Pem
import io.github.jasondorjeshort.dystopo.identity.PolicyLists
import io.github.jasondorjeshort.dystopo.identity.RevocationList
import io.github.jasondorjeshort.dystopo.identity.RootEntry
import io.github.jasondorjeshort.dystopo.identity.TrustAnchors
import io.github.jasondorjeshort.dystopo.identity.Verdict
import io.github.jasondorjeshort.dystopo.identity.X509Cert
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.server.Caches
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * T211: judges an Android key attestation chain by the policy of ADR 0051 (`identity.AttestationPolicy`) and prints
 * the verdict as JSON: accept, stale or refuse, every reason with its lift, the attestation's fields.
 *
 * - `attest --chain <file> [--anchors test|release] [--revocation <file>|google] [--at <ISO instant>]
 *   [--audience member|stranger]`: one chain, as PEM certificates leaf first or as a binary identity record.
 *   `--anchors release` (the default) uses the bundled roots (`attestation/roots.json`); `test` the committed test
 *   root (`--test-root`, default `shared/src/jvmTest/resources/identity/test-root.json`), with the release signer and
 *   package lists either way. `--revocation google` fetches Google's live list into `<cache>/attestation/status.json`;
 *   a file's age is its modification time unless `--revocation-fetched` gives it.
 * - `attest --fixtures <dir>`: every case of the directory the JVM tests write (`shared/build/identity-fixtures`,
 *   from `IdentityFixturesTest`), each judged as its `cases.json` line says, against the verdict the tests computed;
 *   exits 1 when any disagrees.
 */
class AttestCommand : CliktCommand(name = "attest") {
    override fun help(context: Context) =
        "Judge an Android key attestation chain by the attested-identity policy (ADR 0051); prints the verdict, reasons and lifts as JSON. " +
            "--fixtures: run the JVM tests' fixture cases and check agreement."

    private val chain by option("--chain", help = "PEM certificates (leaf first) or a binary identity record").file(mustExist = true, canBeDir = false)
    private val fixtures by option("--fixtures", help = "Directory of fixture cases (cases.json) written by the JVM tests").file(mustExist = true, canBeFile = false)
    private val anchors by option("--anchors", help = "Trust anchors: the bundled release roots or the test root").choice("release", "test").default("release")
    private val testRoot by option("--test-root", help = "The test root's JSON (for --anchors test)").file().default(File("shared/src/jvmTest/resources/identity/test-root.json"))
    private val revocation by option("--revocation", help = "Google's revocation list as a file, or 'google' to fetch it")
    private val revocationFetched by option("--revocation-fetched", help = "When the list was fetched (ISO instant; default: the file's modification time)")
    private val at by option("--at", help = "Judge at this instant (ISO, e.g. 2026-11-01T00:00:00Z; default now)")
    private val audience by option("--audience", help = "member: any revocation list age; stranger: a list under 30 days").choice("member", "stranger").default("member")

    override fun run() {
        val t0 = System.nanoTime()
        val lists = PolicyLists.release
        val policy = AttestationPolicy.release(lists)
        if (fixtures != null) return runFixtures(fixtures!!, policy, lists, t0)
        val file = chain ?: throw UsageError("give --chain <file> or --fixtures <dir>")
        val now = at?.let { instant(it, "--at") } ?: System.currentTimeMillis()
        val list = revocationList(revocation, revocationFetched, file.parentFile)
        val (platform, certs) = readChain(file)
        val trust = trustAnchors(anchors, lists)
        val v = if (platform != null) {
            policy.evaluate(IdentityRecord(platform, certs), trust, JavaSecurityVerifier, now, list, audienceOf(audience))
        } else {
            policy.evaluate(certs, trust, JavaSecurityVerifier, now, list, audienceOf(audience))
        }
        println(
            JsonWriter.write(
                linkedMapOf(
                    "command" to "attest",
                    "chain" to file.path,
                    "format" to if (platform != null) "identity-record" else "pem",
                    "anchors" to anchors,
                    "anchorKeys" to trust.keys.map { linkedMapOf("name" to it.name, "keyId" to it.keyId) },
                    "lists" to lists.toMap(),
                    "audience" to audience,
                    "verdict" to v.toMap(),
                    "ms" to (System.nanoTime() - t0) / 1_000_000,
                ),
            ),
        )
    }

    private fun runFixtures(dir: File, policy: AttestationPolicy, lists: PolicyLists, t0: Long) {
        val doc = MiniJson.parseObject(File(dir, "cases.json").readText())
        val trust = trustAnchors(doc["anchors"] as? String ?: "test", lists)
        val results = (doc["cases"] as List<*>).map { c ->
            val m = c as Map<*, *>
            val list = (m["revocation"] as? String)?.let { revocationList(File(dir, it).path, m["revocationFetchedAt"] as? String, dir) }
            val (platform, certs) = readChain(File(dir, m["chain"] as String))
            val v: Verdict = policy.evaluate(certs, trust, JavaSecurityVerifier, instant(m["at"] as String, "at"), list, audienceOf(m["audience"] as String))
            check(platform == null)
            val expected = m["expected"] as Map<*, *>
            val codes = v.codes.distinct().sorted()
            val agrees = expected["status"] == v.status.json && expected["codes"] == codes
            linkedMapOf("name" to m["name"], "status" to v.status.json, "codes" to codes, "expected" to expected, "agrees" to agrees)
        }
        val agree = results.count { it["agrees"] == true }
        println(
            JsonWriter.write(
                linkedMapOf(
                    "command" to "attest",
                    "fixtures" to dir.path,
                    "total" to results.size,
                    "agree" to agree,
                    "accepted" to results.count { it["status"] == "accept" },
                    "stale" to results.count { it["status"] == "stale" },
                    "refused" to results.count { it["status"] == "refuse" },
                    "cases" to results,
                    "ms" to (System.nanoTime() - t0) / 1_000_000,
                ),
            ),
        )
        if (agree != results.size) throw ProgramResult(1)
    }

    /** PEM certificates, or a binary identity record (its platform tag returned with the chain). */
    private fun readChain(file: File): Pair<String?, List<ByteArray>> {
        val bytes = file.readBytes()
        if (bytes.isNotEmpty() && bytes[0].toInt() == 0x30) {
            return try {
                val rec = IdentityRecord.decode(bytes)
                rec.platform to rec.chain
            } catch (e: AttestationFormatException) {
                // A single DER certificate.
                null to listOf(bytes)
            }
        }
        val certs = try {
            Pem.certificates(bytes.decodeToString())
        } catch (e: AttestationFormatException) {
            throw UsageError("${file.path}: ${e.message}")
        }
        if (certs.isEmpty()) throw UsageError("${file.path}: no CERTIFICATE blocks")
        return null to certs
    }

    private fun trustAnchors(which: String, lists: PolicyLists): TrustAnchors = when (which) {
        "release" -> lists.anchors
        else -> {
            if (!testRoot.isFile) throw UsageError("--anchors test: ${testRoot.path} not found (run from the repository root or pass --test-root)")
            val m = MiniJson.parseObject(testRoot.readText())
            val cert = Base64.getDecoder().decode(m["certificate"] as String)
            TrustAnchors(listOf(RootEntry("test root", X509Cert.parse(cert).publicKey, testRoot.path)))
        }
    }

    private fun revocationList(source: String?, fetched: String?, base: File?): RevocationList? {
        if (source == null) return null
        val file = if (source == "google") fetchGoogle() else File(source).let { if (it.isAbsolute || it.isFile) it else File(base, source) }
        if (!file.isFile) throw UsageError("--revocation: ${file.path} not found")
        val fetchedAt = fetched?.let { instant(it, "--revocation-fetched") } ?: file.lastModified()
        return try {
            RevocationList.parse(file.readText(), fetchedAt)
        } catch (e: AttestationFormatException) {
            throw UsageError("--revocation ${file.path}: ${e.message}")
        }
    }

    private fun fetchGoogle(): File {
        val out = File(Caches.root, "attestation/status.json").apply { parentFile.mkdirs() }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
        val req = HttpRequest.newBuilder(URI.create(GOOGLE_STATUS)).timeout(Duration.ofSeconds(60)).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200) throw UsageError("$GOOGLE_STATUS answered ${resp.statusCode()}")
        out.writeBytes(resp.body())
        return out
    }

    private fun audienceOf(s: String) = if (s == "stranger") Audience.STRANGER else Audience.MEMBER

    private fun instant(s: String, what: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (e: Exception) {
        throw UsageError("$what: '$s' is not an ISO instant (2026-11-01T00:00:00Z)")
    }

    companion object {
        const val GOOGLE_STATUS = "https://android.googleapis.com/attestation/status"
    }
}
