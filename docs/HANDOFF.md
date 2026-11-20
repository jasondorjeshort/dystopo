# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-20, by main session (wave 40 closed, v0.1.35-alpha published)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-40.md`, the board's Later in `docs/TASKS.md`, the finding named under Now, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **No wave is open.** Wave 40 (T309, the Map | Horizon switch and the closest peaks first; T310, the sweep's two red parts) is in `docs/tasks/wave-40.md`; **v0.1.35-alpha** is the published release (2026-11-20 18:17 UTC). Every shell call failed from 03:26 that day until the owner's reboot (the `git-guard` hook with no stderr, ripgrep too; cause unknown).
- **Cost:** the 7-day meter read 2 at wave 40's close; reset 2026-11-27 03:00 UTC (re-read `resets_at` at each stall). Figures: 1.5 a task, 1.5 a wave's fixed cost, 1 a release (the `wave` skill, Plan and launch step 3). The hold is 97.
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g` (the sweep's build: env `ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs`, process scope only); long-path folders go by the `\\?\` delete; at most two Gradle agents and two emulators.

## Next

1. The launch crash after the guard's reboot (`docs/ideas/2026-11-19-launch-crash-rootdir-after-the-guards-reboot.md`, three full sweeps out of three; a narrow rerun did not hit it). Also from wave 40: `git-guard` refused an awk line with backticks (a chore's false positive?).
2. The routing work, in the order of the research finding's Drive routing section (`docs/findings/2026-11-16-research-for-pending-ideas.md`): ADR 0063's amendment with the Outcomes of 0062 and 0063; a new block for the speed-profile idea and a new idea for the profile on the phone; the card offers the other route; pack facts (on the owner's yes). Size it at 3.0 a task against the meter.
3. Cheap phone fixes ready as Plans: the first launch that ignores a late fix, the days-old recording, the blue dots, gross gain per source, the pressure unit.
4. Tooling follow-ups (Later): save logcat on a launch crash (wave 37); the sweep step that opens Report a problem; the blank direct-view frame; `sweep-diff.py` on a truncated PNG.
5. Ideas untriaged: six of 2026-11-17, Java 25 of 2026-11-18, and of 2026-11-19 five GNSS ideas (the signal per track point, a compass check, a weak-signal note, a coverage forecast along a route, RINEX) and the launch-crash bug (the owner: "We'll prioritize later").

## Waiting on user

- **Answers in `docs/ideas/`**: two new Questions of 2026-11-16 (the sharp-bend warning, clock time and sun along a route); a Shortest drive (at rest recommended; your words of 2026-11-16 on route options are noted there, the Answer left blank); energy on a charge (is there an electric car, a hybrid or an e-bike to check against); the pace from your tracks (the hike half: is the fit in Route times what was meant); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.35-alpha** (ADR 0031): the Map | Horizon switch on the map (its place: trial and error, your words); whether the named peaks are the ones you see, closest first, a hidden close one hollow (the weights are first guesses). Strings: the merge body of T309. From v0.1.34-alpha: the Horizon view's toggles, peak names (tap one), today's sun; outdoors, Check my GPS, and send the four-best figure and used of in view (or Send diagnostics): the first real-phone figure for calibrating the verdict's 25 and 35 dB-Hz, and the answer to the suspected GPS; whether "Follow the phone" turns right when held sideways. Strings: the merge bodies of T307 and T308.
- **On the phone, v0.1.32-alpha** (ADR 0031): Settings > About and advanced > Report a problem: Send diagnostics, then Share and Open GitHub issue; Report recent stats (one sent from your phone gives its real start times). Strings: the merge bodies of T301 and T302. **The release key:** T293 found a script started by an agent can read `tools\release.properties` (the deny rule covers the Read tool only); closing it is a permission-settings change, yours.
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285. In Settings the Extended readout row shows Off and Pressure both filled: by design, a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
