# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-07, by main session (ADR 0056's four merged with T229 and T242; v0.1.23-alpha published; T238 running)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (wave 30),
`docs/tasks/wave-29.md`, ADR 0056, ADR 0057, ADR 0059, `out/wave30-research/critic-wave30.md` [no-check: gitignored].

## Now

- **Wave 30 is open, ten tasks in series (ADR 0057), in the board's order.** Merged: T229, T234, T235, T236, T237 and T242 (added
  2026-11-07 after the owner's phone froze over a 40 GB cache: the index off the main thread; the sweep of 08035dae
  green). **v0.1.23-alpha is published** (tag on f3f6af8d, versionCode 1258; notes and reports in
  `out/wave30-reports/` [no-check: gitignored]). Now T238 (running), then T241, T239 (the layout),
  T240 (small, last): implementer (Opus), verifier (Sonnet), merge, next launch; each brief lists what merged ahead.
- **RAM is watched** (the owner's word): free RAM read before each launch and after each merge into the log entry;
  every report quotes the guard log's lowest free and peak private bytes; a guard stop or under 6 GB holds the next
  launch. Readings 13 to 23 GB free so far; a launch-heavy task's boot passes `-PrivateLimitGB 12` (12 boots, no stop since).

## Next

1. T238's report: its verifier, the merge, the log; then T241 (its brief carries T229's chip row and T236's card).
2. After the wave: the ideas of 2026-11-07 (the launch budget and state machine, downloads in cells, the diagnostic
   dump, the flight report's nine), T242's snapshot, Layout C, the two ready Plans, a chore for T227's push-pack
   daemon and T231's lock gap, and a Hatchery-rooted session for the carry-backs.

## Waiting on user

- On v0.1.23-alpha: the large download again, and a Startup freeze report if the start is still slow over it; a
  freeze report from the flight, if Report a problem lists one; the L3 question (one row visible at the largest
  font in landscape, T235's note).
- The name of the Report a problem button once it sends more than crashes (the dump idea).
- ADR 0056's build assumes "pack" stays in code, routes and keys. The top row: a Menu or not (Layout C). The
  location permission at launch or on the tap (T220). "Keep north up" (T176).
- Proposals 0054 and 0055's choices; ADR 0053's Category column; the rest of the board's Owner actions.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the cause open; a snapshot next.
  `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
