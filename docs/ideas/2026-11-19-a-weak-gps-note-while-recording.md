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

Question: build the "GPS signal unusually weak" line now with a deliberately cautious rule (many satellites in
view, all below 25 dB-Hz for five minutes, the terrain rim not explaining it, at most once a recording), or wait
until the per-point signal idea has recorded real tracks to calibrate it on? Recommended: wait. Your own phone read
strong on 2026-11-20, the verdict's 25 and 35 dB-Hz are still uncalibrated (handoff, Waiting on user), and a line
that fires under every tree is worse than none; once a few recorded tracks carry the signal, the same pass can set
the rule from them.

Answer:
