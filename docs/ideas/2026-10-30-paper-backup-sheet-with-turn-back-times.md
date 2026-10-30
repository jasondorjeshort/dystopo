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

Question: the QR code's payload is a format other phones read, which under CLAUDE.md › Decisions goes through a proposal and
a critic. Should the turn-back table go ahead now, with the code after that proposal? Recommendation: yes, split. Ready now as
one devtool and server pass: `devtool print --gpx <route> --turn-back --deadline sunset|civil|<ISO> --margin-min 30` runs T163's
`TurnAround` along the route (as `devtool nav --turn-around` does) and puts a table in the sheet's margin: each junction
or waypoint, its distance, and the turn-back clock time for the deadline and pace. The margin layout is
`SheetComposer` in `devtool/.../PrintCommand.kt`, page maths in `render/PageLayout.kt`, golden in `goldens/print/`;
`GET /print` (`PrintRoutes.kt`) takes the same options.
The proposal then chooses the payload: a `dystopo:` deep link with an encoded, simplified line (`DeepLink.kt` exists; a QR code
holds about 2.9 kB in binary), GPX text that any app reads, or a trip id fetched over the share. It also chooses a QR encoder:
written in-house, or a permissive library in devtool. The phone's sheet stays the printable-sheet idea's Plan.
Answer:
