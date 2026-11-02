---
date: 2026-11-02
kind: feature
related: [2026-10-29-group-positions-without-signal.md, 2026-10-30-mesh-breadcrumbs-for-search-and-rescue.md]
---

# When the estimate gives up, the last known position becomes a record the group sees

T203 draws a grey dot and "No position, last known 12 min ago" when the terrain estimate passes its spread.
That moment is exactly what a companion or a rescuer wants: the last place the phone was sure of, with
the time, the heading it was taking and the spread when it gave up. Make it a record in the group channel
(ADR 0051's sealed positions over the LAN link and the relay) so every phone in the group shows the grey
dot with its time, and keep the record on the phone so the paper sheet (T183) and the trip report (T198)
carry it. The group-positions idea covers live positions; this is the one position that matters when the
live ones stop.

## Open

- Sent automatically or on a tap (a new data flow, the owner's call).
- Whether the record carries the terrain estimate's path since the last fix (small) or only the point.

Recorded 2026-11-02 at the close of wave 26 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
