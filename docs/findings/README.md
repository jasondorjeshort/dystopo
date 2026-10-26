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
| `2026-10-14-z8-contour-lines-missing-in-sweep-tour.md` | resolved | android / contour tiles over a DEM pack | The sweep's z8 view had a server set but unreachable (the debug default, the stopped viewer); tiles over a pack now read the pack's first zoom (T98). |
| `2026-10-14-sweep-update-part-blocked-by-previous-check.md` | resolved | tooling / sweep, in-app update | The update part stopped at `lower-quiet`: the previous build's own check left `lastCheckAt`, so the new build's check was not due; confirmed by logcat, fixed in T99 (check times keyed by versionCode, both builds on the mock). |
| `2026-10-14-debug-test-compile-loses-internal-after-cache-hit.md` | resolved | build / Gradle build cache, Kotlin module files | A cached `compileDebugKotlin` entry carried a module file naming two file facades, so the unit-test compile found none of main's other top-level functions; androidApp's main compiles are not cached since T105. |
| `2026-10-16-soak-route-requests-without-status.md` | resolved | server / soak, route planning | The 11.4 h soak was flat (heap, working set, threads, handles) with 0 failures; the hourly `/route` request with no status (60.9 s) was the probe's own short body, closed unanswered by the JDK listener; T111 answers it 408 and the soak's verdict counts unanswered requests per route. |
| `2026-10-16-cold-view-gap-on-a-quiet-device.md` | resolved | android / first run, direct sources | The 36 s "gap" in a 62 s cold z8 view was the tour's own quiet wait; a stalled OpenFreeMap fetch (30 s a try, two tries) reproduces the slow, label-less view; the timeline now marks those fetches (T115). |
| `2026-10-17-route-to-flows-in-other-apps.md` | resolved | android / routing / interface | Every road navigation app routes from the phone's position ("Your location") to a picked point ("Directions", "Route to") in nearly the same shape and navigates only from the fix; the outdoor apps are the exception (a straight line to a point); Dystopo does the standard thing, "Route to here" first on the long-press card (built by T114). |
| `2026-10-21-direct-view-out-of-memory-after-a-tour.md` | resolved | android / direct sources, memory | An unvisited direct Mont Blanc z9 view right after a full tour crashed the app out of memory on the 192 MB heap; T127: ten renders at once and the geoid memo and pyramids a tour leaves; renders admitted by the heap (three at once), caches trimmed on `onTrimMemory`: cold z8 to z12 views peak at 91 to 166 MB, slower at z8 to z11. |
| `2026-10-23-direct-step-live-heap-unread-with-a-server.md` | resolved | android / device tour / sweep | The wave 18 sweep's `device-tour` failed T127's direct step both runs with "no onTrimMemory trim recorded" and a 32 MB peak: with the sweep's server configured, `GET /online` carries no `direct.memory`, so the live-heap read finds nothing; the implementer and verifier ran with `--server none`. Resolved by T135: the record with a server too, the step run with the app switched to no server and back. |
| `2026-10-26-z8-tiles-check-misses-the-start-up-tile.md` | open | android / device tour / sweep | The wave 20 sweep's region-z8 `tiles` failure on the second PC: the only z8 OSM tile inside this PC's narrow pack is fetched at app start, before the tour's baseline, and the other two viewport tiles lie outside the pack, so the view's served delta is 0 while the map is fully drawn; the app's routing is unchanged since v0.1.12-alpha; the check and the pack recipe are wave 21's. |
