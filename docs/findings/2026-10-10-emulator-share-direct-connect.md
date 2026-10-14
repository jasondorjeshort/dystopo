---
date: 2026-10-10
status: resolved
area: android / emulator networking (phone-to-phone sync)
promoted_to: ../reference/android-tracks-and-sync.md
---

# Two emulators on one host see each other over NSD, but a direct connect between them is not reliable

## Question

Phone-to-phone sync (T54) needs one emulator to reach the other's share listener. Can two emulators
on one host connect directly?

## What was tried

Two emulators on one host share a virtual Wi-Fi network (`wlan0`, 10.0.2.18, 10.0.2.20, ...), so NSD
discovery works. A direct connect to the peer's `10.0.2.x:8841` worked for the T54 implementer and
failed for its verifier on the same host (commit `1b0aedf`, 2026-10-10). T78's verifier then saw two of
its three emulators never get a Wi-Fi carrier, and its peer downloads through the host (an `adb forward`
reached at 10.0.2.2) arrived with every 4 MiB reply shorter than its Content-Length.

T90 (2026-10-13, emulator 37, two x86_64 API 35 emulators on this host, both with Wi-Fi):

- Direct: the receiver added the sharer at `10.0.2.16:8841` (2.5 s), synced a track from it (9 operations
  pulled and applied) and downloaded the Rainier pack (151 MB in 71 s, both SHA-256 matching).
- Discovery: the receiver saw the sharer's announcement 42 to 57 ms after the sharer's mDNS probing
  finished (0.77 to 1.02 s after its listener opened); resolves took 2 to 8 ms.
- Through the host: with Wi-Fi on, the download through the `adb forward` ran whole at 2.3 to 2.8 MB/s,
  also through a relay reading the forward at 220 KB/s per connection. With the receiver's Wi-Fi off
  (`adb shell svc wifi disable`; 10.0.2.2 stays reachable over `eth0`, the emulator's user-mode network)
  and the sharer on `main`, every reply was cut and the download failed in 43 s. The emulator's
  throttling profiles (`-netspeed gsm`, `emu network speed gsm`) did not slow either path measurably.

## Result

On emulators the direct path works when both have a Wi-Fi carrier; an emulator without one is the
unreliable case (its only path to the other is through the host). The cut replies were the sharer
closing its socket right after the body on the user-mode network path, not the speed: T90's share
listener (`share/ShareListener.kt`) waits for the client to close first and has a write deadline, and the
same Wi-Fi-off path then delivered the pack whole in 3 of 3 runs (18 to 20 s, hashes matching, no piece
shrinks). Measurements in `../PLANNING.md` › Sync › Peer sources on a trip; how to test the slow path in
`../reference/android-tracks-and-sync.md`.

## Next

Two real phones on one Wi-Fi network are the case that matters; that check stays with the owner. If an
emulator comes up without a Wi-Fi carrier, restart it rather than debugging the direct path.
