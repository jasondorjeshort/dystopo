---
date: 2026-10-29
kind: feature
related: [2026-10-29-trail-conditions-phone-to-phone.md]
---

# A group sees each other's positions with no signal

A party that splits up on a trail, with no cell service, sees where each member last was and when.
Phone to phone pack sharing already exists (T78, T90: `androidApp/.../share/**`, NSD discovery,
the sealed share link, `peer://` sources), and T78 designed but did not build a Bluetooth LE
discovery payload. This idea reuses that trust model (the share code pairs the group) for small,
frequent position messages instead of bulk packs.

Transports, cheapest first:

1. **One phone's hotspot or a shared network:** positions over the existing share listener; works
   today's way at a camp or a hut, range of a Wi-Fi network.
2. **Bluetooth LE advertisements:** a sealed position (a few bytes: id, time, position, battery) in
   the advertisement, read by any paired phone in range (about 50 to 200 m outdoors), and passed on
   by each phone that hears it, so a strung-out line of hikers relays positions along itself.
3. **A LoRa radio carried by the user:** a pocket radio paired to the phone over Bluetooth reaches
   kilometres in line of sight. Talk to a radio's documented serial or Bluetooth protocol directly.

- **Shown:** each member as a dot with an age ("12 min ago") and a fading trail; a member out of
  range keeps the last known position, labelled as old.
- Agent-testable: two emulators with the share pairing exchange positions over the LAN path; a
  debug route injects a member and its age; the BLE path tested on real phones only.

## Open

- A new data flow of live positions, the most sensitive data the app has: the owner decides whether
  to build it at all, and who sees what; default off, on per trip, visibly on while it runs.
- Licences: the popular open LoRa mesh firmware and its protocol definitions are GPL, so the app
  must not include their code or generated files; check what a clean implementation of the radio
  protocol may use, or support only radios with a permissively documented protocol.
- Battery: BLE advertising and scanning in the background against the T150 rule that the app rests
  in the background; measure on the phone.
- Android: Bluetooth permissions (nearby devices, API 31 and up) and background scanning limits;
  foreground service type.
- Safety wording: this is not an emergency beacon, and the app must not suggest it is.
- Three or more waves; stage 1 alone is about one.
