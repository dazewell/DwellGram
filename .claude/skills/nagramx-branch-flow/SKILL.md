---
name: nagramx-branch-flow
description: "Dazewell's git / integration / upstream-sync model for NagramX (dazewell/DwellGram; parent DrKLO/Telegram; NextAlone/Nagram proposal target; risin42/NagramX historical). Trigger for branches/worktrees, one change = one branch = one session, mandatory #tag, discoverability, upstream proposals, sync, ci.yml/staging.yml gates, <YYYY-MM-DD>_<slug> names, no force-push, follow-up commits, merge authority, batch landing, and phone-triggered sync-build-Telegram automation. Companion to nagramx-workflow."
---

# NagramX branch & integration flow

Current model: **one change = one dated branch = one session**, running start to
finish with subagents. No coordinator branches.

## Topology

Remotes: `origin` is `dazewell/DwellGram`; old `dazewell/NagramX` redirects but
is not a command target. Upstream is `DrKLO/Telegram` `master`, fetched by URL
by `sync-upstream.yml`; no remote is needed.

Branches: `dev` is trunk/build source/durable history; features and upstream
snapshots merge forward into it. Never rebuild/delete/force-push it. `nbase`
points at the newest upstream snapshot (fast-forward only); never force-push or
delete it. `base` is frozen risin42 `a6c7d0ae`. Change branches are short-lived
`<YYYY-MM-DD>_<slug>` from `dev`, PR'd to `dev`, auto-deleted at merge, and
recoverable via `refs/pull/<N>/head`; keep no upstream-candidate branch alive.

Removed: `.github/integration-branches.txt`, `register-topic.yml`, `canary.yml`,
`release.yml`, `pr.yml`, and the whole `.github/sync/` pin-and-guard system
(`pins.env`, `sync-guard.ps1`, `sync-land.yml`, `sync-guard-check.yml`). It
guarded unattended pushes to `dev` across parent changes; sync now only opens a
PR, so review and `ci.yml` are the gate. Do not reintroduce file-count floors or
blob pins.

## Branch names

Branch format is **`<YYYY-MM-DD>` + `_` or `-` + `<slug>`**, lowercase,
date first, hyphens in slug. `process-rules.yml` enforces it on every PR:

```
2026-08-05_video-cc          2026-08-05-video-cc          2026-08-06_ci-tag-check
```

Date required; a bare `video-cc` or `composer-select-all` is wrong. `_` and `-`
both valid; tooling may flatten `_` to `-`; do not flag dated hyphen names. No
camelCase, spaces, owner prefix, or `coord-<slug>`. Slug matches:
`2026-08-05_video-cc` -> `#video-cc`.

Locally this is mostly automatic once `core.hooksPath` is set:
`.githooks/post-commit` dates an undated branch whenever it is committed to
(skipped once the branch is on a remote; opt out with `NAX_NO_AUTO_DATE=1`), and
`.githooks/pre-push` refuses to push one. For a name up front:

```powershell
.\.github\scripts\new-branch-name.ps1 -Slug video-cc     # -> 2026-08-05_video-cc
```

Wrong name already created? Rename before review history accumulates:

```powershell
git branch -m <old-name> <YYYY-MM-DD>_<slug>
git push origin :<old-name>                              # drop the old remote ref
git push origin -u <YYYY-MM-DD>_<slug>
```

Deleting/re-pushing a branch ref is not history rewrite. Retarget/reopen any
PR. Do not rebase/catch up feature branches; resolve sync conflicts in
`dev`.

## Commit tags

Every authored non-merge commit has inline `#<slug>` in subject/body:
`add chat lock #chatlock`. Later fixes reuse it; `git log --grep '#chatlock'`
survives branch deletion. A line starting with `#` may be stripped.

Feature commits use the feature slug. Exempt category tags are exactly `#ci`,
`#docs`, `#build`, `#chore`, `#infra`, `#deps`, `#test`, `#release`, `#slug`,
`#tag`, `#chatlock`, plus any `*-fix`; sync/build tooling uses `#infra`. Anything
else must appear in `FEATURES.md` as `<!-- #slug -->`.
Merge commits are exempt. `.githooks/commit-msg` enforces locally after
`git config core.hooksPath .githooks`; `process-rules.yml` enforces branch
format, tags, and catalogue. Harvest scans whole `base..head` and full bodies,
so a wrong pushed tag is effectively immutable; a real entry or parked marker are
costly escapes. Pick right first. `--no-verify` only in emergency.

