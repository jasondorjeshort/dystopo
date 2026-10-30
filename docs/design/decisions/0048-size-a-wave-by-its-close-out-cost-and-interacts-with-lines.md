# 0048. Size a wave by its close-out cost; an Interacts with line where Owns lines hide a shared mechanism

Date: 2026-10-29
Status: accepted

Record: two ideas filed by the owner on 2026-10-29 (commit 3b30b84) from the critic review of
Hatchery's proposal 0003 (2026-10-13, objections 3 and 1, never answered there), built by the
coordinator's hand the same day on the owner's "I'll take your recommendations. Do it." The third
idea of that commit (closed waves move out of the board, objection 5) stays in `docs/ideas/` as a
wave 22 task.

## Context

Two process gaps the record shows, neither fixed in the base or here:

- **Sizing.** The `wave` skill sized a wave as "5 to 8 implementers at once" and halved it "when a
  wave would not fit the budget", but the budget it checked was the launch. Closing a wave costs a
  verifier per task, a fix round and a re-verify for some, the sweep and the release, and `CLAUDE.md`
  lets all of that run past the hold line. Wave 12 launched its first half at 72% of the weekly meter
  and on 2026-10-13 the gate denied every launch at 95% (T94's Goal); wave 21 closed at 98% with the handoff
  saying no new wave before the reset. Readings on the board and in the handoffs: wave 20 (eight tasks
  in two halves, six research agents, a critic, the sweep, the release, then T148) ran the 7-day meter
  from 55% to 89%, about 3.8 points per task with the fixed cost spread in; wave 21 (nine tasks, one
  agent at a time over about 26 hours) ran it from 89% to 98%, one net point per task while the rolling
  window shed a week-old day's usage underneath.
- **Shared behaviour.** Owns lines partition files, not behaviour. Per the 0003 critic review (the
  T74 and T75 Status lines do not record it): in wave 12, T74 (content-versioned
  tile cache) and T75 (pinned offline areas) had disjoint Owns lines; T75 added a `keep` hook to
  eviction, T74's version change deleted old-version tiles through a path that ignored it, and a pinned
  area lost its tiles whenever the server's content version moved. Both implementers and both
  verifiers passed it; a post-hoc critic found it. Same wave, T77 fixed a latent cross-task bug in
  T76's pack join with no test for the other order.

Verified before writing: `tools/merge-log.py` anchors a task on its `### Tnn.` heading and its
`**Done when:**` line, so a new optional line in the task format moves nothing it reads; the
coordinator already edits rule text on `main` by hand (commits b9af680, 2373338, 3f8dbf2); and both
rules are needed when wave 22 is planned, which a task inside wave 22 could not supply. The `wave`
and `brief` skills are this project's own rewrites, not base-owned files (ADR 0038), so no departure
row is due.

## Decision

1. **Whole-cost sizing** (`wave` skill, Plan and launch step 3 and Close the wave step 4; `CLAUDE.md`
   › Wave size and the usage gate). A wave's cost is, per task, an implementer, a verifier, one fix
   round with its re-verify and the merge, plus a fixed cost per wave for research agents, the critic,
   the post-wave sweep and the release. A wave of N tasks launches only when the 7-day meter at
   planning plus the cost is at or under the hold `policy.json` sets; otherwise a smaller wave, or
   wait for the reset. The 5-hour meter keeps its own line (Resume step 4). Starting figures: 3 points
   of the 7-day meter per task and 5 per wave. They are two data points, not a model: the wave
   paragraph carries one allowance sentence (meter at planning, task count, cost assumed, the figure it
   adds up to), the close adds the meter at the close, and a close more than five points over the
   figure raises the per-task figure in the skill in the same commit. A wave larger than the budget is
   no longer launched in halves on the same meter.
2. **Interacts with** (the task format in `docs/TASKS.md`; `wave` skill steps 1 and 2; the `brief`
   skill's `verifier.md`, `implementer.md` and step 6). An optional line per task names another task
   of the wave and the mechanism they share behind disjoint Owns lines (a hook, a cache, a format, an
   index, a join). The line is copied into both briefs. The verifier of whichever task merges second
   reads the other's change to the mechanism and runs one crossing scenario, from the brief or
   designed from the two Goals, and a wrong result is FIX; when the other task has not merged, it says
   so and the coordinator briefs that task's verifier instead. The critic is asked for behaviour
   shared across tasks that no line names, which is the check for what the coordinator missed.

Out of scope: the hook and `policy.json` (the rule is skill text, not enforcement); the hold values;
the 5-hour rule; the two-halves wording of the wave 12 and 20 records.

Deferred: the closed-waves move (the third idea,
`docs/ideas/2026-10-29-closed-waves-move-out-of-the-board.md`), a wave 22 task whose ADR amends ADR
0020's exemption of the board and names the dated log in `docs/PLANNING.md` as the next file to get
the same treatment; carrying both rules back to Hatchery's wave and brief procedure, in the next
Hatchery-rooted session (`docs/HANDOFF.md` › Next), where the 0003 critic's objections are open.

## Consequences

- A wave that cannot close inside the budget is not started, so a session no longer ends with half a
  wave launched and every verifier waiting on the reset. The cost: smaller waves near the hold, and a
  planning step that writes down its arithmetic.
- The per-task figure will be wrong at first. Each close records what it cost, and the skill says
  when to raise the figure; it says nothing about lowering it, which the coordinator may do once three
  consecutive closes land under the figure.
- Each Interacts with line costs one verifier run more. It catches only what the coordinator or the
  critic names; the critic question is the net under it.

- Prediction, check by 2026-12-01: no wave since this ADR has had its launches denied by the 7-day
  gate mid-wave, and every wave paragraph from wave 22 on carries an allowance sentence with both
  readings.
- Prediction, check when wave 24 closes: the per-task figure has been revised at least once from the
  recorded closes, and no closed wave missed its figure by more than five points twice in a row.
- Prediction, check by 2026-12-01: at least one verifier report has run a crossing scenario, and no
  defect of the T74/T75 kind (two merged tasks of one wave, disjoint Owns, wrong together) has been
  found after its wave closed.
