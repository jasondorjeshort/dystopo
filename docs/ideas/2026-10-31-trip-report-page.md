---
date: 2026-10-31
kind: feature
---

# A trip report page after a recording

After a recording ends the app knows the whole day: the track, the climb and descent, the pace against the fit,
the past efforts on the same trail, the water crossed, the hours in sun and shade, the pressure's course, the
turn-back decision and when the trailhead was reached. T183 can lay out a page. Make one more: the trip report, a
sheet with the map and the track, the profile with the stops, and a margin of the day's numbers, printable or saved
as a PDF, and kept with the track. The hike's own summary for a journal or a friend, and the data the terrain-fix and
readout tuning asked the owner to save, in one place. Open: which numbers make the first version; whether the page
also carries the sun and pressure graphs or only their figures.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Plan: one task, the figures the track already holds; graphs later. In `shared`: `render/TripReport.kt` lays out one page
on T183's `PageLayout` and `SheetCanvas` (`render/PageLayout.kt`): the map frame fitted to the track with the track
drawn (as the sheet's overlay draws one), an elevation profile strip under it with the stops marked (`gpx/TrackStops.kt`), and a margin of the day's numbers: distance, moving and total time, ascent and
descent (`gpx/TrackStats`, the same options the Tracks list uses), the highest and lowest points, the time against
the user's fit (T184's `PaceFits.wholeTrackRatios` in `routing/PaceFit.kt`), earlier walks of the same trail
(`PastEfforts.match` in `routing/PastEffort.kt` over the other stored tracks) and the water crossed
(`routing/WaterAlong.kt` run on the track's points). Generated on demand, never stored, so "kept with the track" is the
Tracks list's action and nothing more to sync. Devtool: `devtool print --gpx <track> --report` writes the PDF and a JSON
report listing every margin figure (the rasterised page viewed as for T167). Phone: a "Report" action beside "Profile"
in the Tracks list (`app/tracking/TrackControls.kt`) through `app/print/SheetPrint.kt`, and `POST /print` with
`track=<id>&report=1` (`app/PrintRoutes.kt` and its KDoc). Survey (ADR 0039): what the established topo and fitness
apps put on a finished activity's summary, to pick and order the margin; no setting. Done: a synthetic track's page
golden-checked through the rasteriser, a public Rainier GPX's page viewed, the phone's PDF from a replayed recording.
Out of scope: the sun and pressure graphs and the turn-back events (not stored with a track today: the trend minutes
live in `PressureCard`'s own store and the turn events only in `NavSession`), a second pass once those are recorded.
