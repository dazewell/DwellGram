---
name: repo-investigation
description: "Dazewell's protocol for a question about this fork that is answered rather than built — why does this happen, where does this live, is this feasible, what would it cost, why did that change break, does the fork already do this. Trigger it whenever the request is a question rather than a change, when a bug report needs diagnosing before anything is decided, when feasibility or cost is being weighed before committing to work, and when dazewell asks what something does or where it lives. Covers: pinning the question and the decision it feeds before reading anything, the evidence standard and the difference between verified, inferred and guessed, re-verifying a codemap citation before relying on it, knowing when to stop since there is no compile gate to stop you, writing the durable finding into docs/codemap/ so it is not re-investigated, and the explicit stop before an investigation turns itself into an implementation."
---

# Investigating instead of building

Some requests are questions, not changes: why does this happen, where does this
live, is this feasible, what would it cost, did that change cause this. They
produce an **answer**, and sometimes a `docs/codemap/` entry — not a diff.

`repo-workflow` governs changes and does not fit here: an investigation is
not a branch-and-compile-gate shaped job, and most end with an answer and no
diff at all. `repo-scout` is recon *inside* a change; this is the standalone
case. What is missing without this file is a stopping rule and an evidence
standard — an investigation has neither by default, so it either never ends or
ends in confident guesswork.

**If it does end in a `docs/codemap/` entry, that entry is an ordinary change**
and takes the ordinary route: a dated branch, a `#docs` commit and a PR, per
step 6. The investigation itself is not a change; what it writes down is.

## 1. Pin the question before reading anything

Write down, in one line each:

- **The question**, precisely. "Why is the lock icon missing on some chats" is
  answerable; "look into chat lock" is not.
- **The decision it feeds** — what dazewell does differently depending on the
  answer. If nothing, say so and ask whether it is worth the time before
  spending it.
- **What would settle it.** A `file:line`, a log line from a device, a git
  range. If you cannot name what evidence would count, you are not ready to
  start.

If the real question is sharper than the one asked — and it often is — **say so
and answer the sharper one explicitly**, naming the substitution. Silently
answering a different question is the most common way an investigation wastes
its result.

## 2. Check what is already known

Read `docs/codemap/` first — `ui-to-code.md`, `upstream-traps.md` and
`dead-ends.md` exist precisely so a question is not re-investigated. Also check
`FEATURES.md` for "does the fork already do this", and
`git log --all --grep '#<slug>'` for prior work on the same feature — `--all`
because the branch that did it was very likely deleted at merge.

**Re-verify any citation before relying on it.** Line numbers drift and traps
get fixed; `docs/codemap/README.md` makes this the reader's obligation. An entry
is a head start on where to look, never a substitute for reading the code there
now. Two wrong conclusions this fork has already reached came from a
confident-sounding claim nobody re-checked.

If a codemap entry turns out to be **wrong or stale, fixing it is part of this
investigation**, not a follow-up.

## 3. The evidence standard

`repo-workflow`'s "verifying a claim" rule applies in full: a claim about the
code is settled by reading it at a `file:line`; a claim about the *device* is
settled by a log line, not by reading the diff.

Label every statement in your answer as one of three, and never let the prose
blur them:

- **Verified** — you read it, and you cite where.
- **Inferred** — it follows from something verified, and you say from what.
- **Guessed** — plausible, unchecked. Say "guessed" or do not say it.

An uncited "this is immune by construction" is treated as false. The damage from
a wrong confident answer here is larger than from a wrong line of code, because
nothing downstream re-checks it — it becomes the premise of the next decision.

**Do not smooth over a contradiction.** If two pieces of evidence disagree, that
is the finding; report it rather than picking the tidier one.

For questions only a device can answer, the probe and capture rules are in
`repo-workflow/diagnostics.md`. Instrument where you are *uncertain*, not
inside the path you already believe.

## 4. Know when to stop

There is no compile gate here, so stopping is a judgement you have to make
deliberately. Stop at the first of:

- **The question is answered** to the standard above. Stop even if interesting
  things remain unread.
- **The answer no longer changes the decision.** Further precision is spending
  without buying.
- **You are three theories deep with nothing verified.** Report the dead ends —
  they are a real result — and say what you would need to go further.
- **The answer is "we would have to build something to find out."** That is the
  answer; hand it back rather than starting to build.

Say which of these ended it. An investigation that stops without saying why
reads as incomplete even when it is not.

## 5. Report, then write the durable part down

Answer first, in the fewest words that are still precise: the answer, the
evidence, the confidence, and what it means for the decision. Dead ends belong
in the report — a theory killed with evidence is worth as much as the answer and
is the thing most often lost.

Then, **in the same session**, write any durable fact into `docs/codemap/` per
its README: a UI→code mapping, an upstream trap, or a disproven hypothesis —
short claim, `file:line` citation you actually checked, and the date. That is a
normal change and needs a branch, a `#docs` commit and a PR like anything else.

Be proportionate. A question answered in two greps contributes nothing; a trace
that killed three theories contributes the three dead ends. A fact that does
not fit one of the three sections is almost always not durable — it is a
detail of this investigation. If you are convinced otherwise, report it to
dazewell and let him decide where it lives; do not invent a fourth section to
fit one fact, and do not quietly drop a fact you have just called durable.

## 6. Stop before it becomes an implementation

The most common failure is an investigation quietly turning into a change: you
find the cause, the fix looks small, and you start typing.

**Hand back instead.** Report the finding, name the fix you would make and what
it would touch, and let dazewell decide whether it happens now, later, or not at
all. A fix that arrives without a plan review, a branch, a slug or a review
round has skipped the entire pipeline — and "it was only two lines" is exactly
how the expensive ones start.

If he says go, that is a change: `repo-workflow` from step 1, with the
investigation's findings as its recon.

## Keeping this current

Edit here when the protocol changes. Bias to subtraction — an investigation is
already reading a lot, and this file is overhead on top of it.
