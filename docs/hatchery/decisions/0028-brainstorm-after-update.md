# 0028. An update from the base ends in a brainstorm offer; ideas a project leaves to the base

Date: 2026-11-25
Status: accepted, superseded in part by 0029
Superseded in part by [0029](0029-machine-sibling-list.md), 2026-11-25: the first Deferred item lands
(a list on the machine, written once on the owner's word); item 5's last three sentences, the
project's closing line and step 2's variable `X` change with it. Everything else stands.

Record: no proposal. Additions to a decided practice (ADR 0026) on the owner's request; the one
independent review reads this record and its sibling. The exact edits, who applies them and the
checks: [the sibling](0028-update-offer-edits.md). Amends by additions ADR 0026 (items 1 to 3 and the
formats of its sibling, section 1), ADR 0019 (items 1 and 4: how Hatchery finds a request without a
diff, and the line its filing commit carries) and the update procedure of ADR 0016 (one check, one
closing step, three report lines) and ADR 0013 (a title convention on idea files). Every item of
those records stands. What the owner sees is the owner's: the request is quoted below, and the four
defaults of item 7 are stated for the owner to reverse with a word, as ADR 0026 did with its two.
Revised the same day after the review (three serious, four minor, all accepted): items 4 to 6.

## Context

The owner, 2026-11-25: "I'd like a...special case of the brainstorm skill where we (the child
projects) always brainstorm ideas to send back to hatchery after update-from-hatchery AND brainstorm
ideas to improve their own projects' workflow."

Evidence of the same day. The five ideas filed in commit b2fb6b2 all came out of two projects'
updates to the base at 6939e79, as frictions the updating agent saw, and reached Hatchery only
because the owner named each file by hand. Hatchery's `triage` step 2 finds pending carry-back
branches through the departure tables of the checkouts beside it (ADR 0019 item 4); nothing tells it
that a sibling holds an idea meant for it. Verified for this record: of those two projects, one is
not checked out beside Hatchery at all; four of the six checkouts beside it are two repositories
cloned twice; `queue-list.js ideas --json` prints each file's `title` and `block`, and reads whatever
tree is the working directory, so Hatchery's own copy can list a sibling's queue. The loop of step 2
split a sibling directory whose name holds a space; it is fixed in the same pass (edit T0). The
review ran the first draft's `git log --grep` on a fixture: a dot in a repository or file name read
`filed` from another file's line and the update deleted an idea nobody had filed, a bracket never
matched its own line, and both blocks hid the reader's failures. Checked against real history: a
dump of commit bodies compared line by line as strings finds a line whole and no near miss.

Constraints: Hatchery has no base line and never runs the update, so the special case exists only in
a tree that has one. ADR 0026's bounds stay: no agent is launched to brainstorm, nothing is written
unasked, nothing is offered at STOP. A separate skill was ruled out by the owner's wording. The
listing's output, the block grammar and the frontmatter keys (ADR 0013) do not change.

## Decision

1. **Trigger.** In a tree with a base line, every update that reaches its commit
   (`update-from-hatchery`, step 10) ends in an offer, made by the coordinator in the turn in which
   it reports the update to the owner. It is made at HOLD too and never at STOP. It does not count
   as the session's one prompted offer, and a session that already made one still makes it. An
   update that stopped before its commit makes none. With no candidate the offer is its header
   alone and its record reads `offered 0`, so "always" can be counted. An update follows the skill
   at the incoming sha (its step 2), so the first update that brings this text ends in an offer.
2. **Candidates.** Whoever ran the update lists up to nine, one line and an anchor each, each marked
   `base` or `project`; a subagent puts them in its report and the coordinator adds its own. The
   anchor decides: a base-owned or mixed path of `.claude/hooks/ownership.json` is for the base; a
   project-owned or base-excluded path (the project's `CLAUDE.md`, `.claude/project/`, its policy
   file, its own skills, its docs) is for the project's workflow. What the product should do is
   neither and waits for a triage or a wave close. A defect the update showed in a base file is not
   a candidate: it is filed as before, without an offer (ADR 0026 item 9), with the title of item 4.
3. **Shape.** One offer under the cap of nine, two headings in this order: `For the base (kept here
   until the base files them):` and `For this project's workflow:`. A group with no line prints
   `none`: the owner asked for both, and a missing heading cannot be told from a group nobody
   looked at. Over nine, each group keeps at least four lines if it has them. The header's second
   sentence counts by group and names the range `<FROM7>..<sha7>`. The bar applies to the project's
   group; a line for the base shows steps 1 and 2 only, because it leaves this queue when the base
   files it, and a tree at step 5 or 6 would otherwise hold back every friction. Source lines and
   the record name the trigger `an update`; the record's format is unchanged.
4. **The marker.** An idea a project leaves to the base (its anchor is base-owned or mixed and the
   project will not build it) has a title that starts `For the base: `. A file recorded from the
   base's group takes it, with no block; a line left unanswered takes it above its offer Question.
   Such a file is live while it has the prefix and `block` is `untriaged`. A live file is not the
   project's triage's, does not make triage due, and no pass builds it; in Hatchery, which has no
   base line, triage reads such a file like any other. Removing the prefix makes it the project's
   own again; a built change goes back by the `carry-back` skill (Send) as before. The listing
   prints `title`, so no reader changes and no skill greps an idea file.
