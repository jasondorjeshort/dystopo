---
date: 2026-11-01
kind: feature
related: [2026-10-29-group-positions-without-signal.md, 2026-10-31-trip-plan-left-with-a-contact.md]
---

# The group's positions on a web page under the share code: the person at home watches the dots

ADR 0051 sends group positions signed and sealed under the share code through the relay
(`serverlib/.../RelayRoutes.kt`). The trip-plan-left-with-a-contact idea gives the contact a PDF. A
read-only page on the site, opened with the share code, that shows the group's last positions on the
map (and the plan's route and turn-back time beside them) is that idea made live: the contact watches
the dots move instead of reading a sheet, and sees when they stop.

## Open

- The sealed records are for phones holding the share code's key; a web page needs the key too (the
  code typed into the page, decrypted in the browser, never on the server) or ADR 0051 says no.
- Privacy by default: the page exists only while the plan's end time plus a margin has not passed.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
