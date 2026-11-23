package io.github.jasondorjeshort.dystopo.app.skylist

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.controls.AnchoredViewSwitch
import io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch
import io.github.jasondorjeshort.dystopo.app.controls.ViewSwitchColours
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.gnss.GnssPalette
import io.github.jasondorjeshort.dystopo.app.gnss.SatellitesSection
import io.github.jasondorjeshort.dystopo.app.gnss.plotSats
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.horizon.FlightCardHost
import io.github.jasondorjeshort.dystopo.app.horizon.FlightsSection
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonData
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonText
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi
import io.github.jasondorjeshort.dystopo.app.horizon.LayerToggles
import io.github.jasondorjeshort.dystopo.app.horizon.PeaksSection
import io.github.jasondorjeshort.dystopo.app.horizon.PickedPeak
import io.github.jasondorjeshort.dystopo.app.horizon.PlaceRow
import io.github.jasondorjeshort.dystopo.app.horizon.SkyViewEffects
import io.github.jasondorjeshort.dystopo.app.horizon.SkyViewHeading
import io.github.jasondorjeshort.dystopo.app.horizon.SkyViewHolds
import io.github.jasondorjeshort.dystopo.app.horizon.TerrainLine
import io.github.jasondorjeshort.dystopo.app.horizon.pickedFlight
import io.github.jasondorjeshort.dystopo.app.horizon.peaksLine
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaData
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaTargets
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaUi
import io.github.jasondorjeshort.dystopo.app.panorama.TargetKind
import io.github.jasondorjeshort.dystopo.gnss.GnssHealth
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayers
import io.github.jasondorjeshort.dystopo.horizon.HorizonPlace
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim
import io.github.jasondorjeshort.dystopo.horizon.PanoramaSweep
import io.github.jasondorjeshort.dystopo.horizon.SkyView
import io.github.jasondorjeshort.dystopo.render.UnitFormat

/** T344: the List view's words (ADR 0031: shown in the task's report; no other product named). */
object SkyListStrings {
    /** The heading over the list. */
    const val TITLE = "List view"

    /** The Panorama's small button that opens the List (its second way in, besides the switch's fourth segment). */
    const val OPEN = "List view"
}

/**
 * T344: the List view's state (wave 47; the owner's Answer of 2026-11-22: step one, the Panorama's pane becomes a fourth
 * view). Which sky view shows, the place and the picks are `HorizonUi`'s, the one open state; the data is the Panorama's
 * (`PanoramaData`: the place swept to the view range, its peaks), so the List and the Panorama read the same place and
 * moment. What this object keeps: where the List was opened from ([from]), what it last drew ([drawn]) and where its parts
 * were laid out, for `GET /skylist`.
 */
object SkyList {
    /** The view the List was last opened from (the switch's segment, the Panorama's button), or null. */
    @Volatile var from: SkyView? = null
        private set

    /** Main thread: the List opens from [v] (the switch's [ViewSwitch.select] and the Panorama's button). */
    fun entered(v: SkyView) {
        from = v
    }

    /**
     * Whether the Panorama, shown again from the List, keeps its aim: the List was opened from the Panorama and the aim is
     * set. Otherwise the Panorama opens as the switch opens it (the aim at opening, choice (c) of wave 45).
     */
    fun keepsAim(): Boolean = from == SkyView.PANORAMA && PanoramaUi.aim != null

    /** The Panorama's list button: the List opens over the same place (main thread). */
    fun openFromPanorama() {
        ViewSwitch.select(SkyView.LIST)
    }

    /**
     * A row's tap (main thread): the Panorama shows, turned to the row's thing (T327's behaviour; the target's rule is
     * [PanoramaTargets]). [event] makes the thing the target; when it is the target already the view only turns to it (a
     * second tap on the Panorama's own row lets it go, which the List does not want).
     */
    fun show(event: PanoramaTargets.Event, kind: TargetKind, id: String) {
        val keep = keepsAim()
        if (!keep) PanoramaUi.opened()
        HorizonUi.show(SkyView.PANORAMA)
        val t = PanoramaUi.targets.target
        if (t == null || t.kind != kind || t.id != id) PanoramaUi.apply(event)
        PanoramaUi.turnToTarget()
        lastRow = "${kind.key}:$id"
        rowsShown++
    }

