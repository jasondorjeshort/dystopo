# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-26, at the commit that carries this file (wave 49 launched, T389 merged), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the open wave `## Wave 49` in
`docs/TASKS.md` (its four opening paragraphs first), the last closed wave's record `docs/tasks/wave-48.md`.

## Now

- **Wave 49 is open: fifteen tasks, T375 to T388 and T390, plus T389.** Planned, read by a critic and launched on 2026-11-26 on the owner's words (the wave paragraph quotes them). **T389 is merged** (940365fa, the phone session's branch `task/gps-clock-log`).
- **In flight:** T375 (`task/T375`, fea0570b, the implementer's report in; a Sonnet verifier running, `-SweepThrough device-boot`) and T376 (`task/T376`, 62453cca, report in; a Sonnet verifier reading the gate's diff). Nothing else is launched.
- **Cost:** the 7-day meter read 93 at 21:30 UTC against the hold of 97; it resets 2026-11-27 04:00 UTC (this session's usage file). The owner: "Go now. We can just hold for a few hours when the hold hits." At the hold the gate refuses every new agent and, until T376 merges, every resume.
- **On the PC:** the main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`; worktrees `dystopo-wt/T375`, `T376` and `T990-verify` (left, held by a process) stand; `dystopo-wt/sweep-w48` holds the next release's `-Prev`; `dystopo-wt/T365` holds the flight recordings. Two phones of the owner's dropped off wireless adb during T389's verify (cause not found).

## Next

1. On each verifier's MERGE: the merge sequence for T375 and T376 (the `wave` skill). T376's implementer counted an empty 5-hour reading as under its hold when the 7-day one is read: accepted, said in its merge body.
2. After T375 merges: a Haiku chore makes `dystopo-wt/tools-main`, a detached worktree of `origin/main` that every verifier and the sweep run their scripts from, and cleans up (the `task/T375`, `task/T376` and `task/gps-clock-log` branches after `origin/main` holds them, their worktrees, `T990-verify`). Then four Gradle agents at once (ADR 0070).
3. Launch in the board's order as slots and the meter allow: T377, T379, T384, T381, T382, T383, then T378, T390, T385, T386, T380, T387, T388, each after the tasks in its brackets. Every brief carries the wave paragraph's `GRADLE_OPTS`, the tools' copy, the fixture and the Hatchery sentence.
4. Before T385 launches: one read-only research agent (a fix's own time against its elapsed-realtime stamp: the platform's guidance, two open trackers); its answer goes into T385's entry.
5. A small release may be cut once T384, T385, T379 and T381 are merged (the wave paragraph); otherwise the closing sweep, with `-Prev` from `dystopo-wt/sweep-w48`.
6. Triage's scan: the earliest dated ADR prediction is due 2026-12-01; ADR 0070's two and ADR 0071's second are checked at this wave's close.

## Waiting on user

- **Your phones:** both dropped off wireless adb on 2026-11-26; reconnect them when you next need them.
- **For your eye after T389:** the glance view's figures at font 2.0 on its side are 55 px with the rows reserved (126 px at font 1.0 on its side).
- **Wave 48's wording and looks, v0.1.44-alpha on a phone, the flight recordings in `dystopo-wt/T365/out/flights/`, ADR 0067's step two** (its idea now has a Plan; say when): as the handoff of 2026-11-26 before this one listed them (`git show 64007414:docs/HANDOFF.md`).
- **Hatchery:** six ideas titled `For the base: ` wait here for Hatchery's triage; its clone sits beside this one, so it should already read them.

## Open findings

- `docs/findings/2026-11-26-gps-time-fault-restarts-the-readout-over-and-over.md`: open (T385).
- `docs/findings/2026-11-25-tile-cache-index-walk-loops-in-the-background.md` (T379, T390, T387) and `2026-11-25-the-map-layout-flips-at-font-2-with-the-bar-unfolded-and-the-turn-back-card.md` (T381): open, in this wave.
- `docs/findings/2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md`, `2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md`: open.
- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; their statuses were not set at the triage of 2026-11-26.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`, `2026-11-20-aircraft-layer-sources-and-display.md`, `2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`, `2026-11-16-research-for-pending-ideas.md`, `2026-11-15-a-drive-as-a-speed-profile.md`, `2026-11-05-emulator-private-bytes-grow-per-cold-launch.md` (guarded), `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
