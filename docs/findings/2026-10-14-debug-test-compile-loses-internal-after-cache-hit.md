---
date: 2026-10-14
status: resolved
area: build / Gradle build cache, Kotlin module files
promoted_to: docs/reference/windows-and-shells.md
---

# The debug unit-test compile loses main's `internal` declarations after a build-cache restore

## Question

The post-wave sweep of 2026-10-14 (`out\sweep-20261014-1730`, commit 53aabea) failed its `check` part:
`:androidApp:compileDebugUnitTestKotlin` reported 40 "Unresolved reference" errors, every one an
`internal` top-level function of `androidApp` main (`budgetError`, `aboutMb`, `corridorMaxZoom`,
`peerSeenLine`, `shownJob`, `ago`, `offlineOfferText`, `refreshApplies`), while
`compileReleaseUnitTestKotlin` compiled the same test sources in the same run, CI was green on the same
commit, and T98's implementer had hit the same errors in its worktree after `assembleDebug` reused
build-cache outputs. Is the code at fault?

## What was tried

- `javap` on the built debug and release classes: the declarations are there.
- In the main checkout at 53aabea: `./gradlew :androidApp:compileDebugUnitTestKotlin` failed at once
  with the same 40 errors, `compileDebugKotlin`, `compileDebugJavaWithJavac` and
  `bundleDebugClassesToCompileJar` all UP-TO-DATE.
- `./gradlew :androidApp:clean`, then `./gradlew :androidApp:compileDebugUnitTestKotlin --no-build-cache`:
  BUILD SUCCESSFUL in 24 s, 21 tasks executed. `tools\sweep.ps1 -Only check -NoDevice` then ok in
  89.6 s (`out\sweep-20261014-1818`). T98's worktree: the same clean and `--no-build-cache` fixed it.
- `gradle.properties` has `org.gradle.caching=true`; nothing sets `kotlin.incremental` or
  `-Xfriend-paths`; the Kotlin Gradle plugin and AGP wire main's classes into the test compile as the
  friend module on their own.
- T105 (2026-10-14, Kotlin 2.2.0, AGP 8.13.2, Gradle 8.14.3): a fresh worktree at 9dd5e86 (main), first
  build `:androidApp:compileDebugUnitTestKotlin`: `compileDebugKotlin FROM-CACHE`, then the test compile
  FAILED with the same errors. The restored `build/tmp/kotlin-classes/debug/META-INF/androidApp_debug.kotlin_module`
  was 145 bytes and listed two file facades, `TileServiceKt` and `DirectSourcesKt` (the two files T98
  changed); a whole one is 1,374 bytes and lists every package's facades. `AreaDownloadKt.class` itself
  held `budgetError` and `aboutMb`. Another task's worktree at the same commit had the same 145-byte file.
- Producing such an entry, without success: an incremental compile after a one-file change, in the same
  checkout and on a state restored from the cache, from this path and from an entry built at another
  worktree path: every one wrote and cached the whole 1,374-byte module file.

## Result

Resolved. Not the code and not the friend paths: the compiler finds a module's top-level functions
through its `.kotlin_module` file (package to file facades), and a cached `compileDebugKotlin` output of
this account's shared local build cache, keyed by main's androidApp sources, carried a module file
naming only two facades. Every checkout whose sources matched restored it, and any compile against
main's classes then saw only those two facades' functions; the unit tests call `internal` top-level
functions of other files, so those were the errors. The release variant had a whole entry. How the
partial file was written (an incremental compile of T98's two files that lost the rest of the mapping is
the likely source) was not reproduced.

Fix (T105): `androidApp/build.gradle.kts` keeps `compileDebugKotlin` and `compileReleaseKotlin` out of the
build cache (`outputs.cacheIf { false }`), so a cached entry can neither be restored nor stored; incremental
builds in a checkout are unchanged. The cost falls on a fresh checkout: a clean `check` on the branch took 3 min 0 s, with `compileDebugKotlin` 21.2 s and `compileReleaseKotlin` 20.7 s where a cache hit restores each in about 0.5 s. The
sequence `:androidApp:clean`, `compileDebugKotlin`, `compileDebugUnitTestKotlin` failed on main and was
green three of three on the branch (`Caching disabled for task ':androidApp:compileDebugKotlin'`).
`tools/sweep.ps1`'s `check` part also retries once after `:androidApp:clean` with `--no-build-cache` when a
unit-test compile reports "Unresolved reference", and says so in `steps.tsv`.

The bad entries were found by opening every entry of `~/.gradle/caches/build-cache-1` used in the last two
days (gzipped tars) and reading the size of `META-INF/androidApp_debug.kotlin_module` in each: two held
145 bytes naming `TileServiceKt` and `DirectSourcesKt`, `5eb619d2bbdd6347a37acc2a16567a38` (last used
15:41) and `790069318e47a3c35f4ba0e367b962b5` (last used 17:30, the sweep's start), against 1,176 to
1,441 bytes in the 70 others. Both were deleted on the coordinator's word (2026-10-14 20:39; a miss only
recompiles). A future hit in a checkout without T105's build file: `./gradlew :androidApp:clean`, a build
with `--no-build-cache`, and the same scan to find and delete the entry.
