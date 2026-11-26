---
name: brainstorm
description: Offer the owner new ideas (at most nine: near-term, intermediate, moonshot), write only what their answer or their silence calls for, act on answers to earlier offers, and run the rest review. Use after a triage, at a wave close or a release, after every update from the base (two groups, for the base and for this project's workflow), when work sparks an idea worth keeping, or when the owner asks for ideas or for the resting ones.
---

# Brainstorm

An offer is at most nine ideas put to the owner after a triage, at a wave close or a release, after
every update from the base (the section "After an update from the base"), when work sparks one, or
when the owner asks. Nothing is written unasked: an idea nobody asked for and
no task implies is written only on the owner's answer, or, when none comes, as a question in the
queue they already read. Decision and reasons: ADR 0026 and its sibling file `0026-brainstorm-contract.md`, and
ADR 0028 for the offer after an update, in `docs/hatchery/decisions/`.
The block grammar of a file is `docs/ideas/README.md`; the reader of every state below is
`node .claude/hooks/queue-list.js ideas --json`, and no step here greps idea files for state.

Only the coordinator offers, because only it can ask the owner. No agent is launched to brainstorm,
at any usage level. A subagent that ran a triage or an update from the base reports up to nine
candidates, one line and an anchor each, and the coordinator makes the offer.

## Procedure

1. Read the tree's state once: `node .claude/hooks/queue-list.js ideas --json`. Open is the rows;
   resting is `rest` true; offered and unanswered is `offered` true with `block` `question`. These
   numbers and the bar below are fixed for the session at this point. Before the first offer in a
   tree, `grep -c brainstorm docs/ideas/README.md`: at 0, replace the `Waits:` bullet with
   sentence 1 of the README check below, add sentences 2 and 3, and let them ride in that offer's
   commit.
2. Act on earlier answers first. For each row with `offered` true and `block` `answered`, and for
   words the owner said to the coordinator about an earlier offer, read which of four they mean and
   act (the coordinator alone; never a subagent):
   - Record: the Source line takes its accepted form and the block is removed, so the next triage
     reads the file.
   - Record at rest: the block becomes the rest line, quoting the answer.
   - Fold into a file: the entry is written on the named file and this file is deleted in the same
     commit.
   - No: the file is deleted, the commit message quoting the owner's words and the date.

   Unclear: ask at the next offer. The date quoted is that of the commit that brought the answer,
   or the day it is acted on. Until then no pass builds the file and triage writes no block from it.
3. Generate from what is already in context, plus a subagent's candidates. Before offering, a cheap
   check per candidate: the anchor exists; the tree lacks it; no open idea matches (compare the
   listing's titles, then read the likely files with the Read tool); one primitive is not split into
   several features. A match becomes an entry on that file (`Came up again:`, `New:` or `Use:`),
   not a new file.
4. Make the offer: the header, then at most nine numbered lines, about three each near-term,
   intermediate and moonshot (formats below). One prompted offer per session; an offer the owner
   asks for in so many words, and the offer after an update from the base, are always made and do
   not count. The offer is made at HOLD too. At
   STOP, where a policy sets one, nothing is offered or written and candidates in hand are not
   kept; the wrap-up report says how many.
5. Write what each answer calls for, all in the offer's one commit:
   - Record: a new idea file with the Source line and no block.
   - Record at rest: the same, ending in the rest line.
   - Fold into a named file: an entry on that file, no new file.
   - No: nothing.
   - No answer (the owner answered other lines, or the session reached its wrap-up with no reply):
     a new idea file with the unanswered Source line, ending in the offer Question and a blank
     `Answer:`. Such a file counts in the open queue, has a block and so does not make triage due,
     and is marked by the reader's `offered` field. A session that ends with neither a reply nor a
     wrap-up writes nothing.

   Entries, one physical line each, above the block with a blank line before it; no line of a block
   starts with an entry prefix. A file with a `New:` line is not ready to build, whatever its block:
   rewrite the `Plan:` in the same commit when the change is settled, taking the line in and
   removing it; otherwise `triage` finds the file by `news` and does so. Ready means `block` is
   `plan` or `answered`, `news` is 0 and `offered` is false.
6. Record the offer: one line of counts, as the commit subject or one body line of the wrap-up
   commit (at HOLD, or when nothing was written). No candidate is stored in a commit message.
7. The rest review. List the `rest` rows sorted by `recurrences`, one line each; the owner says
   keep, wake (the `Waits:` line is removed) or delete. The owner's own request is always
   honoured; prompted at a release, or by the closing line after a triage, it is at most once in
   seven days from the last review record (`git log -1 --grep "^Ideas: rest review 20"
   --format=%cs`). Record it as one line of counts.

## Formats

Header of an offer, numbers read at the session's start (step 1):

```
Ideas: 42 open, 3 resting, 5 offered and unanswered (at the session's start); bar step 3: not one
use of a named primitive. 3 near-term, 2 intermediate, 3 moonshots; 1 entry on an existing file;
held back: 4, ask to see them.
```

Then at most nine numbered lines, grouped near-term, intermediate, moonshot, one or two lines each,
naming the anchor in path form. "Held back" counts candidates below the bar or beyond the nine that
were not shown; they stay with the session. Closing line, after a triage only, when a `rest` row
exists and the last review record is seven or more days old or absent: `Rest review? <n> resting,
last reviewed <date or never>.`

Each of the following is one physical line.

- Source line, the first body line after the title. Accepted: `Source: <who>, generated by <model
  id> and recorded by <model id>, <the public trigger>; offered and accepted by the owner; not
  sanity-checked.` Unanswered: the middle clause reads `offered <date>, not yet answered by the
  owner`; a later "record" makes it `offered <date>, accepted by the owner <date>`. `Moonshot.`
  closes the line when the idea is one. `coordinator (<model id>)` replaces `<who>, generated by
  ... and recorded by ...` when the two models are the same. The owner's own idea: `Source: the
  owner, <context>`. The trigger is named in public terms: a triage, a wave close, a release, an update, a spark.
- `Came up again: <date>, <source> (<model id>), <context>.` Only for an independent source: the
  owner, a report, a finding, or a model that no Source line or entry on the file names.
- `New: <date>, <source> (<model id>): <angle, scope, anchor or constraint>.`
- `Use: <date>, <source> (<model id>): <the use>.`
- Rest line (a block): `Waits: at rest — "<the owner's words>" (the owner, <date>)`.
- Offer question (a block): `Question: offered <date> and not answered: record, record at rest, fold
  into <file>, or no?` then a line `Answer:`, blank. `<file>` is the matching file the cheap check
  found, else the words "a named file". An unanswered file that was offered as an entry also names
  that file in `related:`; nothing is written into the other file.
- Records, as a commit subject, or one body line of a wrap-up commit:
  `Ideas: brainstorm offer <date> (<trigger>): offered <n>, recorded <n>, at rest <n>, entries <n>, unanswered <n>`
  and `Ideas: rest review <date>: listed <n>, kept <n>, woken <n>, deleted <n>`. Read with
  `git log --grep "^Ideas: brainstorm offer 20" --format=%B` (the pattern matches a subject or a
  body line; the date keeps a message that quotes the format from matching). The last review's date:
  `git log -1 --grep "^Ideas: rest review 20" --format=%cs`.

The bar, read once at the session's start from the row count, resting and unanswered included.
Guidance: it never refuses; an offer below it states its reason.

| Step | Open ideas | What an offered idea also shows |
|---|---|---|
| 1 | under 20 | an anchor in the tree |
| 2 | 20 to 40 | no existing file says it (else a `Came up again:` or `New:` entry) |
| 3 | 40 to 80 | it is not one use of a primitive a file or this list names (else a `Use:` line) |
| 4 | 80 to 160 | its lever: what it unblocks or answers, or why a moonshot is worth carrying |
| 5 | 160 to 320 | which open idea of its kind it outranks, one line of reason |
| 6 | 320 and up | the owner asked for its topic, or it recurred from an independent source |

## After an update from the base

In a tree with a base line (`^Hatchery base: ` in `docs/design/decisions/`), every update that
reaches its commit (`update-from-hatchery` skill, step 10) ends in an offer. Hatchery itself has no
base line and never runs the update, so this section never applies there. An update that stopped
before its commit makes no offer. The procedure above holds, with these differences:

1. Candidates. Whoever ran the update lists up to nine, one line and an anchor each, each marked
   `base` or `project`; a subagent puts them in its report (the update skill, Report). The
   coordinator adds its own from that report and the incoming list. The anchor decides the group: a
   base-owned or mixed path (`.claude/hooks/ownership.json`) is for the base; a project-owned or
   base-excluded path (the project's `CLAUDE.md`, `.claude/project/`, its policy file, its own
   skills, its docs) is for this project's workflow. What the product should do is neither and
   waits for the next triage or wave close.
2. When and who. The coordinator makes the offer in the turn in which it reports the update to the
   owner, at HOLD too, never at STOP. It does not count as the session's one prompted offer, and a
   session that already made one still makes this one. With no candidate the offer is its header
   alone, and its record says `offered 0`.
3. Shape. One offer, at most nine lines in all, under two headings in this order: `For the base
   (kept here until the base files them):` and `For this project's workflow:`. A group with no line
   prints `none` under its heading. When more than nine pass the cheap check, each group keeps at
   least four lines if it has them. Within a group near-term lines come first; `Moonshot.` still
   closes a moonshot's Source line. The header's second sentence counts by group:

   ```
   Ideas: 12 open, 1 resting, 0 offered and unanswered (at the session's start); bar step 1: an
   anchor in the tree. After the update 6939e79..5d5d113: 3 for the base, 2 for this project's
   workflow; 1 entry on an existing file; held back: 1, ask to see them.
   ```

4. The bar applies to the project's group as it stands. A line for the base shows steps 1 and 2
   only (an anchor; no existing file says it), whatever the open count: it leaves this queue when
   the base files it.
5. What an answer writes: as Procedure step 5, and the Source line and the record name the trigger
   `an update`. A file recorded from the base's group takes a title that starts `For the base: `
   (`# For the base: <the claim>`) and no block; the file of a line left unanswered takes the same
   title above its offer Question. A fold or an entry is written as ever.
6. Closing line, whenever the tree holds a live file for the base (below). Hatchery's triage reads
   this checkout when it sits beside Hatchery's or is on the machine's sibling list,
   `~/.claude/hatchery-siblings.txt` (ADR 0029). The coordinator checks, in Bash from the root:

   ```
   r=$(pwd -P); case "$r" in "$HOME"/*) e="~${r#"$HOME"}" ;; *) e=$r ;; esac; L="$HOME/.claude/hatchery-siblings.txt"
   [ -f "$L" ] && tr -d '\r' < "$L" | grep -qxF -- "$e" && echo "sibling-list: $e: listed" || echo "sibling-list: $e: not listed"
   ```

   Listed: `For the base: <n> kept here; this checkout is on the machine's sibling list.` Not
   listed: `For the base: <n> kept here. Hatchery's triage reads this checkout if it sits beside
   Hatchery's. If it does not, say "list this checkout" once; if Hatchery is on another machine,
   give its session this repository's URL once.` On the owner's "list this checkout", and never
   without it, the coordinator (never a subagent) appends this checkout's line. The block ends the
   owner's last line first if it has no newline, never rewrites a line, and says `NOT added` when
   the write fails:

   ```
   r=$(pwd -P); case "$r" in "$HOME"/*) e="~${r#"$HOME"}" ;; *) e=$r ;; esac; L="$HOME/.claude/hatchery-siblings.txt"
   [ -f "$L" ] || printf '%s\n' "# Sibling checkouts the base's triage reads besides those beside its own. One directory or clone URL per line." > "$L"
   [ -s "$L" ] && [ -n "$(tail -c1 "$L")" ] && printf '\n' >> "$L"
   printf '%s\n' "$e" >> "$L" && echo "sibling-list: $e: added" || { echo "sibling-list: $e: NOT added: the list could not be written (the error is above)"; false; }
   ```

   The list is outside every repository; its lines are never copied into a tracked file.

The title prefix `For the base: ` marks an idea this project leaves to the base: its anchor is a
base-owned or mixed path and the project will not build it. Any offer or filing in a tree with a
base line may write it; a defect seen in a base file and filed as before takes it too, unless the
project is fixing it itself. Such a file is live while it has the prefix and no block (in the
listing, `title` carries the prefix and `block` is `untriaged`). A live file is not triage's and no
pass builds it. Hatchery's triage lists the live files and Hatchery's coordinator files each as its
own idea (`carry-back` skill, opening paragraph); the next update here finds that commit and
deletes the file, unless a decision record here still names it (`update-from-hatchery` skill, step
8). A project that decides to build one itself
removes the prefix: the file is then an ordinary idea, and the built change goes back by the
`carry-back` skill (Send).

## README check

A project's `docs/ideas/README.md` is project-owned and never patched by an update. The three
sentences below are the whole of what it needs; step 1 replaces the `Waits:` bullet with the first
and adds the other two when it lacks the word `brainstorm`:

1. The `Waits:` bullet: "the owner's deferral: to a trigger it names, or, as `Waits: at rest`, until
   the owner wakes it. Only the owner decides one; the session the owner answered writes it from
   their quoted words."
2. Under what drains the queue: "A pass builds a `Plan:` or an answered `Question:` only when no
   `New:` line stands above it and the Question is not an offer (`Question: offered`, which the
   coordinator acts on), and deletes the file only when no `Use:` line remains; otherwise it
   removes the block and the file is untriaged again."
3. "Offered ideas, the Source line and the entries (`Came up again:`, `New:`, `Use:`): the
   `brainstorm` skill."

Where the README says otherwise, `.claude/rules/docs.md`, Queues, is the record.

## Bounds

- Nothing is written unasked. It covers ideas nobody asked for and no task implies: a line answered
  no writes nothing, a line left unanswered writes the offer Question. Work a session was given or
  started, a defect it saw, a scan hit and a review follow-up are filed as before.
- `Waits:` is the owner's. The session the owner answered, or the coordinator from words under
  `Answer:`, writes it from those words, quoted with the date; never a subagent, never by default.
  The `Waits:` lines a tree already holds are not rewritten.
- No held-back candidate is stored in a commit message, a separate pending file or a `Rests:`
  block.
- Every offer and every rest review leaves one record line of counts.
- No agent is launched to brainstorm. At STOP nothing is offered or written.
- A file titled `For the base: ` is deleted only by the update that finds the base's filing commit,
  or on the owner's word.
