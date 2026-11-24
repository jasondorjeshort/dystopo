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

## The owner's answers (2026-11-23, planning wave 48, by multiple choice with their own words)

- On what a hidden layer does with polling: "I think at some point we want a research agent to try to figure out
  how long dead reckoning on a reduced poll rate is going to be effective. Can we poll every 2, 5, 10 minutes and
  still have reasonably accurate data? Is there a good way to show that a flight's data is out of date and being
  guessed at based on dead reckoning?" Researched the same day:
  `docs/findings/2026-11-23-flights-guessed-between-polls-at-slow-refresh.md` (2 min holds for the map and far
  cruise traffic, 5 min for cruise on the map only, 10 min for nothing; a guessed state with a hollow mark and a
  growing ring; a recorder and an error report before that is built).
- On where the controls live: "Right now "flight tracking" is in map options even though it's a global option,
  while horizon and pano options show in little toggles that only show up if that global option is on. The "flight
  tracking" global needs to move out of map options to...somewhere. Map options can stay where it is for now and
  get "Show flights", but eventually this is a map-view-specific thing that should move out of the settings view
  (when its a view) and onto a map settings place somewhere (maybe where settings is now). Note though there is a
  difference between map settings that require a full tile rerender for the png tile cache (we do have a png tile
  cache right), versus things that move and MUST be overlays such as flights. In general I'd say the final form
  here isn't really decided...a research agent to look at other apps might be cool, but I'm not sure anything else
  has the same needs as us." Then, by choice: the global rows move to **Data and storage** for now.
- The coordinator's note on the tile cache: the contour and shade tiles the phone renders are cached on disk; the
  drawn vector map is not (`2026-11-07-rendered-tiles-cached-on-disk.md`, whose measurement pass is still owed).
  So an option that changes contours or shade costs a re-render of cached tiles, and an overlay such as flights
  costs none: the distinction the owner draws is real in the code.

Plan: model A, in three passes. (1) Wave 48: measure the idle cost first (the architect's four states by replay);
"Show flights" in Map options for the map view (`mapFlights`, synced, default on, shown while Flight tracking is
on); the four global rows to Data and storage; a hidden map stops asking, stops redrawing and clears its marks
once (the poller already makes no request when no view asks); the ticker stops while tracking is off and nothing
is rebuilt or pushed while nothing shows flights; the pattern recorded in a short ADR. (2) Wave 48, devtool only:
the finding's recorder and error report. (3) A later wave, from the measured table: the guessed state and its
look, the guess horizon apart from the interval, "Automatic" refresh. Left open, the owner's: the final home of
per-view settings (with the views-as-tabs idea), a Layers sheet, the same switch for peaks on the map, a hiking
or low-power mode.

2026-11-24, T364 (pass 1 built): "Show flights" in Map options (`mapFlights`, synced, default on, shown while Flight
tracking is on); the rule `shared/.../aircraft/FlightAsks.kt`; a hidden map stops asking and clears its marks once; a
covered map pushes nothing; with nobody asking nothing is built or published; the ticker stops while tracking is off.
Measured by replay, 10 min a state on an emulator: with flights shown on the map MapLibre's render thread is busy the
whole time (about 595 s of CPU in 600 s, before and after); before, a sky view over the map kept it so (595 s), after
it reads 0 s; hidden, 0 s, nothing built or pushed. The Flight tracking rows stay for T370; the help `settings.aircraft`
still reads as before.
