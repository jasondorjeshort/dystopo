---
date: 2026-10-13
kind: feature
status: new
pipeline: none
---

# Battery: a doctrine of highest efficiency, and a low-battery mode

Battery use seems fine today. The doctrine is to keep it as low as possible, especially for long
offline days and week-long hikes or road trips. Method: measure and iterate in a test mode. Plus a
low-battery mode, switched on automatically when offline or under a battery threshold, or by hand.

Today: `docs/PLANNING.md` (design goals) says the app stays small in CPU, memory and battery with
enforced budgets, including the battery cost of GPS tracking; the release sweep checks doze
behaviour. There is no battery measurement harness in the sweep and no low-battery mode.

## Open

- A measurement mode: a scripted scenario (tracking with the screen off, navigation with the screen
  on, idle map) on a real device, reading battery stats (`dumpsys batterystats`, per-uid) over
  hours; emulators cannot give real numbers.
- What the low-battery mode changes: GPS interval and accuracy when stationary, no ahead-of-route
  prefetch or auto-download, cheaper rendering (no multi-direction hillshade, fewer labels, a dark
  style on OLED), the screen-on lock off, network polling stopped.
- Trigger: below a battery percentage, power-saver on, and/or offline; shown plainly, and the user
  can override.
- Related to the voice directions and auto-download ideas, which must respect it.
