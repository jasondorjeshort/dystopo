---
date: 2026-11-19
kind: feature
related: [2026-11-10-real-phone-check-as-a-one-tap-self-test.md]
---

# A GPS view with the satellites, and a check that says whether the GNSS looks broken

The owner suspects their phone's GPS is "semi broken" and the app cannot tell them: it reads only fixes (accuracy and
fix age in the extended readout, `GET /location`'s `puck`), never the satellites. Android's `GnssStatus.Callback`
gives each satellite's constellation, C/N0, carrier frequency (L1/L5) and whether it is used in the fix; nothing in
`androidApp` registers it today. A fault in the hardware looks different from a blocked sky: many satellites in view
but all weak means the antenna or the chip, while a few satellites seen is just indoors or under cover.
(The related self-test idea samples fixes for the release check; this one is about the GNSS itself. It differs by
reading the satellites and giving a verdict to the user, not a file to the developer.)

What to look for (the verdict's inputs): time to first fix (under 30 s outdoors is healthy, over 60 s is suspect);
the median C/N0 of the best four satellites (35 to 45 dB-Hz is healthy, under about 30 dB-Hz with many in view is
suspect); satellites used against satellites in view; constellations and bands seen; the scatter of fixes while
standing still; gaps between fixes; GNSS height against the barometer and the DEM (`/readout` `heights`).

- **GPS view** (a screen): a sky plot (azimuth and elevation, each satellite coloured by constellation, filled when
  used), a bar per satellite for C/N0, and a summary: in view / used, the four best C/N0, constellations and bands,
  accuracy, fix age, time to first fix of this session, GNSS, barometer and DEM heights side by side.
- **Check my GPS** on that screen: the user stands outdoors and taps it; 2 minutes, standing still; a verdict of
  healthy, weak signal (check the sky, the case, a hand over the antenna) or likely hardware fault, with the figures
  behind it. The verdict rule lives in `shared` as a pure function so it has unit tests.
- Later, not in the first pass: a passive note while recording ("GPS signal unusually weak") when many satellites
  stay weak for minutes.

Where it fits: in Settings › Navigation and tracking, a "GPS status ›" row beside the recording rows (they are the
rows a bad GPS affects), and a tap on the accuracy or fix-age entry in the glance view opens it as well. It is not in
About and advanced › Diagnostics: that screen is about the app's memory, and this is about the phone's GPS, which
the user checks in the field.

## Survey (2026-11-19, preliminary)

A read-only survey (public pages only; GPSTest's source not yet read, a follow-up is running) found: GPSTest has
tabs with a status table ("U" for used, "CF" for carrier frequency) and a sky view, and "Clear Aiding Data" with
re-inject; Locus has a sky plot alone (shape per constellation: circle GPS, square GLONASS, triangle BeiDou, diamond
Galileo; colour red to green by signal; a bar per dot; an asterisk for L1+L5) with a summary, on a tab beside the
compass; GPS Status & Toolbox has bars grouped by constellation and an assistance-data reset; SatStat has a plot
that can follow the compass; Garmin handhelds have a sky page and a bar page (hollow tracking, solid used). OsmAnd,
Organic Maps, Gaia and CalTopo have no satellite screen found. No app gives a verdict: all show raw data. Phone C/N0
tops out near 48 dB-Hz, one study's outdoor mean was about 36; first fix 1 to 3 s assisted, 30 to 90 s cold outdoors.

The layout taken from it: one scrolling portrait screen, top to bottom: the summary (used / in view, accuracy, fix
age, height, speed, course, time to first fix, the time) with "Check my GPS" under it; the sky plot full width,
north up, horizon and 30° and 60° rings, a shape per constellation, colour by C/N0 with a legend (not colour
alone), filled when used and hollow when not, the svid beside the dot, a mark for a second band; the list sorted
used first then by C/N0, with the constellation's shape, svid, a bar with its dB-Hz, elevation, azimuth, band (L1,
L5), used and ephemeris. No setting (no split found). The verdict is the project's own and its thresholds are
calibrated against the owner's phone and a known-good one before it says "hardware fault".

## Open

- Thresholds from published practice (the benchmark's technical variant, ADR 0044) rather than the figures above,
  which are the usual rough ones.
- The emulator's GNSS reports a fixed set of satellites; a debug route that injects a synthetic status is what makes
  the weak and faulty cases testable.

Plan: one pass, on the survey above (and its follow-up's drawing rules and thresholds once in). In
`androidApp`: a `GnssStatus` listener registered only while the GPS view is open (and during the check), a GPS view
screen with the layout above, reached from Settings › Navigation and tracking › "GPS status ›" and from the glance
view's accuracy and fix-age entries; "Check my GPS" with the 2-minute run and its verdict. In `shared`: the verdict
rule (`GnssHealth`, pure Kotlin) with unit tests over synthetic satellite sets (open sky, indoors, all-weak, one
constellation missing, a slow first fix). Debug routes: `GET /gnss` (the satellites, the summary, the check's
state and verdict), `POST /gnss` to inject a synthetic status for screenshots, `POST /gnss/check` to start the
check. Done when: `check` green; the screen captured on the emulator with real emulator satellites and with each
injected case, and the screenshots viewed; strings listed (ADR 0031) with the survey cited; `android-app.md`,
`android-debug-routes.md` and the routes' KDoc updated. Out of scope: the passive warning while recording, raw GNSS
measurements, the release self-test of the related idea.
