---
date: 2026-10-10
status: resolved
area: android / emulator rendering
promoted_to: docs/reference/android-emulator.md
---

# Under the emulator's software GPU, MapLibre Native draws no text and no dash patterns

## Question

Labels were missing on the device (T25). Was the style wrong?

## What was tried

The same style under `-gpu swiftshader_indirect` (which `tools/android-emu.ps1` used to pass) and
under `-gpu host`; a new `GET /labels` route (now `/features`) counted the placed symbols per layer.

## Result

The style was right. Under the software GPU MapLibre Native draws no symbol layers and no dash
patterns at all, while `-gpu host` renders every label, elevation and dashed trail. MapLibre placed
the labels in every mode, so the symptom was invisible, not absent, text (`GET /features/text` now
measures drawn text). `android-emu.ps1 -Gpu` defaults to `host` when the machine has a hardware GPU;
the GPU mode is fixed at boot. A real bug found on the way: glyph requests queued behind contour tiles
on the server's four threads and timed out; glyphs now load from `files/glyphs` through a `file://`
URL. Log entry: `docs/PLANNING.md`, T25 (2026-10-10).
