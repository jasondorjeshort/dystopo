---
date: 2026-11-11
kind: bug
---

# A recording from days ago still reads as active, with a turn-back warning

The owner, 2026-11-11, on v0.1.26-alpha: opening the app after it had sat unused for days, it still shows
as active (a recording running), and flights recorded days earlier say the way back cannot be made before dark.

Two things to find: why a recording left running (or never finished) survives days without the app asking about it,
and why the turn-back clock (wave 31's way-back card and its "Turn-back time" chip) computes a verdict for a recording
whose last fix is days old, and for a flight, where wave 31 meant to show no clock for flying, driving, rail or boat.
A stale recording probably wants a question on the next open (finish it, or keep it going), and the clock wants a
cut-off on the age of the last fix. Reproduce on the emulator with a recording started, the clock moved on by days
(`adb shell` date or a fake fix stream), then a cold start; the debug routes `GET /tracks` and the way-back state
report what the card reads.
