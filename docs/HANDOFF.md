# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-09, by main session (wave 31 planned, critic taken, first five agents launched)
Resume: this file, `docs/reference/machines.md` (the owner's PC, 128 GB), the `wave` skill, `docs/TASKS.md` › Wave 31
(the lane order and the ownership paragraph), `out/wave31-research/` [no-check: gitignored] (five surveys, the critic).

## Now

- **Wave 31 is open** (plan at e184b663): seventeen tasks, T244 to T260, two lanes of Gradle work (the owner's
  widening of ADR 0057 for this PC, 2026-11-09) plus three alongside. Launched 2026-11-09: T246 (lane A, console
  5570, host 8694), T248 (lane B, console 5572, host 8696), T244 and T245 (proposals 0060 and 0061, no Gradle),
  T259 (the sheet, Python only). No report yet; no branch merged.
- Lane A order: T246, T247, T250 (after T248 merged), T252, T253 (after T251), T260 (after T251), T256 (after
  T255), T258. Lane B: T248, T249, T251, T254, T255, T257 (after T250). A release after T253 and T255 merge; the
  lane that gets there first idles for the sweep. Within a lane: implementer, verifier (ports 5574/8698 for A,
  5576/8700 for B), merge, then the next implementer.
- The live server on 8765 is up (PID in `out/coordinator-pids.txt` [no-check: gitignored]). Meter at planning: 7-day 2
  (resets 2026-11-13), 5-hour 2. Free RAM 98 GB of 128, no emulator; read it before each launch, after each merge.

## Next

1. As T244 and T245 report `under review`: brief a critic each (the `proposal` skill's `brief-critic.md`, most
   capable model), then message the implementer to revise; the coordinator decides; the ADRs are wave 32's.
2. As T246 or T248 reports: a Sonnet-class verifier (`brief` skill, `verifier.md`; once T246 has merged, its
   script and `docs/reference/verifying-a-task.md` go in every verifier brief); merge by the `wave` skill's
   sequence; then the lane's next implementer (T247 in A, T249 in B), whose brief lists what merged.
3. At T253 and T255 merged: the `sweep` skill, then the `release` skill (ADR 0024). At the wave's end: close by
   the `wave` skill (the meter reading against 53, the figure revised if five points off), then plan wave 32 from
   the Not-taken list (Layout C after the owner's Menu choice, the tracks manager, the launch budget, the world
   basemap, T240's objects, the accessibility sweep, the owner's phone in the sweep) and the two ADRs.

## Waiting on user

- The Menu choice for the top row (Layout C's step 8, wave 32); T220's location prompt; proposals 0054 and 0055.
- On v0.1.24-alpha: whether the start stays quick over the large download; a Startup freeze report if not.
- For your eye (ADR 0031): the T239 landscape and warning-card cases, T235's one-row landscape list, T237's
  wordings, the long-press card's 28.5 dp actions; this wave's strings arrive in the merge bodies.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is wave 32's.
- `docs/findings/2026-11-07-update-check-shows-githubs-raw-504.md`: T247 takes it this wave.
