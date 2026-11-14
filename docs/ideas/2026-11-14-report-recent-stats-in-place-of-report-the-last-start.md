---
date: 2026-11-14
kind: feature
related: [2026-11-10-real-phone-check-as-a-one-tap-self-test.md]
---

# "Report recent stats" in place of "Report the last start"

Every launch figure of wave 32 is an emulator's: T266's 22 stages, T268's budget, T280's stall while the system
grew the table of open files. The phone already keeps what a real one would say: Settings, About and advanced,
Report a problem has **Report the last start** (`androidApp/.../app/reports/ReportRow.kt`, `StartupTrace.kt`),
every start's trace (the newest 3) and `.startup-history` (the newest 20 starts, one line each). The owner,
2026-11-14: "I uploaded one. Add more data to the trace if you need. We probably want to change the 'report latest
launch' debug reporting failure to just 'report recent stats' then. Seems worthwhile." (The newest start report
among the repository's issues that day was number 34, from 0.1.26-alpha.)

So the row stops being about one start that felt slow and becomes the phone's recent figures in one report: the
stage values of the recent starts with their medians beside the ceilings of `tools/launch-budget.json` (the
header's `fdTableMs` and `packageInfoMs` with them), and what else only a real phone can say. Recommendation (the
coordinator), each only where the report does not carry it yet: the tile store's size and file count, the tiles
T279's check dropped as empty since the install, how T267's move went (rename or copy, its time), the kept areas'
count and state, the heap figures of the diagnostic dump. It differs from the self-test idea: that one runs checks
on a tap; this one reports what the phone has already measured. The scrubber and the 4,000-character body rule stay
(`docs/reference/android-app.md`, Problem reports).

## Open

- The row's words (ADR 0031): "Report recent stats" is the owner's; the kind label and the notice follow it.
- Whether a start over the thresholds still files itself as "Startup freeze" (recommended: yes, unchanged).
- A reader on the sweep's side that puts such a report beside the emulator's medians.
