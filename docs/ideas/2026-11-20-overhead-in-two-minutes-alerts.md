---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# "Overhead in 2 minutes": an alert when a flight will pass close or low

What: project each aircraft's track forward (position, ground speed, track, vertical rate) and notify when one will
pass within a set distance of the phone, or low over a nearby ridge, in the next few minutes. The alert names the
flight and the bearing it comes from.

Why it suits Dystopo: the sky views already compute bearing and elevation angle of every aircraft; the prediction is
the same arithmetic moved forward in time. Useful for photographers and spotters outdoors, and for noticing a
helicopter coming toward a hiker.

Data: the live positions only (adsb.lol, ODbL); no new source. The cost is the poll: an alert needs the layer running
in the background, but the plan forbids background polling to protect the 500-requests-a-day quota and the battery.

## Open
- Whether alerts may poll in the background at all, and at what interval; a foreground-only version is cheap.
- A notification is a new permission and flow, so the owner decides it.
- How accurate a straight-line projection is over two minutes (turns); the replay source can measure it.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.

Question: may the app alert you when a flight will pass close or low, and if so only while the app is open (an in-app
banner, no new permission) or also in the background (notifications and background polling, against the plan's rule
that protects the 500-a-day quota and the battery)? Recommended: the foreground-only banner as one task, after the
replay feed measures how far a two-minute straight-line projection drifts; background alerts stay out.

Answer: the owner, 2026-11-21, by multiple choice: the banner while the app is open (recommended); no background alerts.
