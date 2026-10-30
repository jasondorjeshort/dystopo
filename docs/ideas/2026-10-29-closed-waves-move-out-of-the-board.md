---
date: 2026-10-29
kind: chore
related: [2026-10-29-size-a-wave-by-its-close-out-cost.md, 2026-10-29-interacts-with-line-and-a-crossing-scenario-for-the-verifier.md]
---

# Closed waves move out of the board into one record each

`docs/TASKS.md` is read from the bottom at every resume (`wave` skill, Resume step 2), yet it carries
every wave since the first: on 2026-10-29 `wc -l` and `ls -l` give it about 1070 lines and 440 kB, and
`docs/PLANNING.md` about 580 kB; both sit in `edit.capExempt` of `.claude/hooks/policy.json`. Objection
5 of the critic review of Hatchery's proposal 0003 (2026-10-13) asked for one closed record per wave.

What constrains the move, read in `tools/merge-log.py`: `add_status` finds `### Tnn.` in
`docs/TASKS.md`, takes the title from that line, finds the next `**Done when:**` and refuses a task
that already has `**Status:` before the next blank line; `add_log` needs `LOG_ANCHOR` in
`docs/PLANNING.md`; `add_changelog` needs `## Unreleased`. A task is logged while its wave is open and
a wave closes after its last merge (a task that rolls into the next wave moves with it on the board),
so moving closed waves changes nothing the script reads, provided every `### Tnn.` heading stays
exactly once across the board and the records. The post-edit hook warns about paths that no longer
exist in any edited doc, so the records need `anchorExempt` like the other closed records; every wave
section is under 100 lines, so a record needs no cap exemption. The two related ideas edit the same
skill; run the three in sequence. Follow-up, not this idea: the dated log in `PLANNING.md`.

Plan: two steps. (A) One task of the next wave, implementer Opus-class on `task/Tnn`, verifier
Sonnet-class, edits the rules: `docs/tasks/README.md` (new: three lines saying one record per closed
wave, `wave-NN.md` two-digit, the section moved verbatim with its heading as the title, then a table
with one line per record, filled by step B); `.claude/hooks/policy.json`: `docs/tasks/*` added to
`edit.anchorExempt`, `docs/TASKS.md` removed from `edit.capExempt`; `.claude/rules/docs.md`: item 6
gains "closed waves: `docs/tasks/`", the Size caps bullet drops `docs/TASKS.md` from the exempt list
and names ADR 0020 as amended; `docs/ARCHITECTURE.md`, last section: the board line gains "closed
waves, one record each with their Status lines: `docs/tasks/` (index in its README)"; `docs/TASKS.md`
head: one sentence "Closed waves are in `docs/tasks/`, one record each; this board holds the open
wave, Owner actions pending, Later and the follow-up lists." (the rule, never a list of records);
`.claude/skills/wave/SKILL.md`: Resume step 2 reads "the board holds the open wave (its tasks lack a
**Status:** line); the previous wave's Status lines are its record in `docs/tasks/`", Plan and launch
step 2 names "the previous wave's record" for the critic, and Close the wave gains step 5: "A Haiku
chore moves the closed wave to `docs/tasks/wave-NN.md` by the recipe in that README and adds its index
line"; `.claude/skills/handoff/SKILL.md`, the Resume line of the template: "the open wave in
docs/TASKS.md and the last closed wave's record in docs/tasks/"; `tools/merge-log.py` docstring line
8: "appended to the task in docs/TASKS.md (a task is on the board until its wave closes)"; a short
ADR (`adr` skill) amending ADR 0020's exemption of the board, written by the coordinator, not the
implementer. Must not change: any task text, Status line, `merge-log.py` code, `PLANNING.md`,
`CHANGELOG.md`, the ideas or findings READMEs. Verify: `node .claude/hooks/test-hooks.js` green;
`python -I tools/test_merge_log.py` ok; `node .claude/hooks/policy.js` prints without error.
(B) After A merges, one Haiku-class chore on `main` in one pass (or one wave per run; the coordinator
picks), exact steps: record N0 = `grep -cE '^### T[0-9]+\.' docs/TASKS.md`; for each `## Wave NN`
heading, cut the lines from it to the line before the next `## ` heading into `docs/tasks/wave-NN.md`
with the heading demoted to `# `; the `## One-off fixes after wave 20` section goes to the end of
`wave-20.md`; `### Later` (inside wave 20) is lifted out first and becomes `## Later` on the board
above the open wave; `## Browser demo follow-ups` stays; add one index line per record; wave 21 moves
too (it is closed). Checks before the commit: `cat docs/TASKS.md docs/tasks/wave-*.md | grep -oE
'^### T[0-9]+\.' | sort | uniq -d` prints nothing and the same pipe without `uniq -d` counts N0;
each record's body is byte-equal to the board's old section (`git show HEAD:docs/TASKS.md | sed -n
'<a>,<b>p' | diff - <(tail -n +3 docs/tasks/wave-NN.md)`); `git diff --stat` shows only removals from
`docs/TASKS.md` plus the new files; `wc -l docs/TASKS.md` under 300; `java tools/PrivacyCheck.java
scan < docs/tasks/wave-NN.md` clean for every record (a hit is reported, not fixed); `python -I
tools/test_merge_log.py` ok. Stop on the first failed check and report; the coordinator relaunches
Sonnet-class. Commit by the coordinator; delete this file in that commit.
