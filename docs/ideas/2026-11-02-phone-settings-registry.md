---
date: 2026-11-02
kind: feature
---

# One registry for the phone's own settings, so a new setting is not ten edits

Re-filed from proposal 0053's critic round (T206, `docs/design/proposals/0053-critic-review.md`, objection 1): a
phone setting lives in ten places (the `AppSettings` key and property, `SettingsRoutes.KEYS`, a field of
`SettingsRoutes.Parsed.Ok`, a branch of `SettingsRoutes.apply`, a constant plus a `current()` line plus a `publish()`
line in `sync/SettingsSync.kt`, a `SettingsRow`, a case in `SettingsPages`, a `HelpText`) and two test lists. A
registry the route keys and sync iterate would remove three of them; making `Parsed.Ok` a map would remove two more.
Proposal 0053 keeps its registry to what two clients share, so this is the phone-internal clean-up on its own: no
second client gains from it, and the phone's settings are not uniform values (`turnDeadline`, the per-activity
readout and recording composites), so a registry here must not grow into a schema language. How it differs from the
related idea: that one is the cross-client definition; this one is only the phone's maintenance cost.

Plan: one pass, androidApp only, launched only after ADR 0053's build step 1 has merged (it edits the same
files). Confirmed 2026-11-02: `turnMarginMin` shows the ten places (`AppSettings.kt` key, property and
setter; `SettingsRoutes.kt` `Parsed.Ok` field, parse, `KEYS` and `apply`; `sync/SettingsSync.kt` constant,
`current()` and `publish()`; `settings/SettingsLayout.kt`'s `SettingsRow`; `SettingsPages.kt`; `help/
Help.kt`), with `ReadoutSettings.KEYS` and `RecordingProfiles.KEYS` already partial registries. Build one
list of plain settings (key, type, default, sync key) that `SettingsRoutes.KEYS`, `SettingsSync.current()`
and `publish()` iterate, and make `Parsed.Ok` a map keyed by that list; the composites (`turnDeadline`,
readout, recording) keep their own parsers and are listed by key only. Every stored and synced `app.*` key
stays byte-identical (a test diffs `current()` before and after on a seeded store); `SettingsRoutesTest`
and `HelpRegistryTest` stay green; no schema language, no generated screens, no user-visible change.
