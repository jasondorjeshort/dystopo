package io.github.jasondorjeshort.dystopo.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T110 (T106's follow-up): an update offered while navigating. Installing replaces the running app, so an install asked
 * for during a navigation session waits for it to end and then goes ahead once; "Install now" does not wait; declining
 * drops the wait.
 */
class InstallWaitTest {
    @Test
    fun anInstallDuringNavigationWaitsForTheSessionToEnd() {
        var navigating = true
        val w = InstallWait { navigating }
        assertEquals(InstallWait.Decision.WAIT, w.request(now = false))
        assertTrue(w.waiting)
        assertFalse(w.poll(), "still navigating")
        assertFalse(w.poll())
        navigating = false
        assertTrue(w.poll(), "navigation ended: install now")
        assertFalse(w.waiting)
        assertFalse(w.poll(), "once")
        assertEquals(1L, w.waitedInstalls)
    }

    @Test
    fun installNowAndNoNavigationDoNotWait() {
        var navigating = true
        val w = InstallWait { navigating }
        assertEquals(InstallWait.Decision.INSTALL, w.request(now = true))
        assertFalse(w.waiting)
        navigating = false
        assertEquals(InstallWait.Decision.INSTALL, w.request(now = false))
        assertFalse(w.poll())
    }

    @Test
    fun decliningDropsTheWait() {
        var navigating = true
        val w = InstallWait { navigating }
        w.request(now = false)
        w.cancel()
        navigating = false
        assertFalse(w.poll())
        assertEquals(0L, w.waitedInstalls)
    }
}
