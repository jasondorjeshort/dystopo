---
date: 2026-10-30
status: resolved
area: process / task reports / ADR 0044
promoted_to: .claude/skills/brief/implementer.md
---

# Wave 20 and 21 reports left the research behind their choices in the wave paragraph, not the report

## Question

ADR 0044's first prediction, due at the close of wave 21: every wave 20 and 21 report names the
research behind each open choice (user-facing or technical) or the decision that settled it, and
every setting added says why it is a setting. The triage scan of 2026-10-29 found the line without an
Outcome; the coordinator checked it at the close of wave 22.

## What was tried

A Sonnet read-only agent read the eighteen merge commits of waves 20 and 21 (T140 to T148, T149 to
T157; the bodies are the implementers' reports, ADR 0043) and the two wave paragraphs in
`docs/tasks/wave-20.md` and `wave-21.md`, and classified each report: an open choice named with its
research or decision, an open choice named without one, or no open choice.

## Result

The settings half held: no report adds a setting (both wave paragraphs answer every settings
question "one behaviour"; wave 21's says no task adds one), so there was nothing to justify.

The research half failed on the merge bodies alone. Of eighteen reports, three cite a survey
(T142, T141, T145), nine cite a decision, a plan, a measurement or the owner (T140, T144, T147,
T148, T151, T152, T154, T156, T157), three had no open choice (T146, T150, T155), and three chose
without naming what settled it: T149's guard constants (its own measurement, never the wave 21
research that every app ships a prebuilt graph), T153's z10 detail default and new picker mode, and
T143's heap-mark write rule (a measured 503 ms, never the wave 20 heap-diagnostics research). None of
the eighteen names a file under the wave's research folder. T151 is borderline: auto-resume without
a toggle is the owner's requirement in the wave paragraph, and the report is silent.

Cause: the research is read by the coordinator at planning and written into the wave paragraph and
the briefs; the implementer's report records what was built and seen. The prediction asked the
report to carry the citation, and the brief never asked for it.

Fix, in the same commit: the implementer brief's report line asks for the research or decision
behind each open choice and the reason for each setting. Wave 22's briefs already asked for it by
hand, and its reports carry the citations (T172's two research agents, T161's idea research, T163's
and T164's ADR 0044 lines). ADR 0044's second prediction (by 2027-01-25, the owner's removals)
stands.
