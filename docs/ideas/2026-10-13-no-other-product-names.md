---
date: 2026-10-13
kind: chore
status: planned
pipeline: T104 (the check and the renames); the rule is in CLAUDE.md
related: [2026-10-13-avalanche-slope-shading.md, 2026-10-13-external-integrations.md]
---

# Do not name other mapping software in user-visible text

Owner, 2026-10-13: the rule applies to user-visible text only. Attributions in code are fine, and
good: they help keep us consistent with other apps, which is a secondary doctrine. So KDoc, code
comments, the README, docs and the dated log may keep naming other products.

User-visible text means anything the app, the browser viewer or the devtool prints to a user:
labels, help text, option rows, error messages, `--help` output ("CalTopo bands" in the Map
options is the example).

Where it appears in user-visible text today (found by search, not yet changed): the Map options
row (`androidApp/.../options/MapOptionsSection.kt`) and the devtool `--slope-overlay` help
(`MapRenderer.kt`). The browser viewer and the app strings need one more search before a cleanup.

## Open

- Named integrations (the external-integrations idea) must name the service they connect to; that
  is an exception, as are licence and attribution lines the app shows for a data source.
- If adopted: a one-line rule in `CLAUDE.md` plus a test over user-visible strings, and a small
  cleanup task (rename the row and the help text; ties to the slope shading idea).
