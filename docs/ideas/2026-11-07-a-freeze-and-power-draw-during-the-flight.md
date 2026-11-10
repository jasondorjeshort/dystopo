---
date: 2026-11-07
kind: bug
related: [2026-10-13-battery-efficiency.md]
---

# The app appeared to freeze once during the flight and may have drawn a lot of power

The owner, on a flight on 2026-11-07: "the app appeared to freeze at one point and may have been eating a lot of
power. Unsure tbh needs more data."

A freeze after the first frame is `MainWatchdog`'s case (`androidApp/.../app/MainWatchdog.kt`, T144 and T227): a
`freeze-` report with the true length and the main thread's stacks should be waiting under Settings › About and
advanced › Report a problem, unless it was under 3 s. Power has no record in the app beyond `tools/android-battery.py`
on a debug build (T146); the diagnostic dump idea would add the sampled history a flight needs.

## Open

- The owner: send the freeze report from the flight if Report a problem lists one.
- The flight's conditions for a freeze: a long recording with the screen on, airplane mode (every online fetch
  failing fast or slowly?), the way-back clock recomputing over a growing track each second (T180), the readout's
  terrain lookups along a fast-moving position (T203's terrain source).
- ~~The dump idea's memory and CPU history~~: built by T258 (the minute sampler, "Send diagnostics" inside Report a
  problem, `devtool dump-scan`; `docs/reference/diagnostic-dump.md`), 2026-11-10. T250 also took the barometer off
  in the air and T252 the turn-back clock's per-second recompute for flying, two of the suspects above.

Question: Does Report a problem on your phone list a `freeze-` report from the 2026-11-07 flight, and will you send
it, or a "Send diagnostics" dump from the next long flight on v0.1.25-alpha or later? Recommended: send whichever
exists; nothing here can be built or ruled out without that data, and if the next flight shows no freeze and a flat
sampler line, this file is deleted on your word.
Answer:
