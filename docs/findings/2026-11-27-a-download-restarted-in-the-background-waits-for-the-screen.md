---
date: 2026-11-27
status: open
area: android / area downloads, background resume
---

# An area download that the system restarts in the background waits for the screen

## Question

After wave 49's tile-cache gate (T379) and launch gate (T387), does an area download that was running when the
process died still go on by itself, as its row says ("Continues by itself")?

## What was tried

T387's verifier, 2026-11-27, on an API 35 emulator with the branch's debug build (head 52d6b681): a drawn area in a
public test area was downloading in direct mode (151 tiles); the process was killed with `kill -9` at 73 tiles with
the app in the background. No restart came by itself in three minutes; the pending job was then run with
`cmd jobscheduler run -f`.

T387's implementer ran the same kill on its own emulator: there the job started the process, the worker's foreground
request was granted, and the download went on from its cursor under its notification.

## Result

- In the verifier's run the job started the process and the areas' resume check started the download from its
  cursor, but Android refused the worker's foreground request (`ForegroundServiceStartNotAllowedException`: a
  foreground service may not be started from the background; logged by the resume code as its known case). So there
  was no notification and no notified work.
- With no screen and no notified job the tile cache's gate stays shut (ADR 0071, T379), and the download needs the
  whole index: it sat at its manifest, 0 fetched, for about a minute. Once the app was opened it went on at once,
  from 73 of 151 tiles to the end.
- Before T379 the same resume ran with no notification: silent work, which ADR 0071 rules out. So the stall is the
  rule working, not a fault of T387; what is missing is a start that Android grants, or a word to the user.
- Not measured: how often Android grants the foreground request to a job-started worker on a real phone (it depends
  on why the process was started and on the phone's state), and whether `main` before T379 behaved as described
  (read from the code, not run).

## Next

1. A task for the next wave: start a download's resume through a user-initiated data transfer job (the platform's
   way for a long transfer the user asked for, with its own notification; T156's follow-up named it), so that a
   restart in the background has a notification and the gate opens.
2. Until then the row's "Continues by itself" is untrue when the foreground request is refused: the row should say
   that the download goes on when the app is opened (wording for the owner, ADR 0031).
3. On a real phone: kill a long download with the app closed and read whether it resumes, with
   `dumpsys jobscheduler` and the notification shade.
