---
date: 2026-10-12
status: open
area: CI / build inputs
---

# CI downloads every glyph range on each run, so a remote 503 fails a docs-only commit

## Question

Commit `b9aa668` (a handoff edit, 2026-10-12) failed CI; a rerun was green. Why does a docs-only
commit depend on a remote server?

## What was tried

Read the failing job: CI's build job downloads every glyph range from `protomaps.github.io` on each
run, for the glyphs bundled in the APK (`bundleMapAssets`, `androidApp/build.gradle.kts` lines 143 to
157); there is no cache step for `cache/glyphs`. One range answered 503.

## Result

Any transient failure of the glyph host fails CI, whatever the commit changed.

## Next

For the next sweep task: an `actions/cache` step on `cache/glyphs` keyed by the font and range list,
or a retry in the Gradle task.
