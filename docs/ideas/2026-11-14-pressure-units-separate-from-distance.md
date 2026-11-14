---
date: 2026-11-14
kind: feature
related: [2026-11-01-trip-report-v2-sun-pressure-and-decisions.md]
---

# A pressure unit setting, millibar by default, apart from feet and metres

The owner, 2026-11-14, on v0.1.29-alpha: "I need units option for pressure. Mbar should be default but this is
separate from feet vs meters." Pressure shows in the live readout and the trend (`androidApp/.../app/readout/`,
`PressureWatch`, the fall-warning row in `AppSettings.kt`) in hPa only, and the Units row
(`UnitPreference`, `shared/.../render/UnitFormat.kt`) is one choice, feet or metres, seeded by region (ADR 0022).
A US phone seeded to feet would otherwise be tempting to give inHg; the owner wants mbar there too, so the pressure
unit is its own preference and its default is not region-seeded.

Plan: a `PressureUnit` enum in `shared/.../render/UnitFormat.kt` (mbar, hPa, inHg, mmHg; mbar and hPa are the same
number, so mbar is the default and the only label difference is the word), stored beside `unitPreference` in
`AppSettings.kt` with a synced key, and a row in Settings › Units and display under Units (help text: "Pressure is
separate from feet and metres"). Every place the app prints a pressure or a pressure change (the readout, the trend
and its fall warning's threshold row, Trip details, GPX/track exports' text, the report scrubber's keyed values)
goes through one formatter; the stored and exported numbers stay hPa. Not region-seeded. Debug route: the settings
layout and `/readout` report the unit; unit tests for the conversions and the formatter; `StyleParityTest` is
untouched. Done when: `check` green, the readout's screenshot in each unit, `SettingsLayoutTest` updated.
