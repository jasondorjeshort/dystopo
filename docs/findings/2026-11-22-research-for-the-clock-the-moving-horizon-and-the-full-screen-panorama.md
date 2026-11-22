---
date: 2026-11-22
status: open
area: ideas / outside research
---

# Outside research for three ideas answered at the triage of 2026-11-22: the one clock, the moving horizon, the full-screen Panorama

## Question

The owner answered three ideas at triage and asked for research on whatever might come soon:
`../ideas/2026-11-21-one-clock-for-the-whole-app-past-and-future.md` (a proposal first, this its Prior art),
`../ideas/2026-11-21-the-horizon-view-moves-with-you.md` (smoothing first; "is there an algorithm to update steadily
while moving?") and `../ideas/2026-11-22-the-panorama-fills-the-screen-and-the-list-becomes-a-fourth-view.md` (built in
two steps; the survey informs the layout, not whether).

## What was tried

Three read-only agents (Sonnet), public pages only, nothing run in the repository. Sources were thin: several vendors
publish no help text on these points, two pages refused the fetch (search snippets used instead), and each gap is
marked "not established" in the agents' reports rather than guessed. Product names stay here; none goes into
user-visible text.

## Result

**The one clock (ADR 0039 survey).** What we have: `SunClock` (`app/options/SunShade.kt`, a date and a one-day slider in
5-minute steps with 15-minute chips, off at each launch) and `HorizonUi.date` (a date only, no control but the debug
`POST /horizon?date=`); they do not know each other.
- Common practice: a date picker plus a time scrub or step control, day-step buttons, and a single "now" affordance (The
  Photographer's Ephemeris' live clock icon, SkySafari's Now button, Stellarium's Now). Sun, moon and shade follow the set
  time everywhere. A loud not-now banner is rarely documented; most rely on the date shown in the bar.
- No app documents a rule for hiding live layers; Flightradar24's playback replays its archive instead.
- Outliers: The Photographer's Ephemeris restores the last date and asks "switch to today?"; CalTopo's sun layer defaults
  to the day's average, not an instant; SkySafari warns that a simulated time can aim a connected telescope wrong (the one
  documented hazard, an argument for a plain cue). PeakVisor moves sun, moon, shadows and stars with one bottom scrub.
- Recommendation for the proposal: one clock in `shared` (an instant and a zone, null meaning live) that `SunClock` and
  `HorizonUi.date` read; one strip (date, the 5-minute slider, "Back to now") opened from each view; a band naming the day
  and time when not now; live layers (satellites, flights, the live position read-out) hidden or marked while moved, written
  once as a rule. Not kept between launches; a "keep the set time" setting only if the proposal wants it (apps split; a
  stale time is a hazard, so default off).

**The moving horizon (ADR 0044, technical).** No published algorithm updates a raster terrain horizon incrementally for a
moving viewer (papers on polyhedral silhouettes and 2D visibility exist, theory only; GRASS `r.horizon` and `r.viewshed`
recompute per observer and are GPL, ideas only). Geometry allows an estimate: a ridge at range r shifts about d/r in
bearing, so 100 m moves one at 5 km about 1.1 degrees and one at 30 km about 0.2 degrees. Recommended, in three tiers on
`LayeredSkyline`: never clear (keep the last skyline until the next completes; this alone ends the blanking); re-sweep the
near layer at about 25 to 50 m and the far layer at 500 m or more, at least 1 s apart, one sweep at a time with the latest
position winning; store range and height per azimuth so the far layer can be moved to the new position each frame, and
optionally sweep ahead at the position 2 to 3 s ahead. No sweep timing on a phone is recorded anywhere in `docs/`.

**The full-screen Panorama (ADR 0039 survey).** In every app that could be confirmed (PeakFinder, SkySafari, PhotoPills)
the lens is the whole screen with small icon buttons at the edges and no pane under it; the list of what is in view is a
separate screen (PeakFinder's Menu > "Visible peaks", SkySafari's search), and a row tap goes back to the lens showing the
item. Time and layer controls sit behind one control (PeakFinder's Sliders). Calibration is an icon beside the compass,
with manual drag correction, as ADR 0064's heading correction. Recommended: the lens full screen with today's overlays and
one icon for the layers and time; the list as the switch's fourth segment, one page with sections (Peaks, Sun,
Satellites, Flights), a small list button on the lens as a second way in; a row tap turns the Panorama to the item and
returns to it (T327's behaviour); no new setting, except hiding the system bars if it is ever built (default off).

## Next

- The one-clock proposal cites this as Prior art and reads ADR 0025 and ADR 0031, which the survey did not.
- Before the horizon smoothing pass: measure on a phone the near and far sweep times (cold and warm), the cost of reading
  the elevation for each new window, and the error of moving the far layer against a fresh sweep at 50 to 500 m.
- The full-screen Panorama's first step takes the layout above and flags the second list entry for the owner.
