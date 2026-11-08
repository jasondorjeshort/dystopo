---
date: 2026-11-07
status: open
area: android / updater
---

# The update check shows GitHub's raw "API 504" where a sentence belongs

## Question

The owner's phone (v0.1.23-alpha, 2026-11-07) showed "Update check failed: API 504 from GitHub" on the update row.
What does it mean, and is anything wrong on the phone?

## What was tried

Read `androidApp/.../app/update/Updater.kt` (the check's failure path, `reason(e)` into the row's text) and the
meaning of the status: a 504 is the gateway timeout of GitHub's own front end, answered when its backend did not
reply in time to the releases API request the app makes.

## Result

Nothing on the phone is wrong: the app asked for the latest release, GitHub's edge gave up waiting, and the app
reported exactly what it got. The next check (the daily one, or a tap on Check for updates) runs the same request
again, and nothing was downloaded or changed. The row's wording is the transport's error passed through verbatim,
which T223 already left as a follow-up ("the raw update-error text"): a person reads "API 504" as the app's fault.

## Next

- The row says what happened in words and what to do: "GitHub did not answer. Try again later." for a 5xx or a
  timeout, "No connection." for a network error, "Could not read the release list." for a parse error, with the
  code kept in the Technical details fold and the log; the strings listed (ADR 0031).
- A 5xx or a timeout retries once after a minute before the row says anything, since GitHub's gateway timeouts
  are usually seconds long.
