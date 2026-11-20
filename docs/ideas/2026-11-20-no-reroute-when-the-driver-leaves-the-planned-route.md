---
date: 2026-11-20
kind: bug
related: [2026-11-15-a-drive-as-a-speed-profile.md]
---

# Nothing reroutes when the driver leaves the planned route

The owner, 2026-11-20: "we still have no mechanism to reroute any change of our routing. If you take a different road
the app will simply complain to you indefinitely, rather than working around it."

What the code does: `Navigator` (`shared/.../nav/Navigator.kt`) follows a fixed `NavPath`. Past `offRouteM` (50 m) for
`offRouteSeconds` (10 s) it raises one `NavEvent.OFF_ROUTE`, adds the straight-line "rejoin" way back to the line, and
a `BACK_ON_ROUTE` only when a fix is near the old line again. The plan is never recomputed, so a driver who chose
another road is told they are off route until they return to the old line (or stop navigation).

The worse case: a one-way street. If the app believes the driver went the wrong way on one (or the driver simply took
a different road), then driving on in the right direction toward the target makes the old plan's remaining distance
and time grow, and the app keeps pointing back along the planned line, which may mean driving backwards on the
one-way street. It should instead plan from where the car is now, honouring one-ways and turn restrictions.

Wanted: when off route (or when the progress along the line stops making sense), compute a fresh route from the
current fix and heading to the same target with the routing engine that planned the first one, replace the plan, and
say so once ("Rerouting" in the card and the speech), without a flood of alerts. Open choices for the owner or a
research pass (ADR 0044): how long off before rerouting, whether the heading is used to avoid a U-turn on a
one-way, and whether a reroute is silent or announced. The phone has the routing graph for drives (`routing/`), so it
works offline; the walking and hiking `NavPath` case may stay as today.

Repro (to build as a test): a drive plan with a one-way segment; replay fixes (`NavReplay`) that take a parallel
road to the same destination; expect today one OFF_ROUTE and no new plan, wanted a new plan from the first fix after
the threshold.

## The shape the owner wants (2026-11-20)

"I think we instead want the route to be separated into future route and past track. When routing we can implicitly
save a track of our path to date, letting us show both distance traveled and distance to go - in different colors."

So the model changes from one fixed line with a progress marker to two parts:
- **Past track:** while navigating, the fixes are kept implicitly as the path travelled so far (no recording the user
  has to start). It is drawn in its own colour, and gives the distance travelled (and time) so far.
- **Future route:** always planned from the current position to the target. A reroute only replaces this part; the
  track behind the car is never rewritten, so a wrong turn or a different road is simply part of the past, and there
  is nothing left to be "off" from. The card shows distance travelled and distance to go together, the future part in
  the route colour.

This also removes the one-way trap above: the future route starts at the car and heading, so it cannot ask for a
reverse drive, and the remaining distance can only shrink as the car nears the target. Open for the plan: whether the
implicit track is the same thing as a recorded track (it may be able to be saved at the end, as a trip), how it is
kept while the app is backgrounded or killed, the two colours (and the colour-blind check the overlays already get),
and how a walking or hiking route that must be followed closely (the line is the point) differs from a drive.

Question: ADR 0040 decided "plans replan, frozen routes lead back" and its reroute build task was never run; your
shape of 2026-11-20 (a past track kept implicitly and a future route always planned from the car) replaces the
"lead back" half for drives, so should it go in as an amendment to ADR 0040 with a short research pass (how long off
route before a replan, using the heading to avoid a U-turn, announced or silent) and then one build task for drives,
or as a new proposal covering hikes too? Recommended: the amendment and one drive task, leaving a walking route that
must be followed closely as today; the drive graph is on the phone, the change sits in `nav/Navigator.kt` and the nav
session, and the implicit track (kept through a kill, saved as a trip at the end or not) is the one design point the
research should settle.

Answer:
