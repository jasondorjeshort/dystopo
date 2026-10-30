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
