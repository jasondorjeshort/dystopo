# Handoff, split shape

For a project whose coordinators run parallel sessions on one branch: chosen at decant or adoption
by the owner's answer to the writer-count question (ADR 0015), or taken up the day a second
parallel session opens on the branch, recorded then in a project ADR of one page. Nothing
under `.claude/` and nothing in the policy file changes: the base accepts both shapes. Claude Code
only: a reader that does not inline `@` imports sees a map of inert lines. Whether nested imports
re-expand after `/compact` is unverified; a session that has compacted re-reads the map.

## Layout

- `docs/HANDOFF.md` is the map, aim under 15 lines, cap 40: the title; `Coordinator state.
  Subagents follow their brief, not this file.`; `Updated: YYYY-MM-DD by <who>`; one line naming
  this shape and this file; `Waiting on user: the Question: blocks in docs/ideas/ and each
  stream's Waiting line.`; `Open findings: node .claude/hooks/queue-list.js findings --status open`; then
  one bare line `@handoff/<stream>.md` per open stream, nothing else on it. An import resolves
  from the importing file's directory, so `@handoff/`, never `@docs/handoff/`: that spelling
  loads `docs/docs/handoff/` and nothing says so.
- `docs/handoff/<stream>.md`, one per workstream, named for its source (a plan, an ADR rollout, an
  idea's Plan block): `# <Stream>`, `Updated: YYYY-MM-DD by <who>`, `## Now`, `## Next`, and an
  optional `Waiting: <question>` line for an owner question not yet filed as an idea. Cap 40 per
  file, aim under 15; the map plus every stream at most 120 together, the pusher refuses above
  either. Closing a stream is the usual fix.
- No `README.md` in `docs/handoff/`: the map is the index, and the post-edit hook warns about a
  stream the map does not import. No fixed files from the base: a project that needs one
  (deployed state, say) adds it by its own ADR, imports it like a stream, and owns its conflicts.

## Procedure

1. A session edits only the stream it is working on, in the commit of the work. The map changes
   only to open or close a stream, or to bump its Updated line.
2. Open: write the stream file and add its `@handoff/<stream>.md` line to the map, in the work
   commit. Close: `git rm` the file and delete its line, in the commit that finished the work.
3. Wrap-up: step 1 of `SKILL.md`, then rewrite each stream this session touched; the map's Updated
   line only when the map changed. Step 3, 5 and 6 of `SKILL.md` as written.
4. Staleness per stream: `git log -1 --format=%cs -- docs/handoff/<stream>.md`. A stream not
   updated in a working week is a question for triage: close it, or name who has it.
5. A conflict on a stream file means two sessions share a stream, which is the signal that
   matters: split the stream or hand it to one session, by hand, in the resolving commit.
6. Fold back when one session has run alone for a month: rewrite `docs/HANDOFF.md` with the four
   sections of `SKILL.md`, `git rm -r docs/handoff/`, one commit, one line in the project ADR.
