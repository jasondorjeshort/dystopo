---
date: 2026-11-22
kind: feature
related: [2026-11-19-the-horizon-view-beside-the-map-view.md, 2026-11-21-the-horizon-view-moves-with-you.md]
---

# The Panorama fills the screen; the list under it becomes a fourth view

From the owner (2026-11-22, v0.1.41-alpha, in use; "issues with the current alpha that could be fixed in the one in
progress"): in the Panorama view "we really need an option, maybe have it the only way, to use the entire screen space
for the view. In camera mode this is going to be glaring, but it's particularly noticeable on landscape orientation
already. I think this view can have a couple overlays but should not have the scrolled data below it that the Horizon
view currently has. In fact I tend to think that scrolled data is another view entirely (list view?) which would be #4."

**Where it stands.** The Panorama's screen is the plot **and a pane** (`docs/reference/android-panorama-view.md` › The
screen, `app/panorama/PanoramaScreen.kt`, `PanoramaUi.kt`): upright, the plot is the window's full width and three quarters
of that tall, **at most 45 percent of the window's height** (411 by 309 dp on a Pixel 6), the Horizon view's parts below it
(the place line and "Back to here", the heading, "Show:" toggles, the terrain line, then the Flights, Peaks, Sun and
Satellites sections, a flight's card over the pane's lower part); on its side the plot is at the left, the window's width
less a pane of at least 320 dp or 40 percent at the right. The pane is the Horizon view's own (T325 cut it into parts both
views host). A lens that wants a window (a drag, the phone's attitude, a camera behind) gets less than half of it upright
and about 60 percent sideways; the owner's own frame of the pane under a nearly empty plot is the case.

**What is wanted.**
- **The plot takes the whole window** (behind the switch), with a few overlays on it instead of a pane beneath: the
  place line with "Back to here" (when at another place), the zoom buttons that already sit on it, the edge arrow and
  its line, the bearing tape, the credit line, a picked name's line, and a small way to turn the layers on and off
  (Peaks, Sun, Satellites, Flights; the same synced settings).
- **The data goes to a fourth view.** The sections (Peaks with their count and the view range, Sun's times, Satellites,
  Flights and a flight's card, the terrain line) become a **list view**, the switch's fourth segment: Map | Horizon |
  Panorama | List (the owner's "list view?" with its question mark: the name is open). It reads the same place and
  moment as the view the owner came from, and a row's tap can turn the Panorama to it (as the Panorama's list rows do
  today, T327), which is the way back.
- The owner's question is whether the full screen is an **option** or the **only way**: "maybe have it the only way".

**What touches it (coordination).**
- T330 and T331 (wave 46, in flight) own `app/panorama/` and `app/help/Help.kt`: the Follow-the-phone button, the setting,
  the weak-compass line and the edge arrow's sign all sit on this screen. The layout rule's frames (`MapLayoutTest`,
  the a11y baseline, the sweep's `panorama` steps) all change with the plot's size. A fourth segment changes the switch's
  width, the credit line's rule (the credit line is placed by the switch's width, T326's revision) and the first-run
  card's room; ADR 0064 says the Panorama is **one view**, and its fourth view is the first change of that shape.
- The Horizon view keeps its pane unchanged unless the owner wants the same list view there too ("the scrolled data
  below it that the Horizon view currently has" reads as the owner's wish to move it out of both).

**What more we would want to see.**
1. Whether the full screen should hide the system bars (a true full screen, as a camera viewfinder does) or only the
   pane: the camera mode the owner expects makes this the first question; the switch's place at the bottom of every view
   (T315) stays either way.
2. The upright and sideways frames of the Panorama now at font 1.0 and 2.0, to see how much the pane costs: the plot's
   height is capped at 45 percent upright, so a frame with the pane scrolled to its end is the evidence.
3. What the owner would keep on the lens, in order: place line and "Back to here", layer toggles, the zoom buttons, the
   target's line, the sun or peak lines. The overlays compete for a lens that is also steered by touch.
4. Where the list view's entry is (a button on the Panorama, the fourth segment, or both), and whether it is one list
   (Peaks, Sun, Satellites, Flights in one scrolled page) or a list per layer.

2026-11-22, the survey of other apps' layouts: `../findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`
(the lens is the whole screen everywhere it could be confirmed; the list is a separate screen; one page with sections;
a row tap returns to the lens turned to the item).

Use: 2026-11-22, coordinator (claude-opus-5-5): the viewshed's list of places to go (the answered viewpoint-finder idea, `2026-11-21-a-viewpoint-finder-where-a-peak-can-be-seen-from.md`) belongs in the same fourth list view: what is in sight, and where to go to see it (accepted by the owner).
Came up again: 2026-11-24, the owner, having used step one on v0.1.43-alpha (the trigger step two waited for): the Panorama's "List view" button "is now obsolete as it just takes you to the list view which is already a button" (the switch's fourth segment); the Horizon view's list, "at least of flights", "is also obsolete. That list is in the list view, and clicking on the flight pops it up at the bottom anyway"; the Panorama's Layers button is good ("lets us control the layers from there"); the owner called these observations on work in progress, "probably not currently relevant until other things get cleaned up".

2026-11-22, the triage's Question (does the fourth view replace the Panorama's pane, and carry the Horizon view's
too?) and the owner's answer, by multiple choice: "yes, in two steps: the Panorama's lens full screen with the pane's
content as the list view first, then the Horizon view's pane moved to the same list once the owner has seen it".
2026-11-24, the owner, having seen step one (taken in by the triage of 2026-11-26 from its `New:` line): "I tend to
think the bottom area of horizon mode should contain that flight info (or whatever else you click on) rather than them
being popups."

2026-11-23, T351 (step one's second half built): the Panorama's plot is the whole window inside the system bars (kept), upright and on its side, behind the switch, with no pane; its overlays (the tape, Layers and List view at the upper right, the place line with Back to here at a chosen point, a picked peak's line and the weak-compass line at the upper left, the zoom buttons and Follow phone at the lower right, the correction's line and the flights' credit beside or above the switch, the edge arrow) are boxes placed by one rule (`PanoramaOverlays`) that the names keep off. One control, Layers, opens the four layers' switches with the view's heading and help. Step one (T344 and T351) is built; step two, the Horizon view's pane moved into the List, waits for the owner to see step one (the Answer above). Full screen without the system bars was not built (the survey: a setting, default off, only if ever wanted).

Plan: step two, settled by the Answer of 2026-11-22 now that the owner has used step one (2026-11-24), with the
owner's shape of that day; one pass, not in a wave that also owns `app/skylist/` (the List's sort and its map line).
(1) The Horizon view's pane leaves `app/horizon/HorizonScreen.kt`: its sections live only in the List
(`app/skylist/SkyListScreen.kt`), which shows the view it was opened from (the Horizon view's range when opened
there). (2) The space under the round plot (beside it on its side) becomes the selection's detail: a picked flight's
card, a peak's, the Sun's or a satellite's line, in place of the pop-up cards there; with nothing picked it holds the
place line and a hint. The layers' switches come from a Layers control as on the Panorama
(`app/panorama/PanoramaLayers.kt`). (3) The Panorama's "List view" button goes (`app/panorama/PanoramaScreen.kt`,
`shared/.../horizon/PanoramaOverlays.kt` and its test); the switch's fourth segment is the way in. Verify:
`SkyListTest` (no section of either old pane missing), `PanoramaOverlaysTest`, `MapLayoutTest`; on the emulator at
Paradise, upright and on its side at font 1.0 and 2.0, a replayed flight and a peak picked in the Horizon view, frames
viewed; the sweep's `features-sky` and `a11y` parts with their frame names checked first (`sweep-diff.py
--check-first`). Docs: `docs/reference/android-horizon-view.md`, `android-panorama-view.md`, and the Outcome of ADR
0067's step-two prediction. Strings in the report (ADR 0031). Out of scope: full screen without the system bars, the
List's sort, the viewshed's section.