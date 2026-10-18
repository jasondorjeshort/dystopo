## One independent review per change

Every change is read once by an agent that did not write it before it is pushed: `reviewer` for
code and docs, `critic` for a proposal. The coordinator reads the report, not the diff, and
routes fixes back to the writer; a fix to an unpushed commit is amended into it. One review, not
three: the writer's own check and the pusher's mechanical checks are not reviews, and a second
reading of the same diff by the same model finds what the first did. The user reviews what humans
see and touch, and anything the reviewer marked blocking that the coordinator overrode.
