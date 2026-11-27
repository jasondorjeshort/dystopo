# 0072. The kept folder is a one-way GPX mirror with a private ledger

Date: 2026-11-27
Status: accepted

Record: task T380 of wave 49 (`docs/tasks/wave-49.md` [no-check: written at the wave's close]; its merge commit's
body has the report and the verifier's runs), the wave's critic of 2026-11-26, the owner's answer of 2026-11-26
on a hidden item ("The file stays"), and the survey made for the task under ADR 0039. The layout in full is
`docs/reference/android-tracks-and-sync.md`, "The kept folder"; this record is why it is that way.

## Context

The owner asked that tracks be kept as files in a folder of their choosing, so that a folder-syncing app or a
desktop tool has them without an export by hand. That makes a format other tools read (ADR 0033, `CLAUDE.md`
› Decisions), so it gets a record.

Two things constrained it.

- The first plan removed a file whenever its item was no longer in the store. The wave's critic blocked it: a
  store that opens empty, fails to load or is in the middle of a migration would then delete the user's whole
  backup, which is the one thing the folder exists to prevent.
- ADR 0071: nothing is written with no screen and no notification, and no notification is added to make a
  write legal.

The survey (the closest match writes a file after each recording, with a name template of date, time and an id)
found little published practice on renames, deletes or a lost folder. It did show one thing worth copying and
one worth avoiding: a date first in the name sorts the folder by time, and an id first in the name was changed
after users could not find their newest file.

## Decision

1. **One way.** The app writes the folder and never reads it back. A file edited there is not imported; Import
   stays the way in. The setup sentence says so before the first write.
2. **One GPX file per track and per route.** Tracks lie flat in the folder picked; routes in its `routes`
   folder. A track's waypoints are inside its file. A recording is written when it ends.
3. **Names** are `yyyy-MM-dd HHmm <name>.gpx`: the item's start in the phone's zone, then its name with the
   characters a file system refuses replaced. When the name is taken, by another item or by a file the app did
   not write, the item takes the first six characters of its id after the name. A written file keeps its name.
4. **A rename renames the same document; a change rewrites it in place.** Never a second file.
5. **A file is removed only by a recorded delete**: the item is absent and the store's op log holds its
   tombstone, from this phone or from another device by sync. An item merely missing removes nothing, and a
   store that cannot be read plans nothing at all.
6. **A hidden item keeps its file** (the owner, 2026-11-26).
7. **A private ledger is the only list of what the app may touch**: `files/kept-folder.json`, per device, never
   synced, not a setting, and no format anyone else may read. Per file: the item, the document, the name asked
   and the name given, a SHA-256 of the bytes written, the item's version. A file not in it is never renamed,
   rewritten or deleted. A missing or unreadable ledger reads as "no folder kept".
8. **A file of the app's that someone edited** is overwritten by the item's next change (the setup sentence
   warns of it) and is left in place by a delete.
9. **Writes happen on screen or under a recording's notification** (ADR 0071). A change that arrives in the
   background waits for the screen. The pass is not a launch stage.
10. **A lost folder or permission** stops the writing and shows a line with "Pick it again" and "Stop keeping";
    the waiting changes are worked out again from the store and the ledger. **Stop keeping** releases the
    permission and leaves every file.

What is promised to other tools: the folder's layout in items 2 and 3, and that each file is a GPX 1.1 document
with the item's id in `dystopo:id`. Nothing else, the ledger least of all.

Out of scope, each with what revives it:

- **Reading the folder back** (a two-way sync): a design of its own, with a critic; revived by the owner asking.
- **Free-standing waypoints** are in no file: revived when a user's backup is shown to miss them.
- **A write that cannot be cut short.** A rewrite truncates the document in place, so a failure in mid-write
  can leave a cut-off file until the next pass rewrites it (the ledger keeps the old hash). A temporary
  document renamed over the old one is the fix; it is a follow-up task of the next wave.
- **A whole rewrite after a folder is removed and picked again**: only changed items are rewritten today.
- **The name's second date**: a track with the default name repeats the date in its file name.

## Consequences

- The folder can be trusted as a backup: a fault in the app's own storage cannot empty it, and the verifier
  showed that on a device (the track database moved aside, the app restarted, every file still there).
- A delete on one device removes the file on every device that keeps a folder. That is the same user's delete,
  and the delete question already says it reaches their other devices; it is unit-tested, not shown between
  two devices.
- Files nobody deletes pile up: an edited file whose track was deleted, and every file after "Stop keeping".
  That is the safe side, and the user can remove them by hand.
- A user who edits a file in the folder loses the edit at the item's next change. One-way is the price of
  never deleting by mistake; the sentence at setup is the only warning.
- The ledger is per device, so two phones keeping the same synced folder would each write their own files for
  the same items (the second with a short id). Not supported; revived by a report of it.
- Rejected: **removing files for items missing from the store** (the critic's block, above). **An id first in
  the name** (unsortable by eye, as the survey's closest match found). **One file for everything**, or one per
  day (a rename or a delete would rewrite other items' data). **Writing from a background job** (ADR 0071).
  **Matching files by name with no ledger** (the app could then take a user's own file for its own).

Predictions.
- Prediction, check when the next wave closes: the write through a temporary document is built and no report of
  a cut-off file exists.
- Prediction, check by 2027-02-27: no report, from the owner's use or a sweep, of a file removed that the user
  did not delete.
