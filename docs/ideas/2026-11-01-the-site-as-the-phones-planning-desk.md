---
date: 2026-11-01
kind: feature
related: [2026-11-01-share-links-carry-the-map-options.md]
---

# The site as the phone's planning desk: plan on the big screen, open it on the phone

The browser page has the trip editor (`tools/demo/trips.js`, `trip-sync.js`) and the sync peer
(`serverlib/.../SyncRoutes.kt`, `docs/SERVER.md` › The sync peer); the phone has the sync client. The
pieces to plan a route at a desk and have it arrive on the phone exist; what is missing is the pairing
flow between a browser session and a phone. A QR code on the site that the phone scans (the sync
settings' server, token and user in one), then the trip appears in the phone's list.

## Open

- Whether the browser's trip replica (T92) and the phone's share the same user slot, or the site acts
  as a second device of the same user.
- Offline: the phone needs the corridor pack along the route (T130's corridor); the site could build
  it and the phone pull it in the same pairing.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): sync both ways exists, and the trips editor's push already reaches a phone whose sync
settings hold the same server and token. The phone's Share screen scans a QR (`share/ShareLink.kt`), but
its link carries a relay address and share code only and deliberately syncs nothing; the page has no QR
renderer. What is missing is exactly the pairing: a link or QR carrying the server, the token and the user.

Question: do you want a pairing flow in which the site hands a phone its sync credentials, and should the
site act as a second device of your user? Recommendation: yes, as a proposal with a critic, because the
link carries a bearer token (a credential in a QR is a new data flow and a security choice), the page needs
a QR encoder (a new dependency or a hand-written one), and the site-as-second-device question fixes how
the browser's replica and the phone's share a user. The corridor pack pulled in the same pairing comes
after.
Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
