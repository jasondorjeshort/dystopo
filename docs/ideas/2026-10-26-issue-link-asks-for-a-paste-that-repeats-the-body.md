---
date: 2026-10-26
kind: bug
---

# Open GitHub issue asks for a paste that only repeats what the issue already says

The owner (2026-10-26): the prefilled issue ends "The full report is on your clipboard; paste it below.", and pasting gives the same text that is already there. Issue 23 shows it: its comment is the report pasted a second time.

## Cause

- `Report.shown` (`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/reports/Report.kt`) is what goes on the clipboard: the header, then every kept stack to 40 frames.
- `Report.body` in the same file is the issue body: the same header, the first stack's top frames that fit under 4 kB encoded, then `IssueLink.CLIPBOARD_LINE`.
- So the paste always repeats the header, and repeats the whole body when the report has no stack (an ANR, a close for memory or a crash taken from the system's exit record, issues 23 to 26) or when the stack is short enough to fit (issue 27, eight frames). `AFTER_OPEN` in `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/reports/ReportRow.kt` says the same under the button.

## Wanted

- When the body already holds everything `shown` would add, leave out the clipboard line, do not copy, and say under the button that the issue is complete: press Submit.
- Otherwise put only the missing part on the clipboard (the stacks past what the body carries, not the header), and say so: "The rest of the stack trace is on your clipboard; paste it below."
- The title-only link (a report too long for any body) keeps copying the whole text.
- The tests that compare the clipboard with the shown text (`equal` in `POST /reports/open`, `androidApp/src/test/.../reports/**`) move with it, and the privacy notice above the button keeps saying what is sent.