    /** The last row shown in the Panorama (`kind:id`) and the rows shown so far (`GET /skylist`). */
    @Volatile var lastRow: String? = null
        private set
    @Volatile var rowsShown = 0
        private set

    /** What the List last drew (`GET /skylist` `drawn`), and its list's viewport and its switch (px in the Compose root). */
    @Volatile var drawn: Map<String, Any?>? = null
    @Volatile var listPx: List<Int>? = null
    @Volatile var switchPx: List<Int>? = null

    /**
     * The List's parts, in order, as the Panorama's pane had them (the test `SkyListTest` holds the List to this, so no
     * section of the pane is lost). After [SATELLITES] the page leaves room for the viewshed's places to go (the idea's
     * Use: line of 2026-11-22), not built here.
     */
    val PARTS = listOf("PlaceRow", "SkyViewHeading", "PickedPeak", "LayerToggles", "TerrainLine", "FlightsSection", "PeaksSection", "SunSection", "SatellitesSection", "NO_LAYERS")
    const val SATELLITES = "SatellitesSection"
}

/**
 * T344: the List view, the switch's fourth segment ("Map | Horizon | Panorama | List"): the Panorama's pane as a page of
 * its own, for the same place and moment as the sky views: the place line with "Back to here" and the heading (they stay),
 * a picked peak's line, then a list that scrolls: "Show:" and the layer toggles, the terrain line, the Flights, Peaks, Sun
 * and Satellites sections. A tap on a peak's row, on the sun's line while it is up or on a satellite's row shows the
 * Panorama turned to it ([SkyList.show]); a flight's row opens its card over the list. The effects are [SkyViewEffects]'
 * (no compass: the List has no lens), so Back is the sky views' ladder: a picked flight, a picked peak, a chosen point,
 * then the view closes. The list's viewport ends above the switch, so no row passes under it.
 */