5. **How Hatchery learns.** `triage` step 2 (Hatchery only) gains a second listing over the same
   sibling checkouts, read with Hatchery's own `queue-list.js`: one line per live file,
   `base-idea: <repo> <file>: pending | filed <sha7> (<dir>)`, then `base-idea: <n> pending, <m> not
   read`. `<repo>` is the name of the sibling's `origin` repository, else its directory name, so two
   clones of one repository list once. Hatchery's coordinator files each pending one as ADR 0019
   item 1 says: from the sibling's text, against the base files it names, no sibling named in the
   file, no prefix in its title, or as an entry on the Hatchery idea that already says it. The
   commit body carries `Base-idea: <repo> docs/ideas/<file>` on a line of its own, one per sibling
   file. `filed` is that whole line found in a commit body on `main`, compared as a string, trailing
   blanks aside, and never as a pattern: a name with a dot or a bracket matches itself and nothing
   else. A reader failure is never silence: a prefixed file whose `block` reads `?` prints
   `unreadable`, a listing that fails prints `listing failed` with the reader's first error line,
   both counted under `not read`. A sibling that is not beside Hatchery, on this machine or
   another, is read only on the owner's word: the brief names its directory, or a URL that is
   cloned shallow into the scratchpad, and the same listing takes it. Hatchery keeps no sibling list
   (ADR 0019 item 4 stands), so without that word such a sibling is not seen; the project's offer
   closes with a line that says so whenever it holds a live file.
6. **The loop closes at the next update.** Step 8 of the update prints every prefixed file as
   `filed <sha7>` or `kept`, by the same string comparison over the base's whole history up to the
   incoming sha (a range from the base line would miss a filing older than a clone's base line). A
   live file that is filed is deleted in the update's commit: its item, telling the base, is done,
   and the base's queue holds it. One exception keeps ADR 0019 item 6 true: a file that a record in
   the project's `docs/design/decisions/` names (a departure row's `Carry-back` cell may be "an idea
   file in the project") prints `named in <path>` and is not deleted while that record names it;
   it goes at the first update after the row does. Every other line is reported as printed, so a
   wrongly prefixed file, or one the reader could not read, is seen at every update.
7. **Defaults the owner can reverse with a word**, one line each:
   1. After every update that reaches its commit an offer is made, at HOLD too and never at STOP; it
      does not use up the session's one prompted offer; with nothing to offer it is one line.
   2. One offer of at most nine lines under two headings, `For the base` then `For this project's
      workflow`; an empty group prints `none`; the agent that ran the update reports the candidates
      and the coordinator offers.
   3. An accepted idea for the base is a file in the project's own `docs/ideas/` titled `For the
      base: `; the project's triage leaves it alone; a built change still goes by `carry-back`;
      the project's next update deletes its copy once Hatchery has filed it.
   4. Hatchery's triage lists such files from the checkouts beside it and its coordinator files
      them; a project elsewhere is read when the owner names its directory or URL to Hatchery.
8. **Surfaces (ADR 0013).** Additions only. No file written before changes meaning unless its title
   already starts `For the base: `; none does in Hatchery (the listing, 2026-11-25), and a
   project's first update prints any it holds (item 6). No Migration table: every part is
   base-owned and arrives in one update, and no project-owned file is edited. Where a project's
   ideas README says triage reads every untriaged file, `.claude/rules/docs.md` is the record. The
   prefix and the `Base-idea:` line are read across trees on different bases and keep their form,
   as ADR 0019 says of its landing line.
9. **Build.** One commit: a `tester` applies the sibling's edits to the six base-owned files it
   names, the `runner` commits, a `reviewer` reads it once, the `pusher` pushes on request.

Deferred, each with its trigger: a list of sibling directories or URLs kept on Hatchery's machine
and never tracked (the owner names the same sibling to a second triage, or a file prints `kept`,
untriaged, at two updates of its project); a `forBase` field in the listing (a third reader of the
prefix, or one title misread); the two groups' counts on the record line (a prediction or the owner
needs the split); candidates kept at STOP stays as ADR 0026 deferred it.

## Consequences

- Easier: a friction seen during an update reaches the owner the same hour and Hatchery's queue at
  its next triage, no file name carried by hand; the project's own workflow gets a standing turn.
- Costs: one more prompt per update, a line even when there is nothing; silence at a wrap-up now
  writes offer Questions for a second trigger. A prefix hides a file from the project's triage, so
  a wrong one is caught only by the update's report. Hatchery's triage reads other trees' working
  files, uncommitted ones included. The prefix and the `Base-idea:` line must stay the same in
  every skill and rule that states them. Sibling repository names appear in Hatchery's commit
  messages, as carry-back landings already do; two repositories of one name under different owners
  would share a `<repo>`, as they would in ADR 0019's landing line.
- Not solved: a sibling Hatchery cannot see still needs the owner's sentence; that is one sentence
  per sibling in place of one per file, and the deferred list is its lift.
- Exit: undo every edit the sibling lists except T0, in all six files, the rule file's bullet
  included. A prefixed title then reads as any title.

Predictions (the trigger scan reads these; superseding this ADR records each outcome first):
- Prediction, check by 2027-02-25: in every tree checked out beside Hatchery whose base line is at
  or past this ADR's commit, each `Update from Hatchery` commit is followed, before the next one, by
  a record line `Ideas: brainstorm offer <date> (an update)`.
- Prediction, check by 2027-02-25: Hatchery's `main` holds `Base-idea:` lines from at least two
  repositories, and `triage` printed `base-idea: 0 pending, 0 not read` at its last run.
- Prediction, check when the third `Base-idea:` commit is on `main`: each of the three sibling files
  was deleted by its project's next update (a `filed` line in that update's report), none by hand.
