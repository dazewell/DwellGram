#!/bin/sh
# Bring a brand-new worktree branch up to the server's origin/dev.
#
# A worktree is cut from whatever local ref it was pointed at, and a local
# `dev` or `origin/dev` that was last fetched days ago hands the new branch a
# stale base. So fetch first, then fast-forward -- but only when the branch has
# no commits of its own (HEAD is an ancestor of origin/dev). A branch that
# already carries work is never moved, and this never merges or rebases.
#
# Runs from .githooks/post-checkout on every new worktree, before the media
# submodule setup so that step sees the final pin. By hand, in a worktree whose
# branch has no commits of its own:
#   sh .github/scripts/sync-new-branch-with-dev.sh
#
# Always exits 0: offline, a hung remote or a diverged branch must not break
# `git worktree add`. It says on stderr what it did not do.

cd "$(git rev-parse --show-toplevel)" || exit 0

# Detached HEAD is a deliberate pin (a review checkout, a bisect); leave it.
git symbolic-ref -q HEAD >/dev/null || exit 0

# No prompt may hang the hook; the timeout bounds a dead network.
GIT_TERMINAL_PROMPT=0
export GIT_TERMINAL_PROMPT
if command -v timeout >/dev/null 2>&1; then
  timeout 60 git fetch --quiet origin dev
else
  git fetch --quiet origin dev
fi || {
  echo "new-branch sync: could not fetch origin/dev; this branch may start behind the server. Run 'git fetch origin dev' and check 'git log HEAD..origin/dev'." >&2
  exit 0
}

git rev-parse --verify --quiet refs/remotes/origin/dev >/dev/null || exit 0

if ! git merge-base --is-ancestor HEAD refs/remotes/origin/dev; then
  echo "new-branch sync: this branch has commits that are not on origin/dev; left where it is." >&2
  exit 0
fi

[ "$(git rev-parse HEAD)" = "$(git rev-parse refs/remotes/origin/dev)" ] && exit 0

git merge --quiet --ff-only refs/remotes/origin/dev >/dev/null 2>&1 \
  && echo "new-branch sync: fast-forwarded to origin/dev ($(git rev-parse --short HEAD))." >&2 \
  || echo "new-branch sync: could not fast-forward to origin/dev; run 'git merge --ff-only origin/dev' by hand." >&2
exit 0