No AI mentions in commit messages, PR titles/bodies, trailers, or app source. No
assistant `Co-Authored-By`.

## Issues and dispatch gates

Issues are for decided, unstarted work. Each records feature tag and unique
branch slug:

```
<!-- tracking -->
**Branch slug:** `eventschedule-edit`  |  **Feature tag:** `#eventschedule`  |  **Status:** deferred

> Blocked until PR #246 is merged and confirmed on-device.
```

Labels: `status:approved` means dazewell approved work; only he applies it.
`status:in-progress` means active session; `status:blocked` names a dependency;
`status:deferred` is parked. No approved label means proposal. Stop labels
outrank approval. Agents have dazewell's token, so they still must not approve.
Revisit if collaborators gain triage.

Mandatory preflight before issue work:

```powershell
$repo = 'dazewell/DwellGram'
$n    = 254              # issue number
$slug = 'eventschedule-bolt-refresh'   # the issue's declared branch slug

# Exact branch match; loose "*$slug*" false-positives and "_" anchoring misses
# names because branch tooling normalises the separator to "-" on push.
$branchRe = "^\d{4}-\d{2}-\d{2}[-_]$([regex]::Escape($slug))$"

$blockers = @()

# 0. APPROVED BY DAZEWELL? No label, no work.
$issue  = gh issue view $n --repo $repo --json state,labels,assignees | ConvertFrom-Json
$labels = @($issue.labels.name)
if ($labels -notcontains 'status:approved') { $blockers += 'NOT APPROVED by dazewell' }

# 1. open, and no stop labels?
if ($issue.state -ne 'OPEN')                 { $blockers += "issue is $($issue.state)" }
if ($labels -contains 'status:in-progress')  { $blockers += 'already claimed (status:in-progress)' }
if ($labels -contains 'status:blocked')      { $blockers += 'blocked (status:blocked)' }
if ($labels -contains 'status:deferred')     { $blockers += 'deferred (status:deferred)' }

# 2. open PR? --limit is mandatory; gh defaults to 30 and can drop the match.
$pr = gh pr list --repo $repo --state open --limit 200 --json number,headRefName |
  ConvertFrom-Json | Where-Object { $_.headRefName -match $branchRe }
if ($pr) { $blockers += "open PR #$($pr.number) on $($pr.headRefName)" }

# 3. remote branch?
$branch = git ls-remote --heads origin |
  ForEach-Object { ($_ -split 'refs/heads/')[-1] } |
  Where-Object { $_ -match $branchRe }
if ($branch) { $blockers += "remote branch $branch" }

if ($blockers) { "DO NOT DISPATCH:`n - " + ($blockers -join "`n - ") } else { 'clear to dispatch' }
```

The block decides: no approval or any blocker means stop. Claim before work:

```powershell
# Hyphen: tooling flattens "_" on push, so "_" would name a branch that never appears.
$claimBranch = "{0:yyyy-MM-dd}-{1}" -f (Get-Date), $slug
gh issue edit $n --repo $repo --add-label "status:in-progress" --remove-label "status:deferred"
gh issue comment $n --repo $repo --body "Started. Branch: ``$claimBranch``"
```

Derive the date. Re-read after claiming; if another claim landed first, back off.
PR body uses `Closes #<n>`. Before reclaiming stale `status:in-progress`, prove
no matching remote branch, open PR, or live session; remove label and explain.

## Start and work

Use sibling worktree `..\NagramX-<slug>` for iterative features; main clone for
one-shot CI/workflow tweaks, small fixes, tuning, or docs. When unsure, use a
worktree. Hooks are shared; `post-checkout` fast-forwards a new worktree to the server's `origin/dev`.

```powershell
git switch dev; git pull --ff-only origin dev          # trunk already carries upstream
git config core.hooksPath .githooks                     # once per clone, if not set

git switch -c <YYYY-MM-DD>_<slug> dev                   # in place: cut the change branch from the trunk
git worktree add -b <YYYY-MM-DD>_<slug> ..\NagramX-<slug> dev   # or a sibling worktree
cd ..\NagramX-<slug>                                    # work here; the main clone stays on dev
# ...nagramx-workflow steps: design review, hooks, compile, code review...
```

