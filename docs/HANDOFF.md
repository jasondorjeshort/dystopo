# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-20, by main session (ideas triaged; wave 41, the aircraft layer, planned, not launched)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-40.md`, wave 41 and Later on the board in `docs/TASKS.md`, the finding named under Now, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 41 is planned, not launched:** T311, the aircraft layer (`docs/TASKS.md`; research `docs/findings/2026-11-20-aircraft-layer-sources-and-display.md`; the owner accepted all four data-flow choices). Launch it first next session (the owner: "we'll implement next session"). v0.1.35-alpha is the published release.
- **Cost:** the 7-day meter read 2 at wave 40's close; reset 2026-11-27 03:00 UTC (re-read `resets_at` at each stall). Figures: 1.5 a task, 1.5 a wave's fixed cost, 1 a release (the `wave` skill, Plan and launch step 3). The hold is 97.
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g` (the sweep's build: env `ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs`, process scope only); long-path folders go by the `\\?\` delete; at most two Gradle agents and two emulators.

## Next

1. Launch T311 (Opus implementer, Sonnet verifier), then the sweep and a release. After it: the Panorama view (the owner: "soon but after the airplane tracks"), then the window-seat flip of the Horizon view (its idea).
2. Ideas triaged 2026-11-20 (25 blocks: 16 Plans, 9 Questions). Plans ready: the launch crash after the guard's reboot (the stdlib's `FileTreeWalk` over a vanishing folder), the moon, fewer or more peak names, the sky from a long-pressed point, the compass check, the signal per track point, the no-map message, sweep tooling (memory floor, blank screenshots, `changes.json`, a state ledger, speed against the last release, a failing hook says why: the backticks-in-single-quotes false positive hit again).
3. The reroute: an amendment to ADR 0040 for drives (past track and future route), possibly a per-route option (the owner's answer), with a short research pass; then one drive task.
4. The routing work of `docs/findings/2026-11-16-research-for-pending-ideas.md`; the cheap phone fixes ready as Plans (late first fix, days-old recording, blue dots, gross gain, pressure unit).

## Waiting on user

- **Answers in `docs/ideas/`**: the release key (named on 2026-11-20 with no answer; the signing service recommended; Java 25 was answered yes); the next Horizon layer; two new Questions of 2026-11-16 (the sharp-bend warning, clock time and sun along a route); a Shortest drive (at rest recommended; your words of 2026-11-16 on route options are noted there, the Answer left blank); energy on a charge (is there an electric car, a hybrid or an e-bike to check against); the pace from your tracks (the hike half: is the fit in Route times what was meant); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.35-alpha** (ADR 0031): the Map | Horizon switch on the map (its place: trial and error, your words); whether the named peaks are the ones you see, closest first, a hidden close one hollow (the weights are first guesses). Strings: the merge body of T309. From v0.1.34-alpha: the Horizon view's toggles, peak names (tap one), today's sun; Check my GPS: on 2026-11-20 the owner's phone read strong (the suspected fault not seen); the four-best figure and used of in view (or Send diagnostics) would still calibrate the verdict's 25 and 35 dB-Hz; whether "Follow the phone" turns right when held sideways. Strings: the merge bodies of T307 and T308.
- **On the phone, v0.1.32-alpha** (ADR 0031): Settings > About and advanced > Report a problem: Send diagnostics, then Share and Open GitHub issue; Report recent stats (one sent from your phone gives its real start times). Strings: the merge bodies of T301 and T302. **The release key:** T293 found a script started by an agent can read `tools\release.properties` (the deny rule covers the Read tool only); closing it is a permission-settings change, yours.
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285. In Settings the Extended readout row shows Off and Pressure both filled: by design, a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
