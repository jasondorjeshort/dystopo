---
date: 2026-11-19
kind: feature
related: [2026-11-19-gnss-signal-recorded-with-each-track-point.md]
---

# A note while recording when the GPS signal stays unusually weak

The GPS view's check (T307) gives a verdict only when the user asks for one. While a track records,
the same `GnssHealth` rule could watch the satellites and, when many are in view but all stay weak for several
minutes with the terrain horizon (the GPS view's) not explaining it, show one line: "GPS signal unusually weak",
opening the GPS view. Many weak satellites rules out indoors, where few are seen. The position estimate for poor
GPS (`tracking/TerrainSource.kt`) is the natural place it would hand over to.

## Open

- How cautious: a false alarm on every walk under trees trains the user to ignore it. Calibrate on recorded tracks
  once the related idea's per-point signal exists.

2026-11-21, the owner, by multiple choice, asked whether to build the line now with a deliberately cautious rule or to
wait until the per-point signal idea has recorded real tracks to calibrate it on (the recommendation was to wait,
since the verdict's 25 and 35 dB-Hz are uncalibrated and a line that fires under every tree is worse than none):
build it now with the cautious rule (not the recommended wait): many satellites in view, all below 25 dB-Hz for five
minutes, the terrain rim not explaining it, at most once a recording.

Plan: one pass. `shared/.../gnss/` gains a pure watch beside `GnssHealth`, fed the satellites and the terrain rim at
each status: it fires once when at least a set count of satellites stand above the rim and every one of them reads
below 25 dB-Hz for five minutes on end; a stronger signal starts the count again. Unit tests with `GnssCases`' weak
case, a strong one, and a rim that hides the sky (few above it: no line). `Tracking` holds `Gnss` while a track
records (the status callback; the recorder's own listener stays the one that fixes), sweeps the rim where the
recording stands (`Gnss.horizonAt`) and feeds the watch; when it fires, one line "GPS signal unusually weak" beside
the recording's state on the track controls (`app/tracking/RecordingStatus.kt`, `TrackControls.kt`), a tap opening
the Horizon view; no sound, no notification, once a recording. `GET /tracks` reports the watch (`weakSignal`: the
count, the seconds so far, fired) and a debug parameter shortens the five minutes. Verify: `check`; on the emulator
a recording with the weak case of `POST /gnss?case=`, the line viewed at the default text and at font scale 2.0, and a
strong case that never fires; the `tracking` battery scenario before and after in the report. The line and its help
sentence in the report (ADR 0031). It shares the hold with `2026-11-19-gnss-signal-recorded-with-each-track-point.md`:
whichever lands second reuses the first's. Out of scope: calibrating the 25 dB-Hz on recorded tracks, handing over
to the terrain position estimate (`tracking/TerrainSource.kt`).
