---
date: 2026-10-11
status: resolved
area: process / verification
promoted_to: CLAUDE.md (report outcomes from the remote), docs/reference/windows-and-shells.md
---

# A push to another repository was reported done; the commit had landed in this one

## Question

The coordinator reported a Hatchery push that the owner then could not find. What happened?

## What was tried

A Bash call did `cd` into the Hatchery clone and then committed and pushed. The harness's working
directory did not change, so the commit landed in this repository (commit `44b2d0f`, whose message is
about Hatchery, contains this repository's handoff, planning and board edits), and the chained command
printed success.

## Result

Rules since 2026-10-11: report a push, merge, tag or release only after an independent read of the
remote (`bash tools/push-verified.sh`, `gh pr view --json mergedAt`, `gh release view`, the artifact
downloaded from the draft); a command meant for another repository uses `git -C <path>` and prints
`git rev-parse --show-toplevel` first. The same incident is recorded in Hatchery
(`docs/findings/2026-10-11-reported-push-that-never-happened.md` there) and in the
`docs/PLANNING.md` log entry "process base and what differs (Hatchery)".
