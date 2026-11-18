---
date: 2026-11-18
kind: bug
related: [2026-11-18-pusher-follows-a-nested-repos-own-rules.md]
---

# The push scan ignores repository-locating variables on the push line

Anchor: `.claude/hooks/git-guard.js`, `gitContext` (`REPO_ENV`, `REPO_LOCATE`) and rule 20.

Rule 20 hands the scan's git only `GIT_DIR` and `GIT_WORK_TREE` from the push line. A line that
also sets `GIT_COMMON_DIR` (or `GIT_OBJECT_DIRECTORY`, `GIT_INDEX_FILE` and the rest of
`REPO_LOCATE`) makes the real push send one repository's refs while the scan reads another's
commits: `GIT_COMMON_DIR=<repo>/.git git -C <nested> push` is scanned against the nested
repository, so a hit in `<repo>`'s outgoing commits passes when the nested one is clean. Found while
landing the nested-repository privacy change (harness case PV48q, whose hit is the nested commit);
that change only stops such a line from borrowing a nested allow list. The gap predates it.

Two fixes to weigh: pass the `REPO_LOCATE` variables through to the scan's git, or deny a push line
that sets any of them (no workflow here needs one). The second is smaller and fails closed.
The deny needs the same detection `REPO_ENV_WORD` gives for `GIT_DIR`: a prefix or an export alone
misses `set -a`, `read` and `[Environment]::SetEnvironmentVariable`, which already make rule 20 refuse.