@Composable
fun SkyListScreen() {
    if (!HorizonUi.open || HorizonUi.view != SkyView.LIST) return
    @Suppress("UNUSED_VARIABLE") val rev = SettingsUi.revision
    @Suppress("UNUSED_VARIABLE") val data = PanoramaData.revision
    val tracking = AppSettings.AIRCRAFT.value
    val point = HorizonUi.point
    val moved = point != null
    val layers = HorizonPlace.layers(AppSettings.horizonLayers.effective(tracking), moved)
    val night = AppSettings.mapOptions.night
    val pal = remember(night) { GnssPalette(night) }
    val units = AppSettings.units
    val fix = Gnss.position()
    val skyRange = AppSettings.skyRange
    val clock = SkyViewEffects(
        SkyViewHolds(compass = false, satellites = layers.satellites, flightsAt = fix?.takeIf { layers.flights }?.let { it.latitude to it.longitude }, skyRange = skyRange), night,
    )
    val tick = clock.tick
    val pos = HorizonPlace.source(point, fix?.latitude, fix?.longitude)
    val place = HorizonPlace.line(point, fix?.latitude, fix?.longitude, units)
    val rangeM = AppSettings.viewRangeKm * 1000.0
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    HorizonUi.scrollTo?.let { px ->
        LaunchedEffect(px) {
            scroll.scrollTo(px.coerceAtMost(scroll.maxValue))
            HorizonUi.scrollTo = null
        }
    }
    Box(Modifier.fillMaxSize().background(pal.paper).pointerInput(Unit) { detectTapGestures { } }.testTag("skyListView")) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding()) {
            val landscape = maxWidth > maxHeight
            val switchRoom = ViewSwitch.reserveDp.dp + 8.dp
            // The Panorama's place and steps: those its plot last asked for, else the opening's for this window's width.
            val wPx = with(density) { maxWidth.toPx() }.toDouble()
            val steps = PanoramaUi.wanted.ifEmpty { PanoramaSweep.steps(PanoramaAim.OPENING_FOV_DEG, wPx) }
            val work = pos?.let { PanoramaData.request(it.first, it.second, rangeM, steps, point == null) }
            val h = work?.state
            val peaks = if (layers.peaks) work?.peaks else null
            val selected = HorizonUi.selected
            val picked = peaks?.candidates?.firstOrNull { HorizonUi.keyOf(it) == selected }
            val nowMs = PanoramaUi.sunAtMs ?: System.currentTimeMillis()
            val fn = h?.profile?.let { pr -> { az: Double -> pr.angleAt(az) } }
            val sunNow = if (layers.sun && pos != null) io.github.jasondorjeshort.dystopo.nav.Sun.position(pos.first, pos.second, nowMs) else null
            val sunDay = if (layers.sun && pos != null) HorizonData.sunFor(h?.takeIf { it.profile != null }, pos.first, pos.second, HorizonUi.date, nowMs) else null
            val sats = if (layers.satellites) Gnss.satellites else emptyList()
            val plotted = plotSats(sats)
            val summary = GnssHealth.summarize(sats)
            val flights = if (layers.flights) HorizonData.flightsFor(h, fix, skyRange, System.currentTimeMillis()) else null
            val flight = pickedFlight()
            val target = PanoramaUi.targets.target
            // T340: with no terrain the reason stands as the horizon, so the terrain line and Peaks say it and finish.
            val hr = h ?: work?.reason?.let { Gnss.HorizonState(pos!!.first, pos.second, null, it, null, null, 0) }
            Column(Modifier.fillMaxSize()) {
                PlaceRow(place, moved, pal, night)
                SkyViewHeading(SkyListStrings.TITLE, help = "list.view", pal = pal)
                picked?.let { PickedPeak(it, pal, units) }
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        // The viewport ends above the switch (wave 46's follow-up: no row passes under it mid-scroll).
                        .padding(bottom = switchRoom)
                        .onGloballyPositioned { c ->
                            val at = c.positionInRoot()
                            SkyList.listPx = listOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height).map { Math.round(it) }
                        }
                        .verticalScroll(scroll).testTag("skyList"),
                ) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // The pane's order (T327): the toggles, the terrain line, Flights, Peaks, Sun, Satellites.
                        LayerToggles(layers, HorizonLayers.buttons(tracking), HorizonPlace.disabled(moved), moved, pal)
                        TerrainLine(hr, pos, units, pal)
                        flights?.let { FlightsSection(it, fix != null, HorizonUi.selectedFlight, pal, units, help = "panorama.flights") }
                        if (layers.peaks) PeaksSection(
                            hr, peaks, selected, pal, units, help = "panorama.peaks", rangeM = rangeM,
                            onRow = { key, p -> SkyList.show(PanoramaTargets.PeakPicked(key, p.peak.name), TargetKind.PEAK, key) },
                        )
                        if (layers.sun) io.github.jasondorjeshort.dystopo.app.horizon.SunSection(
                            pos != null, sunDay, sunNow, fn, pal, night, tick, help = "panorama.sun",
                            onNow = { SkyList.show(PanoramaTargets.SunTapped(true), TargetKind.SUN, PanoramaTargets.SUN_ID) },
                            nowChosen = target?.kind == TargetKind.SUN,
                        )
                        if (layers.satellites) SatellitesSection(
                            sats, plotted, summary, fn, pal, units, tick, bump = { clock.bump() },
                            onRow = { s -> SkyList.show(PanoramaTargets.SatelliteTapped(s.id, HorizonText.satelliteName(s)), TargetKind.SATELLITE, s.id) },
                            chosen = target?.takeIf { it.kind == TargetKind.SATELLITE }?.id,
                        )
                        if (layers.on.isEmpty()) BasicText(HorizonText.NO_LAYERS, style = TextStyle(color = pal.muted, fontSize = 14.sp))
                        // Room for a section after Satellites: the viewshed's places to go (not built in T344).
                    }
                }
            }
            // T327's flight card, as the pane hosted it: over the list's lower part, above the switch.
            flight?.let { a ->
                FlightCardHost(
                    a,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.6f).padding(8.dp).padding(bottom = switchRoom - 8.dp),
                )
            }
            SideEffect {
                SkyList.drawn = linkedMapOf(
                    "landscape" to landscape, "windowDp" to listOf(Math.round(maxWidth.value), Math.round(maxHeight.value)), "fontScale" to density.fontScale,
                    "night" to night, "layers" to layers.json(), "buttons" to HorizonLayers.buttons(tracking).map { it.key },
                    "disabledButtons" to HorizonPlace.disabled(moved).map { it.key },
                    "point" to point?.let { p -> linkedMapOf("lat" to p.lat, "lon" to p.lon, "name" to p.name) }, "placeLine" to place.text,
                    "moved" to place.moved, "backShown" to moved, "rangeKm" to rangeM / 1000, "steps" to steps,
                    "terrain" to (h?.profile != null), "peaksLine" to (if (layers.peaks) peaksLine(hr, peaks, units, rangeM) else null),
                    "peaks" to peaks?.candidates?.map { it.peak.name }, "selected" to picked?.peak?.name,
                    "flightsListed" to flights?.list?.size, "flightCard" to (flight != null), "selectedFlight" to flight?.hex,
                    "sunNow" to sunNow?.let { HorizonText.sunUp(it.apparentAltitudeDeg) }, "satellitesListed" to (if (layers.satellites) sats.size else null),
                    "target" to target?.let { "${it.kind.key}:${it.id}" },
                    "scrollPx" to scroll.value, "scrollMaxPx" to scroll.maxValue, "switchRoomDp" to Math.round(switchRoom.value),
                )
            }
        }
        // The switch at its anchor, List selected, over the content (T315's one placement).
        AnchoredViewSwitch(SkyView.LIST, ViewSwitchColours(pal.card, pal.ink, pal.line), Modifier.testTag(ViewSwitch.TAG_ON_VIEW)) { r, _ ->
            SkyList.switchPx = r.map { Math.round(it) }
        }
    }
}

