# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-18 (early morning), after commit 0047d5f, by main session (second account)
Resume: this file, `docs/reference/machines.md` › The other account, the `wave` skill, `docs/TASKS.md` › Wave 16 (critic pass done, ports in the paragraph); rewrite with the `handoff` skill.

## Now

- Wave 16 launched 2026-10-18 after the usage window reset: T114 (route to here, the owner's lead
  item), T115, T116, T117 and T118, five Opus-class implementers in worktrees `../dystopo-wt/<task>`
  on branches `task/<task>`; no PR yet. The critic pass (0 blocking, 7 serious, 6 minor) is folded
  into the board; its review stayed in the session scratchpad.
- Before the launch, on `main`: the hook harness green under the updated hooks, the dead commit hashes
  in `docs/`, `CHANGELOG.md` and `README.md` re-recorded through the rewrite kit's maps (fc3dab5).
- The live viewer on 8765 and the standalone server on 8800 run from the main checkout (started
  2026-10-18; PIDs in `out/coordinator-pids.txt`).
- The owner's framing for this wave: T114 first, "minimal otherwise"; each entry names what it defers.

## Next

1. As each PR arrives: a verifier (Sonnet-class, `brief` skill, `verifier.md`; ports 8790 to 8799,
   consoles 5660 to 5670). T114's PR is verified and merged first; the others may wait a turn.
2. Merge by the `wave` skill's sequence (privacy scan of the PR text, squash, confirm from the remote,
   `merge-log.py`, cleanup chore per worktree); then the post-wave sweep (`sweep` skill) and
   v0.1.9-alpha (`release` skill).
3. After the merges: run the `triage` skill over the converted ideas folder (T118); check T117's
   Hatchery draft PR exists from the remote (`gh pr view`).
4. The deferred items in the T114 to T116 entries and the PRs' follow-up lists seed wave 17.

## Waiting on user

- T114's strings, card and From/To line when its PR arrives (ADR 0031: built, then adjusted).
- Real-phone checks of 0.1.8 (`android-firstrun.py`, `features`); Google developer registration;
  MapLibre's own compass (T100); T104's steepness palette and captions; the wave 14 and 15 strings
  (in each PR); the 5 s timeout grace (T111).

## Open findings

- `docs/findings/2026-10-16-cold-view-gap-on-a-quiet-device.md` (T115)
- `docs/findings/2026-10-17-route-to-flows-in-other-apps.md` (T114 resolves it)
