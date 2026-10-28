# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-28, at commit 965910c plus this edit, by main session (wave 21: eight of nine merged, v0.1.14-alpha tagged)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 21.

## Now

- Wave 21 is on the board past the critic: nine tasks, launch order T149, T150, T152, T151, T156, T153, T157, T154,
  T155 (T154 and T155 may take any slot). **One agent at a time** (owner, 2026-10-27): implementer, verifier, merge,
  next task. Merged and logged, branches and worktrees cleaned: T149 (dbe6e69), T150 (98c9a9d), T152 (3f0af48),
  T151 (213bb38), T156 (5412c7b), T153 (2d94d75), T155 (ad31b31), T154 (9830d8d). The post-wave sweep (T154's,
  `out/sweep-w21/`) was green but `notices`, fixed on its branch. **Release in progress:** the CHANGELOG cut and
  README status are on main (965910c); tag `v0.1.14-alpha` is pushed and the release workflow is building the
  draft; the notes are drafted at `%TEMP%/dystopo-wave21/release-notes-v0.1.14.md` with the checksums block to
  fill from the draft's `SHA256SUMS.txt`. Merge drafts: `%TEMP%/dystopo-wave21/`; research: `out/wave21-research/`.
- Usage gate: the weekly hold is 100 (`policy.json`); the 7-day meter read 97% at the tag and resets 2026-10-30
  about 04:00 UTC. T157 (the middle tier) is the one task left; it opens wave 22 unless the meter holds.
- Machine: 3 GB emulators, at most three, Gradle `--no-daemon`, the wide Rainier DEM pack (`machines.md`), the live
  viewer on 8765 (`out/coordinator-pids.txt`); the owner's phone is on wireless adb, so agents address only their
  emulator by serial; no `devtool.local.json` on this PC.

## Next

1. Finish the release (`release` skill, steps 3 to 7): when the workflow run completes, a Sonnet agent downloads
   the universal APK from the draft, checks certificate, version and `SHA256SUMS.txt`, runs `android-ui.py upgrade`,
   `features` and `strings` on a fresh emulator; then the coordinator fills the checksums, scans the notes,
   `gh release edit v0.1.14-alpha --notes-file ... --prerelease --draft=false`, confirms with `gh release view`,
   logs the release in PLANNING and rewrites this file. Then T157's implementer if the meter holds.
2. A Hatchery-rooted session (carry-backs, the ownership idea, ADR 0021's follow-ups, the second base update; the ADR for T144's reference counting); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- The owner's list is `docs/TASKS.md` › Owner actions pending (rewritten 2026-10-27): the phone checks after the
  next alpha (CPU after HOME, no new memory reports, issues 23 to 29), the wave 18 to 21 strings, the decisions
  (auto-resume toggle, ADR 0040 and 0042, S Pen, Play), the git housekeeping, the `RELEASE_*` secrets, the first
  PC's pack recipe. Carried from older waves: T100, T104.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; the phone next.
