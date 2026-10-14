# Findings

Results of investigations: a root cause, a measurement, an approach that did not work and why.
Findings are evidence, not truth: read the `status` before trusting one. Files are named
`YYYY-MM-DD-<slug>.md`, carry frontmatter (`date`, `status: open | resolved | superseded`, `area`,
optional `supersedes`, `promoted_to`), and have the sections Question, What was tried, Result, Next.
Written with the `finding` skill. When a finding is resolved, its durable part goes to
`../reference/` or an ADR and `promoted_to` says where. `../HANDOFF.md` links the open ones.

Measurements of a merged task stay in its dated log entry in `../PLANNING.md`; a finding is for what
should not be investigated twice.

| File | Status | Area | One line |
|---|---|---|---|
| `2026-10-09-junction-into-main-checkout.md` | resolved | process / worktrees | A junction from a worktree into the main checkout wiped the main `out/` when the worktree was removed. |
| `2026-10-09-branch-deleted-before-merge.md` | resolved | process / merge | Deleting a branch after a failed merge closed the PR; git-guard now checks the merge first. |
| `2026-10-10-kill-by-command-line-substring.md` | resolved | process / parallel agents | Stopping processes by substring killed every agent's emulator and the live server; stop by PID or serial. |
| `2026-10-10-emulator-share-direct-connect.md` | resolved | android / emulator networking | Direct connects work when both emulators have Wi-Fi; replies cut through the host were the sharer's early close (T90). |
| `2026-10-10-emulator-software-gpu-draws-no-text.md` | resolved | android / emulator rendering | Under `swiftshader_indirect` MapLibre draws no text or dashes; use `-gpu host`. |
| `2026-10-10-sqldelight-verify-empty-environment.md` | resolved | build / SQLDelight | SQLDelight's own migration check cannot run on Windows here; our own migration test replaces it. |
| `2026-10-11-android-location-listener-lambda.md` | resolved | android / location | A `LocationListener` lambda crashes Android 8 to 10; use the helper, a test forbids lambdas. |
| `2026-10-11-actions-billing-stopped-release.md` | resolved | release / Actions | Actions refused the release job while private; built locally, Actions runs again since public. |
| `2026-10-11-reported-push-that-never-happened.md` | resolved | process / verification | A `cd` inside a Bash call did not take effect; prove a push from the remote, use `git -C`. |
| `2026-10-12-windows-long-path-delete.md` | resolved | tooling / Windows | Long-path deletes need the `\\?\` prefix; the `CLAUDE.md` line had lost a backslash. |
| `2026-10-12-ci-glyph-download.md` | resolved | CI | CI downloaded every glyph range on each run; T87 caches them and retries. |
| `2026-10-12-hawaii-z9-contours-500.md` | resolved | server / contours | Two Hawaii z9 contour tiles answered 500: cells with fewer overviews; T87 picks a common level. |
| `2026-10-13-inherited-pipe-handles.md` | resolved | tooling / Windows processes | Device scripts kept the caller waiting: a daemon they started inherited its pipe; start such processes detached. |
| `2026-10-14-z8-contour-lines-missing-in-sweep-tour.md` | open | android / contour tiles over a DEM pack | The sweep's z8 tour view draws no contour lines again after T88; tiles generated, the rerun's in 1 ms (T98). |
| `2026-10-14-sweep-update-part-blocked-by-previous-check.md` | open | tooling / sweep, in-app update | The update part stops at `lower-quiet`: the previous build's own check leaves `lastCheckAt`, so the new build's check is not due (T99). |
