---
name: proposal
description: Run the major-choice loop (proposal, critic, revision) before an ADR. Use when choosing a storage or file format other tools or phones will read, a network protocol or API surface, a routing or rendering engine, a build system, a platform toolkit, or a dependency the whole project will lean on, or when a task's goal says "options to evaluate and decide", or when asked to write a proposal.
---

# Proposal

A major choice is never made in passing. This skill produces `docs/design/proposals/NNNN-title.md`,
gets it challenged by a critic, and ends by handing the revised proposal to the `adr` skill. From the
Hatchery base; here the implementer of the task that faces the choice usually writes the proposal
(T77 wrote its comparison before building), the coordinator briefs the critic, and the coordinator
decides.

## Steps

1. Allocate `NNNN`: the highest number used across `docs/design/proposals/` and
   `docs/design/decisions/` plus one. Proposals and their ADRs share a number. Create
   `docs/design/proposals/README.md` (an index, one line per file) with the first proposal.
2. Write `docs/design/proposals/NNNN-<short-title>.md` from `template.md` in this directory (Write
   tool). Status: `draft`. Every option gets the same depth; measure where it is cheap (T77 imported
   GraphHopper before rejecting it). The recommendation names what would reverse it. Claims about
   tools, licences, platforms or APIs go under "Claims to verify". Every option states its exit cost
   and moving parts; a dependency the project will lean on answers `dependency.md` in the proposal.
3. Add the proposal to `docs/design/proposals/README.md`.
4. Set Status to `under review`. Launch a critic (the most capable model) with `brief-critic.md`
   filled in. The critic writes `NNNN-critic-review.md` beside the proposal.
5. Revise. In "Critic's objections and responses", number every objection, give the response, and
   state the resolution (accepted, accepted with change, rejected because). Correct factual errors in
   the body in place. Do not delete rejected options; they are the record.
6. If a serious or blocking objection remains unresolved, put both positions to the owner in
   `docs/HANDOFF.md` under Waiting on user. Otherwise set Status to `promoted to ADR NNNN` and run the
   `adr` skill. Only then does the building start (or, when the task already built a spike to measure,
   only then is the spike merged as the chosen design).

## Rules

- A proposal for something humans see or touch (front end, flows, wording, visuals) is still written,
  but the decision waits for the owner; say so in the recommendation.
- Aim under 300 lines; depth goes into options, not prose. Proposals are exempt from the 300-line cap.
- Skip the critic only for a reversal of an existing ADR that the owner has explicitly asked for;
  record it with the `adr` skill as a supersession.
- A task whose Owns line is "decided in the task after the comparison" writes the proposal first and
  reports it before building beyond a measurement spike.
