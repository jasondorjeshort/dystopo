# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit cbf64a5, by main session (second account)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the wave 13 paragraph in `docs/TASKS.md` (second-half ports); rewrite with the
`handoff` skill.

## Now

- Wave 13 second half from `second`; owner: HOLD lifted to 100% for this session (override file until
  Oct 15 12:30 UTC; delete it when the session ends). 7d meter 85% at 13:10 UTC.
- Merged and logged today: T89 (PR #92), T90 (PR #94).
- Running: verifier of PR #93 (T92; `dystopo-wt\T92`, 8730 to 8737); verifier of PR #95 (T91;
  `dystopo-wt\T91`, emulators 5586 and 5588, 8714 to 8716, sweep base 8740); T88 continuation (Opus,
  draft PR #96; `dystopo-wt\T88`, 5560, 8690/8691); T96 implementer (Opus, share fixes from the T90
  verifier; `dystopo-wt\T96`, 5564 and 5566, 8692 to 8695).
- Live viewer 8765 and standalone server 8800 run on this account (`out/coordinator-pids.txt`).

## Next

1. As each report lands: merge sequence and log; a Sonnet verifier for T88 (ports 8717, 8750s,
   console 5590) and T96 when their PRs are ready.
2. After T91 merges: T95 (Opus, 8698, console 5574; `android-online.md` is T88's until PR #96 merges).
3. Doc follow-ups once T92 is in: DYSG v5, `-overlay.idx` and `corruptGraphTiles` in
   `devtool-commands.md`, `SERVER.md`, `live-server.md` (T89's); ADR 0028 Consequences (coordinator).
4. After the last merge: README status, the full sweep from the main checkout (the key is only there;
   confirm the `47:31:38:10` certificate), then `v0.1.6-alpha`; a Haiku chore removes merged local
   branches (`task/T87-ci`, `T89`, `T90`) and worktrees.
5. Wave 14 from the ideas folder (recentre tap rule, turn directions, scale bar, the demo page's trips
   box) and the doctrine ADRs.

## Waiting on user

- Hatchery PR #1; real-phone checks of 0.1.5; Google developer registration.

## Open findings

- none (T90 resolved `2026-10-10-emulator-share-direct-connect.md`).
