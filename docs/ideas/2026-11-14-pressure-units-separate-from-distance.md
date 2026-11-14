---
date: 2026-11-14
kind: feature
related: [2026-11-01-trip-report-v2-sun-pressure-and-decisions.md]
---

# A pressure unit setting, millibar by default, apart from feet and metres

The owner, 2026-11-14, on v0.1.29-alpha: "I need units option for pressure. Mbar should be default but this is
separate from feet vs meters." Today pressure is not its own unit: the readout, the trend card and the fall-warning row show inHg when Units is
feet and hPa when it is metres (`readout/PressureCard.kt:230` and `StripText.kt`, `settings/SettingsPages.kt:744,788`,
`PressureText.amount` in `shared/.../readout/PressureTrend.kt`, all keyed on `AppSettings.units == ContourUnits.FEET`).
The owner's phone is on feet, so it reads "29.14 inHg" (screenshot, 2026-11-14), where they want millibar. So the
pressure unit becomes its own preference and its default is mbar whatever the feet-or-metres choice (ADR 0022's
regional seeding no longer reaches pressure).

Plan: a `PressureUnit` enum in `shared/.../render/UnitFormat.kt` (mbar, hPa, inHg, mmHg; mbar and hPa are the same
number, so mbar is the default and the only label difference is the word), stored beside `unitPreference` in
`AppSettings.kt` with a synced key, and a row in Settings › Units and display under Units (help text: "Pressure is
separate from feet and metres"). Every `inHg: Boolean` parameter of `PressureText` and the strip, card and settings text becomes the `PressureUnit`
(the readout, the trend and its fall warning's threshold row, Trip details, GPX/track exports' text, the report scrubber's keyed values)
goes through one formatter; the stored and exported numbers stay hPa. Not region-seeded. Debug route: the settings
layout and `/readout` report the unit; unit tests for the conversions and the formatter; `StyleParityTest` is
untouched. Done when: `check` green, the readout's screenshot in each unit, `SettingsLayoutTest` updated.
