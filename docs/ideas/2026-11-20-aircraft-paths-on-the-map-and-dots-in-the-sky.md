---
date: 2026-11-20
kind: feature
related: [2026-11-19-the-horizon-view-beside-the-map-view.md, 2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md]
---

# Aircraft: their paths on the map, and dots on the Horizon and Panorama views

The owner, 2026-11-20: airplane paths, pulled from public sources (the owner is not sure there are any). Shown on all
three views: **paths on the map**, **dots on the Horizon view** (and so the Panorama view, the same layer at the
same bearings and elevation angles; see the layer lists of the two related ideas).

What it would be: an Aircraft layer. On the map, each aircraft's recent track as a line with the aircraft at its
head. In the sky views, each aircraft is a dot at its bearing and elevation angle from the phone (computed from its
position, altitude and the phone's, with the terrain horizon deciding whether it is above the ridge), with a short
label (call sign, altitude) and a short trail. It is a toggle beside satellites, peaks and the sun.

Sources, unchecked (this is a guess, to research before anything is chosen): live aircraft positions are broadcast
openly (ADS-B), and several services and community networks publish them; some are free for non-commercial or
limited use, some need a key, some forbid redistribution. Needed from a research pass (`benchmark.md`, technical
variant, ADR 0044): which sources exist, their terms and rate limits, whether any allows an app to use them, the
data licence for `sources.json` (a registry entry first, `CLAUDE.md` Rules), and whether a source offers a bounding-box
query so the phone asks only for its view.

Things this raises, for the owner:
- **Offline-first.** Live positions need a connection; offline the layer has nothing to show, and the app must say so
  rather than draw an empty sky. It cannot be a pack. A static part could be offline: known air routes or the
  airports from the map data, and a forecast of a pass from a schedule is not available offline.
- **A new data flow.** The phone would send its area to a service to ask what flies there. By the project's rules a new
  data flow is flagged, not guessed (`CLAUDE.md`, Decisions): coarsen the query to a box, say where it goes in the
  help text, and make it off by default.
- **Cost and battery.** A poll every few seconds over mobile data; a user setting for the interval, steered by the
  visible limits (no Wi-Fi gate, ADR 0023).
- **Agent-testable.** A debug route that feeds recorded aircraft positions (a replay file, like `NavReplay`), so a
  test needs no network and no live traffic.

Not started; recorded for later.
