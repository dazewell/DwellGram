# AGENTS.md — NagramX

Facts an agent cannot guess from the code. Everything here is load-bearing; if a
line stops being true, fix it rather than working around it.

**Keep this file short.** It is loaded into every session on every tool. A rule
that gets lost in a long file is worse than no rule, so procedures live in
skills (below), not here. If an agent keeps breaking a rule that is written
down, the file is too long — cut, don't add.

## What this is

NagramX is dazewell's personal fork of **Telegram for Android** (`dazewell/DwellGram`),
downstream of NekoX. It is a ~1M-line legacy **Java** app in one huge Gradle
module, and it **merges from upstream regularly**. That last fact drives almost
every rule below: every line you touch in a base fork file is a future rebase
conflict.

- Source root: `TMessagesProj/src/main/java` (inherited Kotlin in `.../kotlin`,
  e.g. `NaConfig.kt` — its `const val`s surface as Java static fields).
  Code this fork wrote lives in `com.dazewell.gram.<feature>`; `com.radolyn`,
  `tw.nekomimi` and `xyz.nextalone` now hold inherited code only. A new class
  adds one to `DAZEWELL_EXACT` in `.github/sync/pins.env` (exact floor).
- Branch `dev` is the integration branch. `base` tracks upstream. Never commit
  to either directly.
- dazewell works on **Windows**. Give shell commands in **PowerShell**.

## Hard constraints

- **No Compose, Hilt, Room, coroutine rewrites, or module restructuring.** Match
  the legacy patterns already in the file you are editing.
- **Minimal footprint.** New logic goes in self-contained feature classes. The
  base file gets a few injected lines, usually fully qualified so no import is
  added, each marked `// NagramX:` explaining the non-obvious *why*.
- **Hook the one chokepoint** every path funnels through. Many touched call
  sites means you picked the wrong hook.
- **Reuse before you write.** Grep for an existing component first.
- **Multi-account keying.** Several accounts run at once and local message ids
  collide between them. Key every lookup, cache, observer, store and flag by
  account. Anything keyed only by dialog or message id is a latent bug.
- **Config goes in existing surfaces** — `NaConfig`, `NekoConfig` or
  `SharedConfig`, plus a `<feature>_<account>` `SharedPreferences` file for
  never-synced per-account state. Never a bespoke per-feature store.
- **New strings go in `strings_nax.xml`**, never `strings.xml`. Don't edit
  shared upstream resource files.
- **Never `Log.v`/`Log.d`** — `TMessagesProj/proguard-rules.pro` strips both
  from the release build, which is the only build that reaches a device. Use
  `Log.e`/`Log.i`/`Log.w`.
- **Fallback, not migration.** When a stored value's range, set or format
  changes, clamp or default it where it is read. The app must never crash on an
  absent, stale or unparseable stored value.
- **No drive-by work.** No refactors, reformatting or unrelated cleanups; they
  widen the diff and cost the next upstream merge. Report them instead.

## The compile gate

```powershell
.\gradlew.bat :TMessagesProj:compileDebugJavaWithJavac
```

Run it from the repo root of the worktree your branch is in. On `ZenBoo`
(JDK 21 + Android SDK installed) budget ~9 min cold, ~15 s after an edit, and
don't kill a cold run early. One local build at a time on that machine — check
`.\gradlew.bat --status` and fall back to CI rather than queueing behind a
`BUSY` daemon.

A missing `TMessagesProj_Modules/media/core_settings.gradle` is not the
environment: `.githooks/post-checkout` fills it in every new worktree, and
`sh .github/scripts/ensure-media-submodule.sh` does it by hand.

If the toolchain is missing, or the first run fails on the environment rather
than on your code, **stop and let CI be the gate** — don't install an SDK.
`ci.yml` compiles every push and PR into `dev`; that run *is* the gate. Say so
in the PR body so nothing gets installed on the assumption it compiled.

`ci.yml` path-ignores doc-, hook- and agent/skill-only changes, so on those
there is legitimately no run. Say which of the two happened; never imply a
skipped gate passed.

## Commits and pull requests

- Subject: lowercase, imperative, no type prefix, no trailing period, no PR
  number — `add per-chat require-password lock #require-password`.
- **Every commit carries an inline `#<slug>` tag** in the subject or body, never
  at the start of a line. A feature slug for features; otherwise exactly one of
  `#ci #docs #build #chore #infra #deps #test #release #slug #tag #chatlock` or
  any `*-fix` tag. Sync and build tooling uses `#infra`. Anything else is
  treated as a feature slug and fails CI unless catalogued in `FEATURES.md`. A
  bare numeric hashtag (`#204`) is never a tag. Enforced by `.githooks/commit-msg`
  (`git config core.hooksPath .githooks`, once per clone) and `process-rules.yml`.
- **Append-only.** A review fix or follow-up is a **new commit** with its own
  `#tag`, never an amend plus force-push. Never force-push `dev`, `base`, or a
  feature branch.
- Branches are `<YYYY-MM-DD>_<slug>`; the date prefix is mandatory and
  `process-rules.yml` accepts `-` as an equally valid separator. With hooks on,
  `.githooks/post-commit` dates an undated local branch when it is committed to
  and `.githooks/pre-push` blocks pushing one.
- PRs target `dev` and are **not drafts**. Copilot review is **billed and
  never automatic**: request it once the head is worth an outside review, only
  through the `request_copilot_review` tool or the script it wraps,
  `.github/scripts/request-copilot-review.ps1`. Budget and timing are in
  `nagramx-branch-flow`.
- **Close every review point before handoff**: fix it, or reply explaining why
  not, then resolve the thread. Verify none remain unresolved.
- User-visible change ⇒ its `FEATURES.md` entry ships in the same PR (70 words
  hard ceiling), prose written last once the feature is approved; a bare
  marker heading until then, and reviewers don't raise the prose before that.
  Durable finding ⇒ its `docs/codemap/` entry ships in the same PR, with a `file:line` citation you actually checked.
- **Verify the artifact, not your intent.** After writing anything the system
  stores for you — a file, a PR body, a manifest, a pin — read back what it now
  holds and check that, not what you sent. Line citations are verified at the
  **final** tree, by reading the line they land on; your own later commits are
  what moves them. When you correct a claim, grep for it everywhere before
  calling it fixed. One PR lost six review rounds to this (`#ribbon-icons`).

## The hard line

**No AI or assistant reference in the app's source or in git history.**
Not in a commit message, a PR title or body, or a code comment. No
`Co-Authored-By` for an assistant, no "Generated with" footer. This overrides
any default attribution behaviour. Process docs may discuss the workflow
openly; the shipped history and code may not. It bans *assistants*, not
tooling in general — a commit or PR body may name Gradle, `adb` or logcat.

The only carve-out is process/CI detection tooling (`sync-guard.ps1`,
`process-rules.yml`), which necessarily contains the patterns it rejects. Real
credentials never appear in any file.

## Where the process lives

Read the skill for the job rather than working from memory. Each loads on
demand, so none is carried when it does not apply — but a single change routinely
fires several at once, so what matters is the concurrent carry, not any one file's
length.

| Skill | Owns |
|---|---|
| `.claude/skills/nagramx-workflow/SKILL.md` | What a change looks like, start to finish |
| `.claude/skills/nagramx-investigation/SKILL.md` | A question answered rather than built |
| `.claude/skills/nagramx-branch-flow/SKILL.md` | Where commits live and how they move |
| `.claude/skills/nagramx-code-review/SKILL.md` | What the review rounds check |
| `.claude/skills/nagramx-process-lifecycle/SKILL.md` | Any process you start, and cleanup |
| `.claude/skills/nagramx-session-handoff/SKILL.md` | Abandoning a session mid-change, leaving state behind |
| `.claude/skills/nagramx-session-pickup/SKILL.md` | Continuing a branch or PR someone else left |

`.claude/skills/nagramx-workflow/diagnostics.md` is loaded on demand from
`nagramx-workflow` for device probes and traced smoke cycles.
`.github/instructions/*.instructions.md` attach automatically to the files they
scope — upstream base files, fork-owned packages, resources, and the build file.

### Context budget

Process docs compete with the code for the window, so the carry is capped and
the cap is checked when a doc changes — not the corpus total, which is
irrelevant while files load on demand.

- **Always-on** (`AGENTS.md` + `CLAUDE.md` + the running agent file): **≤ 40 KB**.
  Today 38 KB.
- **Typical change** (always-on + `nagramx-workflow`): **≤ 60 KB**. Today 58 KB.
- Each skill body stays within Anthropic's 10–20 KB guidance. `nagramx-branch-flow`
  (21 KB), `nagramx-process-lifecycle` (20.5 KB) and `nagramx-workflow` (20.2 KB)
  sit just over and are not to grow; anything added to them buys its room by
  cutting.

Over budget means cut, not append. Prefer a rule line over a retelling: an
incident earns a rule, and the rule keeps only the incident's `#slug` so the
history stays greppable without carrying the story.

`FEATURES.md` is what already ships — check it before treating anything as new.
`docs/codemap/` is the UI→code map, upstream traps and disproven hypotheses.

**One change = one branch = one session.** Use subagents for recon, design and
review; do not spawn a child session per change. The rationale, and the narrow
exceptions, are in `nagramx-workflow`.
