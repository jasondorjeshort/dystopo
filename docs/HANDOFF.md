# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-13, at commit 79e5e74, by main session (session 7b335a00)
Resume: this file, then `docs/reference/machines.md` (the owner's account `Dorje` coordinates again:
checkout `src\dystopo`, key and `devtool.local.json` present) and the `wave` skill; rewrite with the
`handoff` skill at the end of every session and before any stop.

## Now

- Wave 13 is on the board (T88 to T92: the wave 12 follow-ups only, owner's word 2026-10-13) and
  nothing is launched: the usage gate denies every launch at 95% weekly. The owner lifts the weekly
  figure for this wave, which the hook only knows through `DYSTOPO_GATE_OVERRIDE=1`: resume with
  `$env:DYSTOPO_GATE_OVERRIDE='1'; claude --resume 7b335a00-fa4a-4d77-a90a-7fa3644cdb09`.
- No servers on this account yet. CI on 1bd7027 failed in the image build on a transient GitHub 500
  for the Gradle distribution (run 38462712750; rerun, T92 fixes). `E:\dystopo-avd` is empty.

## Next

1. Launch wave 13 (`wave` and `brief` skills): a Haiku chore first (rerun run 38462712750, start the
   live viewer on 8765 from the installed distribution, detached, PID in `out/coordinator-pids.txt`),
   then the five Opus implementers in worktrees under `src\dystopo-wt\` with the ports and AVDs in
   the wave 13 paragraph of `docs/TASKS.md`; then a critic (most capable model, `proposal` skill's
   critic brief) on ADRs 0027 and 0028 and Hatchery proposals 0003 and 0005.
2. Verify, merge and log each PR by the merge sequence; then the sweep, then `v0.1.6-alpha`.
3. Wave 14 from the ideas folder, the owner's decided items first (recenter button with the tap rule,
   turn directions, scale bar and miles), and the doctrine ADRs (driving, alpha interface freedom,
   no other product names in user-visible text).
4. Hook follow-up: a file-based owner override for the gate (`~/.claude/usage-override.json`) was
   refused by the permission classifier as self-modification; the owner applies it, then Hatchery.

## Waiting on user

- The gate override for this wave (above).
- Real-phone checks of 0.1.5 over USB (`android-firstrun.py`, `android-ui.py features`), the in-app
  update from 0.1.4, a real walk with GPS on and the screen off; Google developer registration.

## Open findings

- `docs/findings/2026-10-10-emulator-share-direct-connect.md` (T90 updates it this wave).
