# Device diagnostics and traced smoke cycles

Reference for the `repo-workflow` skill. Load this when a change adds a
decision point you cannot verify by reading the diff, or when you are running an
ADB-traced smoke cycle. Everything here is normative; the pipeline steps that
invoke it are steps 3a and 9a in `SKILL.md`.
## Temporary diagnostics for a new decision point

When the change adds a decision point that determines whether something is
shown, or which of several paths renders the same screen, instrument it. Reading
the diff cannot answer "which path actually executed on the device"; a log line
answers it in seconds. A feature that passed the compile gate, an automated
review and two architect rounds still shipped unreachable once, because all of
those reason about the diff and none can see the device state that picks the
branch.

- **Log only what identifies the path** — booleans, enum and state names, ids,
  counts. **Never** message text, a contact's name or number, a URL, a token, or
  anything else user-identifying; this artifact is release-signed and uploaded.
  If a value cannot be logged safely, log that the branch was taken instead.
- **`Log.e` / `Log.i` / `Log.w` only.** `proguard-rules.pro` strips `Log.v` and
  `Log.d` from release via `-assumenosideeffects`, and the release-signed
  minified variant is the only one that reaches a device. The local debug gate
  cannot catch this — it never builds that variant. A full device cycle was once
  spent proving only that the measurement did not exist, and that silence came
  within a hair of being read as evidence about the feature.
- **Instrument where you are uncertain, not inside the path you expect.** A
  probe in an assumed path yields silence when the assumption is wrong, and
  silence is indistinguishable from broken tooling. When the question is "which
  path ran", log a stack trace at the observed symptom —
  `Log.e(TAG, "<label>", new Throwable())` — which names the real call chain in
  one run where a guessed probe takes several.
- Avoid `onDraw`, scroll and per-message hot paths. Each probe answers one
  predeclared uncertainty and is one-shot or rate-limited.
- **Own commit, single tag literal embedded in the log message text itself**
  (`NAX_SMOKE_<slug>`), written verbatim in the PR body. Verification greps the
  tree for that string, so a tag living only in a commit message is a check
  against nothing.
- **It comes back out as a new commit**, never folded into a feature commit,
  once the smoke build has answered the reachability question.

**Marker discipline for a traced cycle.** Declare all four marker classes before
capture, all sharing the `NAX_SMOKE_<slug>` prefix: a liveness/BEGIN marker at
an unconditionally-reached point carrying build identity
(`BuildConfig.BUILD_VERSION_STRING` embeds the short SHA via `COMMIT_ID` —
`TMessagesProj/build.gradle:24-26,125` — plus `BuildConfig.APPLICATION_ID`, the
scenario id, and the account index where relevant); the expected path marker(s);
the forbidden/competing path marker(s); and an END marker. State expected counts
and order **before** reading the log. Plant them unconditionally whenever
diagnostics are required — device connectivity decides whether the trace can be
*read*, not whether it exists.

This is proportional: a change with no user-visible surface earns none of it.

## ADB-traced smoke cycles

**Local `adb` tooling is always available** — an invariant, not a condition to
test. Only device *connectivity* is optional, so offer a traced cycle whenever
the markers exist, and treat only a disconnected phone as the reason you cannot
read one.

**ADB mechanics live entirely in `repo-process-lifecycle`** and are not
restated here: pinning the target serial, holding the exact process identity,
keeping the capture's working directory and output under an absolute `$env:TEMP`
path outside every worktree, the bounded-capture protocol (declared wall-clock
deadline, a tool wait longer than it, stop on END-marker-or-deadline), prompt
stop with bounded verification on success/failure/cancellation/timeout, never
stopping by process name, never an unqualified `adb kill-server`, the
capture-artifact cleanup obligation, and the ledger entry.

Prefer timestamp-bounded reads over `logcat -c`, which destroys the device's
buffer for every other consumer. Detect a truncated capture by the END marker's
absence, not by a byte-count guess.

**Collateral scope is the app, not the phone.** The host-side filter may add a
narrow allowlist of system tags naming the package (`AndroidRuntime`,
`ActivityManager`, `ActivityTaskManager`, ANR and tombstone lines) — nothing
broader, because raw ambient logcat carries other apps' PII-adjacent lines that
the probe privacy rule does not govern.

**Retention: raw captures are private, ephemeral, and never leave the session.**
They live only under an absolute `$env:TEMP` path — never the repo, a worktree,
a PR, a commit, `FEATURES.md` or a codemap entry — and are deleted immediately
after analysis, in the same turn as the capture. Never quote a raw ambient line
outside the session; a report carries only the declared marker lines plus a
sanitized exception class and its top frame, payload elided. Record the deletion
and the process termination as **separate** evidence; neither implies the other.
If deletion fails, report it and block teardown.

**Cleanup verification is three independent checks**, none evidence for the
others. (a) Grep the final head tree for the exact declared `NAX_SMOKE_<slug>`
literal **and** the bare `NAX_SMOKE_` prefix — a probe planted under a mistyped
slug still has to come out. (b) Inspect the final diff for any added Log call
not declared permanent: every added short `Log.e(`/`.i(`/`.w(` call, resolved
against that file's actual imports (whether `import android.util.Log` was added
in this diff or was already there), **and** every added fully-qualified
`android.util.Log.e(`/`.i(`/`.w(` call — checking only calls behind a
newly-added import would miss a probe dropped into a file that already imported
`Log`. (c) Confirm the raw capture is deleted and the process is stopped.

**Grade the evidence honestly.** `Evidence: ADB-traced` for a traced cycle;
`Evidence: visual + ADB collateral` for a verification build's collateral scan;
`Evidence: visual-only` for each device-absent outcome — not offered, declined,
or not connected — distinguished by its parenthetical, never collapsed into one
generic label.

