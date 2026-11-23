---
date: 2026-11-22
kind: bug
related: [2026-11-22-every-value-that-can-dash-or-dim-gets-a-question-mark-and-a-reason.md]
---

# Flights: Settings turns them on for the map; the sky views' toggle hides them in the sky views only

Priority: **medium** (a missing control, and a battery question on a hike).

Settings has a flights option; on, it shows every flight on the map view. The Horizon and Panorama views
have their own flights toggle, which applies to both of those views but not back to the map view. So the map
view's flights can only be hidden by switching flights off for everything. The owner wants to keep both a
"track flights" option (the data, the polling) and a "show flights" toggle, with the toggle reachable on the
map view and applying there. Likely to recur for every other map overlay or option.

Anchors: `androidApp/.../aircraft/AircraftPoller.kt`, `AircraftRoutes.kt`; the Horizon and Panorama flight
toggles (`horizon/`, `panorama/`); the Settings page (`settings/SettingsPages.kt`).

Cost: the owner suspects flights, like overlays in general, use CPU and battery, which matters in hiking mode
(unmeasured; measure the poller's and the layer's cost before deciding how much a hidden overlay may keep running).

## Open

- What each switch means (track = poll and keep data; show = draw), and whether hiding stops the polling.
- Where the map view's toggle lives (the map options sheet, a chip) and whether one toggle per surface or one
  shared with a per-surface override is the model.
- How the same shape serves other overlays, so the next one is not a third special case.
- Whether hiking mode defaults flights (and other overlays) off or paused.

Plan and survey: a research agent (how other apps handle per-surface overlay visibility and what a hidden
overlay costs) and an architect (the model across map, Horizon and Panorama), 2026-11-22.

## Survey result (read-only agent, 2026-11-22; thin evidence, several apps recalled not verified)

- No source found separates "track" from "show"; that split is a battery and quota design of ours. Common
  practice: one layers sheet, an immediate switch (Material), layer choices saved per mode (Gaia), aircraft
  filters apart from layers (Flightradar24), a pinned quick toggle when layers get buried (Windy).
- Recommendation: track = the Settings switch (`AIRCRAFT`, kept); show = per surface, default on: the sky views'
  `HORIZON_FLIGHTS` (exists) and a new map flag. Poll only while track is on and a showing surface is visible.
  The map's control is one entry in the map options sheet while track is on; a chip only if the owner wants it.
  A small overlay registry (id, track key, show key per surface) so the next overlay is a row. Hiking mode only
  moves defaults, never locks a switch. One behaviour for hidden data (stop polling, keep the last view about
  2 min, then forget), no setting.
- Repo facts: the map layer has no show flag (`AircraftLayer.kt:214-229`, `pauseReason()` checks track,
  background, Horizon open); the sky views' toggle is `HORIZON_FLIGHTS` (`AppSettings.kt:670`,
  `HorizonScreen.kt:243-256`). With track on and nobody asking the poller makes no HTTP request
  (`AircraftPoller.kt:192-198`), but its 1 s ticker keeps running and still builds and pushes map features
  (`:183-224`): a hidden map flag must also stop the draw listener and the idle tick.
- To measure first: 30 min in three states (shown, hidden with track on, track off), `dumpsys batterystats` and
  the `dystopo-aircraft` threads' CPU time. MapLibre update cost is unsourced.

## Open (from the survey)

- Does hiding on the map with Horizon closed stop polling (recommended) or only drawing?
- Forget hidden data after a grace period (about 2 min) or at once?
- Map toggle in the map options sheet only, or also a chip?
- Map default on (as the sky views) or following the track state?

## Architect's design (read-only agent, 2026-11-22; from the code, nothing run)

Today: `aircraft` ("Flight tracking", Settings, off) is the track switch; `horizonFlights` (default on) is
shared by Horizon and Panorama (`SkyViewParts.kt:139`); the map has no key and no control (`AircraftLayer` asks
whenever tracking is on and no sky view is open, `AircraftLayer.kt:214-229`). The poller already stops HTTP when no
view asks (`AircraftPoller.kt:193-198`). Still running when nothing shows flights: the 1 s ticker for the life of
the process, and with tracking on a rebuild and publish each tick; the map layer pushes `setGeoJson` on the main
thread every second, even under an open sky view and with no aircraft. No hiking or low-power mode exists in the code.

Models: **A** keeps `aircraft` as track, adds `mapFlights` (synced, default on, no migration) and a Flights
button on the map shown while tracking is on; the map layer pauses with reason "hidden", clears once, stops pushing;
about one task, low risk. **B** one shared show flag with per-view overrides: needs a migration and reproduces the
owner's confusion. **C** an overlay registry with per-view show keys and a Layers sheet (ADR 0053 D2): high cost,
conflicts with ADR 0053 C2, needs a proposal, critic and ADR.

Recommendation: A, then C only if overlays keep multiplying. Order: measure; the `mapFlights` task (key, button,
"hidden" reason, `POST /aircraft?map=`, a pure shared rule with tests, `map.flights` help and a rewritten
`settings.aircraft`); the idle-cost fixes (skip build and publish when nothing asks, skip `setGeoJson` while
hidden, covered or unchanged, stop the ticker while tracking is off); a short ADR for the pattern ("an overlay is an
optional data switch plus one show key per view; a hidden view stops asking"); then peaks on the map (the reverse
gap: the map cannot hide peaks); a `power` pause reason starting from `isPowerSaveMode`. Related shapes: peaks,
sun, satellites, trail disagreements (`showDivergence`, track and show in one switch), the map layers (only in
Settings, ADR 0053 D2 unbuilt).

Measure first (replay `POST /aircraft/replay`, 10 min per state: tracking off, shown, Horizon open over the map,
hidden): `GET /debug/cpu` threads `dystopo-aircraft`, the main and render threads; `GET /aircraft` counters; on a
real phone `tools/android-battery.py` with a new `flights` scenario (radio wake-ups likely dominate). Key question:
does the covered map redraw under an open Horizon (decides how urgent the idle-cost fix is).

Built in alpha and shown in the report (ADR 0031/0033): the button's place, icon, label, default on, no Settings
row, help wording, the new key. The owner decides: (a) does track keep polling when no view shows flights
(recommended: no); (b) what hiking mode is; (c) separate Horizon and Panorama toggles; (d) a Layers sheet now
instead of a button; (e) the same pattern for peaks on the map.
