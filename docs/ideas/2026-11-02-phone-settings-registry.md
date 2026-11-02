---
date: 2026-11-02
kind: feature
related: [2026-11-01-one-definition-of-controls-and-settings-across-platforms.md]
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
