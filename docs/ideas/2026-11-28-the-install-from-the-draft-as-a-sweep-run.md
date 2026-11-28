---
date: 2026-11-28
kind: chore
related: [2026-11-24-a-phone-capture-script-for-a-release-build-over-usb.md, 2026-11-28-releases-are-getting-very-slow.md]
---

# The release's install-from-the-draft as a sweep run

Source: coordinator (claude-fable-5-2), a wave close; offered and accepted by the owner; not sanity-checked.

Step 5 of the `release` skill has an agent download the draft's APK and run `tools/android-ui.py` `upgrade`,
`features` and `strings` on a fresh emulator. The agent builds the setup by hand each time: the emulator, a server
for the Sync step, the packs. For v0.1.45-alpha `features` failed its `search-tiles` step because that step also
needs the debug build installed beside the release with its location permission granted and the Rainier pack
pushed, which the sweep's emulator has and a boot-only one lacks; it took two more runs to find.

The idea: `tools/sweep.ps1` takes the draft's APK (`-Apk <file>`, or `-FromDraft <tag>` that downloads and checks
it against `SHA256SUMS.txt`) in place of its own release build and runs the release parts named with `-Only`,
with the setup the sweep already makes. The release agent's job becomes one command and reading its summary, and
the draft is checked the way the swept build was.

Lever: every release runs this step; it took about half an hour this time, a third of it on the setup.

## Open

- Which parts: the skill names three; `update` and `first-run` from the draft would cost about twelve minutes more.
- The sweep re-signs its APK copies from a worktree (T293); a draft's APK must be installed as it is.