`FEATURES.md` rides in the same branch. Before self-managed worktree removal
(`git worktree remove ..\NagramX-<slug>` from the main clone, then
`git worktree prune`), stop/verify processes per `nagramx-process-lifecycle`;
app-managed worktrees use only `archive_session`.

## PR, builds, and review

User-visible features open non-draft PRs into `dev` by default; CI/bug/chore PRs
are optional.

```powershell
gh pr create --base dev --head <YYYY-MM-DD>_<slug> --title "<title>" --body "<body>"
```

`ci.yml` compiles every PR push and is the no-local-tools gate; say so in the
body. `process-rules.yml` also runs. Prefer `build-apk`: it builds the PR merge ref
(`dev` + branch), uploads a signed dual test build, and auto-removes itself.

If labelling produces **no run at all** — not a skipped one — check
`mergeable_state`: `dirty` means there is no merge ref to build. Merge
`origin/dev` **into the feature branch**, push, re-label. The one case that
overrides "do not catch up feature branches" above: nothing builds until it is
resolved.

Manual `workflow_dispatch` builds branch head as-is; use only if label fails,
after:

```powershell
git diff --name-only origin/<branch>...origin/dev -- 'TMessagesProj/src'
```

Empty means dispatch is app-source equivalent; non-empty means merge `dev` first.
Report trigger/ref. A workflow that never ran is not a pass.

Request behaviour verification only after review is clean. A UI-facing change
also gets one earlier smoke build after compile, for reachability only.

Copilot review is **billed and never automatic**. Request it once architect
round 2 has cleared and `ci.yml` is green on the head SHA, via the
`request_copilot_review` tool or its script,
`.github/scripts/request-copilot-review.ps1 -PullRequest <n> -Wait`. **Two per
PR**, the second only after fixing an Important-or-above finding; none on doc-
or process-only PRs unless dazewell asks. The script enforces this; never
hand-roll the request.

Close every review point: fix or reply why not,
resolve, verify none remain.

## Follow-up commits

Review fixes, on-device bugs, and later improvements are new commits, not amends:
re-run the compile gate, then commit and push to the same branch.

Name the fix, not "address review". Push re-runs `ci.yml`; APK refresh needs
`build-apk`. Rewriting is off by default even on feature branches; use it only on
dazewell's request or to erase a real bad object, then only `--force-with-lease`,
never `--force`. `dev`, `nbase`, and `base` are never force-pushed.

## Landing

**Default merge authority is dazewell.** A session may press merge only under a
named approval, and the holder is whichever session holds it — not a role. Every
condition below must hold; if any fails, hand back the PR URL and the decision.

- **Explicit in-session approval naming the PR.** A bare "go ahead" is not it.
  Approval authorises **the button, never the evidence**: it waives no review,
  no hard-line grep, no `#slug` check. It binds to the **reviewed head SHA** —
  a later commit makes it stale, so re-verify and re-ask.
- **Non-transferable.** Held by *this* session, closed per PR when it ends. A
  successor re-asks for anything still unmerged.
- **`--admin` and `--auto` are forbidden, always.** `--admin` forces past branch
  protections and you hold the token that makes it available; `--auto` merges on
  a future state you never verified.
- **Not an upstream sync PR.** dazewell lands those himself, as a merge commit.

Know the backstop's edge: ruleset `22861936` requires the
`Every commit carries a` context with an **empty** bypass list, so not even an
admin merge lands an untagged commit. That is the **only** part the platform
enforces — the named approval, the fresh re-verify, the `--admin` ban and the
sync exclusion are process-only. These bind because you follow them, not
because GitHub stops you. The mechanical gate every merge passes is below.

Squash PRs. Settings on 2026-09-10: `allow_merge_commit: false`,
`allow_squash_merge: true`, `delete_branch_on_merge: true`. GitHub makes one
`dev` commit from PR title plus branch messages; merge triggers `staging.yml`.
Never pass `--delete-branch`; deletion is automatic and safe:

```powershell
git fetch origin refs/pull/<N>/head:<local>
```

Preflight settings live:
`gh api repos/dazewell/DwellGram --jq '{squash:.allow_squash_merge, msg:.squash_merge_commit_message, title:.squash_merge_commit_title}'`.
Stop unless `allow_squash_merge` is `true` and `squash_merge_commit_message` is
`COMMIT_MESSAGES`; otherwise `#slug` tags can vanish with CI green.
`squash_merge_commit_title` is `PR_TITLE`, so PR title is permanent history.

