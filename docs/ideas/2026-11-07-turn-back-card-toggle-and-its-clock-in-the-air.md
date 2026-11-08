---
date: 2026-11-07
kind: feature
related: [2026-10-30-arrival-time-as-a-range.md, 2026-10-30-paper-backup-sheet-with-turn-back-times.md]
---

# The turn-back card gets a toggle on the track popup, and its clock follows the activity

The owner, on a flight on 2026-11-07: "a turn back time? Really? Where does it think I'm returning to? The start I
suppose. But this should be made easily toggleable. Plenty of room on my screen for a couple more options on the
track popup." And: "even for plane the turn back is weird. Every second it goes up by like 3 minutes late on the
return time."

The card (`androidApp/.../app/nav/WayBackClock.kt`, the anchor in `tracking/WayBackAnchor.kt`, T180 and T184)
assumes a return on foot to where the recording began. T180's own follow-up names the drift: the clock uses the
hike pace in free tracking, not the activity, and new ground past the turn overestimates the way back. At 500 km/h
the way back grows by minutes of walking every second, which is the figure the owner saw.

After landing on the second flight the card read "Turn back now, out by 05:36. 348 h 16 min late; the turn-back
time was 08:51": a lateness of two weeks, so the turn-back time it compared against belongs to another day (a
stored deadline or anchor from an earlier recording, or a clock-time deadline without its date), a defect beyond the
drift.

After a third flight the owner settled the default: "Turn back mode really needs to be an option. Flying across
the country and walking back wasn't the most sane app expectation. But this is really a feature for tracking, not a
default. Very convenient to enable in a single click, wanted less than half the time, and has to make sane timing
estimates, a much harder problem than it sounds. Especially if you wanted to hike back to your car and get home by
midnight! Giving horrible numbers will just lead to it being mocked."

## Open

- Off by default (the owner's ruling), one tap on the track popup (`tracking/TrackControls.kt`, the recording
  panel) to turn it on for this recording; a setting for its default is the owner's call after the research.
- Sane estimates or none: the clock says nothing it cannot stand behind (no figure without an activity-fit pace
  and a plausible way back), and the estimate's basis is one tap away, as the planning card's is (T217).
- The clock's pace from the activity (T158's detection: flying, driving, cycling) rather than the hike pace, and
  no card at all for an activity with no walk back (flying).
- Where the return is to: the start, a chosen waypoint, or none.
- The 348-hour lateness: find which deadline the card compared against (`nav/WayBackClock.kt`, `shared`'s
  `nav/TurnAround.kt`, the stored `turnDeadline` setting) and give every deadline a date.
