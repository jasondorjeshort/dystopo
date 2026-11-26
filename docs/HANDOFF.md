# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-26, at the close's last commit (the base's update 405ad87f merged, both brainstorm offers answered), by the side session (wave 48's coordinator, second clone; v0.1.44-alpha is the latest release)
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the last closed wave's record `docs/tasks/wave-48.md`, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 48 is closed.** Twenty tasks, T354 to T366 and T368 to T374 (T371 to T374 added by the coordinator; T374 withdrawn, nothing to fix), all merged; the record is `docs/tasks/wave-48.md` and its follow-ups are in the board's `## Later`, those the wave itself settled struck. **v0.1.44-alpha is published** (2026-11-26 05:19 UTC, tag on f5e38641, from the closing sweep on 643dab9c; the log entry of 2026-11-26 has the sweep's reading and the draft's checks). T365 merged after the release (657fd4fa) and is in the next one.
- **Nothing is running; no hold stands on `main`.**
- **On the PC:** the worktree `dystopo-wt/sweep-w48` holds `out/sweep-20261126-w48` (a whole sweep: the next release's `-Prev`) and `out/release-v0.1.44`; the worktree `dystopo-wt/T365` holds the two flight recordings in `out/flights/` (not committed). The live viewer runs on 8765 from the main checkout, whose `main` is behind origin (its local `task/*` branches refuse `-d` until it catches up; never `-D`). Two phones are attached over wireless adb: the owner's, used by another session; no agent of this wave touched them.
- **Cost:** the 7-day meter read 40 at the launch and 92 at the close (three sessions spend from it), against the hold of 97; reset 2026-11-28 03:00 UTC. The 5-hour hold is 75 (the owner, 2026-11-25). The gate also refuses, at a hold, a message that resumes an agent. The `wave` skill's figures are now 1.5 points a task, 2 a wave, 0.5 a release.

## Next

1. The close is complete: costs, the record, the brainstorm offer and rest review, the base's update (ADR 0038's base line is 60072884) and its own offer. One idea "For the base:" is kept in `docs/ideas/`; this checkout is not on the machine's sibling list, so the base's triage reads it only if the owner says "list this checkout" or gives the base's session the repository's URL. Leftovers for a chore: the worktree `dystopo-wt/hatchery-6007288` and its remote branch.
2. Wave 49, by the owner's choices so far: it opens with search and peaks from tiles (`docs/ideas/2026-11-07-search-and-routes-find-nothing-outside-a-pack.md`), then `docs/ideas/2026-11-24-change-a-saved-areas-detail-and-keep-its-shape.md`; its first task is ADR 0070's wait for an emulator slot, after which four agents run at once; and, each the owner's word for wave 49: the tile cache's index walk (finding of 2026-11-25), the glance view saying GPS is searching (the idea of 2026-11-25, mandatory), the layout that flips at the largest font (the idea of 2026-11-26), work already launched resumable at the hold (the idea of 2026-11-25). A triage comes first: ideas are untriaged and one carries a `New:` line.
3. Lessons of this wave for the briefs: a branch that touches the online column or the layout rule is verified through `two-maps`, not `a11y` alone; a verifier is told its hand runs are not optional; timed parts (startup) read high when two sweeps share the PC (ADR 0070: timed runs go alone); a second sweep takes `-SweepArgs "-BasePort 8704"`.
4. Triage's scan: the earliest open ADR prediction is due 2026-12-01; ADR 0070's two are checked at wave 49's close.

## Waiting on user

- **For your eye, wave 48's wording and looks** (each merge commit's body lists its strings): T363's reason sentences and glance notes (d8c9e38a); T361's "Start here" (65ec5346); T364's "Show flights" (577d0fd2); T370's summaries and the Flight tracking rows' place (ca632ef9); T369's five sentences, 145 of 240 country rows greyed with no server set, the prefetch ahead still saving tiles without a tap (cb2871c2); T366's six level names and the outlines' look (b8b5b0ff); T359's four places where the chip's words now show (1afe53cb); T360's panel lines, which name zoom numbers, and its section absent on its side from font 1.5 (e7419f45).
- **On a phone, v0.1.44-alpha:** nothing of this release was tried on a phone. Worth a look: the outlines' colours outdoors; at the system's largest font with a stale recording and the panel open, whether the track bar moves by itself (the finding of 2026-11-25); the board's Owner actions pending.
- **The flight recordings** in `dystopo-wt/T365/out/flights/` go when that worktree is removed: say if they should be kept elsewhere.
- **ADR 0067's step two** (the Horizon view's pane into the List) waits for you to have seen step one.

## Open findings

- `docs/findings/2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md`: open (the guessed state's design; a daytime Rainier sample).
- `docs/findings/2026-11-25-the-map-layout-flips-at-font-2-with-the-bar-unfolded-and-the-turn-back-card.md` and `2026-11-25-tile-cache-index-walk-loops-in-the-background.md`: open, both for wave 49.
- `docs/findings/2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md`: open (a phone's figure wanted).
- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; wave 48 built each one's task, and their statuses are to be set at the next triage.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`, `2026-11-20-aircraft-layer-sources-and-display.md`, `2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`, `2026-11-16-research-for-pending-ideas.md`, `2026-11-15-a-drive-as-a-speed-profile.md`: open.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md` (guarded) and `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
