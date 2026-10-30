# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-30, at commit 4c61312 plus this edit, by main session (wave 22 closed; the alpha waits on the key)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (Later holds wave 22's follow-ups; the record `docs/tasks/wave-22.md` holds its paragraph with the ports and the emulator rule).

## Now

- **Wave 22 is closed** (eighteen tasks, T158 to T175, all merged on 2026-10-30; ADR 0049 and 0050; the three
  triage-scan chores of 2026-10-29 done at ccb5af5). The sweep and its rerun are green on every app part (the
  upgrade part's first capture came back empty twice: the capture tooling, on the board as T175's follow-up).
- **v0.1.15-alpha is tagged (4c61312) and not published.** The workflow's draft is debug-signed (no `RELEASE_*`
  secrets); the local build by the `release` skill's fallback needs `tools/release.keystore` and
  `tools/release.properties` copied into a build worktree, and the permission system refuses that to an agent. The
  owner lifts it one of two ways: set the four secrets (`.github/workflows/release.yml` header), delete the draft and
  re-push the tag; or make the worktree and copy the key by hand (`git worktree add --detach ../dystopo-rel
  v0.1.15-alpha`, then the two files into its `tools/`), after which an agent builds and replaces the draft's assets
  and verifies from the draft (notes drafted at `%TEMP%/dystopo-wave22/release-notes-v0.1.15-alpha.md`, checksums
  to fill). Local pre-rebase branch copies (`task/T169`, `T166`, `T160`, `T172`) are on the housekeeping list.
- The live viewer is up on 8765 (PID 31104, `out/coordinator-pids.txt`); the wave's research and critic review in the
  gitignored `out/wave22-research/`; the emulator rule is in the wave paragraph. Usage: 7-day 3 at launch, 27 at the close.

## Next

1. Publish v0.1.15-alpha once the owner lifts the signing block (above): build, replace the draft's assets, verify
   from the draft (`release` skill steps 3 to 6), the log entry and the README's link already point at the tag.
2. Plan wave 23 from the owner's next theme: the deferred-by-name list in the wave 22 paragraph (sun and shade,
   the viewshed, the drainage toggle, the phone's sheet, the terrain fix's live source) and the Status follow-ups.
3. A Hatchery-rooted session (carry-backs, ADR 0048's included, the ownership idea, ADR 0021's follow-ups, the second
   base update; the ADR for T144's reference counting); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- `docs/TASKS.md` › Owner actions pending: the signing block above; the wave 18 to 22 strings (in the merge commits);
  the decisions (auto-resume toggle, ADR 0040 and 0042, S Pen, Play; the z10 answer decides ADR 0049's C); the git
  housekeeping; the first PC's pack recipe; Android 14 lets users swipe the recording notification away (T159 survives it).

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; the phone next.
