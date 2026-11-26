---
date: 2026-11-26
status: open
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

## Next

- Read `GET /readout` › `inputs.clockLog` and `adb logcat -s DystopoClock` the next time it happens (counts,
  `chipVsPhoneMs`, the `source` of each `readoutClockBack`): `chipClockOff` lines mean the chip's time is wrong;
  restarts from `pressure:sensor` without them mean our offset logic is.
- A fix, for wave 49: stamp barometer samples on elapsed-realtime (not the last fix's time), and drop or flag a fix whose
  time disagrees with its own elapsed-realtime stamp by more than `ClockLog.OFF_MS`, with a visible "GPS clock
  unreliable" line instead of restarts. Optionally run the assistance reset by itself on a sustained `chipClockOff`.
