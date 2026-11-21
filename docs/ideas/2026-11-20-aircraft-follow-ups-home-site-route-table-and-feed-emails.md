---
date: 2026-11-20
kind: chore
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# Aircraft follow-ups: the home site refreshing the route table daily, and the owner's emails to two feeds

Two loose ends the aircraft plan names.

**The home site refreshes the route table daily.** The phone ships a CC0 route table (VRS standing data, per the
finding) so origin and destination need no call-sign lookup. Routes change; the home site would fetch the current
table daily, rebuild the shipped form and let phones download the update as they do other packs. It needs an ADR first
(a scheduled job on the home site, a format phones read, the table's size, the plausibility check beside it).
Open: the cadence, and how a phone fetches it with no Wi-Fi gate (ADR 0023).

**The owner's emails.** The aircraft idea's Answer accepts adsb.lol's archived API repository and 500-a-day quota as the
first release's risk, "with emails to two other community feeds asking whether a free public app may use them". The
finding names adsb.fi and airplanes.live (documented as non-commercial or personal, with no stated position on public
apps). This is an owner action: an agent does not write to outside parties. A yes buys a fallback source behind the
same source interface.
Open: who sends them and from which address; whether a no or silence changes anything before the first release.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.

Question: start the ADR for the home site's daily route-table refresh now, or wait until your phone shows how often
cards read "Route unknown" with the shipped table (already asked under Waiting on user)? The two emails to adsb.fi
and airplanes.live are yours to send, from your address. Recommended: wait on the route table until you report how
many routes are missing (a stale table may serve for months), and send the emails before the next flights release so
a fallback source is known; nothing in the app waits on either.

Answer:
