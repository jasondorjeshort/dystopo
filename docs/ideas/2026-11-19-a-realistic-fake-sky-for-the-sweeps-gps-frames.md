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

Plan: the `horizon` step of `tools/android-ui.py` (`horizon_step`) and the Horizon screen of `tools/android-a11y.py`
post `POST /gnss?case=ridge` (`GnssRoutes.kt`; `GnssCases.ridge` already spreads 23 satellites over five
constellations, some on L5, and puts ten behind the real terrain rim at the fix) after the view opens and before the
frames `horizon` and `horizon-scrolled`, and `POST /gnss?clear=1` before leaving (also on the error path). The step
records the case in its JSON. No new case is needed; if the ridge set looks too sparse at Paradise's rim, the `open`
case is the fallback. Verify: the two frames from one `features` run with `--feature-steps horizon` (markers spread
round the plot, some hollow behind the rim), the a11y run of that screen, and `tools/test_android_ui.py` updated for
the two calls. Out of scope: the emulator's own GNSS, the plot's drawing.
