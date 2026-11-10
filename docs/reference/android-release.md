---
verified: 2026-11-05
source: moved verbatim from CLAUDE.md in T84 (text as of 059b2c3); observed in the tasks named in each paragraph
---

# The release build, signing, deep links and the release smoke tour

How to publish is the `release` skill (`.claude/skills/release/SKILL.md`); the policy is ADR 0024.

## Release build and signing

**Release** (T55). `./gradlew :androidApp:assembleRelease` runs R8 (full mode, shrinking only since T227, below; rules and their reasons in `androidApp/proguard-rules.pro`) and writes one APK per ABI plus a universal one to `androidApp/build/outputs/apk/release/` (`androidApp-<abi>-release.apk`; ABI splits are on only when the Gradle invocation names a release task, so `assembleDebug` still gives the single `androidApp-debug.apk`). Release builds keep the tile routes (`/style.json`, `/dem`, `/osm`, `/contours`, `/glyphs`, `/shade` tiles) and answer 404 to every debug route; the tile routes need the loopback token like debug builds (401 without it), and release has no `/health` to hand it out. Signing uses a local test key; make it once (both files are gitignored, never commit them):
```
keytool -genkeypair -keystore tools\release.keystore -storetype PKCS12 -alias dystopo -keyalg RSA -keysize 4096 -sigalg SHA512withRSA -validity 36500 -dname "CN=Dystopo release, O=Dystopo"
# tools\release.properties:
storeFile=tools/release.keystore
storePassword=<the password you gave keytool>
keyAlias=dystopo
keyPassword=<same>
```
Without `tools/release.properties` release builds are signed with the debug key. The real release key (RSA 4096, SHA-512, valid 100 years, 48-character password, made 2026-10-10) lives only on the owner's machine and in the `RELEASE_*` Actions secrets; it must never change, because a phone only updates an app whose new APK is signed by the same key.

## Readable stacks (T227)

A release stack reads without a tool: `proguard-rules.pro` has `-dontobfuscate`, `-dontoptimize` and `-keepattributes SourceFile,LineNumberTable`, shrinking stays on, and no mapping file is kept or published, since the lines and names are the source's. `-dontobfuscate` alone is not enough, seen on a real dump: while R8 optimises (AGP 8.13.2, R8 8.13.19, min API 26) the watchdog's frame read `SavedView$$ExternalSyntheticLambda0.run(r8-map-id-<64 hex>:1754)`: lambdas merged across classes, the method inlined, a line number R8 made up (228,273 of the mapping's line ranges rewritten against 340 kept), a 72-character file on every frame. With `-dontoptimize` too it reads `MainWatchdog.loop(MainWatchdog.kt:<n>)` and `<n>` is the source line. A plain `Foo.$r8$lambda$...(Foo.kt:0)` and `Foo$$ExternalSyntheticLambdaN.run(R8$$SyntheticClass:0)` below a frame are the lambda's own plumbing. R8's sources give the reason: line rewriting and the map-id file are on when it optimises or minifies; no supported option keeps them true with optimising on (the survey of T227; Organic Maps ships the same two rules for this reason, against Android's general advice).

The cost, measured on an API 35 x86_64 emulator with the Rainier pack: the APK grows by 2.08 MB (arm64 23.32 MB to 25.39 MB, 8.9%; names alone are 0.13 MB of it); cold start `am start -W`, 20 starts each in four interleaved rounds, median 1086 ms optimised and 1124 ms not, within the noise; frame times over 12 pans, 50th percentile 18 to 21 ms and 90th 34 to 48 ms in both. A real phone has not been compared.

APK size (T264): the bundled world overview (`assets/world/world-outdoors.pmtiles`, 1,748,638 bytes, 1,723,683 in the zip) takes the arm64 release APK from 25.57 MB to 27.30 MB (27,298,657 bytes, measured 2026-11-10; the "before" is the same APK less the entry).

To check a build: `adb root` (a `google_apis` image), `kill -3 <pid>`, then pull the newest file of `/data/anr/` and read the `"ui-watchdog"` thread (Git Bash needs `MSYS_NO_PATHCONV=1`, or it rewrites `/data/...`).

## Deep links and `android-release.ps1`

The map opens at a place from a link in every build: `adb shell am start -a android.intent.action.VIEW -d "'dystopo://map?lat=46.853&lon=-121.76&z=12'"` (`z`/`zoom`, `bearing` optional; also as intent extras; `--activity-single-top` moves the open map). `tools/android-release.ps1 -Serial emulator-<port> [-Build] [-Port <host>]` installs the release APK for the device's ABI (uninstalling a build signed with the other key first), pushes the Rainier pack, glyphs and sprites to the app's external files dir (release is not debuggable, so no `run-as`), opens three views through the link, polls `adb exec-out screencap` every 500 ms, and reports `am start -W` times, time to first contours and to a settled screen, the PNGs (`out/release/`, view them), a probe of tile and debug routes without a token (`-Port`: tiles 401, debug routes 404, `probeOk`), and logcat crashes and app errors as JSON. `-Apk <debug apk> -Label debug -Runs 3` gives the debug numbers to compare; the script's `-Build` is not needed once the APK exists, and a new worktree needs the packs and `out\sprite*` copied in (`android-emulator.md`). T79: `-Name <pack>` pushes any locally built pack (`out\<pack>-dem.pmtiles`, plus `-outdoors` when it exists) instead of Rainier; `-NoPush` leaves the install with no pack and no assets.
