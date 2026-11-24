# 0068. An overlay is one data switch and one show key per view; a hidden view stops asking

Date: 2026-11-24
Status: accepted

Record: `docs/ideas/2026-11-22-flights-have-two-switches-and-the-map-view-has-no-way-to-hide-them.md` (the owner's
words, the survey, the architect's model A, the Plan); wave 48's T364 in `docs/TASKS.md` and its dated entry in
`docs/PLANNING.md` (the build and the measurement); the critic's point 12 of 2026-11-24 (this record is written at
the merge, from what was built and measured, not before).

## Context

Flights had two switches that did not cover three views. "Flight tracking" (`aircraft`) turned the data on; the
Horizon and Panorama views shared a Flights button (`horizonFlights`); the map had no key, so its flights could only
be hidden by turning the data off everywhere. The owner asked for both a track option and a show toggle that is
reachable for the map and applies there, and expected the same question for every later overlay (2026-11-22; on
2026-11-23: "Map options can stay where it is for now and get 'Show flights'", and "the final form here isn't
really decided"). The owner also suspected a cost on a hike. Measured before the build (T364, ten minutes a state
by replay, no request to the feed): the one-second ticker ran for the life of the process even with tracking off;
with tracking on, marks were rebuilt and pushed to the map every second, also under an open sky view, and the
map's render thread was busy the whole time in both states.

## Decision

1. **The pattern.** An overlay is data that moves or is fetched and is drawn over a view, never baked into a
   rendered tile. It has at most **one data switch** (whether the phone gets the data at all: requests, quota,
   battery; global) and **one show key per view** (synced, default on, offered only while the data switch is on).
   The sky views (Horizon, Panorama, List) are one owner with one key. A show key never turns data on by itself.
2. **Who asks.** A view asks while its show key is on and it is the view in front. The data is requested, rebuilt
   and published only while at least one view asks. Per state:
   - data switch off: nothing runs, the ticker included;
   - a view hidden by its key: it clears its marks once, then pushes nothing and asks for nothing;
   - a view covered by another (the map under a sky view): it keeps its marks and pushes nothing;
   - no view asking with the data switch on: no request, no rebuild, no publish.
   Turning a show key on again asks at once: marks are back within one poll.
3. **Where the rule lives.** A pure function in `shared` decides who asks and names the pause reason, tested for
   each state (`shared/.../aircraft/FlightAsks.kt`, `FlightAsksTest`). As merged, the map layer and the poller
   call it; the sky views still decide in their own code and are to call it too (T364's follow-up). The debug
   route reports what ran, so the states can be measured (`GET /aircraft`: `mapShown`, `tickerRunning`, `polls`,
   `rebuilds`, `publishes`, `pushesSkipped`, `clears`; `POST /aircraft?map=1|0`).
4. **Flights, the first instance.** Data switch `aircraft` ("Flight tracking"); the map's key `mapFlights` ("Show
   flights", in Map options for now); the sky views' key `horizonFlights` (their Flights button, unchanged).
5. **Deferred, each with its trigger.** An overlay registry and a Layers sheet (the architect's model C): when a
   third overlay needs these switches (peaks on the map, the Sun, satellites, trail disagreements), through a
   proposal. Forgetting hidden data after a grace period: when a stale mark is seen after a show key comes back on.
   A slow poll while hidden and the guessed state:
   `docs/findings/2026-11-23-flights-guessed-between-polls-at-slow-refresh.md`, after the recorder's table. A power
   pause reason: when a low-power or hiking mode is decided. The final home of per-view settings: the owner's.

Measured (T364; an emulator at Rainier, zoom 9, six aircraft by replay; CPU seconds in 600 s):

| State | Feed requests | Rebuilds | Pushes | Ticker | Aircraft thread | Main | Map render | Process |
|---|---|---|---|---|---|---|---|---|
| Before, tracking off | 0 | 0 | 0 | running | 0.19 | 4.4 | 0.0 | 10.5 |
| Before, shown on the map | 0 | 605 | 605 | running | 0.86 | 11.4 | 595.1 | 620 |
| Before, a sky view over the map | 0 | 605 | 605 | running | 0.72 | 24.6 | 594.9 | 638 |
| After, tracking off | 0 | 0 | 0 | stopped | 0.00 | 5.4 | 0.0 | 12.4 |
| After, shown on the map | 0 | 604 | 604 | running | 0.92 | 10.8 | 597.1 | 621 |
| After, a sky view over the map | 0 | 598 | 0 (598 skipped) | running | 0.57 | 15.5 | 0.0 | 27.7 |
| After, hidden | 0 | 0 | 0 | running | 0.37 | 5.2 | 0.0 | 13.0 |

## Consequences

A hidden or covered map costs what tracking off costs, within noise; before, a sky view over the map cost a whole
core on the emulator. Each overlay adds a row per view, and a user can leave the data on with every view hidden:
then nothing is asked and nothing is drawn, and the only remainder is the ticker (0.37 s of CPU in ten minutes).
The rule is one more place to extend when a view is added. Not solved here: while the map draws flights, its render
thread never rests (`docs/findings/2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md`); the
figure is an emulator's, and a phone's battery cost is unmeasured. A registry would supersede item 3's one rule per
overlay.

- Prediction, check when a second overlay gains a per-view show key: it is built as a key and a case of the same
  kind of rule, with no change to how the first overlay's data is polled.
- Prediction, check by 2027-01-15: a phone measurement (`tools/android-battery.py`) shows the hidden state within
  noise of tracking off.
