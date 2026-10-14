# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit 74d03d6, by main session (second account)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, no release key, no `devtool.local.json`), the `wave` skill, and
the wave 13 paragraph in `docs/TASKS.md` (second-half ports); rewrite with the `handoff` skill.

## Now

- Wave 13 second half, launched 2026-10-14 12:40 UTC from `second`; owner: HOLD lifted to 100% for this
  session (override file until Oct 15 12:30 UTC; delete it when the session ends). 7d meter 82% at launch.
- Running: verifier of PR #92 (T89; `dystopo-wt\T89-verify`, emulator 5580, 8710/8711); verifier of
  PR #94 (T90; `T90-verify`, emulators 5582 and 5584, 8712 to 8715); T92 finish (soak write-up, PR
  #93; `dystopo-wt\T92`, 8720 to 8727; the soak ran 6.34 h, data in `E:\dystopo-soak\T92`); T88
  continuation (Opus, PR #96 draft; `dystopo-wt\T88`, 5560, 8690/8691); T91 finish (PR #95 draft;
  `dystopo-wt\T91`, 5568 and 5572, 8696/8697).
- Live viewer 8765 and standalone server 8800 run on this account (`out/coordinator-pids.txt`).

## Next

1. As each report lands: a Sonnet verifier per PR (T92 on 8730 to 8737; T88 and T91 on 8714 to 8717,
   consoles 5586 to 5590), then the merge sequence and the log, T89 before T91 (`trips/` lines).
2. After T90 and T91 merge: T95 (Opus, 8698, console 5574).
3. Coordinator docs after merges: ADR 0028 Consequences (T89's z8 level, cell files, order test);
   `android-emulator.md` line 28 (T90 follow-up); README status line.
4. After the last merge: the sweep with `-Skip upgrade,update` (no key here), then `v0.1.6-alpha`
   through Actions (the draft's install check covers the upgrade).
5. Wave 14 from the ideas folder (recentre tap rule, turn directions, scale bar) and the doctrine ADRs.

## Waiting on user

- Sign-off on what people see: T91's long-press card (Waypoint here / Route from here / Cancel instead
  of starting a route at once); T92's new demo-page box "Trips on the server and waypoints".
- Hatchery PR #1; real-phone checks of 0.1.5; Google developer registration.

## Open findings

- `docs/findings/2026-10-10-emulator-share-direct-connect.md` (T90's PR resolves it).
