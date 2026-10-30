---
date: 2026-10-30
kind: feature
related: [2026-10-29-group-positions-without-signal.md, 2026-10-29-trail-conditions-phone-to-phone.md]
---

# Mesh breadcrumbs: a phone with no signal leaves its last fixes with passing phones

When a hiker is overdue, the useful data is their last known positions, and those sit on a phone
with no signal. With the group-positions transport, a phone could broadcast a small signed record
(its last few fixes and times, opt-in, encrypted to the user's own contacts) to any Dystopo phone
that passes within radio range; the carrier stores it and forwards it to the user's contacts or a
server when it next has a tower. A store-and-forward mesh of breadcrumbs, the way the sync bundle
already relays over LAN and server (`shared/.../sync/`). Open: it is the most sensitive data flow
the app could have, so it is opt-in per trip with a visible state, and the transport proposal's
attestation and the privacy rules come first; what the receiving side is (a contact's phone, a
rescue service's endpoint, both); battery cost of listening.
