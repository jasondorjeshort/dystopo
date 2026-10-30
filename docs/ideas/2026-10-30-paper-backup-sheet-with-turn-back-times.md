---
date: 2026-10-30
kind: feature
related: [2026-10-29-printable-map-sheet.md]
---

# A paper backup: the route sheet with turn-back times and a QR code of the plan

T167's `devtool print` draws a map sheet; T163's `nav/TurnAround.kt` computes the turn-back time
for a route and a deadline. Put them on one page: the route drawn on the sheet, a table of the
turn-back time at each junction or waypoint for the chosen deadline and pace, and a QR code
carrying the route's GPX (or the `dystopo:id` of the saved trip and its sync bundle) so a
companion's phone loads the same plan with no signal. The phone's own sheet is the printable-sheet
idea's Plan; this is the content that makes the sheet worth printing before a hike. Open: how much
GPX fits a scannable code (a simplified line, or the trip id with the plan fetched over the LAN
share).
