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

## Next

- Done in T231: every boot by `tools/android-emu.ps1` runs a guard that stops that emulator past `emulators.privateLimitGB` (8 GB) of private bytes or under `freeFloorGB` (4 GB) of free RAM, and a boot waits or refuses without the RAM it needs (`docs/reference/android-emulator.md`, The guard and admission by RAM). A brief no longer needs to cap cold launches by hand; an emulator booted before T231 has no guard until the script adopts it.
- The cause: repeat the growth table with `-gpu swiftshader_indirect`, then with a build from before T228. If software rendering does not grow, the fix may be a mode for launch loops.
- Whether the sweep's parts are exposed: they are, and tours grow it too, not only cold launches. The release sweep of 2026-11-06 lost its emulator to the guard at 8.2 GB, 3.6 GB over its boot, after 11 minutes of the first device tour run. T233 measured every device part: a device tour run adds 3.1 to 3.7 GB, first-run's `--direct` run (the phone's own download from the public sources) 3.9 to over 4.0 GB in about two minutes, every other part 0 to 1.5 GB; private bytes can also fall back (a tour run's peak of 8.3 GB ended at 6.0 GB). The sweep now reboots its emulator past 6 GB and runs it under a 12 GB guard limit (`.claude/skills/sweep/SKILL.md`).
- A snapshot for measurement loops (the owner, 2026-11-07): boot once, save the emulator's snapshot, restore it before each cold launch, so every launch starts from one state and the host process does not live through ten launches; whether the growth then stays away says whether it is tied to the guest's lifetime. Tooling only.
