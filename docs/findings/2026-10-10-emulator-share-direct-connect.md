---
date: 2026-10-10
status: open
area: android / emulator networking (phone-to-phone sync)
---

# Two emulators on one host see each other over NSD, but a direct connect between them is not reliable

## Question

Phone-to-phone sync (T54) needs one emulator to reach the other's share listener. Can two emulators
on one host connect directly?

## What was tried

Two emulators on one host share a virtual Wi-Fi network (`wlan0`, 10.0.2.18, 10.0.2.20, ...), so NSD
discovery works. A direct connect to the peer's `10.0.2.x:8841` worked for the T54 implementer and
failed for its verifier on the same host (commit `1b0aedf`, 2026-10-10).

## Result

The tested path goes through the host: `adb -s <A> forward tcp:8841 tcp:<A's share port>`, then on B
`POST /share/connect?addr=10.0.2.2:8841&code=<A's code>` (10.0.2.2 is the host from an emulator);
remove with `adb -s <A> forward --remove tcp:8841`. Documented in
`docs/reference/android-tracks-and-sync.md`. The relay (T72) needs no forward: both emulators reach
the server at `http://10.0.2.2:<port>`.

## Next

Find out why the direct path is not reliable (emulator version, Wi-Fi state on API 35 images, the
order the two emulators boot), or confirm it on two real phones on one Wi-Fi network, which is the case
that matters (T78 peer sources rely on it).
