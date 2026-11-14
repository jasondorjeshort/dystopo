---
date: 2026-11-14
kind: chore
---

# One signing step owns the release key; no worktree holds a copy

Gradle reads the release key from `tools/release.properties` (`androidApp/build.gradle.kts`, the block under
"T55"), which exists only in the main checkout. `.claude/settings.json` denies agents
`Read(tools/release.properties)`, while the `sweep` skill and `docs/reference/release-realism.md` tell a task
worktree to copy that file and the keystore into its own `tools/`. The rule refuses that (T278's implementer was
refused the file), so in wave 32 `upgrade` ran only from the main checkout, in the sweep and the draft check; and
a copy step, where it works, leaves copies of the key in worktree folders. The owner, 2026-11-14: "Well we aren't
READING it, right?", and on this idea: "Yes that would be smart".

One script in the main checkout (`tools/sign-release.ps1`, say) takes an APK's path and signs it with the SDK's
`apksigner`, reading the key where it lives; `tools/android-ui.py` already calls `apksigner` for a re-signed
install. A worktree builds its release APK without the key (signed with the debug key, as the build does today
when the file is absent), hands the path to the script and installs the result. No agent opens, copies or prints
the key; the deny rule stays; the two docs lose the copy step.

Recommendation (the coordinator): this in place of the board's Later line of the same day (the build finding the
main checkout's file by itself), which would sign every worktree's release build whether or not anyone asked.

## Open

- Whether the permission rule lets an agent run a script that reads the file (the sweep's Gradle does, from the
  main checkout); test it in isolation before the docs promise it.
- A re-signed APK against one Gradle signed: the same certificate, so it installs over a published build; confirm
  that `upgrade` and `update` pass with it.
- Copies of the key left in worktree folders by earlier waves, if any: the owner's to look for and remove.
