# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-25, at commit e7ca974 plus this edit, by main session (wave 20 first half in flight)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 20.

## Now

- Wave 20 first half launched 2026-10-25 on the second PC (`machines.md`): Opus implementers in
  `~/Documents/src/dystopo-wt/<task>`, forked at 2373338, ports as the wave paragraph assigns, AVDs
  under `~/Android/avd` (not E:). Merged: T141 (87ed16c), T147 (786cc26; its worktree folder was
  held and awaits the next cleanup chore). With verifiers: T140, T144, T143. Building: T146.
- RAM ran short (owner, 2026-10-25): emulators at `-Memory 3072`, at most three at once, Gradle
  `--no-daemon` (`machines.md`, the second PC's RAM row); every brief from now on carries it.
- The launch chore is done: distributions installed, the Rainier packs and sprite in `out/`, the
  extract in `cache/extracts/`, the live viewer on 8765 (`out/coordinator-pids.txt`), the marker
  `out/packs-ready.txt`. The dry run's outdoors pack was bad; `tilebuild.bat` cannot run here (`machines.md`).
- Meters at launch: 5-hour 24%, 7-day 61% (holds 50 and 100).

## Next

1. Verifiers (Sonnet, `verifier.md`, ports 8790 to 8799, consoles 5660 to 5670) as reports arrive;
   merges by the `wave` skill's sequence; cleanup chores after each merge.
2. Then T142 and T145 (host 8762 console 5656; host 8764 console 5658), briefs from `implementer.md`
   with `region-picker.md` and `crash-reports.md` attached and the first half's Status lines.
3. Post-wave sweep, release, handoff. Then a Hatchery-rooted session (carry-backs, the ownership
   idea, ADR 0021's follow-ups, the second base update); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- `git push origin --delete task/T138` and `git branch -D carry-back/ownership-project-list`; the
  `RELEASE_*` secrets; the wave 18 and 19 strings (ADR 0031); ADR 0042's confirmations, ADR 0040's
  positions; a real-phone run of `tools/android-battery.py`, real-phone heap numbers and the
  real-phone checks of v0.1.12-alpha; whether session links may stay in commit trailers.
- Carried: the S Pen question; the T122 folder, `triage-saved` and `refs/original/*`; the two older
  Claude-authored commits; T122's and the wave 14 to 17 strings; Play registration; T100, T104.

## Open findings

none
