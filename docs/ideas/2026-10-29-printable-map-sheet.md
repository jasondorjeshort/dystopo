---
date: 2026-10-29
kind: feature
---

# A map sheet you can print

One tap on the phone, or one command, gives a PDF of the current view fit to a paper size, with a
grid, a scale bar, the declination arrow, the contour interval, the route and its waypoints, and
the date and sources: the paper backup that offline-first implies, since a phone can drown or
die. The renderer the goldens guard already draws contours, hillshade and labels to an image
(`devtool/.../MapRenderer.kt`, `RenderCommand.kt`, `shared/.../render/`), the unit and scale
logic exists (`render/UnitFormat.kt`, `androidApp/.../controls/ScaleBar.kt`), the declination
comes from `nav/Wmm.kt` and the UTM maths from `geo/Utm.kt`; what is missing is a page.

- **A sheet:** paper size (A4, Letter, A3), portrait or landscape, a scale the user picks from a
  short list (1:25 000 by default, the convention of printed topo sheets) or "fit the view", margins
  with a title, the grid labels (UTM by default, lat/lon as an option), the north arrows, the scale
  bar, the interval, the magnetic declination for the sheet's centre and date, the sources line
  from the registry. Drawn at print resolution (300 dpi) so the hillshade and labels do not pixelate.
- **The phone:** the platform's PDF writer (`android.graphics.pdf`, no dependency) fed by the same
  Compose or bitmap render the map shows; the system print or share sheet takes it from there. A
  route, the recorded track and the waypoints in view go on the sheet.
- **The devtool:** `print --area rainier --scale 25000 --paper a4` writes the PDF and a PNG of the
  page to `out/` with a JSON report (scale, page extent, dpi, time); the viewer (`tools/demo/`) gets
  a print button that calls the server for the same.
- Agent-testable: a golden of the page PNG for one area; a unit test for the page layout maths
  (extent at a scale and a paper size, grid line placement); the devtool command in the sweep.

## Open

- The phone draws through MapLibre, the devtool through its own renderer; a sheet made on the phone
  must look like the phone's map, so it is a high-resolution snapshot of the MapLibre view, not the
  devtool renderer. The two differ in style parity (`StyleParityTest`), which the sheet makes visible.
- Multi-page sheets for a long route (a strip map, as route planners print) are a second pass.
- Label density at print scale: the map's z-based labels are tuned for a screen; at 300 dpi a page
  shows the equivalent of a much larger screen and wants more labels, not the same few enlarged.
- Benchmark (ADR 0039): what established apps put on a printed sheet and which scales they offer.
- About one wave for the devtool and the server; one more for the phone.

Plan: one task for the devtool and the server; the phone's sheet is a second task after it. A benchmark survey first (ADR 0039, one survey: what the established apps put on a printed sheet and which scales and paper sizes they offer). Then `devtool print --area <name> --scale 25000 --paper a4 [--landscape]` in a new `PrintCommand.kt` beside `RenderCommand.kt`, rendering through `MapRenderer` at 300 dpi; the page maths (extent for a scale and a paper size, UTM grid lines and labels from `geo/Utm.kt`, lat/lon as an option) in a new `shared/.../render/PageLayout.kt` with unit tests; the margins carry the title, the scale bar (`render/UnitFormat.kt`), true and magnetic north (`nav/Wmm.kt` at the sheet's centre and date), the contour interval, the registry's sources line and the date. The PDF through Apache PDFBox (Apache-2.0, `devtool` only, never `shared`; `licenseCheck` the proof), plus a PNG of the page in `out/` and a JSON report (scale, extent, dpi, time); a golden of the Rainier page; the server gets a `GET /print` route returning the PDF and the viewer a Print button that calls it, exercised by `tools/demo-check.py`. Out of scope: the phone (a MapLibre snapshot, its own task once this shows what a page holds), multi-page strip maps, print-scale label density beyond what the renderer's zoom choice gives.
