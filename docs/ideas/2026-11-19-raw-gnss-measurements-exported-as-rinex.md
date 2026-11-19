---
date: 2026-11-19
kind: feature
related: [2026-11-19-a-gps-view-and-a-gnss-health-check.md]
---

# Raw GNSS measurements exported as RINEX

Android exposes raw pseudoranges, carrier phase and Doppler (`GnssMeasurementsEvent`, Android 7 and up; full
tracking behind the developer option "Force full GNSS measurements"). Logged during a recording and exported as a
RINEX observation file beside the GPX, they let a user post-process the track against base-station data to a
decimetre or better. Niche, but it fits an open-source tool for people who care where they were. A writer for the
format in `shared` (from the published RINEX 3 or 4 specification), a switch on the recording, the file in Share.

## Open

- Whether the owner wants it at all (a Question for triage).
- File size: about 1 MB an hour at 1 Hz with 30 satellites; a cap and a note.
