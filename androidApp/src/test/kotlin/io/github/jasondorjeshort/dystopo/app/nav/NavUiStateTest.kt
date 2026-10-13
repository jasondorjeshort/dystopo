package io.github.jasondorjeshort.dystopo.app.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** T86: the first-launch crash, "Reading a state that was created after the snapshot was taken". */
class NavUiStateTest {
    private object Lazy { val s = mutableStateOf("x") }

    private fun onOtherThread(f: () -> Unit) { thread { f() }.join() }

    @Test fun stateCreatedOnAnotherThreadAfterTheSnapshotCannotBeRead() {
        // The old shape: a background thread is first to touch the object that owns the state
        // while the composition's snapshot is already open.
        val snap = Snapshot.takeSnapshot()
        try {
            onOtherThread { Lazy.s }
            assertFailsWith<IllegalStateException> { snap.enter { Lazy.s.value } }
        } finally { snap.dispose() }
    }

    @Test fun navUiStateIsReadableFromASnapshotTakenBeforeAnyThreadTouchesNavigation() {
        NavUiState.prepare() // Application.onCreate
        val snap = Snapshot.takeSnapshot() // the first composition
        try {
            onOtherThread { NavUiState.ui = null } // a background thread touching navigation meanwhile
            assertNull(snap.enter { NavUiState.ui })
        } finally { snap.dispose() }
    }

    @Test fun writesAreSeenByLaterSnapshots() {
        NavUiState.prepare()
        val before = Snapshot.takeSnapshot()
        try {
            assertEquals(null, before.enter { NavUiState.ui })
        } finally { before.dispose() }
    }
}
