---
date: 2026-11-18
status: open
area: tooling / sweep, in-app update
---

# GitHub's unauthenticated API quota for this network is spent, and it reddened two sweep parts

## Question

Why were `diagnostics` (exit 1 in 86 s) and `update` (exit 1 in 595 s) red in the 2026-11-18 sweep
(`out/sweep-20261118-0901`, at c8d29ca7), and is either a fault of the app?

## What was tried

Read both parts' reports and logs: `diagnostics.json`, `diagnostics.err.log`, `ui/diagnostics/03-row-failed.png`,
`update.json`, `ui/update/updater-log.txt`. Read GitHub's quota for the PC's public address twice, 5 minutes apart,
with no sweep running (`GET https://api.github.com/rate_limit`, which does not spend the quota).

## Result

- **Both failures are GitHub's rate limit.** The release build's update check (at first start, and on Check for
  updates) is unauthenticated: 60 requests an hour per public address, shared with every device behind it (the
  emulator reaches GitHub through the PC's NAT). `updater-log.txt:24`: `update check failed (attempt 1): other 403:
  HTTP 403 from api.github.com (rate limited; try later)`. The quota read `used 60, remaining 0` both times; its
  reset time moved from 18:47 to 19:00 UTC between the two reads, which a single address's hourly window does not
  do, so the network's outgoing address is likely shared or rotating (a carrier-grade NAT) and the quota spent by
  others: on this network the real check should be expected to fail most hours.
- **diagnostics**: the failed check leaves the Updates row with a "Technical details" fold of its own, open on
  "Hide" (the frame shows "Kind: other, HTTP 403"). `read_diagnostics` tapped the first "Technical details" on the
  page, closing the Updates fold, and then took its own fold as opened, so "Max heap" never showed and the row step
  failed after 40 s. A script bug: fixed in T305 (`diagnostics_fold` picks the heading above the Diagnostics row's own
  line).
- **update**: every mock step passed (offer, install, survived); only `real-api`, the one step that asks GitHub
  itself, failed. The app said "The update check did not work. Try again later." (UpdateFailure's OTHER sentence,
  T301), which the script's `UPDATE_LINE` did not know, so it waited its whole 120 s. Fixed in T305: the failure
  sentences end the wait, and the step is a **skip with its reason** (`skipped` in the step, `skippedSteps` in
  `summary.json` and on the sheet's row) when the quota read before the check is 0 or the app's log names the rate
  limit; any other failure stays red.
- **The app**: a 403 "rate limited" is classed `other` (Updater.kt `UpdateFailure.of`), not retried, and the row
  reads "The update check did not work. Try again later." That is true and harmless, but a phone on such a network
  would see it most days.

## Next

- An app choice, not a tooling one: whether a rate-limited check gets its own sentence and kind (say "GitHub is
  busy; the app will try again tomorrow") and a later retry, or whether the check moves off the API's
  unauthenticated quota (the releases' Atom feed or the `latest` redirect on github.com, which are believed not to count against it:
  unverified here, a question for the research agent).
  An idea for triage.
- On this network the sweep's `real-api` step will mostly read as a skip; a sweep on another network (or the
  nightly runner, which is authenticated only for `gh`) still exercises it.
