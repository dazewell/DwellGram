---
name: nagramx-workflow
description: "Dazewell's process for adding or changing anything in the NagramX repo (a personal Telegram-for-Android fork, GitHub dazewell/DwellGram). Trigger this whenever work touches that repo: adding a feature, fixing a bug, preparing a commit or PR for it, or when dazewell references \"the usual process\" for NagramX. Also trigger when he gives new or corrected guidance about how this workflow should run — this file is meant to be edited, not just read. Covers: the one-change-one-session rule and why child sessions were dropped, the reuse-first / minimal-footprint hook style with concrete hook points and config surfaces, the two review rounds, the compile gate and its CI fallback, the fallback-not-migration rule, temporary diagnostics and the smoke build, the FEATURES.md entry, on-device testing, and when a PR actually gets opened."
---

# NagramX contribution workflow

`AGENTS.md` holds the repo's facts and hard rules — read it first, and do not
expect them repeated here. This file is the **procedure**: what a change looks
like, in order, and the concrete addresses to build it at.

## One change, one branch, one session

A change runs start to finish in **one session**. Recon, design, implementation,
review and handback share one trace, because the decisions made early have to
still be available to the code written late.

**Do not spawn a child session per change.** A child cannot see the conversation
that produced it, so every constraint has to be re-serialised into a brief — and
what a brief drops is exactly what an implementer needs: the `file:line`
citations and the reasoning behind the rejected alternative. Coordination across
sessions became the single largest source of lost time on this repo, ahead of
any bug. Published practice agrees for work shaped like ours: sequential,
same-file, dependency-heavy changes belong in one session.

**Subagents are how you buy context back.** `nagramx-scout` for read-only recon,
`nagramx-ux` for behaviour and placement, `nagramx-architect` for both review
rounds. They report into your trace without flooding it, and none can commit.
Give each an objective, an output format and explicit boundaries.

Two or more **genuinely independent** changes at once — disjoint files, disjoint
hooks, no ordering between them — can run as parallel sessions dazewell starts
himself. If they share a base file or a hook, they are not independent: run
them one at a time in one session.

## The pipeline, in order

### 1. Recon, then design review (round 1 of 2)

Dispatch `nagramx-scout` and check `FEATURES.md` and `docs/codemap/` before
asking dazewell anything. **If it already ships, stop and say so** — that is a
good outcome. For a user-visible change, run `nagramx-ux` next so the questions
you ask are about real design forks rather than mechanics.

Then put the plan to `nagramx-architect` (round 1) **before writing a line**:
does this fight the architecture, will it survive the next upstream merge, is
there a simpler hook point, does something equivalent already ship? The persona
and the checklist live in `nagramx-code-review`. Do not start implementing until
you and that reviewer are aligned — a plan defect caught here costs a paragraph;
caught after implementation it costs the branch.

**Proportionality.** If you could describe the diff in one sentence, skip the
plan and round 1. Over-process is a real failure, not a safe default.

**A design gate for stateful or concurrent changes — a real gate, not a note.**
If recon *or implementation* reveals the change touches a cache, asynchronous
work, or invalidation — any two of those, or any one plus multi-threading —
implementation **stops** until a short state-and-interleaving spec goes through
review: what state exists, who writes it, on which thread, what clears it, and
which interleavings matter. Route it through round 1, or a **round 1.5** the
moment the risky part surfaces mid-implementation, because **a design review
conducted before the hard part existed has not reviewed the hard part.** Say
that plainly rather than assuming round 1 covered it. (Earned by
`#infinite-video`.)

**State the trade-off budget in one line:** what may be spent for correctness —
an extra query, a round trip, some memory, a slower rare path. Without an
explicit budget an implementer optimizes by default, then defends that
optimization through several review rounds arguing a false economy.

**Cost optional slices separately.** Name the implementation, review, build or
device-test overhead unique to each optional or distinct-risk subfeature, never
hidden inside a total. Mid-flight, **stop and report** if a slice reopens
design, introduces a mechanism serving only itself, accumulates repeated
Critical/Important findings, or costs an extra device cycle — give its unique
cost, whether the rest is healthy, and the options: keep, simplify, substitute,
drop. Whether it is worth it is dazewell's product decision, not yours.

### 2. Branch

Cut a short-lived branch off `dev`, named `<YYYY-MM-DD>_<short-slug>` — **the
date prefix is mandatory**; `_` or `-` between date and slug (tooling normalizes
it), hyphens inside the slug. An undated `require-password` is wrong: fix it
before the first push. One change per branch. The repo auto-deletes the branch
at merge, and its range stays recoverable via `refs/pull/<N>/head`.

