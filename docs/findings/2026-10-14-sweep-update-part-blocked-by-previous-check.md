---
date: 2026-10-14
status: open
area: tooling / sweep, in-app update
---

# The sweep's update part stops at `lower-quiet`: the previous build's own check makes the new build's check not due

## Question

The post-wave 13 sweep (`out\sweep-20261014-1427`) failed its `update` part at the `lower-quiet` step
with "the start-up check never asked the mock API" (`dailyCheckAsked` 0) after `install-previous`,
`create-data`, `install-current` and `alive` passed; the six later steps (wrong checksum, wrong
certificate, offer, install, survived, real API) never ran, so T83's in-app update flow was not
exercised by this sweep. Why did the current build's start-up check not ask the mock?

## What was tried

- `tools\android-ui.py update` as the sweep runs it: the previous release (v0.1.5-alpha, the first
  published build with the updater) is installed and used long enough for `create-data`, the current
  build is installed over it with `install -r`, then `update-api.txt` (the mock's address) is written
  and the step waits up to 60 s for the mock to be asked.
- `Updater.due()` (`androidApp/.../update/Updater.kt`): a check runs when 24 h have passed since
  `lastCheckAt` and 1 h since `lastAttemptAt`, both in the app's preferences.

## Result

Open, with a probable cause: the previous build ran past its 15 s start-up delay with the real
releases API reachable, so it did its daily check and wrote `lastCheckAt`; `install -r` keeps the
preferences, so the current build's first check was not due and the mock was never asked. Earlier
sweeps upgraded from a previous release without the updater, which wrote nothing. Not confirmed on
the device: the sweep deletes its AVD and the part keeps no logcat.

## Next

T99 (wave 14): confirm with logcat (`Updater` lines or `GET /update` in a debug build), then make the
part independent of the previous build's preferences, for example by pointing the previous build at
the mock too and treating a check recorded by another version code as not done (`due()` keyed by
version code), so every step of the part runs; put the part's screenshots on the sheet.
