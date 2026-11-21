# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-21, by main session (wave 41 closed, v0.1.37-alpha published)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-40.md`, wave 41 and Later on the board in `docs/TASKS.md`, the finding named under Now, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 41 (Flights) is closed:** T311, T312 and T313 merged; **v0.1.37-alpha published** 2026-11-21 after a green sweep (`out/sweep-20261120-w41`, its two red parts fixed and rerun green), after the preliminary v0.1.36-alpha. The board's wave 41 section still waits to move to `docs/tasks/wave-41.md` (the `wave` skill, Close step 5: a Haiku chore), and the task branches and worktrees to be cleaned up (`task/T311`, `task/T312`, `task/T311n`, `task/T313`; worktrees `T311`, `T312`, `T311n`, `T313`, `T313-verify`, and the verifiers' `T311-verify`, `T312-verify`; AVDs `dystopo35-T311`, `dystopo35-T312` on E:).
- **Cost:** the 7-day meter read 7 at the close; reset 2026-11-27 03:00 UTC. Figures unchanged (1.5 a task, 1.5 a wave, 1 a release). The hold is 97.
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; at most two Gradle agents and two emulators. An unknown qemu (PID 68336 at 02:00 UTC) was left running by no agent of this session.

## Next

1. Close-out chores (above), then triage the new ideas: the 11 flight ideas of 2026-11-20, the owner's two of the evening (the Horizon view from another place; flight refresh choices of two and three minutes).
2. The Panorama view (the owner: "soon but after the airplane tracks"), then the window-seat flip of the Horizon view.
3. The reroute: an amendment to ADR 0040 for drives, with a short research pass; then one drive task.
4. The routing work of `docs/findings/2026-11-16-research-for-pending-ideas.md`; the cheap phone fixes ready as Plans.

## Waiting on user

- **On the phone, v0.1.37-alpha:** Flight tracking (Settings > Map options) on the map and in the Horizon view; whether more airline flights now show routes (a call sign from any "Route unknown" airline card helps), the ±300 ft/min climbing threshold, the 18,000 ft flight level, Sky range's Overhead 45° and Near 50 km; the emails to the two other flight feeds (`docs/ideas/2026-11-20-aircraft-follow-ups-home-site-route-table-and-feed-emails.md`).
- **Answers in `docs/ideas/`**: the next Horizon layer; two new Questions of 2026-11-16 (the sharp-bend warning, clock time and sun along a route); a Shortest drive (at rest recommended; your words of 2026-11-16 on route options are noted there, the Answer left blank); energy on a charge (is there an electric car, a hybrid or an e-bike to check against); the pace from your tracks (the hike half: is the fit in Route times what was meant); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.35-alpha** (ADR 0031): the Map | Horizon switch on the map (its place: trial and error, your words); whether the named peaks are the ones you see, closest first, a hidden close one hollow (the weights are first guesses). Strings: the merge body of T309. From v0.1.34-alpha: the Horizon view's toggles, peak names (tap one), today's sun; Check my GPS: on 2026-11-20 the owner's phone read strong (the suspected fault not seen); the four-best figure and used of in view (or Send diagnostics) would still calibrate the verdict's 25 and 35 dB-Hz; whether "Follow the phone" turns right when held sideways. Strings: the merge bodies of T307 and T308.
- **On the phone, v0.1.32-alpha** (ADR 0031): Settings > About and advanced > Report a problem: Send diagnostics, then Share and Open GitHub issue; Report recent stats (one sent from your phone gives its real start times). Strings: the merge bodies of T301 and T302. **The release key:** T293 found a script started by an agent can read `tools\release.properties` (the deny rule covers the Read tool only); closing it is a permission-settings change, yours.
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285. In Settings the Extended readout row shows Off and Pressure both filled: by design, a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
