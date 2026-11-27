---
date: 2026-11-26
status: resolved (T385)
promoted_to: docs/PLANNING.md
area: android / readout / GPS clock
---

# A GPS fix with a wrong time makes the readout restart every second; the cause is probably the receiver

## Question

The glance view showed "Phone clock went back 46 min, 1 s ago" four times in the last minute, then "No GPS fix"
with the elevation dimmed (held), and its text kept moving as fixes came and went. What does the message mean, and
why does it repeat?

## What was tried

Two owner screenshots of the glance view (a phone, release build), read against the code; no device run.
`Readout.clockCheck` (`shared/.../readout/Readout.kt`) restarts the estimator when an input is more than
`CLOCK_BACK_MS` older than the latest one held. `LiveReadout.feedFix` sets `offsetMs = fix.time - wall clock` on every
fix, and `onSensorPressure` stamps each barometer sample `wall + offsetMs`. The owner rebooted the phone afterwards
(a reboot usually fixes its GPS), so the fault could not be repeated.

## Result

- "Phone clock went back N min" is the readout's name for an input stamped N minutes before the newest time it holds.
  The elevation filter, the speed and climb windows and the fix history start over; the last height is shown dimmed,
  up to 1 min ("held", T362).
- The offset is 46 min on every line, with fixes at "no GPS fix": consistent with a receiver that lost its time
  (stale or default fix times). The pressure value stayed steady, so the barometer itself is not the suspect.
- **Why it repeats:** one fix with a wrong time moves `offsetMs`; every barometer sample after it is then stamped
  46 min off, so each sample is a clock-back and a restart. The estimator is reset by the offset logic, not only by the chip.
- Not confirmed: whether the wrong time came from the fixes themselves or from the offset step. The logging below decides it.
- **Layout (separate defect):** the glance view reserved a reason row only while a line had a reason, and its
  last-minute notes only while there were some, so the figures moved as signal came and went. Fixed on the branch:
  both areas are always reserved.
- The "Reset assistance data" action (T307: `delete_aiding_data`, `force_time_injection`, `force_psds_injection`) is the
  only way the app can ask the chip to reset its time; some phones refuse (`Gnss.resetAssistance`, `lastReset`).

## Resolution

T385 (2026-11-27). The readout orders device fixes and barometer samples on elapsed realtime with a base taken once from
the phone's clock (`ReadoutClock`; `docs/PLANNING.md` › The readout's time base), so a fix's own time can no longer move
it; replays and injected fixes keep their own times. A GPS fix more than 5 s off the phone's time is flagged and still
used (`GpsClockCheck`); the glance view's GPS area says "GPS clock unreliable" only when the fix's time is impossible on
its own, otherwise that the two clocks differ, and names "Reset assistance data" (never run by itself) once an
unreliable fault has lasted 60 s; the last minute keeps one note per fault. On the emulator at Paradise with the
barometer on and device fixes through the console, `POST /readout/skew` put the receiver 1024 weeks back for 90 s:
`clockLog` counted 76 fixes off, 76 implausible, one fault and 0 `readoutClockBacks`; the estimator's `clockResets` 0
and `lateInputs` 0 over 328 barometer samples; the position and the height kept updating, undimmed. 46 min and 18 s back
read as the neutral line with no restart; a phone clock set 10 min back with the receiver right read the same.

## Next

- The owner's check on a phone: read `GET /readout` › `inputs.clockLog` and `adb logcat -s DystopoClock` the next time it happens (counts,
  `chipVsPhoneMs`, the `source` of each `readoutClockBack`): `chipClockOff` lines mean the chip's and the phone's clocks disagree (`kind` `unreliable`: the chip's);
  restarts from `pressure:sensor` without them mean our offset logic is.
  With T385, `fault.kind` says which line the glance view shows, and `readoutClockBacks` should stay 0 for device inputs.
- Built by T385 (above): flagged, never dropped; the assistance reset is offered, not run by itself (an automatic reset
  is the owner's to ask for). Left: the receiver's raw time kept beside a recorded point (a track database migration).
