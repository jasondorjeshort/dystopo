---
date: 2026-11-20
kind: feature
related: [docs/ideas/2026-11-19-the-horizon-view-beside-the-map-view.md]
---

# The moon on the Horizon view

The owner, 2026-11-20, after v0.1.35-alpha: "Add a moon entry for the future (idea)."

A Moon layer beside Satellites, Peaks and Sun on the Horizon view: the moon where it is now, its path across today's (or tonight's) sky like the sun's, its phase and how much of it is lit, and when it clears the real skyline and when it drops behind it, from the same terrain rim the sun uses (`Sun.onTerrain` and `HorizonProfile.angleAt`). Useful for night hikes, alpine starts and photography (a rising moon behind a named peak).

What it needs: a lunar position model in `shared` (pure Kotlin, no `java.*`; a few arc-minutes is plenty at the plot's scale), the phase from the sun–moon elongation, a toggle row and its help text, the moon's line in the content pane, and a debug route field in `GET /horizon`. The layer's toggle joins the `kind` system the peak labels use, so its labels take part in the same collision and density pass (`HorizonLabels`).
