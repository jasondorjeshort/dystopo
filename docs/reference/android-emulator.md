---
verified: 2026-10-12
source: moved verbatim from CLAUDE.md in T84 (text as of c875bc9); observed in the tasks named in each paragraph
---

# Android emulators on this machine

Booting, running several side by side, and the host requirements. Paths and ports: `machines.md`. Device matrix flags (`-Api`, `-Device`, `-Locale`, `-FontScale`, `-Dark`, network flags): `release-realism.md`.

## Several emulators side by side

Several emulators side by side (one per task): own AVD, console port and host port; the script uses the serial `emulator-<EmuPort>` for every adb call, prints it, and refuses a console port or host port that belongs to another AVD (`-Port` is the host side, forwarded to the debug server's device port read from logcat). `-Gpu` defaults to `host` when there is a hardware GPU: under `swiftshader_indirect` MapLibre draws no text and no dash patterns. The GPU mode is fixed at boot (`-Stop`, then boot again); every run reinstalls the app and re-pushes the packs (`-PackName rainier` pushes a named pack, `<name>-dem.pmtiles` / `<name>-outdoors.pmtiles`). `-Build` writes `local.properties` if the worktree has none. T65: the script's progress (build, boot, install, push) goes to the output stream, so `| Tee-Object`, `> log` or a parent process see each step as it happens. Each AVD takes about 6 GB: on this machine put task AVDs on E: with `-AvdHome E:\dystopo-avd` (C: ran out of space with eight), and remove them when done with `-Stop -Delete` (same `-AvdHome`). Pass the serial to the checker too, so it refuses to drive anything else:
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
- Two emulators discover each other over NSD, but a direct connect between them is not reliable; go through the host (finding `../findings/2026-10-10-emulator-share-direct-connect.md`).
