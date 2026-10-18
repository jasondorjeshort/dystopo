---
verified: 2026-10-12
source: moved verbatim from CLAUDE.md in T84 (text as of 059b2c3); observed in the tasks named in each paragraph
---

# The release build, signing, deep links and the release smoke tour

How to publish is the `release` skill (`.claude/skills/release/SKILL.md`); the policy is ADR 0024.

## Release build and signing

**Release** (T55). `./gradlew :androidApp:assembleRelease` runs R8 (full mode, rules and their reasons in `androidApp/proguard-rules.pro`) and writes one APK per ABI plus a universal one to `androidApp/build/outputs/apk/release/` (`androidApp-<abi>-release.apk`; ABI splits are on only when the Gradle invocation names a release task, so `assembleDebug` still gives the single `androidApp-debug.apk`). Release builds keep the tile routes (`/style.json`, `/dem`, `/osm`, `/contours`, `/glyphs`, `/shade` tiles) and answer 404 to every debug route; the tile routes need the loopback token like debug builds (401 without it), and release has no `/health` to hand it out. Signing uses a local test key; make it once (both files are gitignored, never commit them):
```
keytool -genkeypair -keystore tools\release.keystore -storetype PKCS12 -alias dystopo -keyalg RSA -keysize 4096 -sigalg SHA512withRSA -validity 36500 -dname "CN=Dystopo release, O=Dystopo"
# tools\release.properties:
storeFile=tools/release.keystore
storePassword=<the password you gave keytool>
keyAlias=dystopo
keyPassword=<same>
```
Without `tools/release.properties` release builds are signed with the debug key. The real release key (RSA 4096, SHA-512, valid 100 years, 48-character password, made 2026-10-10) lives only on the owner's machine and in the `RELEASE_*` Actions secrets; it must never change, because a phone only updates an app whose new APK is signed by the same key.

## Deep links and `android-release.ps1`

The map opens at a place from a link in every build: `adb shell am start -a android.intent.action.VIEW -d "'dystopo://map?lat=46.853&lon=-121.76&z=12'"` (`z`/`zoom`, `bearing` optional; also as intent extras; `--activity-single-top` moves the open map). `tools/android-release.ps1 -Serial emulator-<port> [-Build] [-Port <host>]` installs the release APK for the device's ABI (uninstalling a build signed with the other key first), pushes the Rainier pack, glyphs and sprites to the app's external files dir (release is not debuggable, so no `run-as`), opens three views through the link, polls `adb exec-out screencap` every 500 ms, and reports `am start -W` times, time to first contours and to a settled screen, the PNGs (`out/release/`, view them), a probe of tile and debug routes without a token (`-Port`: tiles 401, debug routes 404, `probeOk`), and logcat crashes and app errors as JSON. `-Apk <debug apk> -Label debug -Runs 3` gives the debug numbers to compare. T79: `-Name <pack>` pushes any locally built pack (`out\<pack>-dem.pmtiles`, plus `-outdoors` when it exists) instead of Rainier; `-NoPush` leaves the install with no pack and no assets.
