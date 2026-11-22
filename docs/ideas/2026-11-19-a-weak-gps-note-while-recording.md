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

Built 2026-11-22 (T333): the watch `WeakSignalWatch` in `shared/.../gnss/` with the cautious rule (at least 8
satellites above the terrain rim, every one under 25 dB-Hz, five minutes on end, once a recording); the recording holds the
GNSS status only, sweeps the rim only while that many weak satellites stand above the flat horizon, and shows the line on
the recording panel, a tap opening the Horizon view (`docs/reference/android-app.md` › A weak GPS signal while recording).
Left: checking the 25 dB-Hz and the count against recorded tracks once the related idea's per-point signal exists; the
hand-over to the terrain position estimate (`tracking/TerrainSource.kt`).
