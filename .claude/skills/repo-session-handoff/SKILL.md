---
name: repo-session-handoff
description: "Dazewell's protocol for abandoning a session mid-change so a fresh one can continue it on the same branch. Trigger it when dazewell says hand off, wrap up, start fresh, this session is stuck / confused / going in circles, or asks for a handoff; when you have corrected the same problem twice and the context is now full of failed approaches; and before any deliberate session restart on an unfinished change. Covers: what to commit and push before abandoning, opening the PR if none exists, writing the handoff into the PR body under a stable marker so it is the first thing anyone sees, the dead-ends section that is the only part a fresh session cannot reconstruct, updating an existing handoff rather than stacking a second one, and stopping work once the state is stamped. The incoming side is repo-session-pickup."
---

# Handing a change to a fresh session

A change lives on its **branch**, not in a session. A session can be abandoned
at any point without losing work, provided it leaves state somewhere durable
first. This file is the outgoing half; `repo-session-pickup` is the incoming
half.

**Why this exists:** once you have corrected the same problem twice, the context
is full of failed approaches and every new attempt inherits all of them. A fresh
session's entire advantage is *not* carrying that. So the handoff carries the
**conclusions** — above all, what already failed — and never the transcript that
produced them. A handoff that replays the thread rebuilds the exact problem it
was meant to escape.

## When to hand off

- The same problem has been corrected **twice** and a third attempt is starting.
- Dazewell says so — "hand off", "start fresh", "you're going in circles".
- The session has drifted off the agreed plan and re-explaining costs more than
  restating the problem.

**Do not** hand off merely because a change is long; length is fine, repeated
failure in one region is not. And do not hand off to dodge a finding — an
unfixed Critical is still unfixed in the next session, and now nobody is
watching it.

## The four steps

Do all four, even if the work is ugly.

### 1. Commit and push everything

**Nothing belonging to this change stays uncommitted.** A pushed commit
survives a worktree removal; a dirty tree does not, and a worktree gets removed
without ceremony. So the question is never *whether* to commit a half-finished
file, only how to label it. Append-only — new commits, never an amend.

Split it the obvious way: commit what stands on its own with the change's
`#<slug>` tag, then commit the rest in one clearly-marked partial commit
(`stash partial <what>, unfinished #<slug>`, or `... does not compile yet
#<slug>` when that is the problem) — normal lowercase imperative form, no type
prefix, per `AGENTS.md`. Then push, and confirm **both** that
`git status --short` is empty and that the push actually landed — a clean tree
only means the commits left the index, not that they left the machine, and an
auth or network failure leaves them nowhere but the worktree about to be
removed:

```powershell
$branch = git rev-parse --abbrev-ref HEAD
$remote = gh pr list --head $branch --json headRefName --jq '.[0].headRefName // empty'
if ($LASTEXITCODE) { throw 'PR lookup failed - do not guess the destination ref' }
if ($remote) { $branch = $remote }    # push to the spelling the PR actually tracks
git push origin HEAD:$branch
if ($LASTEXITCODE) { throw 'push failed - the handoff is not durable yet' }
git rev-parse HEAD; git ls-remote --heads origin $branch   # the two must match
```

Push to the ref the **PR** tracks, not the local name. `_` and `-` are both
valid after the date prefix and tooling may flatten one into the other, so a
local `2026-01-02_slug` whose PR head is `2026-01-02-slug` would otherwise
create a second branch, verify that one, and leave the PR — and therefore the
handoff — pointing at the old commit.

Never discard work to make the branch look tidy.

### 2. Make sure a PR exists

If there is none, open one now, **non-draft, into `dev`**; if one exists as a
draft, mark it ready (`gh pr ready <n>`). A draft can't be reviewed, and
review history is a large part of what the next session reconstructs. An
unfinished change is not a reason to draft it; the handoff
block below is what says it is unfinished. **The PR is where the handoff
lives**: it is the one surface that outlives the session, reads from any
machine, and is already bound to the branch.

