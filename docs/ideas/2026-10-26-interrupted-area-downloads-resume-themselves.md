---
date: 2026-10-26
kind: feature
related: [2026-10-26-downloads-without-a-size-cap.md]
---

# Interrupted area downloads resume themselves: on regaining service, on a schedule, not only at launch

The owner (2026-10-26): losing service during a big download is normal, so the phone has to pick the download up again later without being asked, and not only when the app next starts (which can be days away). The check has to be cheap: it must not scan the downloads folder each time. Whether yesterday's crash was this is unconfirmed (see Open).

Distinct from `2026-10-26-downloads-without-a-size-cap.md`, which is about what may be started; this is about what happens to one that is cut short. They meet at the progress cursor below.

## Seen (from reading the code, not run on a device)

- The download is a daemon thread of the app process (`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/areas/AreaDownload.kt`, `start`), with no foreground service or scheduled job behind it. A killed process ends it, and nothing at launch restarts it (`Areas.attach` in `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/areas/Areas.kt` only reads the records and starts trip corridors).
- The saved record keeps `state = "downloading"`. `AreasScreen.kt` hides Retry and Download again for that state and shows Dismiss only for a failed one, so the card is stuck: Show, Pin, Rename and Remove (which throws the tiles away).
- `TripCorridors.reconcile` counts a record left "downloading" as current, so a killed trip corridor is not restarted either.
- A resume re-walks the whole manifest and asks the cache about every tile. Cheap at thousands of tiles; at the hundreds of thousands a large area has, that walk is itself the scan.

## Wanted

- **A cheap check.** Look only at the records of unfinished areas (state downloading or partial, with no live job): a read of the small areas file, a few entries, nothing from the tile cache. With nothing unfinished it returns at once.
- **Triggers, all feeding that one check:** app start; the app coming to the foreground; regaining connectivity (a network callback while the process lives, and a scheduled job with a connected-network constraint for when it does not, which also survives a reboot); and a periodic run. Regaining Wi-Fi is one more connectivity event, not a gate: nothing is limited to Wi-Fi (`docs/design/decisions/0023-no-wifi-gates.md`).
- **Coalescing.** One area download at a time stays the rule; triggers that arrive while one runs, or while a check is queued, make one run.
- **A progress cursor.** Tiles are fetched low zoom first, so the record keeps where it got to (a zoom and row, or a count of completed rows) and a resume starts there; the failed tiles are kept as rows. Resume cost then follows what is left, not what the area is. This is also what the row-based cover in the size-cap idea needs.
- **Repair at start.** A "downloading" record with no live job becomes "partial", so Retry shows at once, and a trip corridor in that state is restarted.
- **Not against the user.** A cancel is the user's: a cancelled download does not auto-resume. A budget refusal does not loop. A run that fails repeatedly backs off and stops at "partial" with Retry, rather than retrying forever.
- **Surviving the background.** A long run needs a foreground service or a long-running scheduled worker with a notification (progress, Cancel), so the system does not kill it for being in the background.
- **Agent-testable.** A debug route runs the check now and reports JSON (unfinished ids, what it started, how long the check took); the test is: start a large download, `am force-stop`, restart the app or toggle the network, and see it resume from the cursor.

## Open

- **Was yesterday's crash this?** The previous run's exit reason is kept on the phone: Settings › About and advanced › Report a problem (T145), and the Diagnostics row's last exit (T143). If the system killed the app for memory or it crashed during a download, that's the first piece of evidence.
- Android 15 limits how long a data-transfer foreground service may run in a day (recalled, not checked); if it applies, a long download needs pausing and resuming across that limit, which the cursor allows.
- The notification permission on Android 13 and later.
- Whether the periodic run is needed once the connectivity trigger exists; its interval is a battery cost (`docs/ideas/2026-10-13-battery-efficiency.md`).
