# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-16 16:25 UTC, at the commit after 2165eb32, by main session (wave 35 launched)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-34.md`, the board's Later in `docs/TASKS.md`, the finding named under Now, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 35 is open** (`docs/TASKS.md`, T292 to T298, the build-process cleanup; planned and past a critic 2026-11-16, commit 2165eb32). Running since about 16:20 UTC: implementers T292 (tools lane, emulator `dystopo35-T292` port 5560), T294 (capture lane, `dystopo35-T294` port 5564), T296, T297, T298 (process lane, no emulator, no Gradle). Waiting: T293 after T292's merge; T295 after T294's and T292's. T298 merges last; its live hook check is the coordinator's after the merge. Merge sequence: the `wave` skill.
- **The review of every pending idea** (2026-11-16): research in `docs/findings/2026-11-16-research-for-pending-ideas.md`; five ideas triaged that day. No idea was found already built.
- v0.1.31-alpha is published (tag on bfd6121d); its sweep `out/sweep-20261115-2123` is the next close's `-Prev`. The owner: this wave "might not even lead to a release".
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; an out-of-memory Kotlin compile clears with `"-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\?\` delete.

## Next

1. Verify and merge wave 35 as each implementer reports; launch T293 and T295 when their gates merge; then the coordinator's own edits the wave paragraph lists, the close, a sweep if anything Gradle builds changed.
2. Then the routing work, in the order of the research finding's Drive routing section: ADR 0063's amendment with the Outcomes of 0062 and 0063; a new block for the speed-profile idea and a new idea for the profile on the phone; the card offers the other route; pack facts (on the owner's yes).
3. Cheap phone fixes ready as Plans: the first launch that ignores a late fix, the days-old recording, the blue dots, gross gain per source, Report recent stats (it reads ADR 0060's due move prediction), Send diagnostics, the pressure unit.

## Waiting on user

- **Answers in `docs/ideas/`**: two new Questions of 2026-11-16 (the sharp-bend warning, clock time and sun along a route); a Shortest drive (at rest recommended; your words of 2026-11-16 on route options are noted there, the Answer left blank); energy on a charge (is there an electric car, a hybrid or an e-bike to check against); the pace from your tracks (the hike half: is the fit in Route times what was meant); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285. In Settings the Extended readout row shows Off and Pressure both filled: by design, a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
