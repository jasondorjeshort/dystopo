# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-21, at commit e4df82f2, by main session
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 17 (its paragraph has the ports, AVDs, ownership and sequencing); the identity rule in `.claude/hooks/policy.json` (`$comment`, privacy); the `handoff` skill.

## Now

- Wave 17 launched 2026-10-21 (owner's account, meter 12% / 39% at launch): T121 (base update to
  Hatchery f7f41af, merges first), T122 (font scale 2.0, nav bar help icons, fit paddings), T123
  (online stall guard, background debug routes, phone request log), T124 (Android sweep 10), T125
  (server and tooling sweep 10); implementers on `task/T12n`, no PRs yet. The live viewer on 8765 is
  being restarted by a chore; the standalone server on 8800 is down and nothing in the wave needs it.
- History is clean: the 19 placeholder commits and `v0.1.9-alpha` were rewritten under the owner's
  identity and force-pushed (`a99411e5` at the time; tag on `d8bd466a`, the release intact); T120
  (the scan accepts the attribution trailer and GitHub's web committer) is merged and logged; the
  ten triage commits are on `main`. Two commits authored as Claude remain (older); the owner's call.
- In Hatchery (`../hatchery-wt/`): `T120` holds T120's carry-back (ADR 0020), uncommitted, waiting
  for the owner's terminal commit and push (only Hatchery's `runner`/`pusher` may, and they do not
  exist in this session); `T121` holds proposal 0021 "guardrails bound damage, not who may act"
  (the owner agreed to the whole loop; proposal, critic, ADR 0021), an architect agent in progress.

## Next

1. As each PR opens: a Sonnet verifier (T121 first; its push may need the owner's terminal if the
   main checkout's scan refuses a scan-contract change), merge by the `wave` skill's sequence.
2. Hatchery: after the owner commits and pushes `T120`'s branch, open its PR; when the architect
   reports, read ADR 0021, put any unresolved objection to the owner, then commit the same way.
3. After ADR 0021 lands in Hatchery: a second base update (board › Later) and a rules pass on
   `CLAUDE.md` (the light lane, the guard's reversible/irreversible line, the identity source).
4. Post-wave sweep, then release per ADR 0024 (Actions still off while the repository is private).

## Waiting on user

- The three Hatchery commands for `hatchery-wt/T120` (in chat); `git branch -D triage-saved` and
  the `refs/original/*` cleanup in the main checkout (the guard refuses both from agents).
- The eight `Answer:` lines in `docs/ideas/`; whether the two Claude-authored commits get rewritten.
- The repository is private: no phone's updater sees v0.1.8/v0.1.9-alpha and Actions does not run.
- Real-phone checks of v0.1.9-alpha; Google developer registration; MapLibre's compass (T100); T104's palette and captions; the wave 14 to 16 strings and help texts (ADR 0031); the 5 s timeout grace.

## Open findings

- none
