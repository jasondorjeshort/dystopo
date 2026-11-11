---
date: 2026-11-01
kind: feature
related: [2026-10-30-paper-backup-sheet-with-turn-back-times.md]
---

# Print from the site as the paper-backup flow

The printed map sheet exists on the phone, in devtool and in the page's Print box
(`tools/demo/index.html`, `docs/reference/live-server.md`; T167). A browser on a desk is where printing
is natural; the phone is where it is awkward. Make the sheet the site's headline second flow after the
map: a route (planned on the site or opened from a link), the turn-back times of the paper-backup idea,
a UTM grid, A4 or Letter by region, one click to a PDF at a true scale.

## Open

- The paper-backup idea's turn-back times need the route and a start time; the site has no clock
  of the hiker's pace unless the trip carries it.
- Whether the site's sheet and the phone's sheet come from one renderer (they do today through
  `shared`) and stay pixel-identical.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): `/print` lives in devtool (`PrintRoutes.kt`, PDFBox and the devtool renderer), so the
Print box shows on `devtool serve --live` only and the home site answers 404 (`live-server.md`). It takes
a point, `area=` or `bbox=`, paper by region, `scale=` and `grid=utm`; no route and no start time. The
phone's sheet and devtool's share `PageLayout` and `SheetGrid`, not pixels.

Question: is print the site's headline second flow, worth moving the sheet renderer and PDFBox into the
server? Recommendation: yes, as a proposal with a critic (PDFBox, Apache-2.0, becomes a dependency the
public server leans on, and the renderer move is the first time the standalone server draws anything):
pass 1 moves `/print` to serverlib and the box appears on the site; the route on the sheet and the
turn-back times come with the paper-backup idea, which waits on your answer there.
Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
