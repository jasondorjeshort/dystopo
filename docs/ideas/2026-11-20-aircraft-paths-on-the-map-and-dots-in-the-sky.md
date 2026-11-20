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

2026-11-20: the owner, "displaying airplane paths is what we'll focus on next" (the next release). Two read-only
research passes are under way: the sources (positions, origin and destination by call sign, aircraft details,
offline airport data; terms and licences) and how established apps show aircraft on a map and in the sky, origin
and destination above all. Seen from a seat, the same layer draws on the Horizon view flipped to look down
(`2026-11-10-window-seat-mode-with-the-fix-moved-up-to-now.md`).

2026-11-20: the research is `docs/findings/2026-11-20-aircraft-layer-sources-and-display.md`.

Question: four choices are the owner's before the build (a new data flow, `CLAUDE.md` Decisions): (1) the phone sends a
rounded centre (about 0.1°) to adsb.lol, directly, while the layer is on, off by default; (2) origin and destination
come from the CC0 route table shipped with the app (refreshed by the home site), so no call sign leaves the phone;
(3) the source credit "ADSB.lol contributors (ODbL)" in the attribution line, which ODbL requires; (4) adsb.lol's
archived API repository and 500 requests a day are accepted as the risk for the first release, with emails to two
other community feeds asking whether a free public app may use them. Recommended yes to all four: a direct call
keeps each phone on its own quota, the shipped table keeps offline-first and leaks nothing, the credit is a licence
duty rather than a product mention, and nothing better is free. On a yes, the build is one Opus pass (sized 3.0):
a source interface with the adsb.lol client and a replay source, the route table and its plausibility check in
`shared`, the map layer (silhouette, altitude-coloured trail, dashed remaining route on tap), dots on the Horizon
view (hollow when behind terrain or stale), the card, the settings (layer off, refresh 20 s with the day's remaining
quota shown), `sources.json` entries, and `POST /aircraft/replay` plus `GET /aircraft` debug routes.

Answer: (the owner, 2026-11-20) "I accept all your recommendations on the plane plan. Let's write it up - we'll
implement next session." Planned as T311 in `docs/TASKS.md` (wave 41).
