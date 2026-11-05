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
    fun theFirstLineSaysWhatToDoAndTheHintIsAnExample() {
        assertTrue(SHARE_INSTRUCTION.startsWith("Give this code"))
        assertEquals(1, ADDRESS_HINT.count { it == ':' })
    }
}
