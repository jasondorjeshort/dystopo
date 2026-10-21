# 0007. Ideas and operator queues, triage blocks, and one independent review per change

Date: 2026-10-13
Status: accepted, superseded in part by 0016 and 0017
Superseded in part by [0016](0016-base-owned-and-project-owned-files.md), 2026-10-17: the queue
directories, their READMEs and `docs/design/README.md` are project-owned and never patched from
the base; the grammar they share is recorded in base-owned `.claude/rules/docs.md` and here, and a
grammar change is an incompatible surface change whose migration names the READMEs. Everything
else stands.
Superseded in part by [0017](0017-queue-directories-list-on-demand.md), 2026-10-17: the queue
READMEs carry no index table ("every idea costs a file and an index line", and the table the hook
nags about, under Consequences); `queue-list.js` lists the files and reads the blocks the same way
`triage` does. The grammar, the three blocks and the review rule stand.

Record: no proposal. Merged from the two downstream projects in one sitting with the owner, who
holds all three repositories; see Context.

## Context

Hatchery was extracted from Dystopo, which took its docs practice from Dystopia, a third project
with a longer history of unattended agent loops. By 2026-10-13 the three had drifted: Dystopia
had the most tested queue rules, Dystopo the newest `git-guard`, and Hatchery, the base the other
two are meant to sync from, had neither. This ADR records the docs half of bringing the base up;
ADR 0006 records the hooks half.

What Dystopia's loops had learned, in its own `docs/README.txt` and the READMEs of its queues
[no-check: paths in the sibling repository]:

- A shared list (`backlog.md`) cannot be triaged file by file, cannot be built from one item at a
  time without a merge, and rots because deleting a bullet feels like losing something. One file
  per item with the date in the name, deleted in the commit that completes it, has none of those
  problems, and a directory holding only its README is a recognisable resting state.
- An item needs a machine-readable state so a build pass can take only what is ready: `Plan:`
  (settled and pass-sized), `Question:` with a blank `Answer:` (the owner's choice; filling the
  answer is how the choice reaches the tree), `Waits:` (the owner's deferral to a named trigger).
  Reading an item and deciding what should happen to it are cheap; building it is not; so triage
  runs as its own pass and writes the block, and no stage deletes an item for not being wanted.
- Steps only the owner can take (accounts, keys, elevation) otherwise end up buried in whatever
  doc raised them. A queue of them, filed in the commit that needs each step, keeps the tree
  honest about what is waiting on a human.
- Prose that inventories files or carries counts is wrong by the next commit and gets "corrected"
  by every session that measures it. A doc states the rule that generates the list.

What the owner said about review, from running Dystopia's loops: a reviewer agent that did not
write the change is worth its cost and replaces human review for most changes; three readings of
the same diff (at writing, at commit, at push) are not.

## Decision

- `docs/backlog.md` is retired [no-check: deleted by this decision]. `docs/ideas/` holds one
  dated file per improvement, with the
  frontmatter and the three blocks its README defines, and the `triage` skill writes the blocks.
  `docs/operator/` holds one dated file per owner-only step. Both are deleted-when-done queues;
  `.claude/rules/docs.md` carries the rule, each README its format. Neither is a task board;
  `docs/HANDOFF.md` stays the only now-and-next file.
- Dystopia's `future/` (parked projects the owner has decided to build) is not adopted: in the
  base an idea with a `Waits:` block covers it. A project with enough parked work to need the
  distinction adds the directory itself.
- Findings keep their `status:` frontmatter and are not deleted on resolution, because in this
  base a finding is an investigation record, and both downstream projects promote the durable
  part to `reference/` rather than deleting. Dystopia's delete-on-fix findings are a different
  thing (defects) and stay its own.
- A `reviewer` agent (Opus, high effort, reads and reports, never fixes) joins the roster, and
  `CLAUDE.md` gains the rule: one independent review per change, before the push, by an agent
  that did not write it; the coordinator reads the report, not the diff.
- `CLAUDE.md`'s cap rises from 130 to 150 lines. The base was already over 130 and failing its
  own check; the additions here are rules that belong in the every-turn file, and 150 is still
  small. The `decant`, `adopt-hatchery` and `update-from-hatchery` skills check the new figure.
- The post-edit hook warns about a path named in a doc that does not exist, with
  `[no-check: <reason>]` as the per-paragraph opt-out (ADR 0006 has the hook).

## Consequences

Easier: a project adopted from the base gets a queue an unattended loop can drain, with the
owner's choices reaching it as answers rather than interruptions. Harder: every idea costs a
file and an index line, and the index table in `docs/ideas/README.md` is one more thing the
post-edit hook has to nag about. Maintained: the `triage` skill and the three-block grammar
must stay identical across the three repositories, or a drain written against one will misread
another's files; a change to either lands here first. Superseded when a build pass that reads
blocks exists in the base and shows the grammar wants a fourth state.
