#!/usr/bin/env bash
# Push the current branch and prove it: fetch afterwards and compare the remote ref to HEAD.
# A chained command's echo ("pushed") is not evidence; this script's VERIFIED line is.
# Usage: bash tools/push-verified.sh [remote] [branch]   (defaults: origin, current branch)
# Exit 0 only when origin/<branch> equals HEAD after the fetch; 1 otherwise, with both SHAs printed.
set -u
echo "repo   $(git rev-parse --show-toplevel)"
remote="${1:-origin}"
branch="${2:-$(git rev-parse --abbrev-ref HEAD)}"
if [ "$branch" = "HEAD" ]; then echo "push-verified: detached HEAD; name the branch" >&2; exit 2; fi
git push "$remote" "$branch"
push_exit=$?
git fetch -q "$remote" "$branch" || { echo "push-verified: fetch failed" >&2; exit 1; }
local_sha=$(git rev-parse HEAD)
remote_sha=$(git rev-parse "$remote/$branch" 2>/dev/null || git rev-parse FETCH_HEAD)
echo "local  $local_sha"
echo "remote $remote_sha"
if [ "$local_sha" = "$remote_sha" ]; then
  echo "VERIFIED: $remote/$branch equals HEAD ($(git log --oneline -1 | cut -c1-80))"
  exit 0
fi
echo "NOT VERIFIED: $remote/$branch differs from HEAD (push exit $push_exit)" >&2
exit 1
