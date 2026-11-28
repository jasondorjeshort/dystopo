package io.github.jasondorjeshort.dystopo.app.update

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T393: the rule that decides the update offer shows on the map ([offerShown]) reads the updater's start as Compose state.
 * Since T392 the launch's `updater` stage runs after the first frame, so the map's first composition evaluates the rule
 * before [Updater.start]; it must then have read something the start writes, or nothing recomposes the offer when the
 * start, and a ready or refused update after it, come (the second closing sweep of wave 49: `offer` red).
 */
class UpdateOfferTest {
    @Test
    fun aRuleReadBeforeTheStartIsInvalidatedByTheStart() {
        val reads = HashSet<Any>()
        val before = Snapshot.observe(readObserver = { reads += it }) { offerShown(waits = false) }
        assertFalse(before, "nothing to offer before the updater started")
        val changed = HashSet<Any>()
        val handle = Snapshot.registerApplyObserver { set, _ -> changed.addAll(set) }
        try {
            Updater.markStarted() // what Updater.start does once its context is set
            Snapshot.sendApplyNotifications()
        } finally {
            handle.dispose()
        }
        assertTrue(Updater.started)
        assertTrue(reads.any { it in changed }, "the rule read nothing the updater's start writes (reads: ${reads.size}, changed: ${changed.size})")
    }
}
