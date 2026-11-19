---
date: 2026-11-19
kind: chore
---

# A realistic fake sky for the sweep's GPS frames

The emulator's own GNSS reports six GPS satellites all at elevation 0° and azimuth 0°, C/N0 30 (T307's runs), so
the sweep's GPS view frames (`tools/android-ui.py` `features`, step `gps`; the a11y screen `gps`) show six markers
stacked on the north mark, all "behind terrain". The before-and-after sheet then cannot show a real change to the
plot. Before capturing, the step injects a fixed, realistic set through `POST /gnss` (`GnssRoutes.kt`): about 25
satellites over four constellations, spread in azimuth and elevation, some on L5, some behind Rainier's terrain at
the sweep's position, and clears it after (`POST /gnss?clear=1`). The set lives in one file the step and the tests
share (`shared/.../gnss/GnssCases.kt` already holds synthetic cases).
