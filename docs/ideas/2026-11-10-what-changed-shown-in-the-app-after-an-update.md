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

Plan: two passes; the tools first, with no Gradle, and this file stays for the second. Pass one:
`tools/sweep-summary.py` writes, beside the sheet, one crop pair per changed screen and a `changes.json` (screen,
step, the before and after files, their sizes, and a line of words the coordinator fills in from the notes);
`.github/workflows/release.yml` attaches them and `version.json` names the index, so an older app ignores it (a
format change, listed in the report, ADR 0033). Done when: the summary's test green on a fixture sweep; a draft
built from a test tag carries the index and the crops. Pass two, the phone: on the first launch after an update a
"What changed" card shows every changed pair (no count of which screens this phone opened: that would be a new
record of use), swipeable, "Later" keeping it under Settings, About; `GET /update` reports the index read and a
tour step opens the card from a fixture index. Before pass two, a survey (ADR 0039) of how apps present what is
new after an update.
