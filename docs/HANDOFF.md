# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-17 06:30 UTC, at the commit after fb68d247, by main session (wave 35 closed, v0.1.32-alpha published)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-35.md`, the board's Later in `docs/TASKS.md`, the finding named under Now, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **No wave is open.** Wave 35 (T292 to T302, the development-process cleanup and four debugging tasks) is closed and recorded in `docs/tasks/wave-35.md`; its follow-ups are in the board's Later. **v0.1.32-alpha is published** (tag on 19662337, versionCode 1797; Send diagnostics ends in Share and Open GitHub issue; Report recent stats). Its sweep `out/sweep-20261116-2212` is the next close's `-Prev` (its frames carry sidecars, so the next diff can leave out by area).
- The closing sweep was red on `startup` alone on a loaded machine, green rerun alone; an A/B run put main about 5 % over v0.1.31-alpha in map-drawn time, 6,270 to 6,611 ms against a 6,680 ceiling: measure again at the next sweep (Later).
- **Cost:** the 7-day meter read 85 at the close (47 at planning); the `wave` skill's per-task figure is now 3.0 points, and `python -I tools/agent-cost.py <Tnn>` gives each task's real token sum. The hold is 97, so the next wave must fit the remaining points or wait for the 7-day reset (`resets_at` in the session's usage file).
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g` (the sweep's build: env `ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs`, process scope only); long-path folders go by the `\\?\` delete; at most two Gradle agents and two emulators.

## Next

1. The routing work, in the order of the research finding's Drive routing section (`docs/findings/2026-11-16-research-for-pending-ideas.md`): ADR 0063's amendment with the Outcomes of 0062 and 0063; a new block for the speed-profile idea and a new idea for the profile on the phone; the card offers the other route; pack facts (on the owner's yes). Size it at 3.0 a task against the meter.
2. Cheap phone fixes ready as Plans: the first launch that ignores a late fix, the days-old recording, the blue dots, gross gain per source, the pressure unit.
3. Tooling follow-ups from wave 35 (Later): the sweep step that opens Report a problem; the blank direct-view frame; `sweep-diff.py` on a truncated PNG.

## Waiting on user

- **Answers in `docs/ideas/`**: two new Questions of 2026-11-16 (the sharp-bend warning, clock time and sun along a route); a Shortest drive (at rest recommended; your words of 2026-11-16 on route options are noted there, the Answer left blank); energy on a charge (is there an electric car, a hybrid or an e-bike to check against); the pace from your tracks (the hike half: is the fit in Route times what was meant); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.32-alpha** (ADR 0031): Settings > About and advanced > Report a problem: Send diagnostics, then Share and Open GitHub issue; Report recent stats (one sent from your phone gives its real start times). Strings: the merge bodies of T301 and T302. **The release key:** T293 found a script started by an agent can read `tools\release.properties` (the deny rule covers the Read tool only); closing it is a permission-settings change, yours.
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285. In Settings the Extended readout row shows Off and Pressure both filled: by design, a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
