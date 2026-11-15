---
date: 2026-11-15
kind: feature
related: [2026-11-02-physical-limits-from-your-own-tracks.md, 2026-11-15-your-own-metric-sliders.md]
---

# The pace learns from your own tracks: hike and drive

The app records tracks and already fits a pace to them on the desktop (devtool `PaceFitCommand.kt`); a hike card
shows past effort. The drive's turn seconds and class speeds (`routing/TurnCost.kt`, `routing/Router.kt`) are first
values nobody measured. Wanted, on the phone and private to it: the hike model's parameters and the drive's speeds
and turn seconds fitted to what you actually did, shown as "based on your last N trips" with the fitted figures,
and used for planned times. The owner, 2026-11-15: having the pace learn from you would be good.

## Open

- Hike first (the owner's interest; the fit exists) or drive.
- Whether the fitted values sync between your phones (they describe you, not the phone).
- A floor on how much data before it replaces the default, and a way back to the default.
