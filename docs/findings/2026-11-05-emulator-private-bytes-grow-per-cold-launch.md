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

- A guard, since anything looping cold launches is exposed: `tools/android-emu.ps1` or `tools/android-startup.py` reads the qemu process's private bytes and stops or warns at 8 GB. Until then a brief caps a boot at six cold launches and reads private bytes between steps (T228's verifier does); only `android-startup.py`'s docstring warns.
- The cause: repeat the growth table with `-gpu swiftshader_indirect`, then with a build from before T228. If software rendering does not grow, the fix may be a mode for launch loops.
- Whether the sweep's parts are exposed: it boots several times and relaunches for its tours; read the peak in the next sweep.
