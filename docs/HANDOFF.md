# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-04, at commit 96b5e019, by main session (wave 28 launched)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (the open
wave 28, T217 to T225; the last record `docs/tasks/wave-27.md`), ADR 0053.

## Now

- **Wave 28 is running** (the owner's go, 2026-11-04; the owner's theme: usability of the phone app). Launched:
  T217, T218 and T220 to T225 (implementers, Opus-class), each in its own worktree on its task branch. T219 waits
  on T217's merge. A task branch on the remote with no merge is that task's work; an agent's report is its record.
- The wave's common brief and its two wait scripts (free RAM, an emulator slot) are outside the repo, in the
  `.scratch` folder of the worktrees' folder [no-check: outside the repo]. Host ports 8701 to 8708 and consoles
  5570 to 5584 went to the implementers in task order; verifiers take host 8721 up and consoles 5600 up.
- Meter at launch: 73 on the 7-day, 1 on the 5-hour; with the figured 16.5 points, 89.5 against the hold of 97.

## Next

1. As each implementer reports: a verifier (Sonnet-class, at most two at once) in the slot order of the wave
   paragraph: T223 and T218 first, T217 early. Merge by the `wave` skill's sequence.
2. On T217's merge: launch T219, the ninth implementer.
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