Merge-time gate:
- Poll `.github/scripts/test-merge-preflight.ps1` with a deadline. It requires an
  open, non-draft PR into `dev`, the exact branch and approved SHA, `CLEAN`
  merge state, and no unresolved review threads. Any failure or timeout is
  stop-and-report. Never update/resolve while landing.
- Re-read the PR head through that script every poll; a new commit aborts and
  reopens non-CI gates.

```powershell
$deadline = (Get-Date).AddMinutes(10)
do {
  $LASTEXITCODE = 1
  .\.github\scripts\test-merge-preflight.ps1 -Repository dazewell/DwellGram `
    -PullRequest $n -ExpectedHead $approvedHead -ExpectedBranch $branch
  if ($LASTEXITCODE -eq 0) { break }
  if ($LASTEXITCODE -ne 1) { throw 'Merge preflight has a terminal blocker; re-review and re-approve before landing.' }
  Start-Sleep -Seconds 15
} while ((Get-Date) -lt $deadline)
if ($LASTEXITCODE -ne 0) { throw 'Merge preflight did not become clean before the deadline.' }
```

- Current head needs `ci.yml` `success` for code, or if path-ignored, required
  `Every commit carries a` green. Classify by live `.github/workflows/ci.yml`;
  absence alone is ambiguous.
- Re-verify same head: attribution greps clean and the missing-`#slug` query
  clean. The shared preflight script owns PR state and review-thread checks.
- Merge with `gh pr merge <n> --squash --match-head-commit <headRefOid>`.
- Pass neither `--body` nor `--subject`; either can override `COMMIT_MESSAGES` /
  `PR_TITLE` and land an untagged `dev` commit.

### Batch landing

Order: declared blocker (`status:blocked` + `> Blocked until PR #<n> ...`),
stacked PR after base, same base file/hook point, feature before its `*-fix`.
Registry files (`FEATURES.md`, `strings_nax.xml`, `NaConfig.kt`,
`NekoConfig.java`, `docs/codemap/*`) are textual risk unless the hunk changes
behaviour; classify by hunk.

Before first and each later merge: re-read blockers.