/**
 * T344: the List's help pop-ups. The three sections' texts moved here with their sections from the Panorama's pane (their
 * keys kept: `panorama.peaks`, `panorama.sun`, `panorama.flights`); `list.view` is the List's own.
 */
internal val SKY_LIST_HELP: List<HelpText> = run {
    val placed = "The map: the switch's ${ViewSwitch.LIST}"
    val show: () -> Unit = { ViewSwitch.select(SkyView.LIST) }
    listOf(
        HelpText("list.view", SkyListStrings.TITLE, placed, show = show) { _ ->
            "What the Panorama view shows here, as lists: the layers to show, the highest ground, flights, peaks, the sun's day " +
                "and satellites, for the same place. Tap a peak, the sun or a satellite to see it in the Panorama, turned to it; " +
                "tap a flight for its card."
        },
        HelpText("panorama.peaks", HorizonText.PEAKS, "$placed, its ${HorizonText.PEAKS} list", show = show) { units ->
            "Named peaks within your view range of ${UnitFormat.distance(AppSettings.viewRangeKm * 1000.0, units)} in view or just out of " +
                "sight, closest first. Tap a row to see it in the Panorama, turned to it; " +
                "${HorizonText.VIEW_FROM_HERE} on its line stands the views on its top."
        },
        HelpText("panorama.sun", HorizonText.SUN, "$placed, its ${HorizonText.SUN} section", show = show) { _ ->
            "Today's sun at this place: where it is now, when it clears the terrain and goes behind it, and the flat horizon's " +
                "times. Tap the line saying where it is now to see it in the Panorama while it is up."
        },
        HelpText("panorama.flights", HorizonText.FLIGHTS, "$placed, its ${HorizonText.FLIGHTS} list", show = show) { _ ->
            "Each flight in the air within Sky range, closest first, with its distance, bearing, angle and altitude; hollow when " +
                "hidden by the terrain or not heard lately, a ring for an emergency code. Tap a row for its card."
        },
    )
}
