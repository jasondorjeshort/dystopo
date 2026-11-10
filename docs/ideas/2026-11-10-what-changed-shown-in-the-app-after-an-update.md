---
date: 2026-11-10
kind: feature
---

# "What changed" inside the app: the before-and-after pairs of the screens you use, after an update

Every alpha since v0.1.25 carries `dystopo-<tag>-changes.png`, the before-and-after sheet of every screen the
sweep found changed (`tools/sweep.ps1`, `tools/sweep-summary.py`'s `changes`), linked from the notes on the release
page. A phone updating through the app (`androidApp/.../app/update/Updater.kt`, from the release's `version.json`
read by `UpdateFeed.kt`) never sees it. The owner, 2026-11-10: "That would be nice."

- On the first launch after an update the app shows a "What changed" card: the pairs for the screens that exist on
  this phone (the map, the readout, the screens the user has opened), swipeable, with the one line from the notes
  under each; "Later" keeps it under Settings › About.
- The release carries the pairs as a small index (`changes.json`: screen, step, before and after crops) beside the
  sheet, written by the sweep's summary step, so the app downloads only the crops it shows, not the whole sheet.
- Agent-testable: `GET /update` reports the index read; a tour step opens the card from a fixture index.

## Open

- The sheet is one large PNG today; per-pair crops are a change to the sweep's output and the release workflow.
- Which screens count as "yours": those opened on this phone (a small per-screen counter) or all of them.
