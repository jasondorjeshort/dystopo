---
date: 2026-11-22
kind: chore
---

# ADR 0064's T322 prediction and ADR 0059's per-release check are due

The triage scan of 2026-11-22 found two predictions with their triggers met and no Outcome:
`docs/design/decisions/0064-the-panorama-view-one-view-a-lens-two-ways-to-steer.md` ("check when T322
is merged: the round plot's placed names at Paradise are the same before" and after) and
`docs/design/decisions/0059-no-built-in-server-locations.md` ("check at every release's `apk-strings`
sweep part: the release APK's strings hold no host").

Plan: a Sonnet-class chore, read-only on code. For 0064: compare the round plot's placed names at Paradise
before and after T322 (the horizon test or golden in `shared`, or `devtool` on the two commits). For 0059:
read the `apk-strings` result in the closing sweep summaries of waves 44 to 46 under `out/`. Deliverable:
an Outcome on each prediction line (held or failed, with the evidence); 0059's line stays open for later
releases, so its Outcome is dated. A failed one also gets a finding. Out of scope: code changes.
