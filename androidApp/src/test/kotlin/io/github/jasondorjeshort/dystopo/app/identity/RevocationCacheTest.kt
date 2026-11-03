package io.github.jasondorjeshort.dystopo.app.identity

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T216: the phone's revocation list copy: read from disk with its fetch time, refetched only when due and online. */
class RevocationCacheTest {
    private val day = IdentityRules.DAY

    @Test fun readsTheCopyWithItsAgeAndFetchesOnlyWhenDueAndOnline() {
        val dir = Files.createTempDirectory("revocation").toFile()
        try {
            var now = 1_793_000_000_000L
            val cache = RevocationCache(dir) { now }
            assertNull(cache.current())
            assertEquals("offline", cache.refreshIfDue(online = false))
            assertEquals(0, cache.fetches)

            // A copy fetched half a day ago: fresh, no request.
            File(dir, "revocation.json").writeText("""{"entries":{"2c8cdddfd5e03bfc":{"status":"REVOKED"},"123456789":{"status":"SUSPENDED"}}}""")
            File(dir, "revocation-fetched.txt").writeText((now - day / 2).toString())
            val fresh = RevocationCache(dir) { now }
            assertEquals(2, fresh.current()!!.entries)
            assertTrue(fresh.current()!!.isRevoked("2c8cdddfd5e03bfc"))
            assertTrue(fresh.current()!!.isRevoked("75bcd15")) // 123456789 in hex
            assertEquals("fresh", fresh.refreshIfDue(online = true))
            assertEquals(0, fresh.fetches)
            assertEquals(0L, fresh.json()["ageDays"])

            // A day later it is due; offline it stays as it is and says so.
            now += day
            assertEquals("offline", fresh.refreshIfDue(online = false))
            assertEquals(1L, fresh.json()["ageDays"])
            assertEquals(0, fresh.fetches)
        } finally {
            dir.deleteRecursively()
        }
    }
}
