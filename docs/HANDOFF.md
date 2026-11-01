# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-01, after the wave 25 close commit, by main session (wave 25 closed, no release)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (no open
wave; Later carries waves 24 and 25's follow-ups; the record `docs/tasks/wave-25.md`), ADR 0052.

## Now

- **Wave 25 is closed** (T199 to T201 merged 2026-11-01 by verifier verdict, no fix round; the close entry in
  `docs/PLANNING.md`'s log). The sweep was green in 1015 s with the device-boot part proving T201's hook: the
  emulator survived the sweep agent's yield for the first time since T190. No release (no app code changed).
  Nothing is running; no worktree, no task branch, no emulator, no lock, no `Dystopo site` task registered. The
  live viewer on 8765 is still up (PID in `out/coordinator-pids.txt`). Meter: 8 on the 7-day at the close.
- **The site is built but not installed.** The owner's five steps are on the board (Owner actions pending › The
  home site): `privateTerms`, the elevated install with `-Host <name> -Firewall`, the 443 forward, the
  dynamic-DNS updater, power settings; then a first open from a phone on mobile data. The host name is a
  private term: never in a tracked file, brief or report.
- **Port 80 on this PC** is held by a java process outside the project; the site's Caddy runs with the HTTP
  redirect off (`docs/reference/home-site.md`). Ask the owner what it is.

## Next

1. The next wave is unplanned. Candidates: the owner's cross-platform idea (`docs/ideas/2026-11-01-one-
   definition-of-controls-and-settings-across-platforms.md`; an inventory and a benchmark survey first, then a
   proposal), the site's follow-ups once it runs (an `-HttpsPort` for coexisting test instances, pre-built public
   packs, rate limits when load or publicity revives ADR 0052's deferral), Later's lifted follow-ups.
2. A Hatchery-rooted session: the pending carry-backs plus T190's two, T201's (`SubagentStop` fields), the sentinel rule.

## Waiting on user

- The home site's five steps (above) and what holds port 80.
- The paging file (12.8 GB on C:, set 32 GB or automatic); install v0.1.17-alpha and the hike checks; the
  `Question:` blocks; ADR 0051's two answers; the git housekeeping row.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
