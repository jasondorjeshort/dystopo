---
date: 2026-11-14
kind: feature
related: [2026-11-14-report-recent-stats-in-place-of-report-the-last-start.md, 2026-11-10-real-phone-check-as-a-one-tap-self-test.md]
---

# Send diagnostics ends in a share, not a file the tester must find

The owner, 2026-11-14: "I did 'send diagnostics' but this just saves a text file locally that I can't find. Do I
need to find and paste it here? This should be sendable to github?" Settings › About and advanced › Report a problem
› Send diagnostics (T258, `reports/DiagnosticDump.kt`, `docs/reference/diagnostic-dump.md`) writes through the
system's save dialog (`SaveFile.bytes`) and says nothing about where; the doc already lists the share sheet (a
FileProvider) as the follow-up. A tester who does not remember the folder has no way on.

Recommendation: after the save, the row names the folder and file and offers two buttons, **Share** (the system share
sheet, via the FileProvider; the file stays scrubbed, `dystopo-dump 1`) and **Open GitHub issue**. GitHub's
new-issue link cannot attach a file and the dump (about 768 kB of log) is far past the 4,000-character body limit of
`ReportRow.kt`, so the issue opens with the dump's short header (version, device, the ring's last heap figures, the
`starts` line) as the body and the line "Drag the saved file onto this box", with the file name shown; the share
sheet is the way to a mail or a drive. The app still sends nothing itself.

## Open

- Whether the dump goes through the existing Report row's detail screen (one place for crash reports and dumps) or
  stays its own button.
- A smaller "summary" form of the dump that does fit an issue body, so no attachment is needed.

Plan: one pass in `androidApp/.../app/reports/` (`ReportRow.kt`, `DiagnosticDump.kt`) plus a FileProvider entry in
the manifest (its `paths` limited to `files/diagnostics/`). After the dump is made it is copied to
`files/diagnostics/share/` (the newest kept) and the row shows the saved name and **Share** (an `ACTION_SEND` with
`text/plain` and the FileProvider uri) and **Open GitHub issue** (the header-only body, the file name in the last
line; `GitHub issue` URL rules as in `android-app.md`, Problem reports). Debug route reports the share uri and the
issue url's length; unit test on the header-only body's length and scrubbing. Done when: `check` green and an
emulator run shows the share sheet receiving the file (`adb` screenshot) and the issue link opening with the body.
