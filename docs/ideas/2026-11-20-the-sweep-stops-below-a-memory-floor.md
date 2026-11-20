---
date: 2026-11-20
kind: chore
related: [docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md, docs/ideas/2026-11-20-a-failing-hook-says-why.md]
---

# The sweep stops below a memory floor

Wave 40's full sweep (`out/sweep-20261120-0131`) ran its emulator to a peak of 5.3 GB of private bytes; soon after, on 2026-11-20 at 03:26, every tool call on the machine began failing until a reboot, leaving a rerun sweep and its emulator running unattended with no agent able to stop them. The sweep's guard already reboots the emulator over 6 GB (`tools/sweep.ps1`), but it watches the emulator, not the machine.

Wanted: before each device part, `tools/sweep.ps1` reads the machine's free physical memory and commit headroom; below a floor (a named constant, set from a measurement of this machine) it stops the part cleanly, stops its own emulator by serial, writes the reading and a named reason to `steps.tsv` and `summary.json`, and exits red, so a sweep never leaves the machine too short to run the tools that would clean it up. The reading also goes into each part's line, beside the emulator's own figures.

The owner, 2026-11-20: "Record them all. We'll triage and sanity check later."
