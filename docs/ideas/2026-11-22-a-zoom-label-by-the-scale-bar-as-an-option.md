---
date: 2026-11-22
kind: feature
related: [2026-11-22-the-map-is-nearly-empty-at-the-zooms-between-the-world-overview-and-the-packs.md]
---

# A zoom label by the scale bar, as an option

From the owner (2026-11-22): showing the zoom level (for example "z8") by the scale bar "is a good idea but needs to be
an option. On for now but we'll definitely want it off by default in a post beta release." The owner has no way to know
the zoom in use, and every limit that matters (the world overview to z5, the packs from z8, roads thinning by zoom in
`tilebuild/PROFILE.md`) is stated in zooms.

**Where.** A small label beside the bar drawn by `controls/ScaleBar.kt`, placed by the layout rule (`app/MapLayout.kt`),
next to the readout strip that shares that line; the map's current zoom is the camera's, read on the main thread.
A Map options row with a synced settings key (`MapOptionsSection.kt`; each row is the `POST /settings` key of the same
name, `android-debug-routes.md`), its help text, and a debug route result so a script reads the label's text.

**The default is a build-time decision.** On while the app is alpha and beta; a post-beta release turns it off by default
(users who want it switch it on). That default should be one named constant with a comment saying when to flip it, and
the release checklist (the `release` skill's beta-to-release step) carries a line for it, so the flip is not forgotten.
Existing installs that saved the row keep their choice; an install that never touched it follows the default.

**What more we would want to see.** The wording ("z8", "Zoom 8", or a decimal such as "z8.4" since the camera zooms
continuously; the owner's own example was "z8 or z=8"); whether it shows beside the scale bar when the bar is hidden
or off; the layout at large text and on its side (the layout rule's frames).

Plan: a Map options row "Show zoom level" (on for now), the label beside the scale bar through the layout rule, the
setting's key, help and debug route, a test of the label's text and of its placement at font 1.0 and 2.0, the default as
one constant with its flip noted for the post-beta release; strings in the report (ADR 0031).
