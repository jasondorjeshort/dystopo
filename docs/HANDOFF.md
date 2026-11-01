# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-01, at commit b82b7b41 plus this edit, by main session (wave 25 launched)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` (the open wave 25 and its
paragraph; Later carries wave 24's follow-ups), ADR 0052.

## Now

- **Wave 25 is launched** (planned and critic-reviewed 2026-11-01, b82b7b41): the owner's short diversion to get
  the web site running from this PC under a dynamic-DNS name. Three implementers are out: T199 (Opus, the site as
  Windows scheduled tasks, test instance on 8691), T200 (Opus, the page opens at the browser's location and fits a
  phone, `serve --live` on 8690), T201 (Sonnet, the stop hook and the sweep's small defects). Worktrees under
  `~/Documents/src/dystopo-wt/T199..T201`; scratch under `%TEMP%/claude-wave25/`. Verifier ports: 8693 (T199), 8692
  (T200). No emulator in the wave; no release at the close (no app change); the sweep skips device parts but `device-boot`.
- **The host name is private** (a host name under `.claude/rules/docs.md`): it never enters a tracked file, a
  brief or a report; the owner adds it to `privateTerms` before T199 is pushed (Owner actions pending). The live
  viewer on 8765 is still up (PID in `out/coordinator-pids.txt`). Meter: 0 on both windows at planning.
## Next

1. As each implementer reports: a Sonnet verifier (`brief` skill); T199's or T200's verifier, whichever is second,
   runs the Interacts-with scenario (install a test instance from `main`, run T200's geolocation check against it).
   Merge by the `wave` skill's sequence; at T201's merge strike the "Wave 24 sweep findings" Later line.
2. Close: the sweep, no release, handoff, the chore's move of the wave into its record under `docs/tasks/`. Then
   the owner installs the real instance (T199's script with the default root and one elevated run) and the first
   outside check is the owner's phone on mobile data.
3. After the wave: the owner's cross-platform idea (`docs/ideas/2026-11-01-one-definition-of-controls-and-settings-
   across-platforms.md`) is a proposal when the owner wants it; the Hatchery carry-backs; ADR 0040/0042/0049's C.

## Waiting on user

- **Before T199 pushes:** the host name into `privateTerms` in `devtool.local.json`. Then router forwards for 80 and
  443, the dynamic-DNS updater, power settings (no sleep), the elevated install command T199's report names.
- The paging file (12.8 GB on C:, set 32 GB or automatic); install v0.1.17-alpha and the hike checks; the `Question:`
  blocks; ADR 0051's two answers; the git housekeeping row.

## Open findings

- `docs/findings/2026-11-01-stop-hook-kills-the-emulator-of-an-agent-that-yields-its-turn.md` (T201 resolves it).
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
