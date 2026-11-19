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

## Open

- Thresholds from published practice (the benchmark's technical variant, ADR 0044) rather than the figures above,
  which are the usual rough ones.
- The emulator's GNSS reports a fixed set of satellites; a debug route that injects a synthetic status is what makes
  the weak and faulty cases testable.

Plan: one pass, after a short survey (`benchmark.md`, both variants: where established topo and navigation apps put
a satellite view and what it shows; published C/N0 and time-to-first-fix figures for a healthy phone). In
`androidApp`: a `GnssStatus` listener registered only while the GPS view is open (and during the check), a GPS view
screen as the body describes, reached from Settings › Navigation and tracking › "GPS status ›" and from the glance
view's accuracy and fix-age entries; "Check my GPS" with the 2-minute run and its verdict. In `shared`: the verdict
rule (`GnssHealth`, pure Kotlin) with unit tests over synthetic satellite sets (open sky, indoors, all-weak, one
constellation missing, a slow first fix). Debug routes: `GET /gnss` (the satellites, the summary, the check's
state and verdict), `POST /gnss` to inject a synthetic status for screenshots, `POST /gnss/check` to start the
check. Done when: `check` green; the screen captured on the emulator with real emulator satellites and with each
injected case, and the screenshots viewed; strings listed (ADR 0031) with the survey cited; `android-app.md`,
`android-debug-routes.md` and the routes' KDoc updated. Out of scope: the passive warning while recording, raw GNSS
measurements, the release self-test of the related idea.
