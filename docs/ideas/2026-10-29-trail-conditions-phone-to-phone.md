---
date: 2026-10-29
kind: feature
related: [2026-10-29-group-positions-without-signal.md, 2026-10-29-trail-corrections-from-tracks.md, 2026-10-23-osm-trace-upload.md]
---

# Trail conditions that spread from phone to phone

A washout, a bridge out, the snow line, a closed road: the people who know are the ones coming
down the trail, and there is no signal to post it. Let a user pin a short note to a place, and let
notes copy between phones that meet (at the trailhead, at a hut, passing on the trail) with no
server, so a report made on Monday reaches Thursday's hikers through whoever walked between. A
delay-tolerant gossip network over the share machinery the app already has
(`androidApp/.../share/**`: NSD discovery on a shared network, the share listener and client, the
sealed share link of T78 and T90) and the sync model of `shared/.../sync/` (`Ops.kt`,
`SyncReplica.kt`: a note is an op with an HLC, so two phones merge the same way two of the owner's
devices do). The group-positions idea moves live positions inside one party; this moves a few
hundred bytes of public text between strangers, which is a different trust question.

- **A note:** place, time, a type from a short list (closed, hazard, water, snow, trail state), a
  line of text, a sender id that is a random per-install key, a signature. Expires by type (a
  washout in months, "water running" in weeks).
- **Carry:** a phone keeps notes for the areas it holds packs for; on meeting another phone (today's
  share pairing, or the BLE advertisement the group idea wants, carrying only a notes digest) each
  side sends what the other lacks for areas both hold. No pairing code for public notes: the
  transport is open, the signature is what identity there is.
- **Shown:** a marker layer with the note's age in the label; a note the user did not write can be
  confirmed ("still so") or contradicted, and the counts travel with it.
- Agent-testable: the sync simulator (`sync/SyncSimulator.kt`) exchanges notes between replicas with
  expiry and asserts convergence; two emulators meet over the LAN path and a debug route lists what
  each holds; `devtool` prints a note store as JSON.

## Open

- Abuse: an open channel carries spam and lies; expiry, confirmation counts, a cap per sender per
  area and a mute are the first-pass answers, and the owner decides whether open relay is wanted at
  all or only between paired phones.
- Privacy: a note reveals that its author was at a place around a time; the app says so when the
  user writes one, and the sender id rotates by area or by month.
- Where there is signal, the same notes could reach a relay (the server of `docs/SERVER.md` as one
  more peer); then the phone-to-phone path is the offline tail of a normal feed.
- Benchmark (ADR 0039): how established outdoor apps collect and show user conditions reports, and
  how long they keep them.
- Two waves: notes with sync between a user's own devices first (worth having alone), the meeting
  exchange second.
