# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-26, at commit 5eeea6c0, by the side session (wave 48 launched from it; v0.1.43-alpha is the latest release)
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the wave's
paragraphs in `docs/TASKS.md`, the last closed wave's record `docs/tasks/wave-47.md`, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 48 is launched** (the owner, 2026-11-24: "Go ahead with the wave when ready."), **coordinated by the side session in the second clone**, not by the main session: twenty tasks, T354 to T366 and T368 to T374 (T371 to T374 added by the coordinator; T374 withdrawn, nothing to fix), in `docs/TASKS.md`, after two premise reads against the code wave 47 left and a critic (LAUNCH AFTER EDITS, answered in the wave paragraph). Merged: T362 (56fff2d0), T354 (81b70c86), T363 (d8c9e38a), T355 (a85a9f76), T361 (65ec5346), T356 (32fa89ff), T364 (577d0fd2, with ADR 0068), T371 (e11467fb), T370 (ca632ef9), T369 (cb2871c2), T368 (0922394e, with ADR 0066's first prediction outcome), T366 (b8b5b0ff), T372 (7cb73582), T359 (1afe53cb), T357 (47b6e3a0, after a FIX round on the join's cost), T360 (e7419f45, after a FIX round). T358 (f06b86b7; ADR 0066's chain is whole). T373 (2a68e4f6, with a finding: the layout flips at font 2.0, older than this wave). Nothing is running. Left: T365's second recording, its tables and its verifier; then the close.  T365's build is pushed (head d470c57c, worktree `dystopo-wt/T365` kept for the recordings): recording 1 (rainier) ended well on 2026-11-25 (236 requests, 234 ok, 45 aircraft, `out/flights/rainier-1` there; the day's allowance of 240 is spent); recording 2 (mont-blanc, 240 polls, the command in `<scratchpad>/T365-interim.txt`) is started by a chore at about 06:30 UTC on 2026-11-26 (morning traffic; before any other feed request that day), then its implementer is briefed for the tables and a verifier follows. Slots: (consoles 5570 and 5574, hosts 8694 and 8696; a second sweep at once takes `-SweepArgs "-BasePort 8704"`). T371's fix for the freeze at font 2.0 is in; the closing sweep's `a11y` part is its next check, and in the state that froze the bar now stands wrapped mid-number (a follow-up for the layout rule in `shared`).
- **Order** (the board's Order sentence is binding): slot 1 T354, T355, T356, T368, T365 (moved up, its lead line), T372, T357, T358; slot 2 T362, T363, T361, T364, T370, T369, T366, T359, T360. Next ports: consoles 5578 and up by fours, hosts 8698 and up by twos; never 9012 or 9013.
- **Where things run:** implementers make worktrees from the main checkout (`dystopo-wt/Tnn`) and use its cache; merges and pushes happen in the second clone (hooks on, the private terms copied in). The live viewer runs on 8765 from the main checkout's installed distribution (T368 and T358 need a server set; PIDs in its `out/coordinator-pids.txt`). A verifier's worktree often refuses removal right after its report (a process holds it for some minutes): the cleanup chore retries later.
- **Cost:** the 7-day meter read 40 at launch (the side session's account) and 88 at T358's merge (other sessions spend from it too), against the hold of 97; reset 2026-11-28 03:00 UTC. The 5-hour hold is 75 from 2026-11-25 (the owner; `policy.json`), 50 before; since the base's update the gate also refuses, at a hold, a message that resumes an agent.

## Next

1. T365: at about 06:30 UTC on 2026-11-26 a chore starts recording 2 from `dystopo-wt/T365` (the command in `<scratchpad>/T365-interim.txt`; a dry run first; no flights step and no other `devtool aircraft` request that UTC day before it); when it ends (two hours) the implementer is briefed for the tables from the two folders under `out/flights/` there, then a verifier (devtool only, no emulator), the merge, and the measured table written into a finding.
2. Follow-ups to place at the close (ideas, or wave 49's tasks): every merged task's Status line lists its own; the leftovers are cleared but local `task/*` branches in the main checkout that refuse `-d` until its `main` catches up (never `-D`).
3. The close (the `wave` skill's Close, reread: it gained a step 7, the base's update as a task): the sweep alone on the PC (ADR 0070; `features` and `features-sky`, `-Prev out/sweep-20261124-w47-rerun2`), the release, costs, the wave record, the brainstorm offer by the `brainstorm` skill.
4. Triage's scan: the earliest open ADR prediction is due 2026-12-01.

## Waiting on user

- **Nothing is wanted from your phone for wave 48's release:** the Panorama draws terrain on v0.1.43-alpha (your word of 2026-11-24, by the other session; T340 fixed the blank), and the missing peaks are the peak read asking offline maps only inside an area kept as tiles, folded into `docs/ideas/2026-11-07-search-and-routes-find-nothing-outside-a-pack.md`. By your choice wave 49 opens with search and peaks from tiles, then `docs/ideas/2026-11-24-change-a-saved-areas-detail-and-keep-its-shape.md`.
- **Answered 2026-11-24, for wave 49's planning:** the 200 km view range with the map's chips kept at 10, 30 and 100; a large area offered as several downloads, each by a tap, after T369 has merged (the two ideas' Answer: lines).
- **For your eye, T369 (cb2871c2):** in direct mode 145 of 240 country rows are greyed as over the cap; the prefetch ahead still saves tiles without a tap (your answer of 2026-11-23 keeps it), which the terms' line on automated collection may not cover; a Retry after the 50 MB stop is a new download of up to 50 MB more; its five new sentences are in the merge commit's body.
- **For your eye, new wording:** T363's twenty reason sentences, glance notes and two help texts (merge commit d8c9e38a); T361's "Start here", its row line and help (65ec5346); T364's "Show flights" row, line and help (577d0fd2); T370's two summaries, the Flight tracking help, and the rows' place at the end of Data and storage (ca632ef9). T366's six level names and the outlines' look (b8b5b0ff); T359's four places where the chip's words now show (1afe53cb). T360's panel lines, which name zoom numbers, and its section absent on its side from font 1.5 (e7419f45).
- **ADR 0067's step two** (the Horizon view's pane into the List) waits for you to have seen step one on v0.1.43-alpha.
- **On the phone, v0.1.43-alpha and older:** the handoff of commit c93655c8 and the board's Owner actions pending.

## Open findings

- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; each is the basis of a wave 48 task.
- `docs/findings/2026-11-25-the-map-layout-flips-at-font-2-with-the-bar-unfolded-and-the-turn-back-card.md` (wave 49, the owner 2026-11-26; its queue entry is the idea of 2026-11-26), `2026-11-25-tile-cache-index-walk-loops-in-the-background.md` (wave 49) and `2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md` (a phone's figure wanted): open.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`: open.
- `docs/findings/2026-11-20-aircraft-layer-sources-and-display.md` (outside research) and `2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`: open.
- `docs/findings/2026-11-16-research-for-pending-ideas.md` and `2026-11-15-a-drive-as-a-speed-profile.md`: open.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: open (guarded).
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