Topology, the `#tag` every commit carries, sync and the no-force-push rule are
normative in `nagramx-branch-flow`.

### 3. Implement with minimum footprint

The base fork's own files move as little as possible, so rebasing onto upstream
stays cheap.

**Reuse before you build.** Grep for an existing component, helper, flow or
pattern that already does the thing. The passcode screen is
`org.telegram.ui.Components.PasscodeView`, already embedded standalone in
`ExternalActionActivity` / `BubbleActivity` — reuse it rather than hand-rolling
one. Same for dialogs, menu items, bulletins, biometric prompts, config toggles.
Write new code only when nothing fits — and say so.

**Hook the chokepoint.** For anything touching a core flow, find the single
place all paths funnel through rather than patching many call sites. Every way
of opening a chat funnels through `ChatActivity.onFragmentCreate()` →
`presentFragment` / `addFragmentToStack`.

**Design as hooks.** New logic goes in self-contained feature classes, typically
under `com.dazewell.gram.<feature>` (e.g. `hidelastmessage`, `chatlock`; shared
helpers in `com.dazewell.gram.helpers`), with `tw.nekomimi` and `xyz.nextalone`
holding inherited code only. A new class bumps `DAZEWELL_EXACT`. The base file
gets a few injected lines, usually **fully qualified so no import is added**,
each marked `// NagramX:` explaining the non-obvious *why*. Mirror an existing
feature when adding a similar one; `hidelastmessage` (with its `ChatActivity` /
`DialogCell` hooks) and `chatlock` are the good references.

**Common hook points:**

- `org.telegram.ui.ChatActivity` — in-chat overflow (header) menu items via
  `headerItem.lazilyAddSubItem(...)`; per-feature int ids live with the other
  `nk*` / `nkheaderbtn_*` constants; clicks handled in the big `onItemClick`
  chain. `createView` builds the chat view; `dialog_id` is resolved by then.
- `org.telegram.ui.Cells.DialogCell` — chat-list row rendering.
- `org.telegram.ui.DialogsActivity` — chat list;
  `showChatPreview(DialogCell)` is the long-press peek.
- `org.telegram.ui.LaunchActivity` — app entry; intent / notification /
  shortcut / deep-link handling, app-lock (`onPasscodePause`), `instance`
  singleton, overlay-passcode stack.
- `org.telegram.messenger.SharedConfig` — app passcode (`passcodeHash`,
  `passcodeType`, `checkPasscode`), app-lock state.

**Config & persistence** — existing surfaces only:

- `xyz.nextalone.nagram.NaConfig` (`NaConfig.kt`), read as
  `NaConfig.INSTANCE.getX().Bool()`.
- `tw.nekomimi.nekogram.NekoConfig` — NekoX-era settings.
- `org.telegram.messenger.SharedConfig` — upstream settings.
- Per-account, never-synced state goes in a `<feature>_<account>`
  `SharedPreferences` file (see `HideLastMessageController`,
  `ChatLockController`).

**Fork-owned resources.** New strings go in `res/values/strings_nax.xml`, never
`strings.xml`. Reuse existing drawables and strings where one fits.

**Fall back, don't migrate.** When a stored value's range, set or format
changes, clamp it to the nearest valid value or replace it with a default **at
the point it is read**. No data rewrite, no versioned migration, no
per-value grandfathering. The floor this does not lower: the app must not crash
on an out-of-range, absent or unparseable stored value, anywhere it is read.
What it lowers is the obligation to preserve the old value's *meaning*.

Migration is the exception and must be argued for, with its cost stated the
moment it is proposed — justified only when falling back loses something the
user would actually miss and could not trivially recreate. Preservation
machinery is the part that breaks: a clamp is one expression at a read site,
while preserving an out-of-range value means tracking which values are legacy,
keeping them distinguishable, and stopping every write path from laundering one
into the other. (Earned by `#eventschedule`: grandfathering armed triggers past
a cap change cost six times the code and three Criticals, then was deleted for a
two-boundary clamp.)

**Target the diffstat of a comparable feature.** `git show --stat <commit>` on
the nearest equivalent: a handful of files, most of the diff in new code, only a
few lines touching anything pre-existing.

### 3a. Temporary diagnostics for a new decision point

