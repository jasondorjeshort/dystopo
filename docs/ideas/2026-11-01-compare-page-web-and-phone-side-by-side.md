---
date: 2026-11-01
kind: feature
related: [2026-11-01-one-definition-of-controls-and-settings-across-platforms.md]
---

# A compare page: the web panel and the phone's screen of the same place, side by side

The owner wants the two clients to put things "in generally the same categories and places". Nothing
shows the two together today. A `/compare` page (or a devtool command writing a contact sheet) takes
a place and the map options, renders the web page at phone width (`tools/demo-check.py --width 390`)
and takes the emulator's screenshot of the same view (`tools/android-check.py`), and puts them side by
side with the panel and the settings screen open on each. Where a control sits in a different category
it is visible without anyone describing it. The inventory the cross-platform definition needs falls
out of the first run.

## Open

- An emulator is needed for the phone half; the sweep already boots one, so the sheet could be a
  sweep part.
- Whether the comparison is a human read only, or a check (same labels in the same order) once the
  definition exists.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
