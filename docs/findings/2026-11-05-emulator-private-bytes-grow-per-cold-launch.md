---
date: 2026-11-05
status: open
area: android / emulator
---

# An emulator's private bytes grow with every cold launch, to 44 GB in under an hour

## Question

During T228 the owner saw one emulator holding 44 GB, with the PC down to 0.2 GB of RAM free. Its guest RAM was 3072 MB and T190 measured such an emulator at 4.9 GB private. What grew, and does the startup trace cause it?

## What was tried

T228's implementer and the coordinator read the process (`qemu-system-x86_64-headless.exe`, second PC, API 35 `google_apis` x86_64 image, `-gpu host`, `-memory 3072`, `-no-snapshot-save`), and the implementer read its private bytes between steps on later boots, with the trace on and off.

## Result

The peak: 44.6 GB private, 5.7 to 7.4 GB working set, 307 GB virtual, 489 threads, after about 85 cold launches in 51 minutes. Nothing read it during that run. Free RAM on the PC was 0.2 GB with it running and 29.4 GB after the kill, so nearly all of it was charged to the host.

Growth per step, read on later boots:

| Step | Private bytes |
|---|---|
| Boot | to about 4.6 GB |
| Dystopo cold start, debug, trace on or off alike | about +0.5 GB |
| Dystopo cold start, release | about +0.23 GB |
| Another app's cold start | about +0.07 GB |
| Hot return | about 0 |

So the trace does not cause it, and it grows in the host's emulator process, not in the guest. It scales with what a cold start does; a GL or graphics buffer leak in `-gpu host` fits, but that is unproven. The log holds 8 `Failed to import memory to ColorBufferGl` lines, not tied to the growth. A build before T228 was not run and other GPU modes were not tried.

Also seen: booting within about 10 s of `emu kill` failed twice with "exited (code 1) before booting", the second after a 5 s wait; waiting 60 s worked.

### A snapshot load against the reboot (T273, 2026-11-13)