When the change adds a decision point that determines whether something is
shown, or which of several paths renders the same screen, **instrument it**. The
same applies when the effect is gated on lifecycle state you never watched
happen — a dialog still showing, a view not yet destroyed.
Reading the diff cannot answer "which path actually executed on the device"; a
log line answers it in seconds. A feature that passed the compile gate, an
a Copilot review and two architect rounds still shipped unreachable once,
because all of those reason about the diff and none can see the device state
that picks the branch.

The rules — what may and may not be logged, why only `Log.e` / `Log.i` /
`Log.w` survive the release build, where to place a probe, the
`NAX_SMOKE_<slug>` tag literal, the four marker classes, and the obligation to
remove them in their own commit — are in
[`diagnostics.md`](diagnostics.md). Read it before planting a probe.

This is proportional: a change with no user-visible surface earns none of it.
### 4. Compile gate

The command, the machine budgets and the CI fallback are in `AGENTS.md`. Run it
in the worktree the change lives in, so it compiles the actual branch. Don't
move on until it is clean, and never claim you compiled something you did not —
show the command and the tail of its output.

Gradle daemon and cleanup obligations are normative in
`nagramx-process-lifecycle`.

### 5. Code review (round 2 of 2)

Once it compiles — or once it is pushed and `ci.yml` is the gate — take the
**real diff** back to `nagramx-architect`. Run round 2 on a **different model
family** from the one that wrote the code: a model tends to be blind to its own
mistakes in the same places.

Then review **the whole, on the final state of the code** — not the lines, and
not the diff. A change that arrived as a dozen small fixes reads fine
hunk-by-hunk and can still leave a muddled state machine. What triggers this is
what *happened*, not just up-front size: repeated fixes in one region, a design
that shifted mid-flight, or several review rounds. The repeated-fix trigger is
defined in `nagramx-code-review`.

Verify each finding before implementing it — a reviewer can be wrong for *this*
codebase. Push back with `file:line` evidence rather than performative
agreement; when a finding is right, just fix it. **A reviewer's prescribed fix
is binding**: implement the named mechanism or contest it with evidence before
shipping a different one. Silently shipping a cleverer variant of a rejected
approach is how one finding becomes three review rounds.

### 6. FEATURES.md, and any codemap fact — both ride along

**User-visible ⇒ a `FEATURES.md` entry in the same PR**, under the right
`## section`, with a `### Feature name` heading marked `<!-- #slug -->`. Plain
prose in dazewell's voice, matching its neighbours — read three before writing.

**Write the prose last.** Behaviour keeps shifting while a feature is being
tested and fixed, and every shift makes the entry stale and buys a review round
about the entry. Until the feature is approved and about to be finalized, carry
only a bare heading with the `<!-- #slug -->` marker (that is all `ci` checks)
and no prose. Write and count the real entry as the last step before handoff,
against the final behaviour, then read it back against the code. Never write or
revise the prose mid-iteration, and don't merge with the stub still bare.

**70 words, hard ceiling — count them.** Prose under the heading; images and
shortcut tables excluded. Most entries are ~35. Two beats: what it does and
where you find it, then its setting and default. Cut edge cases, failure
behaviour, interaction step-by-steps, storage and lifetime detail, rationale,
and how it used to work. A sentence opening with *unless*, *except* or *note
that* comes out; one caveat survives only if a user would misuse the feature
without it.

If your feature extends one that already has an entry, add your `<!-- #slug -->`
to that heading and fold the behaviour in — then re-count the merged entry and
cut old detail. **When you shorten an entry, re-check every surviving sentence
against the code**, not just the ones you rewrote: dropping a qualifier is the
cheapest way to lose words and the easiest way to make a sentence false.

**Separately, a durable fact the work established** — a UI→code mapping, an
upstream trap, or a hypothesis you disproved — goes into `docs/codemap/` in the
same PR, per `docs/codemap/README.md`. This is independent of user-visibility:
it is judged on what the work *learned*, not what it shipped. Only what would
save a future investigation real time, and only with a `file:line` citation you
actually checked against this branch.

### 7. Commit

Subject style, the `#<slug>` tag and the append-only rule are in `AGENTS.md`;
the exempt set and the full protocol are in `nagramx-branch-flow`.

### 8. Merge-forward, don't rebase in the loop

`dev` is the trunk. Bring `dev` into your branch when you need what landed
there; never rebase a pushed branch. Details in `nagramx-branch-flow`.

### 9. Open a PR into `dev` — that *is* the preview build

For a user-visible feature this is a standing step, not something to wait to be
told: once the change is ready, **commit → push → open the PR**, so dazewell
always has a route to a test build. Opening it and every later push triggers
`ci.yml` (fast Java/Kotlin validation, no APK) and `process-rules.yml`.

The build dazewell tests must be **`dev` + the change**. To get it, apply the
**`build-apk`** label to the PR: it builds the PR **merge ref** as the
release-signed dual-package APK and uploads it to Telegram as a *test* build.
**Prefer the label over dispatching `staging.yml`** — a dispatch builds the
branch head as-is, not the merge ref. The label is auto-removed at the start of
the run, so re-applying it requests a fresh build.

PR mechanics — requesting the billed Copilot review, its budget, the severity
floor, and thread resolution — are normative in
`.github/agents/nagramx-implementer.agent.md` and are not restated here.

**Request the verification build only once review has settled** — round 2 clean,
any final-state pass clean, nothing Important or above outstanding. An APK
requested earlier is stale the moment a later round finds a Critical — a
build-then-review ordering once had dazewell test a build three subsequent
Criticals invalidated.

**A UI-facing change earns a second, earlier build first — the smoke build.**
Review reads the diff; it cannot see whether a control's precondition is ever
true on a device, which is how a fully-reviewed feature shipped unreachable
(above). So for anything a user can see or tap, once it compiles,
request a build and ask **one** question: does the control appear, and can you
reach it? Not correctness, not edge cases — reachability only. Round 2 and any
final-state pass run **after** that comes back positive; there is no point
reviewing the craftsmanship of code nobody can reach.

The smoke build is disposable by design and is never described as a build to
verify behaviour against. A UI-facing change costs at most two builds, one per
purpose, never two for the same purpose. A change with no user-visible surface
gets no smoke build at all.

**When a build is up, ask for the test explicitly — never bury it in a
handback.** A test request is a *blocking* request for dazewell's hands, and
prose he has to read to the end to discover is prose he may not reach for hours.
Issue an explicit `ask_user` prompt: it interrupts deliberately, stays visibly
unanswered until he acts, and his reply comes back attached to the question.

Make it **specific and short**: which build to install (variant, and the PR or
commit it came from), the exact thing to try, and what a pass versus a fail
looks like. "Please test the build" wastes the interruption; "Install the
Unofficial build from PR #244, arm three videos on one trigger in one chat, then
send that trigger from another account — all three should send one after the
other" spends it well. **One round at a time** — a list of six checks is a task,
not a question, and it stalls. The same applies to any request for his hands or
eyes, such as a screenshot for `FEATURES.md`.

### 9a. ADB-traced smoke cycles

**Local `adb` tooling is always available** — an invariant, not a condition to
test. Only device *connectivity* is optional, so offer a traced cycle whenever
the markers exist, and treat only a disconnected phone as the reason you cannot
read one. The capture protocol, the retention and privacy rules, the three
independent cleanup checks and the evidence-grading labels are in
[`diagnostics.md`](diagnostics.md); the process mechanics are normative in
`nagramx-process-lifecycle`.
### 10. Land it

Landing into `dev` is dazewell's decision by default. The merge procedure, and
the narrow conditional authority under which a session holding a named approval
may press the button, are normative in `nagramx-branch-flow`. **Never write
"ready to merge"** — nothing in this pipeline
establishes it, because nobody ran the app. Say what you actually verified.

## Priorities: protect the irreplaceable thing first

1. **Risk of losing or corrupting the irreplaceable thing** — the recording, the
   message, the user's data. Nothing outranks this.
2. **Behavioural correctness the user would notice** — right output, no crash,
   no regression in an existing flow.
3. **Maintainability and elegance** — lean code, a clean state machine, fewer
   moving parts, a diff the next upstream merge survives.

When two conflict, the lower number wins, and say which you traded.

## Verifying a claim about the system

Prefer evidence over inference. A claim about what the code does is settled by
reading the code at a `file:line`, not by reasoning about what it probably does.
A claim about what the *device* does is settled by a log line, not by reading
the diff. When you cannot settle it either way, say so — an uncited "this is
immune by construction" is treated as false. See
`MessagesController.java:18213-18237`, where the notification posts before the
DB write is enqueued, so nothing downstream may assume the write landed.

## Keeping this current

This file is meant to be edited. When dazewell corrects how the workflow should
run, change it here in the same session, and fix anything it contradicts rather
than leaving both. The bias is **subtraction**: this file is read in full every
time it is loaded, and a rule lost inside a long file is worse than no rule. If
an agent keeps breaking a rule that is written down, treat the length as the
cause.