Merge back-to-back so `staging.yml`'s `staging-dev` `cancel-in-progress: true`
best-effort collapses uploads; cancelled runs are expected. Between merges, wait
for `dev`'s post-merge `ci.yml` on the just-merged commit to finish `success`.
After the batch, confirm delivery: classify the merged commits against live
`.github/workflows/staging.yml` `paths-ignore` (not identical to `ci.yml`'s),
and if any path is non-ignored, require a successful `staging-dev` with
`Upload staging` green on the final non-ignored SHA. Record "no staging run
expected" only after proving every commit ignored. An explicit
`build-apk`/dispatch needs its own successful run regardless.

### Rare local landing

Manual chore path only, never approval-based agent landing:

```powershell
git switch dev; git pull --ff-only origin dev
git merge --squash <YYYY-MM-DD>_<slug>      # stage the change, no commit yet
git commit -m "<summary> #<slug>"           # one commit; carry the slug so it stays greppable
git push origin dev                          # -> staging.yml builds + uploads
git branch -d <YYYY-MM-DD>_<slug>            # local only
```

A never-PR'd branch has no `refs/pull`; PR it if it might be proposed upstream.
Worktree cleanup still requires process-lifecycle checks.

## Later fix

Use a new dated fix branch and the same feature tag:

```powershell
git switch dev; git pull --ff-only origin dev
git switch -c <YYYY-MM-DD>_<slug>-fix dev
# ...implement, compile gate (local, else `ci.yml` on the PR), review...
git commit -m "<what the fix does> #<slug>"   # SAME slug as the feature
```

PR to `dev`, merge, delete. If user-visible, update the existing `FEATURES.md`.

## Sync from upstream

```powershell
gh workflow run sync-upstream.yml --repo dazewell/DwellGram
```

It fetches Telegram `master` and wraps its tree in one `dazegram-sync`-authored
snapshot commit (`#infra`) whose parent is the previous snapshot, found in
`dev`'s history by that author. No upstream commit, author or message enters
`dev`. Then:

- **Up to date** — nothing happens.
- **Clean merge** — pushes the snapshot to `nbase`, pushes
  `<date>_upstream-<version>` with the merge, opens a PR, pings Telegram.
- **Conflicts** — pushes the snapshot to `nbase` and pings Telegram. Finish on
  a PC:

      git fetch origin dev nbase
      git switch -c <date>_upstream-<version> origin/dev
      git merge origin/nbase
      # resolve, compile gate, commit, push, PR into dev

- **An earlier sync not yet landed** (`nbase` not in `dev`) — the run refuses
  while its `*_upstream-*` PR is open; with none open it resumes from `nbase`
  instead of minting a new snapshot.

Review the sync PR like any other: compile gate, then read the conflicted and
fork-touched hunks. Check that `TMessagesProj/release.keystore`, the signing
block in `TMessagesProj/build.gradle`, `google-services.json`, branding and
`.gitmodules` kept the fork's side. Upstream often adds a call to a helper the
fork's copy of a file lacks without a textual conflict, so trust the compile,
not the merge.

**Land it as a merge commit, never a squash.** The next sync's merge base is
the snapshot, so it must stay in `dev`'s ancestry. Repo settings allow squash
only, so dazewell flips `allow_merge_commit` on, runs
`gh pr merge <n> --merge --match-head-commit <sha>`, and flips it off again.

`SYNC_TOKEN` (fine-grained, this repo only) needs **Contents: write** and
**Pull requests: write**; a PR opened with `GITHUB_TOKEN` would not run
`ci.yml`.

## Propose upstream

Recover merged PR range from `refs/pull/<N>/head`; propose compatible features to
`NextAlone/Nagram`:

```powershell
git fetch https://github.com/NextAlone/Nagram.git dev:refs/remotes/nagram-reference/dev
git fetch origin refs/pull/<N>/head:<YYYY-MM-DD>_<slug>   # branch auto-deleted at merge; recover the range
git switch -c <YYYY-MM-DD>_<slug>-pr <YYYY-MM-DD>_<slug>   # throwaway copy
git rebase --onto nagram-reference/dev <branch-point> <YYYY-MM-DD>_<slug>-pr   # replay onto pristine proposal target
git checkout nagram-reference/dev -- FEATURES.md          # drop the fork-only doc hunk
git rebase -i nagram-reference/dev                        # squash to one clean commit
git push origin <YYYY-MM-DD>_<slug>-pr
gh pr create --repo NextAlone/Nagram --base dev --head dazewell:<YYYY-MM-DD>_<slug>-pr
```

Delete the `-pr` branch after merge. Strip only `FEATURES.md`.

## Automation notes

`ci.yml` is the fast push/PR-to-`dev` gate:
`:TMessagesProj:compileDebugJavaWithJavac`, `NATIVE_TARGET=SKIP`, two-package
matrix, setup-gradle cache, no native build/signing/APK/Telegram; AGP may still
provision `ndkVersion`. It checks `.class` files; `deep` covers build scripts,
manifest, and `proguard-rules.pro`. Docs/hooks/agent/skill-only are ignored;
workflow edits are not.

`staging.yml` is the only publish pipeline: signed dual APK + Telegram upload on
push to `dev`, `build-apk`, or dispatch. Matrix: `nekox.messenger` ->
**Unofficial** (`DwellGramX-Unofficial-…`), `org.telegram.messenger.beta` ->
**Official** (`DwellGram-Official-…`); default local package
`APP_PACKAGE=nekox.messenger`. Package names must track `applicationId` in
manifest contact mimeTypes, `res/xml`, `resValue`, `BuildConfig.APPLICATION_ID`;
`resValue` needs `buildFeatures.resValues = true`; `google-services.json` needs a
client per `package_name`. Docs/`.github`/`staging.yml`-only dev pushes do not
publish; verify pipeline changes by dispatch. Labelled PR builds have no
paths-ignore. Both workflows use `github.head_ref || github.ref_name`
concurrency with `cancel-in-progress`; unrelated labels get a suffix. Upload
captions include GitHub Models summary via `GITHUB_TOKEN` (`models: read`,
optional `AI_MODEL`), trimmed for Telegram's 1024-char cap.

Phone sync: GitHub mobile or bot POST to
`/repos/dazewell/DwellGram/actions/workflows/sync-upstream.yml/dispatches` with
fine-grained PAT (Actions read/write only).

## Keeping this current

When flow changes, edit this file in the same session and keep `CLAUDE.md`,
`nagramx-workflow`, and memory maps in sync.