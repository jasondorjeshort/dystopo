---
date: 2026-11-20
kind: feature
related: [docs/ideas/2026-11-19-the-horizon-view-beside-the-map-view.md]
---

# Fewer or more peak names on the Horizon view

T309 chose the rim's names by a score and a density rule (`HorizonLabels` in `shared`, the weights and the 4° x sqrt(n/5) spacing as named constants); the weights are first guesses, and its research found no app that publishes its own, and apps that differ in how many names they show (the Horizon view idea's research lines). By ADR 0044 that split calls for a setting with a researched default.

Wanted: a Fewer / Normal / More control on the Horizon view (a synced setting, its row, help text and a debug route), scaling the density gap and the score cut-off together; Normal is today's rule. It also gives the owner a way to tune the rim on real skylines before the defaults are settled.

The owner, 2026-11-20: "Record them all. We'll triage and sanity check later."

Plan: a synced setting `horizonPeakNames` (Fewer, Normal, More; Normal the default and today's rule) through the
settings registry, its row with help text under the Horizon view's Peaks section and in Settings beside the Horizon
rows. In `horizon/HorizonLabels.kt` one factor per level scales the density gap (`gapDeg`: Fewer x1.5, More x0.7,
the 2 deg floor scaled with it) and the score cut-off together; the dominant summit stays named at every level. The
`GET /horizon` route reports `peakNames` and `POST /settings?horizonPeakNames=` sets it. Verify: unit tests in
`HorizonViewTest` that Fewer names a subset of Normal and More a superset on the same rim; frames at the three levels
at Paradise from the emulator. The report says why it is a setting (ADR 0044: no app publishes its weights and apps
differ in how many names they show). New strings in the report (ADR 0031).
