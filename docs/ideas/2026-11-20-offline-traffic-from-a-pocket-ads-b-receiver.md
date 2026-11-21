---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# Offline traffic from a pocket ADS-B receiver

What: read the network feed of an external receiver (Stratux-class devices broadcast GDL90, others a text feed in SBS
form, over the device's own Wi-Fi) so aircraft show with no phone signal and no daily quota. The phone joins the
receiver's access point and listens on its UDP or TCP port.

Why it suits Dystopo: it is the one way to get the aircraft layer fully offline, matching the offline-first rule.
Both protocols are public specifications, so the app writes its own parser; no GPL code (readsb, dump1090 and
librtlsdr are GPL, per the finding) is linked.

Data: none to license, the receiver is the source. The layer's source interface (beside the adsb.lol client and the
replay source) takes a third implementation; the aircraft model and card follow unchanged.

## Open
- On the receiver's Wi-Fi the phone usually has no internet; how the app binds to that network on Android.
- Which protocol first (GDL90 is the common aviation one; SBS is plain text and easy to replay in tests).
- Whether owning such a device is common in the audience; the owner may know.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.

Question: support external ADS-B receivers over their own Wi-Fi (GDL90 or SBS), the one way to fully offline traffic?
Recommended: only if you or testers own such a device; the protocols are public and the source interface takes a
third implementation cleanly, but Android's binding to a no-internet Wi-Fi and no device to test against make it a
speculative pass. If yes, SBS first (plain text, testable with the replay source), then GDL90.

Answer: the owner, 2026-11-21, by multiple choice: only if the owner or a tester owns such a device (recommended); then SBS first.