Measured on the owner's PC (128 GB), API 35 `google_apis` x86_64, `-gpu host`, `-memory 3072`, the debug build at ae4da781, under a guard at 16 GB (it stopped nothing; peak 6.2 GB). A snapshot saved by the console (`adb -s emulator-N emu avd snapshot save clean`, OK in 29 to 37 s, a 3.0 GB `ram.bin` in the AVD's `snapshots\` folder) works on an emulator booted with `-no-snapshot-save`, with or without `-no-snapshot-load`: that flag stops only the save at exit. Three rounds, each ten cold launches (`am start -W` after a force-stop, 10 s each) then a load, then ten more then the sweep's reboot (`-Stop`, 60 s, a boot). Private bytes (the qemu process; its commit charge read the same), working set, GB:

| Round | Load: before | 0 s | 5 s | 30 s | Reboot: before | 0 s | 5 s | 30 s |
|---|---|---|---|---|---|---|---|---|
| 1 (process with 4 loads) | 5.84 / 4.18 | 5.74 / 4.23 | 5.35 / 4.12 | 5.24 / 4.14 | 6.10 / 4.22 | 4.20 / 2.71 | 4.28 / 3.03 | 4.46 / 3.72 |
| 2 (fresh boot) | 5.54 / 4.01 | 5.36 / 4.18 | 4.91 / 4.04 | 4.80 / 4.06 | 5.59 / 4.09 | 4.24 / 2.52 | 4.31 / 2.91 | 4.38 / 3.61 |
| 3 (fresh boot) | 5.47 / 3.95 | 5.45 / 4.17 | 5.00 / 4.04 | 4.90 / 4.06 | 5.67 / 4.14 | 4.36 / 2.85 | 4.31 / 3.00 | 4.33 / 3.61 |

0 s is when adb answers `sys.boot_completed` again (load) or when the boot script returns (reboot). The PC's commit charge moved by under 2 GB with other work on it and says nothing either way. A cold launch now adds 0.07 to 0.11 GB (0.72 to 1.09 GB per ten), a fifth of the 0.5 GB above. A load gives back 64 to 78% of what ten launches added and leaves the process 0.3 to 0.8 GB over a reboot's level (4.33 to 4.46); two device tour runs in one process (+0.24 and +0.18 GB) each ended with a load at 4.74 and then 4.85: the load returns the guest's share and keeps a level of its own that creeps up by about 0.1 GB a load. So the growth is mostly tied to the guest's state, but a load does not return a reboot's memory.

Times: a load answers OK in 4.6 to 8.5 s and `sys.boot_completed` reads 1 at 5.2 to 11 s; the app's first frame 10.2 to 13.4 s after the load command (`TotalTime` 3.6 to 6.5 s); `android-emu.ps1 -SnapshotLoad` with the app started, forwarded and its style loaded, 21 to 24 s. The reboot: 108 to 123 s for the stop, the 60 s wait and the boot (37 to 54 s to `sys.boot_completed`), and the first frame 68 to 74 s after the boot started with an install and a push in between (`TotalTime` 7.7 to 11 s). Pitfalls seen in 22 loads: adb offline for minutes after one load, where `adb reconnect` did not help and the same load again did in 2 s; the guest clock at `sys.boot_completed` 0 to 2 s off in most loads and once 47 and once 61 minutes behind (the snapshot's age), corrected later without `-no-snapshot-update-time`; two snapshots saved on a busy or stuck guest (one a minute after an install and a launch on a fresh boot, one after a killed load) whose app did not start for 2 to 6 minutes after their loads, while a snapshot saved 17 s after a boot started its app in 17 s; a KO for a missing name in under a second, leaving an empty folder of that name. The guest's storage after a reboot: `pm path android` and a write to `/sdcard/Android/data` answered on the first poll 9 times in 10 (4 to 11 s after `sys.boot_completed`, the poll itself slow) and on the second once (14 s); an install and a push right after it succeeded 10 times of 10. In T273's sweep runs the wait read 2.7 to 9.9 s on the first poll in three reboots and waited 31 s over 10 polls in a fourth.

Decision (T273): `android-emu.ps1` gains `-SnapshotSave` and `-SnapshotLoad` for the 85 to 100 s they save a measurement loop or a hand session, and the sweep keeps rebooting, since a load does not bring the emulator back to a reboot's level and undoes the guest's disk.

## Next

- Done in T231: every boot by `tools/android-emu.ps1` runs a guard that stops that emulator past `emulators.privateLimitGB` (8 GB) of private bytes or under `freeFloorGB` (4 GB) of free RAM, and a boot waits or refuses without the RAM it needs (`docs/reference/android-emulator.md`, The guard and admission by RAM). A brief no longer needs to cap cold launches by hand; an emulator booted before T231 has no guard until the script adopts it.
- The cause: repeat the growth table with `-gpu swiftshader_indirect`, then with a build from before T228. If software rendering does not grow, the fix may be a mode for launch loops.
- Whether the sweep's parts are exposed: they are, and tours grow it too, not only cold launches. The release sweep of 2026-11-06 lost its emulator to the guard at 8.2 GB, 3.6 GB over its boot, after 11 minutes of the first device tour run. T233 measured every device part: a device tour run adds 3.1 to 3.7 GB, first-run's `--direct` run (the phone's own download from the public sources) 3.9 to over 4.0 GB in about two minutes, every other part 0 to 1.5 GB; private bytes can also fall back (a tour run's peak of 8.3 GB ended at 6.0 GB). The sweep now reboots its emulator past 6 GB and runs it under a 12 GB guard limit (`.claude/skills/sweep/SKILL.md`).
- A snapshot for measurement loops (the owner, 2026-11-07): done in T273 (above): a load returns most of the growth, not a reboot's level; `tools/android-emu.ps1 -SnapshotSave`/`-SnapshotLoad` (`docs/reference/android-emulator.md`, Snapshots). Not tried: `-gpu swiftshader_indirect` as the control, and loads past ten in one process.
