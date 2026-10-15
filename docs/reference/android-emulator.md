---
verified: 2026-10-14
source: moved verbatim from CLAUDE.md in T84 (text as of c875bc9); observed in the tasks named in each paragraph
---

# Android emulators on this machine

Booting, running several side by side, and the host requirements. Paths and ports: `machines.md`. Device matrix flags (`-Api`, `-Device`, `-Locale`, `-FontScale`, `-Dark`, network flags): `release-realism.md`.

## Several emulators side by side

Several emulators side by side (one per task): own AVD, console port and host port; the script uses the serial `emulator-<EmuPort>` for every adb call, prints it, and refuses a console port or host port that belongs to another AVD (`-Port` is the host side, forwarded to the debug server's device port read from logcat). `-Gpu` defaults to `host` when there is a hardware GPU: under `swiftshader_indirect` MapLibre draws no text and no dash patterns. The GPU mode is fixed at boot (`-Stop`, then boot again); every run reinstalls the app and re-pushes the packs (`-PackName rainier` pushes a named pack, `<name>-dem.pmtiles` / `<name>-outdoors.pmtiles`). `-Build` writes `local.properties` if the worktree has none. T65: the script's progress (build, boot, install, push) goes to the output stream, so `| Tee-Object`, `> log` or a parent process see each step as it happens. Each AVD takes about 6 GB: on this machine put task AVDs on E: with `-AvdHome E:\dystopo-avd` (C: ran out of space with eight), and remove them when done with `-Stop -Delete` (same `-AvdHome`). T105: `-Server <url>|none` sets the app's server from its first start (written into a fresh install's preferences with `run-as`, then `POST /settings?server=`); without it a debug build on an emulator uses the host's 8765, the coordinator's live viewer, so give a task emulator its own server or `none` (`android-check.py --server` does the same before a tour). Pass the serial to the checker too, so it refuses to drive anything else:
```
powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Build -Avd dystopo35-T28a -AvdHome E:\dystopo-avd -EmuPort 5570 -Port 8694   # serial emulator-5570, debug server on localhost:8694
python -I tools/android-check.py --port 8694 --serial emulator-5570 --views-file tools/android-tour.txt --out out/t28a/tour.png
powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Avd dystopo35-T28a -AvdHome E:\dystopo-avd -EmuPort 5570 -Port 8694 -Stop -Delete   # kill it, drop the forward, delete the AVD
```

## Host requirements

The emulator needs CPU virtualization, which an administrator enables once: Windows Hypervisor Platform (`Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All`, reboot) or the Android Emulator hypervisor driver. Without it, x86_64 images never start under `-accel off` and ARM images are refused on x86_64 hosts, so `android-emu.ps1` stops with these instructions; `-NoEmulator` uses a phone connected with USB debugging instead.

## Known quirks

- Under `-gpu swiftshader_indirect` MapLibre Native draws no text and no dash patterns; `-Gpu host` is the default when there is a hardware GPU, and the GPU mode is fixed at boot (finding `../findings/2026-10-10-emulator-software-gpu-draws-no-text.md`).
- Images before API 30 need `-Feature GLESDynamicVersion`, or the emulator offers only OpenGL ES 2 and MapLibre crashes in its render thread.
- On API 35 emulators keep wifi enabled, or 10.0.2.2 (the host) is unreachable.
- A just-booted emulator is busy for minutes: a new API 35 AVD with four cores used 1.6 to 3.9 cores besides the app (from `/proc/stat`) for two and a half to three minutes after `sys.boot_completed`, then 0.0 to 0.3; `android-emu.ps1`'s install, pack push and first start take the first one to one and a half of them. A cold view started in that time took 40 to 47 s instead of 10 to 18 (T88, T98); `android-check.py` waits for a quiet device before its first view (`--quiet-cores`, T105: 75 and 100 s on two fresh AVDs, then the z8 view in 16.6 and 11.0 s).
- Two emulators on one host share a virtual Wi-Fi network (`wlan0`, 10.0.2.x): they discover each other over NSD and connect directly to each other's `10.0.2.x:8841` when both have a Wi-Fi carrier; an emulator that never gets one can only go through the host (`adb forward`, reached at 10.0.2.2), so restart it (finding `../findings/2026-10-10-emulator-share-direct-connect.md`, resolved by T90). Every agent's emulators share that network, so discovery also finds other agents' phones; keep a pair to itself with `iptables` (`android-tracks-and-sync.md`).
