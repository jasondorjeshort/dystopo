# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-21, at commit e4df82f2, by main session
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 17 (ports, AVDs, ownership, sequencing in its paragraph); `.claude/hooks/policy.json`'s `$comment` (the identity rule); the `handoff` skill.

## Now

- Wave 17 launched 2026-10-21 (owner's account, meter 12% / 39% at launch): T121 (base update to
  Hatchery f7f41af, merges first), T122 (font scale 2.0, nav bar help icons, fit paddings), T123
  (online stall guard, background debug routes, phone request log), T124 (Android sweep 10), T125
  (server and tooling sweep 10); implementers on `task/T12n`, no PRs yet. The live viewer runs on
  8765 (PID in `out/coordinator-pids.txt`); 8800 is down and nothing in the wave needs it.
- History is clean: the 19 placeholder commits and `v0.1.9-alpha` rewritten under the owner's identity and force-pushed (tag on `d8bd466a`, the release intact); T120 merged and logged; the ten triage commits on `main`. Two older commits authored as Claude remain; the owner's call.
- In Hatchery (`../hatchery-wt/`): `T120` holds T120's carry-back (ADR 0020), uncommitted, waiting for
  the owner's terminal commit and push (only Hatchery's `runner`/`pusher` may; neither exists in this
  session); `T121` holds proposal 0021 "guardrails bound damage, not who may act" (the owner agreed
  to the whole loop: proposal, critic, ADR 0021), an architect agent in progress.

## Next

1. As each PR opens: a Sonnet verifier (T121 first; its push may need the owner's terminal if the
   main checkout's scan refuses a scan-contract change), merge by the `wave` skill's sequence.
2. Hatchery: after the owner commits and pushes `T120`'s branch, open its PR; when the architect
   reports, read ADR 0021, put any unresolved objection to the owner, then commit the same way.
3. After ADR 0021 lands: a second base update (board › Later) and a rules pass on `CLAUDE.md`
   (light lane, the guard's reversible line, identity source). Then the sweep and a release (ADR 0024).

## Waiting on user

- The three Hatchery commands for `hatchery-wt/T120` (in chat); `git branch -D triage-saved` and the
  `refs/original/*` cleanup (the guard refuses both from agents); the eight `Answer:` lines in
  `docs/ideas/`; whether the two Claude-authored commits get rewritten.
- The repository is private: no phone's updater sees v0.1.8/v0.1.9-alpha and Actions does not run.
- Real-phone checks of v0.1.9-alpha; Google developer registration; MapLibre's compass (T100); T104's palette and captions; the wave 14 to 16 strings and help texts (ADR 0031); the 5 s timeout grace.

## Open findings

- none
