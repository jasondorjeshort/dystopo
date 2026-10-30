---
date: 2026-10-29
kind: chore
related: [2026-10-29-interacts-with-line-and-a-crossing-scenario-for-the-verifier.md, 2026-10-29-closed-waves-move-out-of-the-board.md]
---

# Size a wave by its close-out cost, not by whether its launch fits

The `wave` skill ("Plan and launch", step 3) sizes a wave as "5 to 8 implementers at once" and halves
it "when a wave would not fit the budget", but the budget it checks is the launch. Closing a wave costs
a verifier per task, a fix round and a re-verify for some, the sweep and the release, and `CLAUDE.md`
(Wave size and the usage gate) lets all of that run past the hold line. Objection 3 of the critic
review of Hatchery's proposal 0003 (2026-10-13) names this; it was never answered there or here.

Evidence on the board and in the handoff: wave 12 launched its first half at 72% of the weekly meter
(`docs/TASKS.md`, the Wave 12 heading) and wave 13 then found the gate denying every launch at 95%
(T94's Goal); wave 21 closed at 98% and `docs/HANDOFF.md` (Now) says no new wave
before the reset. The hold and the meters are `usage` in `.claude/hooks/policy.json` (`sevenDay.hold`
100 while this project is the owner's priority, 75 by default); the fix is a skill rule, not a hook.

The two related ideas edit the same skill section; run the three in sequence, never in parallel.

## A starting per-task figure, from waves 20 and 21

- Wave 20 (eight tasks in two halves, six research agents, a critic, the sweep, the release, then the
  one-off T148): the 7-day meter read 55% at planning on 2026-10-25 (the Wave 20 paragraph and the
  handoff of that day) and 89% when wave 21 was planned on 2026-10-27 (the Wave 21 paragraph): 34
  points for nine tasks plus the fixed cost, about 3.8 per task with the fixed cost spread in.
- Wave 21 (nine tasks, one agent at a time, about 26 hours of wall clock): 89% at planning, 93% at
  T156's launch, 95% at T155's, 97% at the tag, 98% at the close (the handoff history and the wave 21
  close entry in `docs/PLANNING.md`): one net point per task, but a rolling 7-day window sheds the
  usage of a week earlier through those 26 hours, so the gross cost is higher than the net rise.
- Starting figure: **3 points of the weekly meter per task** (implementer, verifier, one fix round and
  re-verify, the merge) **plus 5 per wave** (research agents, the critic, the sweep, the release). A wave
  of N tasks launches when meter + 3N + 5 is at or under the hold. Checked against the record: wave 12
  at 72 under a hold of 75 would not have launched (right: wave 13 stalled at 95); wave 20 at 55 under
  100 allows 13 (it ran 8 and had room); wave 21 at 89 allows 2 (it ran 9 one at a time and closed at
  98, so the rule is conservative for a slow wave, which re-sizes after each merge anyway).

Plan: one task of the next wave, implementer Opus-class on `task/Tnn`, verifier Sonnet-class.
1. `.claude/skills/wave/SKILL.md`, "Plan and launch", step 3 becomes: "Size the wave by its whole
   cost: per task an implementer, a verifier, one fix round with its re-verify and the merge, plus the
   wave's fixed cost of research agents, the critic, the post-wave sweep and the release. Starting
   figures: 3 points of the 7-day meter per task and 5 per wave, revised at each close from the
   readings below; launch only when the meter at planning plus that cost is at or under the 7-day hold
   `policy.json` sets, and otherwise plan a smaller wave or wait for the reset (the 5-hour meter keeps
   its own line, Resume step 4). Write the allowance into the wave paragraph as one sentence: the
   meter at planning, the task count, the cost assumed, the figure it adds up to. Still 5 to 8
   implementers at once; a wave larger than the budget is not launched in halves on the same meter."
2. "Close the wave", step 4 gains one sentence: "Add the meter at the close to the wave paragraph's
   allowance sentence; when it is more than five points over the figure, raise the per-task figure in
   step 3 of Plan and launch in the same commit and say so in the handoff."
3. `CLAUDE.md`, the "Wave size and the usage gate" bullet: after "5 to 8 implementers at once" add
   ", sized by the whole-cost rule of the `wave` skill (Plan and launch, step 3)". Nothing else there.
4. The Wave 22 paragraph on the board, when it is written, carries the first allowance sentence.
Must not change: the hook, `policy.json`, the hold values, the 5-hour rule, the two-halves wording of
the wave 12 and 20 records, any Status line. Verify: `node .claude/hooks/test-hooks.js` green;
`wc -l CLAUDE.md` under 130; the three sentences present verbatim (`grep -n "whole cost"
.claude/skills/wave/SKILL.md CLAUDE.md`); delete this file on the branch.
