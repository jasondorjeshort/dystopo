# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-07, by main session (T229 merged and swept; v0.1.22-alpha published; T234 running)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (wave 30),
`docs/tasks/wave-29.md`, ADR 0056, ADR 0057, ADR 0059, `out/wave30-research/critic-wave30.md` [no-check: gitignored].

## Now

- **Wave 30 is open, nine tasks in series (ADR 0057), in the board's order.** T229 merged (4690f260), the sweep of
  d5c48a40 green. **v0.1.22-alpha is published** (tag on abe0329e, versionCode 1237; notes and reports in
  `out/wave30-reports/` [no-check: gitignored]). Now T234 (B1a, running), then T235 to T237 (ADR 0056's build), T238, T241, T239 (the layout), T240 (small, last):
  implementer (Opus), verifier (Sonnet), merge, next launch; each brief lists what merged ahead (the wave paragraph).
- **RAM is watched** (the owner's word): free RAM read before each launch and after each merge into the log entry;
  every report quotes the guard log's lowest free and peak private bytes; a guard stop or under 6 GB holds the next
  launch. Readings 17.5 to 22.6 GB free so far; the guard stopped emulators three times at 8 GB on cold-launch loops
  (about 0.5 GB per launch, expected); a launch-heavy task's boot passes `-PrivateLimitGB 12` from now on.
- The live server on 8765 is up. The gate research is untracked in `reports/` and `research_notes/` [no-check: untracked].

## Next

1. T234's report: its verifier, the merge, the log; then T235.
2. The owner's Startup freeze report from v0.1.22-alpha: the fix it names becomes the next launch, ahead of the queue.
3. After the wave: the five ideas of 2026-11-07 (launch budget, launch state machine, downloads in cells, the
   diagnostic dump), Layout C, the two ready Plans, a chore for T227's push-pack daemon and T231's lock gap, and a
   Hatchery-rooted session for the carry-backs.

## Waiting on user

- A Startup freeze report from the phone on v0.1.22-alpha (does the splash still freeze without the recorder's wait?).
- The name of the Report a problem button once it sends more than crashes (the dump idea).
- ADR 0056's build assumes "pack" stays in code, routes and keys. The top row: a Menu or not (Layout C). The
  location permission at launch or on the tap (T220). "Keep north up" (T176).
- Proposals 0054 and 0055's choices; ADR 0053's Category column; the rest of the board's Owner actions.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the cause open; a snapshot next.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
