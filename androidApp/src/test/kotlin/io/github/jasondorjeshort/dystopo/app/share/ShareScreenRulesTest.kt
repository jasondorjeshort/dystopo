package io.github.jasondorjeshort.dystopo.app.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T220: the Share screen's small rules: counts with a singular, and when "Enter details instead" is open. */
class ShareScreenRulesTest {
    @Test
    fun countsHaveASingular() {
        assertEquals("1 change", counted(1, "change"))
        assertEquals("0 changes", counted(0, "change"))
        assertEquals("3 packs", counted(3, "pack"))
    }

    @Test
    fun theFieldsFoldWhileDiscoveryOrTheScannerCanDoTheJob() {
        assertFalse(enterDetailsShown(null, discovery = true, scanner = true, addr = "", code = ""))
        assertFalse(enterDetailsShown(null, discovery = true, scanner = false, addr = "", code = ""))
        assertFalse(enterDetailsShown(null, discovery = false, scanner = true, addr = "", code = ""))
        assertTrue(enterDetailsShown(null, discovery = false, scanner = false, addr = "", code = ""))
    }

    @Test
    fun aChosenPhoneOpensThemForItsCodeAndATapDecides() {
        assertTrue(enterDetailsShown(null, discovery = true, scanner = true, addr = "phone.local:8841", code = ""))
        assertFalse(enterDetailsShown(null, discovery = true, scanner = true, addr = "phone.local:8841", code = "ABCD")) // a scanned code: Sync is next
        assertTrue(enterDetailsShown(true, discovery = true, scanner = true, addr = "", code = ""))
        assertFalse(enterDetailsShown(false, discovery = false, scanner = false, addr = "", code = ""))
    }

    @Test
    fun aFailedSyncNeverReadsAsSynced() {
        // T219's start words, which the screen colours by: only a sync that worked starts "Synced".
        for (e in listOf(ShareClient.WrongCodeException("no"), IllegalArgumentException("the code is 16 letters"), java.io.IOException("10.0.2.2 timed out"), RuntimeException("boom"), Error("oom"))) {
            assertFalse(shareSyncFailed(e).startsWith("Synced"), shareSyncFailed(e))
        }
        assertEquals("Wrong code", shareSyncFailed(ShareClient.WrongCodeException("no")))
        assertEquals("Sync failed: could not reach the other phone", shareSyncFailed(java.io.IOException("10.0.2.2 timed out")))
        assertFalse("10.0.2.2" in shareSyncFailed(java.io.IOException("10.0.2.2 timed out")))
        assertEquals("Sync failed: the code is 16 letters", shareSyncFailed(IllegalArgumentException("the code is 16 letters")))
        assertEquals("Sync failed: check the address", shareSyncFailed(IllegalArgumentException("no host in http://10.0.2.2x:8841/share/sealed")))
    }

    @Test
    fun theFirstLineSaysWhatToDoAndTheHintIsAnExample() {
        assertTrue(SHARE_INSTRUCTION.startsWith("Give this code"))
        assertEquals(1, ADDRESS_HINT.count { it == ':' })
    }
}
