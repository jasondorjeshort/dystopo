# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-12 (night), by main session

## Now

- Wave 12 second half, from the owner's second account on the development machine (paths in the
  second-half paragraph of `docs/TASKS.md`). Batch A: T73 (PR #79), T74 (#80) and T75 (#81) merged
  and logged on 2026-10-12; T85 (#82, rebased onto T74) is with a Sonnet verifier on
  emulator-5616 (its first verifier was cut off by a usage limit). Batch B: T77 and T78
  implementers launched the same evening; T84 waits for #82 because both edit `CLAUDE.md`.
- Running on this account: the live server on 8765 and the standalone server on 8800 (no token,
  LAN at 192.168.12.186), PIDs in `out/coordinator-pids.txt`. GitHub Actions runs again (CI green
  on main at the T74 merge).
- Hatchery has the delegation rule (commit 0ce6670, unpushed: a push there needs the owner's word).

## Next

1. Merge #82 on a MERGE verdict, then launch T84 (Hatchery alignment; it restructures CLAUDE.md,
   so it goes after every other CLAUDE.md edit); verify and merge T77 and T78 as their PRs arrive.
   Critic pass on Hatchery proposal 0003 in parallel.
2. Sweep, then `v0.1.5-alpha` (the first update the 0.1.4 updater will offer).
3. Make `tools/server-soak.ps1` print the reason for a non-zero exit (it exited 1 on a passing run).

## Waiting on user

- Google developer registration (publisher verification) before many people install; see
  `docs/TASKS.md` owner actions.
- A real walk with GPS on and the screen off on mobile data, on the owner's phone.

## Open findings

- Hatchery proposal 0003 (wave workflow) awaits a critic pass; see `../hatchery/docs/HANDOFF.md`.
