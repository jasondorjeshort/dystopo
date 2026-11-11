---
date: 2026-10-31
kind: feature
related: [2026-10-30-mesh-breadcrumbs-for-search-and-rescue.md, 2026-10-30-safe-return-planner.md]
---

# A trip plan left with a contact, and a back-by-itself message

The way-back anchor, the deadline and the route are a trip plan. Before leaving signal, send it to a contact as a
sealed record over ADR 0051's transport: the route, the trailhead, the turn-back deadline and the expected return
(the range's slow end plus the margin). When the recording returns to the trailhead the phone sends "back" by itself
on the next signal; if the expected return passes with no "back" and no signal, the contact's copy of the app shows
"overdue since 18:40, last known position <the last breadcrumb>" and offers the number to call. The breadcrumbs idea
supplies the last positions; this idea supplies the plan and the overdue state. A new data flow, so the owner's word
and ADR 0051's stage order first; the phone-to-phone copy needs no server beyond the relay. Open: the wording of the
overdue card so that it is advice, not an alarm; how a late "back" cancels an alert already shown.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Question: sending a trip plan to a contact and raising an overdue card on the contact's phone is a new data flow and a
safety message; do you want it, and must the contact run the app? Recommendation: yes, but not before ADR 0051's stage
1b (groups and transport) is built, and through a proposal, because ADR 0051 does not carry it: its relay keeps a
`GROUP` frame 30 minutes (`serverlib/.../RelayRoutes.kt`, the planned lifetime) while a plan must wait hours for a
contact who is offline, and its groups are share codes among walkers, not a one-way contact. The proposal settles the
mailbox lifetime, the contact's pairing (a share code with a "watcher" role), the overdue rule (the range's slow end
from `routing/PastEffort.kt` and `routing/PaceFit.kt` plus the margin of `nav/TurnAround.kt`), how a late "back"
withdraws a card, and the wording (advice, never an alarm). A no-app fallback (a plain text the user sends from the
share sheet with the route link and the expected return, no automatic messages) needs no new flow and could ship
first as one task in `app/share/`; that is the piece to approve separately if wanted.

Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
