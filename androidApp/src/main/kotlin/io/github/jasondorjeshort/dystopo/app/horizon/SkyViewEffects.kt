package io.github.jasondorjeshort.dystopo.app.horizon

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.aircraft.AircraftPoller
import io.github.jasondorjeshort.dystopo.app.controls.Heading
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.gnss.GnssUi
import io.github.jasondorjeshort.dystopo.horizon.SkyRange

/**
 * T325: what a sky view (the Horizon view's round plot, and a second view over the same pane) asks to be held while it is
 * open and its activity started. [compass]: the app's one compass (`Heading.setGnssWanted`, for "Follow the phone");
 * [satellites]: the GNSS (`Gnss.hold("view")`, only while the Satellites layer draws; off, the position is the map's own
 * fix); [flightsAt]: the flights' poller asked as owner `horizon` around that position (lat, lon) at [skyRange]'s radius,
 * or null for no poll (the Flights layer off, or no fix).
 */
class SkyViewHolds(
    val compass: Boolean = true,
    val satellites: Boolean,
    val flightsAt: Pair<Double, Double>?,
    val skyRange: SkyRange,
)

/** T325: the sky view's clock: [tick] counts the seconds the view has been open (a read of it re-reads everything once a second); [bump] reads again now. */
class SkyViewClock internal constructor(private val state: MutableState<Int>, val started: Boolean) {
    val tick: Int get() = state.value

    fun bump() {
        state.value++
    }
}

/**
 * T325: the effects of an open sky view, in one call a host makes from its own body (cut from `HorizonScreen()`, no
 * change in behaviour): whether the activity is started; [holds] while started (the compass, the GNSS, the flights'
 * poller); Back's ladder (the satellites' menu's confirmation, the menu, a picked flight, a picked peak, a chosen point
 * (as "Back to here"), then the view closes); a tick each second; the status bar's icons light over the [night] paper
 * (the activity keeps dark ones for the light map). The host reads the returned clock's [SkyViewClock.tick] in its own
 * body, so the whole view is read again each second.
 */
@Composable
fun SkyViewEffects(
    holds: SkyViewHolds, night: Boolean,
    /** T327: the host's own Back step after the satellites' menu and before the picks (the Panorama's target); true when used. */
    back: (() -> Boolean)? = null,
): SkyViewClock {
    val owner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) started = true
            if (e == Lifecycle.Event.ON_STOP) started = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val tick = remember { mutableStateOf(0) }
    DisposableEffect(started, holds.compass) {
        Heading.setGnssWanted(started && holds.compass)
        onDispose { Heading.setGnssWanted(false) }
    }
    DisposableEffect(started, holds.satellites) {
        Gnss.hold("view", started && holds.satellites)
        onDispose { Gnss.hold("view", false) }
    }
    BackHandler {
        when {
            GnssUi.confirmReset -> GnssUi.confirmReset = false
            GnssUi.menuOpen -> GnssUi.menuOpen = false
            back?.invoke() == true -> {}
            HorizonUi.selectedFlight != null -> HorizonUi.selectedFlight = null
            HorizonUi.selected != null -> HorizonUi.selected = null
            HorizonUi.point != null -> HorizonUi.back() // T317: Back first returns to the fix, as "Back to here"
            else -> HorizonUi.close()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            Foreground.delay(1000)
            tick.value++
        }
    }
    // Light status-bar icons over the night paper while the view is open (the activity keeps dark ones for the light map).
    val view = LocalView.current
    DisposableEffect(night) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val was = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = !night
        onDispose { if (was != null) bars.isAppearanceLightStatusBars = was }
    }
    // T312: the poll's centre is the phone's position rounded to 0.1 degree (the poller rounds; a new key only when it moves that far).
    val at = holds.flightsAt
    val centre = at?.let { Math.round(it.first * 10) to Math.round(it.second * 10) }
    DisposableEffect(started, centre, holds.skyRange) {
        if (started && at != null) {
            AircraftPoller.request(HorizonData.FLIGHTS_OWNER, at.first, at.second, HorizonData.radiusNm(holds.skyRange))
        }
        onDispose { AircraftPoller.release(HorizonData.FLIGHTS_OWNER) }
    }
    return SkyViewClock(tick, started)
}
