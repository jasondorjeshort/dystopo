---
date: 2026-11-22
kind: feature
related: [2026-11-22-the-panorama-fills-the-screen-and-the-list-becomes-a-fourth-view.md, 2026-11-19-the-horizon-view-beside-the-map-view.md, 2026-11-11-trim-the-default-settings-before-beta.md]
---

# The views as equal tabs: the map is not home, Back does not return to it, and Settings is a view

From the owner (2026-11-22, v0.1.41-alpha): "Right now map view is primary, in a way I don't like. Clicking back on
another view takes you back to map view. I'd imagine these as all having equal priority, like tabs on a browser.
Intuitive for people. Settings could then become a view of its own. Many other features would be map view only, but
this might end up being surprising. Showing a waypoint, track or route on one of the other views makes complete sense;
editing doesn't though. What you call the Pane sounds like the fourth view I had in mind. But then settings, in
hindsight, seems to fit most naturally as a view."

**Where it stands.**
- The switch (Map | Horizon | Panorama, T315, T326) is a control over the map: the Horizon and Panorama are **overlays on
  the map screen**, and Back "lets go of a pick, then a chosen point, then closes" the sky view, which shows the map
  (`app/horizon/SkyViewEffects.kt`, `docs/reference/android-panorama-view.md`, `android-horizon-view.md`). The map's
  own controls (the Settings and Offline maps chips, Search, Waypoint, Tracks, Routes, Car or bus, the readout strip,
  the first-run card) belong to the map screen only.
- Settings is a **sheet** opened from a chip on the map (`SettingsSheet.kt`, rows in `settings/`), and so are Offline maps
  (`packs/PacksScreen.kt`), Storage, tracks and routes: each is a layer over the map, closed by Back or Close.
- The idea of the same date (the Panorama fills the screen and the pane's list becomes a fourth view) takes the pane as
  the **List** view; the owner says that is the fourth view they meant, and Settings the fifth.

**What is wanted.**
1. **Equal views, like browser tabs.** No view is home. Switching between them keeps each view's own state (the map's
   camera and selection, the Horizon's place and layers, the Panorama's aim), as a tab does.
2. **Back does not mean "go to the map".** The owner dislikes Back from another view landing on the map. What Back does
   instead is the subject of a research project of its own (below); inside a view it still lets go of a pick or a
   chosen point first, and at the top level it still minimizes the app, as it always has.
3. **Settings is a view** (the settings sheet becomes a screen of the same standing, no longer a chip over the map).
   Offline maps, Storage, Tracks and the like are the neighbours that may follow it, and are not asked for here.
4. **Per-view features.** Many controls are map-only. The owner's rule: **showing** a waypoint, track or route on another
   view makes sense (a mark on the Panorama at its bearing, a line on the sky plot where it passes, a route's high point,
   the ideas on file); **editing** does not, and a control that edits should say where it lives.

**The open points (what more we would want to see, and decide).**
- **What Back does at the top level of a view.** The owner (2026-11-22): at the top level Back has **always exited
  (minimized) the app**, "a weak point to be addressed later", and "eventually it should be its own research project".
  So the Back question is **not settled here and not part of this change's first stage**: the tabs keep Back as it
  is (inside a view, let go of a pick or a chosen point; from a sky view, to the map as today; at the map, minimize),
  and a **Back and navigation research project** of its own (a survey under ADR 0039 of how the established apps do it:
  exit, a history of views, the start view; whether a double Back exits; where Settings and the sheets sit in the
  stack) decides the rest. The owner's present dislike, a sky view's Back landing on the map, is then fixed by that
  project, not by the tabs alone.
- **What the switch becomes with five views.** Map | Horizon | Panorama | List | Settings does not fit one line at
  large text: the switch (a three-segment pill, T326, the credit line's rule is set by its width) becomes a bottom bar of
  five items with icons, or Settings stays a chip while four views take the pill. A bottom bar changes every view's room
  and the layout rule's frames (`MapLayout.kt`, `MapLayoutTest`, the a11y baseline, the sweep's steps).
- **Where a map-only control goes when its view is not the map.** Hidden, shown disabled with a line, or opening the map
  view first. Editing a waypoint or a route from the Panorama's mark: tapping it could open the map at that item (the
  place that edits), which is the surprise-free version of the owner's rule.
- **State kept per view** across switches, and whether a view is **kept alive** while another shows (the Panorama's
  sensor and the flights' poll are held only while a view is open, `SkyViewHolds`; tabs would need the same, a cost in
  battery the owner's rule of a sensor held only while shown protects).
- **Where the first-run card, the readout strip and the credit line live** (the card and the strip are map screen items;
  the credit line is placed by the switch's width).

**Coordination.** This changes the shape ADR 0064 set (the Panorama is one view of the switch) and ADR 0057's and T315's
rule of the switch in one place: a new ADR with a proposal and a critic (a navigation structure is a major choice,
`CLAUDE.md` › Decisions), after the survey of the established apps, **not** a patch to wave 46. T330 and T331 own
`app/panorama/` and the help text; the Panorama's full screen (the idea of this date) and these tabs should be designed
together so the lens's overlays and the switch's place are settled once.

Question: should the views be equal tabs with Settings a view, built as one navigation change after a survey and a
proposal, with Back's behaviour left to its own research project? Recommended: yes in principle, built in stages: first
the List view and the full-screen Panorama on the present switch (nothing lost), then the tab structure with a survey
and a proposal for the bar (Back unchanged), then Settings as a view; the Back and navigation research runs on its own
and lands before or with the tab structure's ADR, since the two share the stack.

Answer: In stages, yes (the owner, 2026-11-22). No priority: "we've got a lot to do and we'll get there eventually".
