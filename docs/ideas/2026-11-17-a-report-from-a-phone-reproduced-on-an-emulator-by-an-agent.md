---
date: 2026-11-17
kind: feature
related: [2026-11-10-real-phone-check-as-a-one-tap-self-test.md]
---

# A report from a phone reproduced on an emulator by an agent

T301 and T302 make Send diagnostics and Report recent stats end in a GitHub issue with a structured body (version,
phone model and Android version, memory, the recent starts' figures). An agent watching new issues could read that
body, boot an emulator matching it (Android version, font scale, units, the packs and settings named), replay what
the report allows (the start, the screen, the route), and attach to the issue what it saw: reproduced with the
frames and figures, or not, with what differed. A report from the owner's phone would then arrive with its first
debugging done. Anchors: `androidApp/.../app/reports/` (the issue bodies), `tools/android-emu.ps1`,
`tools/android-check.py`, `gh issue`.

## Open

- What the issue body must add for a replay (settings that differ from defaults, without names or places).
- Who runs the watcher (a scheduled agent) and its cost against the meter.
- A moonshot, recorded from conversation, not triaged or checked against the code.
