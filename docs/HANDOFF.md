# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-16 22:40 UTC, at the commit after 350a35dc, by main session (wave 35: seven merged, the 5-hour gate holding)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-34.md`, the board's Later in `docs/TASKS.md`, the finding named under Now, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 35 is open** (`docs/TASKS.md`, T292 to T302, the development-process cleanup and four simple debugging tasks the owner added). **Merged (7 of 11):** T292, T294, T296, T297, T298, T301, T302 (log entries and Status lines written). **Running:** T295's implementer (capture lane, emulator `dystopo35-T295` on 5560). **Not launched:** T293 and T299 (tools lane, in that order), T300 (capture lane, after T295). **The 5-hour gate holds** (52 % at 22:39 UTC; resets 23:30 UTC, 18:30 Eastern): no agent launch, verifiers and chores included, until then or the owner's grant.
- Owed by the coordinator: a cleanup chore for T292, T301, T302 (branches, worktrees) and the folders left by long paths (`dystopo-wt/T294`, `T301-verify`, `T302-verify`: the `\\?\` delete); the wave paragraph's coordinator edits still open: the release skill's lines T293 reports, the `release-realism.md` lines T295 reports, a line for `tools/release-notes.py` in `release-realism.md` (T297's follow-up), the Later lines T299 and T300 strike.
- New flakes to watch at the close's sweep: `features`' Settings > Sources test failing to reach a LAN server in a `-Only features` run (T292's verifier); the device tour's offline world view at z3 not drawn once (T302's verifier).
- **The review of every pending idea** (2026-11-16): `docs/findings/2026-11-16-research-for-pending-ideas.md`. v0.1.31-alpha is the last release; its sweep `out/sweep-20261115-2123` is the next close's `-Prev`. The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; long-path folders go by the `\\?\` delete.

## Next

1. After the reset: the cleanup chore; T293 (tools lane) on a free emulator slot; T295's verifier when it reports; then T299 after T293 and T300 after T295. The close: a sweep (the app changed: T301, T302), and a release if it is green (the owner: this wave "might not even lead to a release"; T301 and T302 are user-visible, so one is likely).
2. Then the routing work, in the order of the research finding's Drive routing section: ADR 0063's amendment with the Outcomes of 0062 and 0063; a new block for the speed-profile idea and a new idea for the profile on the phone; the card offers the other route; pack facts (on the owner's yes).
3. Cheap phone fixes ready as Plans: the first launch that ignores a late fix, the days-old recording, the blue dots, gross gain per source, the pressure unit.

## Waiting on user

- **Answers in `docs/ideas/`**: two new Questions of 2026-11-16 (the sharp-bend warning, clock time and sun along a route); a Shortest drive (at rest recommended; your words of 2026-11-16 on route options are noted there, the Answer left blank); energy on a charge (is there an electric car, a hybrid or an e-bike to check against); the pace from your tracks (the hike half: is the fit in Route times what was meant); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285. In Settings the Extended readout row shows Off and Pressure both filled: by design, a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
