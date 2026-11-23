---
date: 2026-11-22
kind: bug
related: [2026-11-07-blue-dots-on-the-map-unexplained.md]
---

# The elevation readout turns to a dash or blinks, and nothing on the bar says why

Priority: **high** (the first number on the bar; seen by the owner on v0.1.42-alpha).

The owner sees the bar's elevation change to "–" at times and, at others, blink. Read from the code, not
yet reproduced on a device:

- **Blink:** `ReadoutEstimator.readout` (`shared/.../readout/Readout.kt:543`, `:571`) dims the elevation
  (alpha 0.45, `ReadoutStrip.kt` `DIM`) when no fix came for `gapMs` (5 s, or 3 x the profile's interval)
  and the barometer is not carrying the height. Fixes that arrive in bursts (weak GPS, indoors, the network
  and GPS providers alternating) flip it dim and full every few seconds.
- **Dash:** `Readout.kt:574` returns HIDDEN with reason "no height yet" when the filter has no height; the
  bar draws a dash for it (`StripText.kt:140`). The estimator starts afresh on a jump over 2 km at an
  impossible speed (`LiveReadout.kt:342`, counter `jumpResets`: a network fix replaced by GPS after a
  silence), on a clock that goes back, and on `reset()`; the height is gone until the next fix.
- **No explanation:** the only tap on the strip is the pin mark's "Set at ...". The elevation never uses
  the "?" (`UNKNOWN`) state the climb has, so "no height yet" and "stale" read the same as "fine".

## Open

- Which of the two (or a third) the owner saw: `GET /readout` while it shows (`fields[].state`, `reason`,
  `dimmed`, `jumpResets`) on a debug build.
- Whether a restart should clear the height at all: holding the last height dimmed for a while may be better
  than a dash.
- Whether the dim should have a hold (hysteresis) so a short gap does not blink.
