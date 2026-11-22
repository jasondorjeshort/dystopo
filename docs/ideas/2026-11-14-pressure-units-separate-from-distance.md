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

**T337 (board entry, 2026-11-22).** A pressure unit setting, millibar by default, apart from feet and metres (the owner's highest priority, 2026-11-22)
**Launch:** next in the order of this wave once T330 is merged (it owns the same settings files): the owner raised it on 2026-11-14 and again on 2026-11-22 ("I still have no (findable) option to change the units on air pressure. I want the default to be mbar."); the Plan sat in the ideas since 2026-11-14 and was never put on a wave, which this entry corrects. If T330 is long, T337 may launch first on a branch told to run `git merge origin/main` (no rebase of a pushed branch) before it reports.
**Goal:** `docs/ideas/2026-11-14-pressure-units-separate-from-distance.md`, built as its Plan says: a `PressureUnit` (mbar, hPa, inHg, mmHg) in `shared/.../render/UnitFormat.kt`, stored beside `unitPreference` in `AppSettings.kt` with a synced key, a row in Settings › Units and display under Units, default **mbar** for every install whether it is on feet or metres and whether or not it ever set Units (ADR 0022's regional seeding does not reach it; an install on feet that read inHg now reads mbar, listed in the report, ADR 0033), and every place that picks inHg or hPa from `AppSettings.units == ContourUnits.FEET` goes through the one formatter: `readout/PressureCard.kt`, `readout/StripText.kt`, `PressureText.amount`, `body` and `station` in `shared/.../readout/PressureTrend.kt`, the fall-warning row's thresholds in `settings/SettingsPages.kt` (3, 4 and 6 hPa; 0.09, 0.12, 0.18 inHg; the mmHg equivalents) and its Pressure help sentence, Trip details and the exports' text where they show pressure; stored and exported numbers stay hPa. The strip's width reserve in `StripText` ("1088" hPa or "88.88" inHg) holds for each unit. Debug route: `/settings` reports and sets the key; `/readout` reports the unit with the value.
**Owns:** `shared/.../render/UnitFormat.kt` and its test; `shared/.../readout/PressureTrend.kt` (`PressureText`) and its test; `androidApp/.../app/readout/PressureCard.kt`, `StripText.kt`, `LiveReadout.kt` (the unit passed in) and `StripTextTest.kt`; `AppSettings.kt`, `settings/SettingsPages.kt`, `SettingsLayout.kt`, `SettingsRegistryTest.kt`, `SettingsLayoutTest.kt`, `shared/.../controls/AndroidControls.kt`, `tools/demo/controls.json` (the setting's lines in each); `app/help/Help.kt` and `HelpRegistryTest.kt` (its block); `tools/android-a11y.py` and `tools/a11y-baseline.json` (the settings screen's lines only); `docs/reference/android-app.md` and `android-debug-routes.md` (the row and the route); the idea file (deleted).
**Interacts with:** T330 (the same settings files: the second to merge runs `git merge origin/main`).
**Done when:** `./gradlew check` green with tests for the conversions (hPa to inHg and mmHg, the thresholds' round numbers), the formatter in each unit, the default (a fresh install, one that saved feet units, one that saved metres: all mbar) and `SettingsLayoutTest` with the new row; the settings screen frame with the row at font 1.0 and 2.0; the readout strip and the pressure card read in each of the four units on the emulator (screenshots viewed, strip not clipped at 2.0); a real install over an older one (the release `settings` part's upgrade case, or the emulator's) keeps its other settings and reads mbar; `GET /settings` and `/readout` report the unit. New strings in the report (ADR 0031: the row's name, its four labels, its help sentence "Pressure is separate from feet and metres").
**Checks:** none.
