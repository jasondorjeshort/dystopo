## Major decisions get a proposal, a critic, and an ADR

A major choice is any of: language, platform UI toolkit, cross-platform strategy, data storage,
network protocol or API surface, build system, a dependency the whole project will lean on, or
an incompatible change to a promised surface (ADR 0012).
The targets are listed in `docs/design/06-platforms.md`. A major choice is never made in passing:
the `architect` writes a proposal in `docs/design/proposals/`, the `critic` argues against it, the
architect answers each objection in writing and records an ADR in the tree's `docs/design/decisions/`, and
only then does implementation start (`proposal` and `adr` skills). Unresolved disagreement goes
to the user with both positions stated.

Technical decisions do not need the user's sign-off; the coordinator makes the call once the
objections are answered and the ADR is recorded, and reports it. Decisions about what humans see and touch (the front end, flows, wording,
visuals) do need the user, because their feedback is the ground truth there. So do decisions
about what the owner pays: plan limits, overage, and any threshold that stops or spends money.
Flag those explicitly and do not guess.

A change is measured by the software it leaves behind: ADR 0012 names the practices (promised
surfaces, the dependency checklist, fitness checks, the trigger scan) and the file each lives in.
