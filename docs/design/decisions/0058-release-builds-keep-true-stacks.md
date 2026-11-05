# 0058. Release builds keep true stacks: no renaming and no R8 optimising

Date: 2026-11-05
Status: accepted

Record: wave 29's paragraph in `docs/TASKS.md`, T227's report (the merge commit's body); no proposal. A technical decision of the coordinator's after the critic of the wave's plan and T227's research; the owner is told, and may reverse it.

## Context

The owner reports significant freezes at startup and asks for a way to trace them through Report a problem. Release stacks could not be read: issue 29's frames read `d7.m.c(r8-map-id-…:120)`, no mapping was kept anywhere, and the app-frame test (`MainWatchdog.blockingFrame`, the same test in `CrashCapture` and `ExitImport`) matches our package name, so it found nothing in a release build.

The wave planned `-dontobfuscate` with shrinking and optimising left on. T227 measured that on a real `kill -3` dump (R8 8.13.19, min API 26, release APK on an API 35 emulator). It does not give a readable frame:
`io.github...app.SavedView$$ExternalSyntheticLambda0.run(r8-map-id-794249ec...e01e:1754)`. Lambdas are merged across classes, `loop` is inlined, and every frame carries a 72-character file token; 228,273 of the mapping's line ranges are rewritten and 340 kept. A kept `mapping.txt` fixes a maintainer's reading only: the phone's frame test and an issue's title would still name the wrong class.

A read-only research agent (ADR 0044) read R8's sources second-hand and found no supported option that keeps lines true while R8 optimises. Organic Maps ships these same two rules for this reason; NewPipe, K-9 and Mihon use `-dontobfuscate` alone; Tusky obfuscates. Android's docs advise against `-dontoptimize` in production. No published cost for a Compose app was found.

## Decision

`androidApp/proguard-rules.pro` carries `-dontobfuscate`, `-dontoptimize` and `-keepattributes SourceFile,LineNumberTable`. Resource and code shrinking stay on. One behaviour, no setting: it is a build property and no user can choose it. No mapping file is kept as a release artifact, since with true lines it adds nothing.

Rejected:
- **`-dontobfuscate` alone:** the frame above. The class named is often the wrong one, and the token eats the issue body's 4,000 characters.
- **Optimising on, `mapping.txt` kept per release, stacks retraced off the phone:** keeps the optimised build. It needs a release artifact and a retrace tool in the reading path, and the phone's own frame test, the report's headline and the issue's title stay wrong. The fallback if this ADR is reversed.
- **Optimised release plus an unoptimised alpha track:** two builds to test, and the freeze being traced is in the one the tester runs.

Out of scope: `ProfilingManager` system traces (wave 29's paragraph); renaming for size.

## Consequences

Every frame in a report reads as the source does, on the phone and in the issue, with no tool. Lambda plumbing appears below the real frames (`Foo.$r8$lambda$...(Foo.kt:0)`, `Foo$$ExternalSyntheticLambdaN.run(R8$$SyntheticClass:0)`).

Costs:
- APK size, arm64: 23,318,145 to 25,394,604 bytes, +2.08 MB or 8.9%; renaming accounts for only 0.13 MB of it. Universal: +2.08 MB of 59.8 MB.
- Speed is measured only on an x86_64 emulator with a host GPU, against `-dontobfuscate` alone: cold `am start -W`, 20 starts each, median 1086 ms against 1124 ms, within the noise; frames over 12 pans, 50th percentile 18 to 21 ms and 90th 34 to 48 ms in both. **No real phone has been compared.** Compose without R8's optimising may be slower there. The owner's complaint is the app freezing, not how long things take to open (owner, 2026-11-05), so what would count against this is main-thread stalls and panning, not the start's length.
- A start whose stalls change on the first release carrying this may be this change, not a fix. The freeze reports read against it say which build they came from.

Supersede this when the phone comparison below shows a cost, or when the freezes are understood and the project prefers the size and speed back (`mapping.txt` then becomes the route, with the frame test reworked).

Predictions (the trigger scan reads these).
- Prediction, check when a release carries T228: on the owner's phone, the startup history's first-frame median and the feel of panning are no worse than an optimised build of the same commit signed with the same key, within 10 percent on the median.
- Prediction, check when the first `startup-` or `freeze-` report arrives from a release build: its app frame names a real class and a line that matches the source at that tag.
