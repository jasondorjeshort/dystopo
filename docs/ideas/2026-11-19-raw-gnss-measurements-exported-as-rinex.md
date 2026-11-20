---
date: 2026-11-19
kind: feature
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

Question: do you want raw GNSS measurements logged during a recording and exported as a RINEX observation file for
post-processing? Recommended: no for now, leave it at rest. It serves a few surveying-minded users, needs the
developer option for full tracking to be useful, adds about 1 MB an hour, and a RINEX writer is a format other tools
read (a proposal first by the project's rules); the per-point signal idea covers the everyday "why was my track
wobbly" question at a fraction of the cost.

Answer:
