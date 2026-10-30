---
date: 2026-10-29
kind: chore
related: [2026-10-29-size-a-wave-by-its-close-out-cost.md, 2026-10-29-closed-waves-move-out-of-the-board.md]
---

# An "Interacts with:" line per task, and the verifier runs one scenario across it

Owns lines partition files, not behaviour, and nothing on the board or in the verifier brief checks
the difference. Objection 1 of the critic review of Hatchery's proposal 0003 (2026-10-13) gives the
wave 12 account: T74 (content-versioned tile cache) and T75 (pinned offline areas) had disjoint Owns
lines; T75 added a `keep` hook to eviction that T74 was told about, and T74's version change deleted
old-version tiles through a path that ignores `keep`, so a pinned area lost its tiles whenever the
server's content version moved. Both implementers and both verifiers passed it; a post-hoc critic found
it. Same wave: T77 found and fixed a latent cross-task bug in T76's pack join, with no test for the
other order. The objection was never answered in Hatchery and never landed here.

Anchors: the task format in `docs/TASKS.md` (the head paragraph that defines Owns), the `wave` skill
("Plan and launch", steps 1 and 2), `.claude/skills/brief/verifier.md` (steps 4 and 5) and
`.claude/skills/brief/implementer.md` (the Sequencing field). The two related ideas edit the same
skill section; run the three in sequence, never in parallel.

Plan: one task of the next wave, implementer Opus-class on `task/Tnn`, verifier Sonnet-class.
1. `docs/TASKS.md`, head: after the paragraph beginning "Owns" lists the files a task may change
   freely add one sentence: "**Interacts with:** (optional) names another task of the wave and the
   mechanism the two share although their Owns lines are disjoint (a hook, a cache, a format, an
   index, a join); the verifier of whichever merges second runs one scenario across both."
2. `.claude/skills/wave/SKILL.md`, "Plan and launch", step 1: after "a **Done when:** line." add
   "and, where two tasks share a mechanism behind disjoint Owns lines, an **Interacts with:** line on
   each naming the other task and the mechanism." Step 2's list of what the critic is asked about
   gains, after "cross-task sequencing", the item "behaviour shared across tasks that no Interacts
   with line names".
3. `.claude/skills/brief/verifier.md`: a field after the machine facts paragraph: "Interacts with:
   <Tmm, its merge SHA from its Status line or "not merged", the files of the shared mechanism, the
   crossing scenario, or "none">." A new step after step 4 (renumber 5 and 6 to 6 and 7): "5. When
   the task carries an **Interacts with:** line and Tmm has merged: read Tmm's change to the mechanism
   (`git show <sha> -- <its files>`) and run the crossing scenario from the brief, or one you design
   from the two Goals when the brief gives none, saying which; a wrong result is FIX, and the report
   names the scenario and what it showed. When Tmm has not merged, say so: the coordinator briefs
   Tmm's verifier with this task's change instead."
4. `.claude/skills/brief/implementer.md`, the Sequencing field: after "the order" add "; the task an
   **Interacts with:** line names, with the mechanism and whether its branch has merged".
5. `.claude/skills/brief/SKILL.md`, step 6: after "name what must not be touched" add "; an
   **Interacts with:** line is copied into both the implementer's and the verifier's brief".
Must not change: any existing task text, Status line or wave paragraph; the verifier's existing
steps beyond renumbering; `tools/merge-log.py` (it finds a task by its `### Tnn.` heading and its
**Done when:** line, neither moves). Verify: `node .claude/hooks/test-hooks.js` green; `grep -c
"Interacts with" docs/TASKS.md .claude/skills/wave/SKILL.md .claude/skills/brief/verifier.md
.claude/skills/brief/implementer.md .claude/skills/brief/SKILL.md` shows every file at least once;
`python -I tools/test_merge_log.py` ok; a dry brief for two invented tasks sharing a hook fills every
field of `verifier.md` without a gap; delete this file on the branch.
