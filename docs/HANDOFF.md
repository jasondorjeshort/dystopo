# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-24, at commit 4d190b2e, by the side session (wave 48 launched from it; v0.1.43-alpha is the latest release)
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the wave's
paragraphs in `docs/TASKS.md`, the last closed wave's record `docs/tasks/wave-47.md`, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 48 is launched** (the owner, 2026-11-24: "Go ahead with the wave when ready."), **coordinated by the side session in the second clone**, not by the main session: sixteen tasks, T354 to T366 and T368 to T370, in `docs/TASKS.md`, after two premise reads against the code wave 47 left and a critic (LAUNCH AFTER EDITS, answered in the wave paragraph). Merged: T362 (56fff2d0), T354 (81b70c86), T363 (d8c9e38a), T355 (a85a9f76), T361 (65ec5346). Running: the implementers of T356 (slot 1) and T364 (slot 2), each with its own emulator (consoles 5570 and 5574, hosts 8694 and 8696).
- **Order** (the board's Order sentence is binding): slot 1 T354, T355, T356, T368, T357, T358, T365; slot 2 T362, T363, T361, T364, T370, T369, T366, T359, T360. Next ports: consoles 5578 and up by fours, hosts 8698 and up by twos; never 9012 or 9013.
- **Where things run:** implementers make worktrees from the main checkout (`dystopo-wt/Tnn`) and use its cache; merges and pushes happen in the second clone (hooks on, the private terms copied in). The live viewer on 8765 is down; T368 and T358 need a server set, so a chore starts it from the main checkout before T356 merges (T356 is running: start it when its report arrives). A verifier's worktree often refuses removal right after its report (a process holds it for some minutes): the cleanup chore retries later.
- **Cost:** the 7-day meter read 40 at launch (the side session's account), the 5-hour meter 6; 17.5 points figured, 57.5 against the hold of 97. Reset 2026-11-28 03:00 UTC.

## Next

1. As each implementer reports: a verifier (Sonnet-class, `verifier.md`; `-SweepThrough two-maps` for T354, T356, T368, T359), then the merge sequence, then the next task of that slot (slot 2 after T364: T370, T369 once T355 and T356 are merged, T366, T359, T360). Every Gradle run takes `-Pkotlin.daemon.jvmargs=-Xmx4g`.
2. At T364's merge: the ADR for the overlay pattern (one data switch, one show key per view, a hidden view stops asking), with the measured table.
3. At the close: T365's two recordings on two UTC days with no flights step running; the sweep (`features` and `features-sky`), the release, the brainstorm offer.
4. Triage's scan: the earliest open ADR prediction is due 2026-12-01.

## Waiting on user

- **The blank Panorama on your phone on v0.1.43-alpha is still unexplained** (`docs/ideas/2026-11-22-at-the-phones-own-position-with-two-packs-the-panorama-is-blank-and-horizon-peaks-never-draw.md`): the next step is the phone on USB for one `adb logcat -s Dystopo DystopoAssets` while you open the Horizon view and the Panorama there (the release build is enough; no Gradle slot), before wave 48's release, which changes the maps' order (T354). The missing peaks are explained (the other session, 2026-11-24, from your screenshots): both downloads there are drawn areas kept as tiles, and the Horizon's peak read asks offline maps only; your choice puts search and peaks from tiles first in wave 49.
- **Answered 2026-11-24, for wave 49's planning:** the 200 km view range with the map's chips kept at 10, 30 and 100; a large area offered as several downloads, each by a tap, after T369 has merged (the two ideas' Answer: lines).
- **For your eye when T369 reports:** in direct mode nearly every country row is over the cap; and the prefetch ahead saves tiles without a tap, which the terms' line on automated collection may not cover.
- **For your eye, new wording:** T363's twenty reason sentences, glance notes and two help texts (merge commit d8c9e38a); T361's "Start here", its row line and help (65ec5346).
- **ADR 0067's step two** (the Horizon view's pane into the List) waits for you to have seen step one on v0.1.43-alpha.
- **On the phone, v0.1.43-alpha and older:** the handoff of commit c93655c8 and the board's Owner actions pending.

## Open findings

- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; each is the basis of a wave 48 task.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`: open.
- `docs/findings/2026-11-20-aircraft-layer-sources-and-display.md`: open; outside research.
- `docs/findings/2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`: open.
- `docs/findings/2026-11-16-research-for-pending-ideas.md` and `2026-11-15-a-drive-as-a-speed-profile.md`: open.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: open (guarded).
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
