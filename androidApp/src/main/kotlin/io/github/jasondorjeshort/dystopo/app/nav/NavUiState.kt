package io.github.jasondorjeshort.dystopo.app.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * T86: the Compose state behind [NavSession.ui], in an object of its own that only the UI path touches.
 * A `mutableStateOf` created on a background thread while the first composition runs is "created after the
 * snapshot was taken" for that composition, and reading it throws `IllegalStateException`. It used to live
 * in [NavSession] itself, whose members (`navigator`, `active`) background threads read (T75's prefetch
 * poll starts at app launch), so the first thread to touch `NavSession` decided where the state was created.
 * Now [prepare] runs on the main thread in `Application.onCreate`, before any composition exists, and
 * nothing else creates it.
 */
internal object NavUiState {
    var ui: NavSession.Ui? by mutableStateOf(null)

    /** T180: the free-tracking turn-around clock ([WayBackClock]); null when nothing records or while navigating. */
    var wayBack: WayBackClock.Ui? by mutableStateOf(null)

    /** Forces creation now (call from the main thread, before the first composition). */
    fun prepare() {}
}
