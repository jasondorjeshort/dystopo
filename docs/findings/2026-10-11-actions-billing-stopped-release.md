---
date: 2026-10-11
status: resolved
area: release / GitHub Actions
promoted_to: .claude/skills/release/SKILL.md (local fallback)
---

# GitHub Actions refused to start the release job while the repository was private

## Question

The `v0.1.3-alpha` tag did not produce a draft release. Why?

## What was tried

The release workflow's job did not start: "recent account payments have failed or your spending
limit needs to be increased" (billing on a private repository). The release was then built locally
at the tag with the workflow's flags (versionCode 220 from the commit count), packaged with the same
asset names, uploaded as a draft and verified from the draft as usual.

## Result

The repository went public on 2026-10-11; public repositories get Actions minutes free, and CI, the
nightly sweep and the release workflow run again (`v0.1.4-alpha` was built by the workflow). The local
build stays in the `release` skill as the fallback.
