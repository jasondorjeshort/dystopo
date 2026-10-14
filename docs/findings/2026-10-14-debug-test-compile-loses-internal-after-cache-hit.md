---
date: 2026-10-14
status: open
area: build / Gradle build cache, Kotlin friend paths
---

# The debug unit-test compile loses main's `internal` declarations after a build-cache restore

## Question

The post-wave sweep of 2026-10-14 (`out\sweep-20261014-1730`, commit 957a807) failed its `check` part:
`:androidApp:compileDebugUnitTestKotlin` reported 40 "Unresolved reference" errors, every one an
`internal` top-level function of `androidApp` main (`budgetError`, `aboutMb`, `corridorMaxZoom`,
`peerSeenLine`, `shownJob`, `ago`, `offlineOfferText`, `refreshApplies`), while
`compileReleaseUnitTestKotlin` compiled the same test sources in the same run, CI was green on the same
commit, and T98's implementer had hit the same errors in its worktree after `assembleDebug` reused
build-cache outputs. Is the code at fault?

## What was tried

- `javap` on the built debug and release classes: the declarations are there.
- In the main checkout at 957a807: `./gradlew :androidApp:compileDebugUnitTestKotlin` failed at once
  with the same 40 errors, `compileDebugKotlin`, `compileDebugJavaWithJavac` and
  `bundleDebugClassesToCompileJar` all UP-TO-DATE.
- `./gradlew :androidApp:clean`, then `./gradlew :androidApp:compileDebugUnitTestKotlin --no-build-cache`:
  BUILD SUCCESSFUL in 24 s, 21 tasks executed. `tools\sweep.ps1 -Only check -NoDevice` then ok in
  89.6 s (`out\sweep-20261014-1818`). T98's worktree: the same clean and `--no-build-cache` fixed it.
- `gradle.properties` has `org.gradle.caching=true`; nothing sets `kotlin.incremental` or
  `-Xfriend-paths`; the Kotlin Gradle plugin and AGP wire main's classes into the test compile as the
  friend module on their own.

## Result

Open as to the mechanism, resolved as to the code: a stale build state, not a defect. A restore of
`compileDebugKotlin` (or of the main-classes jar the test compile reads) from the build cache or the
incremental state leaves the test compile with output in which the friend path no longer grants
`internal` visibility, so the declarations resolve as missing although the classes hold them. The
release variant was unaffected because its tasks recompiled from scratch or restored a consistent entry.
Seen twice on 2026-10-14 (a task worktree after `assembleDebug`, the main checkout after the sweep's
builds).

## Next

A wave 14 sweep task: make the build immune (for example `outputs.cacheIf { false }` on the Kotlin
compile tasks of `androidApp`, or a `kotlin.incremental` setting, verified by reproducing the
cache-hit sequence), or have `sweep.ps1`'s `check` part retry once after `:androidApp:clean
--no-build-cache` and say so in `steps.tsv`. Until then: `./gradlew :androidApp:clean` and
`--no-build-cache` when the unit-test compile reports unresolved `internal` functions.
