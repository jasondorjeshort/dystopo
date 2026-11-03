package io.github.jasondorjeshort.dystopo.identity

import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Writes every [Scenarios] case to `shared/build/identity-fixtures/` (a PEM chain each, the revocation list and
 * `cases.json` with the clock, audience and the verdict this test computed), so `devtool attest --fixtures
 * shared/build/identity-fixtures` can show that the command agrees with the tests. Regenerated on every test run;
 * nothing here is committed.
 */
class IdentityFixturesTest {
    @Test fun writeFixtures() {
        val dir = File("build/identity-fixtures").apply { deleteRecursively(); mkdirs() }
        File(dir, "revocation.json").writeText(Scenarios.revocationJson)
        val cases = Scenarios.all.map { case ->
            File(dir, "${case.name}.pem").writeText(Pem.encode(case.chain))
            val v = Scenarios.evaluate(case)
            assertEquals(case.expected, v.status, case.name)
            linkedMapOf(
                "name" to case.name,
                "chain" to "${case.name}.pem",
                "at" to Der.iso(case.now),
                "audience" to case.audience.json,
                "revocation" to if (case.revoked) "revocation.json" else null,
                "revocationFetchedAt" to if (case.revoked) Der.iso(case.revocationFetchedAt ?: (case.now - TestChains.DAY_MS)) else null,
                "expected" to linkedMapOf("status" to v.status.json, "codes" to v.codes.distinct().sorted()),
            )
        }
        File(dir, "cases.json").writeText(
            JsonWriter.write(
                linkedMapOf(
                    "about" to "T211 fixtures: chains under the committed test root (shared/src/jvmTest/resources/identity/test-root.json), judged by the release rules with the test anchors",
                    "anchors" to "test",
                    "cases" to cases,
                ),
            ),
        )
        assertEquals(Scenarios.all.size, dir.listFiles()!!.count { it.name.endsWith(".pem") })
    }
}
