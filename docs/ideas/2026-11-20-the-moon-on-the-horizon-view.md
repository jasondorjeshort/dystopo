---
date: 2026-11-20
kind: feature
related: [docs/ideas/2026-11-19-the-horizon-view-beside-the-map-view.md, 2026-11-21-stars-and-planets-over-the-named-skyline.md]
---

# The moon on the Horizon view

The owner, 2026-11-20, after v0.1.35-alpha: "Add a moon entry for the future (idea)."

A Moon layer beside Satellites, Peaks and Sun on the Horizon view: the moon where it is now, its path across today's (or tonight's) sky like the sun's, its phase and how much of it is lit, and when it clears the real skyline and when it drops behind it, from the same terrain rim the sun uses (`Sun.onTerrain` and `HorizonProfile.angleAt`). Useful for night hikes, alpine starts and photography (a rising moon behind a named peak).

What it needs: a lunar position model in `shared` (pure Kotlin, no `java.*`; a few arc-minutes is plenty at the plot's scale), the phase from the sun–moon elongation, a toggle row and its help text, the moon's line in the content pane, and a debug route field in `GET /horizon`. The layer's toggle joins the `kind` system the peak labels use, so its labels take part in the same collision and density pass (`HorizonLabels`).

Later, the same layer or a sibling: the bright planets (Venus and Jupiter are often the first lights out at dusk over a ridge), from the same kind of position model. Added 2026-11-20 on the owner's "Record them all."

Plan: a `nav/Moon.kt` beside `nav/Sun.kt` (pure Kotlin, a published low-precision lunar series good to a few
arc-minutes, written from the formulas, with topocentric parallax since the moon's is up to a degree): position, the
illuminated fraction and phase from the sun-moon elongation, rise and set on the terrain rim as `Sun.onTerrain` does.
A fourth `HorizonLayer.MOON` (synced `horizonMoon`, default on) with its toggle, the moon drawn at its place with its
lit fraction, tonight's path dashed like the sun's, a Moon section in the pane (phase, lit percent, when it clears and
drops behind the skyline), and its label taking part in `HorizonLabels` with a `kind` weight. `GET /horizon` gains
`moon`. Verify: unit tests against published positions and phases for a few dates (computed values, no copied
tables), frames at Paradise by day and night with `date=`. New strings in the report (ADR 0031). Out of scope: the
planets, the Panorama view.
