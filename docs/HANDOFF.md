# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-16, after the Hatchery update (ADR 0035), by main session (second account, Fable 5.1)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and `docs/TASKS.md` (wave 15 closed: T108 to T113 merged; no open wave); rewrite with the `handoff` skill.

## Now

- Updated from Hatchery (`0ce6670` to `c50b0da`) on `main`, pushed (749392b): hooks, harness and
  status line are the base's under `policy.json` (ADR 0035 supersedes 0021). New for a session: the usage
  cache is `~/.claude/usage-state/<session_id>.json`, grants go in `~/.claude/usage-gate-overrides/`, a
  subagent's `git pull` on `main` is denied. Restart the session so the new hooks load, then run the harness.
- Wave 15 closed, v0.1.8-alpha published (2026-10-16 08:33 UTC, tag on 9b2f723, versionCode 396), sweep
  `out\sweep-20261016-0340` green. No agent is implementing; worktrees and task AVDs removed. Servers: live
  viewer 8765 and standalone 8800 (PIDs in `out/coordinator-pids.txt`). Leftover local branches are harmless.

## Next

1. Plan wave 16 (`wave` skill, critic pass before launch). Candidates: the wave 15 paragraph's deferred list
   (T98's coast tile, T100's course tracking, T101's tilt, T103's rows, browser-side directions op, a phone
   request log); the Status-line follow-ups of T108 to T112 (`/help/close`, `HelpHost()` in `MainActivity`,
   Storage help icons, `DirectSources`' trim, `pinnedCacheMb` cap, roundabouts and "then" cues, nav bar help
   icons); the open finding below; bushwhack (proposal first); route-to, auto-download, by region, battery.
2. Carry to Hatchery with PR #1: the critic-pass step of the `wave` skill, the settings-command harness
   group (H, HS, SL cases), `--ff-only` pulls under the guard's rule 4, elided `...` paths in the anchor check.

## Waiting on user

- ADR 0035's two questions: keep a subagent's pull on `main` denied (the coordinator pulls)? Align
  `docs/ideas/` with the base's queue grammar so the `triage` skill comes in?
- Real-phone checks of 0.1.8 (`android-firstrun.py` and `features` on the phone); Hatchery PR #1; Google
  developer registration; delete `task/T87-ci`? MapLibre's own compass (T100)? T104's steepness palette and
  legend captions; the wave 14 and 15 strings (listed in each PR); the 5 s timeout grace (T111) if a proxy needs 60.

## Open findings

- `docs/findings/2026-10-16-cold-view-gap-on-a-quiet-device.md` (wave 16 Android sweep)
