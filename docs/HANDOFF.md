# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-02, at the wave 27 launch, by main session (T208 to T215 launched on the owner's go)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 27
(T208 to T216; the critic's review and the four surveys under `out/wave27-research/`, gitignored), ADR 0053, ADR 0051.

## Now

- **Triage of 2026-11-02 done:** the 25 untriaged ideas read against the code by four read-only agents; 5 got a
  `Plan:` (best start time, glance view, phone-style toggle, phone settings registry, plus ADR 0053's existing
  one), 20 a `Question:` with a blank `Answer:`. The ADR 0048 scan prediction fired and failed: the wave cost
  figure is now 1.5 points a task and 3 a wave (finding of 2026-11-02; the `wave` skill's close step revises in
  either direction). The hook tests pass (two SKIPs are the base's own, no chore).
- **Wave 27 launched 2026-11-02 on the owner's go** ("Go with wave 27"): T208 to T213 Opus-class, T214 and T215
  Fable (proposals); T216 waits for T211's merge. Every brief points at `out/wave27-research/common-brief.md`
  (gitignored). Emulators: T209 5570/8692, T210 5572/8693, T212 5574/8694, T213 5576/8695; two slots. Meter at
  launch 41 on the 7-day, 11 on the 5-hour; allowance 55 at the revised figure against the hold of 97.
- Running: the eight implementers in worktrees under `dystopo-wt/`; the live viewer on 8765 (`origin/task/T138` is housekeeping).

## Next

1. As each implementer reports: a Sonnet verifier (`brief` skill, `verifier.md`), at most two at once; merge on
   MERGE by the `wave` skill; T209 before T212; T216 launched when T211 has merged. T214 and T215 return
   proposals: the coordinator reads them, no verifier.
2. Owner answers to the twenty `Question:` blocks as they come; the ones that unblock the most: estimated points
   in exports, trip-report v2's storage, physical limits (pressure recorded with tracks).
3. A Hatchery-rooted session: the pending carry-backs plus T190's two, T201's (`SubagentStop` fields), the
   sentinel rule.

## Waiting on user

- T203's pass 2 needs a yes on its `ACTIVITY_RECOGNITION` prompt before it is planned.
- The `Answer:` lines of the twenty Questions in `docs/ideas/` (triage 2026-11-02); ADR 0051's two answers.
- Install v0.1.18-alpha and the phone checks on the board; the home site's six steps; the housekeeping row.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
