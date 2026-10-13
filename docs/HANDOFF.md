# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-13, at commit 0b3247a, by main session
Resume: this file, then `docs/reference/machines.md` (paths, ports, servers; this is the owner's
second account: no release key, no `devtool.local.json`) and the `wave` skill; rewrite it with
the `handoff` skill at the end of every session and before any stop.

## Now

- Nothing in flight. Wave 12 is closed: T73, T74, T75, T77, T78, T84, T85, T86 and the sweep
  task T87 are merged and logged; `v0.1.5-alpha` is published (2026-10-13 05:30 UTC, built by
  the workflow, verified from the draft: certificate, hashes, upgrade from 0.1.4, features).
- Servers on this account: live viewer 8765 (installed distribution, no Gradle daemon) and the
  standalone server 8800 (no token, LAN 192.168.12.186); PIDs in `out/coordinator-pids.txt`.
- Hatchery is two commits ahead of its origin (0ce6670, 3e55be3), unpushed by its rule.

## Next

1. Next wave: plan from the status lines of T73 to T87 (follow-ups) and `docs/findings/`; the
   usage gate and the `wave` skill apply. Candidates: the 15 s cold-view target, peers kept
   across restarts and the slow-path cut, a second overlay level, critic passes on ADRs 0027/0028.
2. Push the CI `cache/glyphs` step (local branch `task/T87-ci`, patch in the session scratchpad)
   and the stashed `release.yml` line 9 pointer once the token has the `workflow` scope.
3. Real-phone checks of 0.1.5 over USB (`android-firstrun.py`, `android-ui.py features`), and
   the in-app update from 0.1.4 on the owner's phone.
4. Remove the locked empty `..\dystopo-wt\T85` folder when its holder exits.
5. Critic pass on Hatchery proposals 0003 and 0005; `server-soak.ps1` exits 1 on a passing run.

## Waiting on user

- `gh auth refresh -h github.com -s workflow` on this account; the word to push Hatchery `main`;
  copying `devtool.local.json` here; whether the two second-account commits on main get rewritten.
- Google developer registration (publisher verification) before many people install.
- A real walk with GPS on and the screen off on mobile data, on the owner's phone.

## Open findings

- `docs/findings/2026-10-10-emulator-share-direct-connect.md` (T78 on real phones; the slow-path cut too).
- `docs/findings/2026-10-13-inherited-pipe-handles.md` (resolved in T87; watch for other scripts).
