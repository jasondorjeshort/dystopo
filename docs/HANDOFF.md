# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-02, after the triage and the wave 27 plan, by main session (planned, critic taken, not launched)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 27
(T208 to T216; the critic's review and the four surveys under `out/wave27-research/`, gitignored), ADR 0053, ADR 0051.

## Now

- **Triage of 2026-11-02 done:** the 25 untriaged ideas read against the code by four read-only agents; 5 got a
  `Plan:` (best start time, glance view, phone-style toggle, phone settings registry, plus ADR 0053's existing
  one), 20 a `Question:` with a blank `Answer:`. The ADR 0048 scan prediction fired and failed: the wave cost
  figure is now 1.5 points a task and 3 a wave (finding of 2026-11-02; the `wave` skill's close step revises in
  either direction). The hook tests pass (two SKIPs are the base's own, no chore).
- **Wave 27 is planned and not launched.** Nine tasks on the board: T208 (ADR 0053 step 1), T209 (best start
  time), T210 (glance view), T211 and T216 (ADR 0051 stage 1a in two halves; T216 after T211 merges), T212
  (the cards at large font scales), T213 (trail divergence marker), T214 and T215 (proposals: bushwhacking,
  the dwell box). Critic (Fable): 1 blocking, 7 serious, 9 minor, all taken; "launch after the edits".
  Allowance: 41 on the 7-day at planning, 55 at the revised figure (69 at the old) against the hold of 97.
- Nothing running of ours but the live viewer on 8765; no worktree, task branch or emulator (`origin/task/T138` is housekeeping).

## Next

1. On the owner's go: launch T208 to T215 with the `brief` skill (Opus-class implementers; T214 and T215 are
   proposal writers, most capable model), each with its own worktree, ports and AVD name; the emulator rule of
   the wave paragraph (device check last, two slots, T216 asks for both). Launch T216 when T211 has merged.
2. Owner answers to the twenty `Question:` blocks as they come; the ones that unblock the most: estimated points
   in exports, trip-report v2's storage, physical limits (pressure recorded with tracks).
3. A Hatchery-rooted session: the pending carry-backs plus T190's two, T201's (`SubagentStop` fields), the
   sentinel rule.

## Waiting on user

- The go for wave 27; T203's pass 2 needs a yes on its `ACTIVITY_RECOGNITION` prompt before it is planned.
- The `Answer:` lines of the twenty Questions in `docs/ideas/` (triage 2026-11-02); ADR 0051's two answers.
- Install v0.1.18-alpha and the phone checks on the board; the home site's six steps; the housekeeping row.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
