# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-19, by main session (wave 38 open: T307 implementing)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-37.md`, the board's Later in `docs/TASKS.md`, the finding named under Now, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 38 is open** (`docs/TASKS.md`, one task): T307, the GPS view (satellites, the terrain's horizon, "Check my GPS"), critic's edits made, implementer launched 2026-11-19 on `task/T307` (worktree `dystopo-wt/T307`, AVD `dystopo35-T307`, ports 8694/5570). Then a verifier, the merge, a sweep and a release; the owner: stop at the hold and wait out the reset. Wave 37 is recorded in `docs/tasks/wave-37.md`. **v0.1.32-alpha** is the published release; `out/sweep-20261116-2212` stays the next release's `-Prev`.
- **Cost:** the 7-day meter read 90 at T307's launch (89 at wave 37's close); the per-task figure stays 3.0, a sweep about 2 to 3, a release 2.5. The hold is 97; the 7-day reset is 2026-11-20 04:00 UTC (`resets_at` in the session's usage file).
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g` (the sweep's build: env `ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs`, process scope only); long-path folders go by the `\\?\` delete; at most two Gradle agents and two emulators.

## Next

1. The routing work, in the order of the research finding's Drive routing section (`docs/findings/2026-11-16-research-for-pending-ideas.md`): ADR 0063's amendment with the Outcomes of 0062 and 0063; a new block for the speed-profile idea and a new idea for the profile on the phone; the card offers the other route; pack facts (on the owner's yes). Size it at 3.0 a task against the meter.
2. Cheap phone fixes ready as Plans: the first launch that ignores a late fix, the days-old recording, the blue dots, gross gain per source, the pressure unit.
3. Tooling follow-ups (Later): save logcat on a launch crash (wave 37); the sweep step that opens Report a problem; the blank direct-view frame; `sweep-diff.py` on a truncated PNG.
4. Ideas untriaged: six of 2026-11-17 and Java 25 of 2026-11-18 (the owner: "We'll prioritize later").

## Waiting on user

- **Answers in `docs/ideas/`**: two new Questions of 2026-11-16 (the sharp-bend warning, clock time and sun along a route); a Shortest drive (at rest recommended; your words of 2026-11-16 on route options are noted there, the Answer left blank); energy on a charge (is there an electric car, a hybrid or an e-bike to check against); the pace from your tracks (the hike half: is the fit in Route times what was meant); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.32-alpha** (ADR 0031): Settings > About and advanced > Report a problem: Send diagnostics, then Share and Open GitHub issue; Report recent stats (one sent from your phone gives its real start times). Strings: the merge bodies of T301 and T302. **The release key:** T293 found a script started by an agent can read `tools\release.properties` (the deny rule covers the Read tool only); closing it is a permission-settings change, yours.
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285. In Settings the Extended readout row shows Off and Pressure both filled: by design, a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
