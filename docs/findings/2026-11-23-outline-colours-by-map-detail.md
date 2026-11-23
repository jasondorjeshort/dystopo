---
date: 2026-11-23
status: open
area: android / offline maps, the downloader
---

# Outline colours by map detail: the survey, a palette and a second cue

## Question

`../ideas/2026-11-22-colour-the-other-maps-outlines-by-resolution-in-the-downloader.md` (the owner: "Which colours
is its own research project"): which colours, and which second cue, tell a coarse map's outline from a fine one's
and from the area being drawn?

## What was tried

One read-only agent (Sonnet-class) on `main` at 48bf56fe: the code read, eleven sources fetched in the run (five
more answered 403 or 404 and carry no weight), contrast ratios and colour-vision distances computed by a script of
its own. Nothing was viewed on a device. Product names stay here; none goes into user-visible text.

## Result

**The code today.** Every outline is one teal: `AreaOutlines.COLOR = "#00796b"` in
`androidApp/.../app/areas/AreasScreen.kt`, width 2 with a dash of 2 and 1.5, the box fill at 0.10 and the corridor
band at 0.18. The draft uses the same layer and colour as the saved maps, which is why the owner's draft and the
held map look alike. The night palette does not touch the outlines (2.7:1 on the night paper). Features carry
`kind` and the record id, no detail and no label. The paper is #f6f1e4 by day and #2f2816 by night
(`NightColors.of`); forest blends to #d2debf and #3f4429, water is #a5cbe9 and #23445d
(`shared/.../style/DefaultOsmStyle.kt`). The list's rows have state words (`OfflineMapList.kt`) and no swatch.

**Practice.** No offline-map manager found colours regions by detail: one open-source app colours state
(downloaded green, selected yellow, deactivated orange, not downloaded grey); a national elevation programme's
status maps colour coverage by type and status in a few distinct hues with a legend. Cartographic guidance for
ordered data is a lightness-led sequential ramp ("suited to ordered data that progress from low to high"); WCAG
1.4.11 asks 3:1 against adjacent colours and 1.4.1 a cue beyond colour. Two nested regions of different detail
is new ground.

**The palette recommended.** One hue, indigo, clear of the forest green, the water blue, the contour brown, the
draft's teal and the error red; ordered by lightness so more detail reads stronger against the paper (darker by
day, lighter by night). Contrast is WCAG's (L1 + 0.05) / (L2 + 0.05), against paper / forest / water.

| Level | Day | Contrast | Night | Contrast | Width, dash |
|---|---|---|---|---|---|
| Lowest (if the smaller detail is built) | #5f6f99 | 4.4 / 3.5 / 2.9 | #7d8aae | 4.3 / 3.0 / 3.0 | 1.5, dots 0.4 and 2.2 |
| Less | #4c4d95 | 6.6 / 5.3 / 4.4 | #a29dd3 | 5.8 / 4.0 / 4.0 | 2, dash 2 and 2 |
| Medium | #38287d | 10.5 / 8.5 / 7.0 | #c8b4f2 | 7.8 / 5.4 / 5.5 | 2.5, dash 5 and 2 |
| Full | #1f0a58 | 15.0 / 12.0 / 9.9 | #f0e6ff | 12.2 / 8.4 / 8.5 | 3, solid |
| Off (kept, read by nothing) | #67625a | 5.4 / 4.3 / 3.5 | #a09a8f | 5.2 / 3.6 / 3.7 | 1.5, dots 0.4 and 3, no fill |
| Being drawn | #00796b | 4.7 / 3.8 / 3.1 | #4fd1be | 7.8 / 5.4 / 5.4 | 3.5, solid, fill 0.12, corner handles |

Adjacent levels stay apart under the three common colour-vision deficiencies (CIE76 distance on Machado
simulations: no pair under 10; the closest are Less and Medium at night, 10, and Medium and Full by day, 12 to
13). One miss: Lowest on day water is 2.9:1, covered by the casing below, or darkened to #5a6a95.

**The second cue and the rest.** Width and dash both rise with detail ("thicker and more solid is finer"), so the
order survives without colour. A 1 px paper-coloured casing under every saved outline; saved outlines carry no
fill (stacked fills darken where maps overlap); the draft is the only filled shape. Coarse drawn first and the
finest on top, which matches ADR 0066's finest-wins rule; where two coincide the finer covers the coarser and the
list's Show action marks a hidden one. One label per outline, on its line, the level's name and the map's name
where it fits, sorted so the finest wins a collision. A key on the picker's card listing only the levels on
screen (a line sample and the name), and a swatch in the same line style on each Offline maps row beside the
state word. A map with no record takes its level from its metadata. One behaviour, no setting (ADR 0044: an
accessibility rule, no split in practice).

## Next

The build is the idea's Plan. For the owner's eye (ADR 0031): indigo against another hue and whether four steps
read apart outdoors; the casing's weight; the widths and dashes on a real screen; the draft's teal and the Off
grey at night; the label's place and whether the key sits on the card; the level names ("Less detail", "Medium
detail", "Full detail", "Not used", "Area being drawn").
