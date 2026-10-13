# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-13, at commit 66418ac, by main session
Resume: this file, then `docs/reference/machines.md` (paths, ports, servers; this is the owner's
second account: no release key, no `devtool.local.json`) and the `wave` skill; rewrite it with
the `handoff` skill at the end of every session and before any stop.

## Now

- Wave 12 is merged: T73, T74, T75, T77, T78, T84, T85, T86 (PRs #79 to #86), logged on
  2026-10-12 and 13. The sweep on 799d1a9 was green except the z8 contour miss (tour 9/10 twice),
  a stale local pack (drive route) and the two parts that need the release key (upgrade, update).
- T87 (sweep leftovers, the z8 miss first): Opus implementer in `..\dystopo-wt\T87`,
  emulator-5642, port 8772. A Haiku chore rebuilds `out/rainier-outdoors.pmtiles` (profile 2) and
  restarts the servers on 8765 and 8800 (PIDs in `out/coordinator-pids.txt`).
- Hatchery is two commits ahead of its origin (0ce6670, 3e55be3), unpushed by its rule.

## Next

1. Verify (Sonnet) and merge T87; device tour 10/10 with no server is the bar.
2. Tag `v0.1.5-alpha`; `release.yml` builds it (no key here); an agent verifies the draft APK
   (certificate `47:31:38:10...`, upgrade from 0.1.4 on an emulator); publish (`release` skill).
3. Push the stashed `release.yml` line 9 pointer once the token has the `workflow` scope.
4. Remove the locked `..\dystopo-wt\T84-verify` and `T85` folders once their Gradle daemon exits
   (`Remove-Item -LiteralPath ('\\?\' + path) -Recurse -Force`, `git worktree prune`); `sweep` too.
5. Critic pass on Hatchery proposals 0003 and 0005 and ADRs 0027 and 0028; `server-soak.ps1`
   exits 1 on a passing run (print the reason).

## Waiting on user

- `gh auth refresh -h github.com -s workflow` on this account; the word to push Hatchery `main`;
  copying `devtool.local.json` here.
- Google developer registration (publisher verification) before many people install.
- A real walk with GPS on and the screen off on mobile data, on the owner's phone.

## Open findings

- `docs/findings/2026-10-12-ci-glyph-download.md`, `2026-10-12-hawaii-z9-contours-500.md` (T87).
- `docs/findings/2026-10-10-emulator-share-direct-connect.md` (T78 on real phones; the slow-path cut too).
