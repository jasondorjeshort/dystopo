---
date: 2026-10-14
status: resolved
area: tooling / sweep, in-app update
promoted_to: docs/reference/release-realism.md
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

Confirmed in T99 (2026-10-14) on an API 35 emulator with release builds signed by the debug key (the
release key is not in task worktrees): a build of v0.1.5-alpha's updater code (versionCode 269) and a
pre-T99 build of `main` (330), run through `main`'s `android-ui.py update`, stopped at `lower-quiet`
exactly as the sweep did (`dailyCheckAsked` 0). The `DystopoUpdate` log shows the previous build's
start-up check against the real API ("up_to_date v0.1.5-alpha", 15 s after its start) and no check by
the current build until the step's manual Check for updates 60 s after its start.

Fixed in T99: `UpdateFeed.CheckRecord` keys the check times by the versionCode that recorded them
(`checkedByCode`), so a build installed over another one checks at its first start (`UpdateFeedTest`);
the part points the previous build at the mock too, from its first start, and keeps the app's update
log (`updater-log.txt`); a throwaway key replaces the debug key for the wrong-certificate step; the
part's screenshots are on the sheet. With the T99 harness the pre-T99 current build still stops at
`lower-quiet`, so the harness alone does not fix it; with the T99 build all ten steps and `alive`
pass (269 -> 330 -> 331, debug-signed).

## Next

The part from the main checkout with the release key and the published v0.1.5-alpha APK: the next
post-wave sweep.
