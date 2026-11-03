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

2026-11-02 (triage): ADR 0051's group positions are decided, not built: stage 1b waits on your two answers
on the board, the relay accepts `RELAY` frames only and no `GROUP` purpose or `DPOS` record exists. The
page has no crypto, and WebCrypto lacks ChaCha20-Poly1305, so the browser would need its own sealing and
signature code. The page lifetime the idea wants belongs to the trip-plan idea, also unanswered.

Question: does a browser at home count as "between copies of the signed app" (your condition of
2026-10-29)? A browser cannot attest itself; the page would hold the share code and decrypt locally, which
keeps the server blind but lets anyone with the code watch. Recommendation: hold until stage 1b is built;
then, if you say a contact's browser may watch, a proposal that amends ADR 0051 (a `GROUP` frame the
page can read, the page's lifetime tied to the plan's end). Not before.
Answer:
