---
date: 2026-11-19
kind: feature
related: [2026-11-19-a-gps-view-and-a-gnss-health-check.md, 2026-11-19-gnss-signal-recorded-with-each-track-point.md]
---

# A note while recording when the GPS signal stays unusually weak

The GPS view's check (the related idea) gives a verdict only when the user asks for one. While a track records,
the same `GnssHealth` rule could watch the satellites and, when many are in view but all stay weak for several
minutes with the terrain horizon (the GPS view's) not explaining it, show one line: "GPS signal unusually weak",
opening the GPS view. Many weak satellites rules out indoors, where few are seen. The position estimate for poor
GPS (`tracking/TerrainSource.kt`) is the natural place it would hand over to.

## Open

- How cautious: a false alarm on every walk under trees trains the user to ignore it. Calibrate on recorded tracks
  once the related idea's per-point signal exists.
