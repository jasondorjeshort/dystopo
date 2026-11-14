---
date: 2026-11-14
kind: feature
related: [2026-11-07-downloads-as-a-synchronisation-in-cells.md]
---

# A power-cut part in the sweep: the emulator stopped hard in mid-write, then the app must recover

T279's damage (tiles written in the seconds before a hard stop of the device came back as zero-byte files, served
as hits for ever) was not found by a test: nothing in `tools/sweep.ps1` ends the guest without a shutdown. The
owner calls a reboot in the middle of a download "a fairly common scenario in the back country" and "pretty
essential" (the cells idea, ADR 0060). A part that stops its own emulator hard (the emulator's process by PID,
never by name; `tools/android-emu.ps1`), boots it again and asserts recovery, at three moments: during an area
download, during a recording, and during T267's move of the kept tiles while that code lives. Recovered means: no
crash line, no tile served empty, the download resumes and fetches little twice, the recording is kept up to its
last flush, the track database opens. The owner, 2026-11-14: "Okay".

Recommendation (the coordinator): one task; the download case first, the one that already bit, reporting the bytes
fetched twice, which is the figure ADR 0060's step 3 asks of its real-phone checks.

## Open

- Whether a hard stop of the emulator loses the guest's unwritten pages as a phone's power loss does (T279's merge
  body says how the damage was reproduced). Test it in isolation before the part relies on it.
- The moment: a debug route that says "writing now", or a fixed delay after the download starts.

Plan: one pass, the download case first. Before the part is written, a test in isolation: does stopping the
emulator's process (by PID, through `tools/android-emu.ps1`) lose the guest's unwritten pages as T279's damage did;
if it does not, the report says how T279's was made and the part uses that. Then a sweep part `powercut` in
`tools/sweep.ps1` with a script of its own: an area download over Rainier started, the emulator stopped hard a
fixed time after the first tile is stored (a debug route's count, not a guess), booted again, and the assertions:
no crash line, no tile served empty, the download resumes with no tap, and the bytes fetched twice reported (the
figure ADR 0060's step 3 asks for). Done when: the part green three times running, and red with T279's fix
reverted on a scratch branch. Out of scope, the next passes from this file: the same during a recording and during
T267's move.
