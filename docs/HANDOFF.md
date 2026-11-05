# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-04, at commit 96b5e019, by main session (wave 28 launched)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (the open
wave 28, T217 to T225; the last record `docs/tasks/wave-27.md`), ADR 0053.

## Now

- **Wave 28 runs one Gradle-using agent at a time** (the owner, 2026-11-04, after seven builds left 2 GB free; the
  board's wave paragraph). Order: T223, T218, T217, T219, T220, T221, T222, T224; T225 alongside, documents only.
  T223 and T225 are running. T217, T218, T220, T221, T222 and T224 were stopped part-way: their work is in their
  worktrees, unpushed and unchecked (T224's uncommitted). Resume each with its agent, or a new one in its worktree.
- The wave's common brief and wait script are outside the repo, in the `.scratch` folder of the worktrees' folder
  [no-check: outside the repo]. Host ports 8701 to 8708 and consoles 5570 to 5584 went to the implementers in task
  order; verifiers take host 8721 up and consoles 5600 up.
- Meter at launch: 73 on the 7-day, 1 on the 5-hour; with the figured 16.5 points, 89.5 against the hold of 97.

## Next

1. As T223 reports: its verifier (Sonnet-class), then the merge by the `wave` skill's sequence, then T218.
2. On T217's merge: launch T219, then go on down the order, one at a time.
3. After the last merge: the sweep (it checks the labels `tools/android-ui.py` taps), the release, the close.
4. The next wave's candidates: the layout authority's second half with ADR 0053's build step 2 and the top-row
   regroup (T224's design note), T225's build tasks once the owner chooses, the two ready Plans.
5. A Hatchery-rooted session: the pending carry-backs plus T190's two, T201's, the sentinel rule.

## Waiting on user

- Any change to wave 28's "For the owner" list (the board's wave paragraph).
- "Keep north up": rename or keep (T176). The map screen's top row: regroup behind a Menu or not.
- v0.1.19-alpha on the phone (`GET /identity`, the board's phone checks); proposals 0054 and 0055's choices;
  ADR 0053's Category column; the rest of the board's Owner actions.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
