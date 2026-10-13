---
name: release
description: Publish an alpha release of the Android app from main (tag, draft from the release workflow, verify and install from the draft, hand-written notes, publish), or build the same release locally when Actions cannot run. Use after a green post-wave sweep, when asked to release or publish, or when a draft must be checked or replaced.
---

# Release

Moved from `CLAUDE.md` (Android, "Publishing (owner's policy, 2026-10-10)") in T84. The policy (alpha
after every green sweep without asking, GitHub Releases, universal APK only, one key forever, Google
Play for beta) is ADR 0024. The coordinator decides and announces the release, tags and publishes;
everything that builds, downloads or installs is an agent's (ADR 0026). Release build and signing
facts: `docs/reference/android-release.md`.

## Policy in one paragraph

Republish regularly: during alpha the channel is GitHub Releases only (universal APK, in-app updater).
Beta (decided 2026-10-11): Google Play, starting with closed testing, under one developer registration
the owner makes once (a Play Console account; its identity check also covers Google's developer
verification for sideloaded installs), with our own key kept under Play App Signing; sideloading is a
short-lived alpha arrangement. The coordinator publishes at the end of every wave once the sweep on
main is green, without asking again.

## Steps

1. Cut the CHANGELOG section before tagging.
2. Tag `v0.<minor>.<patch>-alpha` on main (patch bumps per wave; the minor bumps when the owner calls
   a milestone), push the tag, and let `release.yml` build the draft (the version name is the tag, the
   version code the commit count, so every build updates over the last).
3. An agent downloads the universal APK from the draft itself (alpha releases ship only the universal
   APK, owner's policy 2026-10-11; per-ABI files return at beta or once a server picks the ABI) and
   checks the certificate (`apksigner verify --print-certs`: "CN=Dystopo release", fingerprint
   `47:31:38:10...`), the version (`aapt dump badging`) and `SHA256SUMS.txt`.
4. Write the notes by hand from the CHANGELOG section (install steps for a phone, what works, known
   limits, checksums and licences); scan them with `PrivacyCheck`.
5. **Install from the draft before publishing** (T81): one agent downloads the APKs from the draft
   itself (`gh release download <tag> -p "*x86_64.apk" -p "*arm64-v8a.apk"`, never a local build) and
   on a fresh emulator runs `python -I tools/android-ui.py upgrade --serial <serial> --prev-apk <the
   last published release's APK> --apk <the draft's APK>` (the update a user gets: track, trip, pack
   and setting survive), `features --apk <the draft's APK>` and `strings --apk <the draft's universal
   APK>`; when a phone is connected over USB (`adb devices` lists a serial that is not `emulator-*`)
   the same agent also runs `python -I tools/android-firstrun.py --serial <phone serial> --apk <the
   draft's arm64 APK> --server <LAN server> --until test` and `android-ui.py features --serial <phone
   serial> --apk <the draft's arm64 APK>` on it (the steps that inject fixes through `adb emu geo fix`
   work only on emulators; on a phone the track step needs a real fix, so a failed track step there is
   reported, not blocking).
6. Only then `gh release edit <tag> --notes-file <notes> --prerelease --draft=false`, and confirm from
   the remote (`gh release view <tag>`).
7. Record the release in the planning log and the README status line.

A draft that is superseded before publishing is deleted together with its tag.

## When the workflow cannot run

2026-10-11: GitHub Actions billing stopped every job (finding
`docs/findings/2026-10-11-actions-billing-stopped-release.md`). An agent builds the same release
locally:

1. `git worktree add --detach ..\dystopo-rel <tag>`, copy `tools\release.keystore` and
   `tools\release.properties` in.
2. `./gradlew licenseCheck :androidApp:assembleRelease :server:installDist :devtool:installDist -PdystopoVersion=<tag without v> -PdystopoVersionCode=$(git rev-list --count HEAD)`.
3. Name and zip the assets as the workflow's "Collect artifacts" step does
   (`dystopo-<tag>-universal.apk` only during alpha, `dystopo-server-<tag>.zip`,
   `dystopo-devtool-<tag>.zip`, `NOTICES.md` from `notices.py --out`, the tour sheets, `version.json` as
   the workflow writes it (T83: tag, versionCode, versionName, apk, sha256 of the universal APK),
   `SHA256SUMS.txt`).
4. `gh release create <tag> --draft --prerelease --verify-tag --notes-file <notes> out/release/*`, then
   verify from the draft and publish as usual (steps 3 to 7).
5. Delete the signing copies and the worktree afterwards.
