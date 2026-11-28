---
date: 2026-11-28
kind: feature
related: [2026-11-01-group-positions-on-a-web-page-under-a-share-code.md, 2026-10-31-trip-plan-left-with-a-contact.md, 2026-11-28-where-am-i-for-a-rescue-call.md]
---

# Moonshot: a live track for the person at home, with no server, through the kept folder

Source: coordinator (claude-fable-5-2), a wave close; offered and accepted by the owner; not sanity-checked. Moonshot.

The owner, 2026-11-28, accepting it with the photos idea: "#7 and #8 sound pretty cool. These are moonshots though
and there's a lot to do first."

"Keep a folder" (wave 49, ADR 0072) writes one GPX file per track into a folder the user picks, when a recording
ends. The folder can be one a cloud drive's app keeps in step with the user's account.

The idea: while a recording runs, its GPX file in the kept folder is rewritten every few minutes. Whoever can
open that folder elsewhere (the person at home, on a computer) sees where the walker was a few minutes ago, with
no server of ours, no account and no second app. The write happens under the recording's notification, which
ADR 0071 allows. Anchor: `androidApp/.../app/tracks/KeptFolder.kt`.

Lever: answers the group and contact ideas for the common case of one walker and one person at home.

## Open

- Whether a drive's app uploads a file rewritten in the background, and how soon; what it costs in data and
  battery; what the walker is told about where the track goes.
- ADR 0072's first prediction (the write through a temporary document) comes first: a reader must never see a
  cut-off file.