### 3. Write the handoff into the PR *body*

Not a comment. A comment sinks under review threads and `gh pr view` hides it
behind `--comments`; the body is the first thing both dazewell and the next
session see. Append this block to the end of the body, under its marker:

```markdown
<!-- handoff -->
## Handoff — <YYYY-MM-DD>

**State:** `<branch>` @ `<short-sha>`. Compiles: yes / no / not tried /
path-ignored (no gate ran).

**Left running:** <nothing, or what was not stopped and its exact id.>

**Done.** <Finished and review-clean. One line each.>

**Half-done.** <Started but not right, and what specifically is wrong — the
observed symptom, not the suspicion.>

**Not started.** <What the agreed plan still owes.>

**Dead ends — do not retry.**
- <Approach that failed> — <why, concretely>.

**Open questions for dazewell.** <Blocking only. Omit if none.>
```

Read the body first and **update an existing `<!-- handoff -->` block rather
than appending a second one.** Two handoffs on one PR means the next session has
to work out which is current, which is exactly the ambiguity this removes. Merge
the dead ends — they accumulate across handoffs and are the most valuable thing
on the page. Refresh the date and the sha.

Use `gh pr edit <n> --body-file` with the full body, or the workspace's
PR-editing tool. Never hand-retype the existing body: read it, replace or append
the block, write it back.

Optionally add a one-line comment pointing at it (`Handed off — see the handoff
block in the description`) so watchers get a notification. The body stays
authoritative.

**No AI or assistant mention anywhere in it.** The hard line in `AGENTS.md`
covers PR bodies like everything else.

### 4. Stamp it and stop

The dead-ends section is **the part a fresh session cannot reconstruct**.
Everything above it, it rebuilds from the diff in a minute; a dead end it cannot
rebuild at all and will walk straight into. Give each a concrete reason —
"thrashes on every re-bind", "stripped from release by `-assumenosideeffects`" —
never "didn't work". A reasonless dead end reads as an untested idea and gets
retried.

Keep it short. If half-done needs more than a few lines, the real problem is
that the change is too big — say that instead.

**Close the review threads — after the last push, not before.** `AGENTS.md`
requires every review point to get a fix or an explicit reply, then a resolve;
handing off does not suspend that, it makes it more urgent, because a thread
the next session inherits has nobody attached to it. Reply to each open thread
with the fix or why it will not be changed — a finding you are deliberately
leaving is a fine reply, and belongs in the half-done list too — and resolve
it. Verify none are left, per the `reviewThreads` query in
`repo-session-pickup`. **Do not run the bug hunt just to hand off** — an
unfinished head is not one worth reviewing; record in the handoff how many of
its two runs the branch has used, and each open bug-hunt finding with its
disposition, since those live in no thread.

**Stop every process you started, and say so in the handoff.** This is the one
step where `repo-process-lifecycle` bites hardest: the worktree you are
abandoning is about to be removed, and a logcat client, a Gradle daemon or an
`adb` handle still holding it is exactly how a worktree got corrupted before.
Run that skill's teardown — stop by exact identity, verify termination, delete
any capture artifact — and record the outcome in the **Left running** field, so
the next session knows rather than guessing.

**If a finding needs code, you are not stopping yet.** Fixing it means another
commit and another push, which invalidates the sha you just wrote. Go back to
step 1, then refresh the handoff block — sha, state,
half-done — before stopping. The last thing written must describe the last thing
pushed.

Then tell dazewell the branch, the PR number and one line on what to open next.
**Stop working.** Further commits invalidate the state you just stamped.

## What dazewell has to say

Nothing precise. "Hand off" or "start fresh" is enough. He should never need a
form of words, and a handoff must never depend on him having described the state
correctly — you read the state yourself.

## Keeping this current

Edit here when the protocol changes. Bias to subtraction: this file is read at
the moment a session is already in trouble, so length is a real cost.
