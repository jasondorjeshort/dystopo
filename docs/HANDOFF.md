# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit 45cb98a, by main session (second account)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the wave 13 paragraph in `docs/TASKS.md` (second-half ports); rewrite with the
`handoff` skill.

## Now

- Wave 13 second half from `second`; owner: HOLD lifted to 100% for this session (override file until
  Oct 15 12:30 UTC; delete it when the session ends).
- Merged and logged today: T89 (#92), T90 (#94), T92 (#93), T91 (#95), T97 (#97); ADR 0029.
- Running: T88 continuation (Opus, draft PR #96; `dystopo-wt\T88`, 5560, 8690/8691); T95
  implementer (Opus; `dystopo-wt\T95`, 5574, 8698, servers 8775/8776); verifier of PR #98 (T96;
  `dystopo-wt\T96`, emulators 5586 and 5588, 8714 to 8717).
- Live viewer 8765 and standalone server 8800 run on this account (`out/coordinator-pids.txt`); they
  run a pre-T89 build until restarted.

## Next

1. As each report lands: merge sequence and log; Sonnet verifiers for T88 and T95 (free ports
   8710/8711 with console 5580, 8718/8719 with console 5590).
2. After T95 merges: an ADR superseding ADR 0027 in part (pinned tiles keep their manifest's
   version; coordinator, `adr` skill).
3. After the last merge: README status; restart the 8765 viewer on the new build (Haiku chore);
   the full sweep from the main checkout (key only there; confirm the `47:31:38:10` certificate),
   then `v0.1.6-alpha`; a Haiku chore removes merged local branches and worktrees.
4. Owner review before the release: new strings from T95 and T96 (listed in their PRs).
5. Wave 14 from the ideas folder and the follow-ups in the T89 to T97 Status lines.

## Waiting on user

- Hatchery PR #1; real-phone checks of 0.1.5; Google developer registration.

## Open findings

- none
