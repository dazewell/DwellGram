---
name: repo-process-lifecycle
description: "Dazewell's rule for any process, daemon, or background command an agent starts on this fork: adb, logcat, Gradle daemons, dev servers, watchers, emulators, detached shells. Trigger it before Start-Process, an async or detached shell, adb, gradlew, an emulator or other long-running command, and before archiving a child session. Binds every agent that can start a process and any coordinator that archives its direct implementer children. Covers: recording the exact PID or native handle at start, stopping promptly, cleanup on success, failure, cancellation, timeout, exact-PID-only stopping, Windows PID-reuse-safe identity checks, ownership-aware daemon shutdown (never an unqualified adb kill-server, gradlew --stop or adb emu kill; only session-owned isolated instances), the process ledger format, and the pre-archive checklist. It exists because a session archived while adb held its worktree open corrupted it."
---

# Process & session lifecycle

Any session that starts a process follows the starter rules. A coordinator that
archives a direct implementer child follows the checklist. Usually there is no
archiver: one change, one branch, one implementer session, which cleans itself.
(Earned by an `adb logcat` that held a worktree open during archival.)

## The contract

1. **No untracked background process.** Before or immediately after starting
   any long-running or background command, record the tool-native handle or,
   if there is none, the exact PID plus image name, start time, and path/cwd.
   In scope: `Start-Process`, async/detached shells, `adb`, `logcat`, Gradle,
   emulators, dev servers, watchers, and anything similar.
2. **Prefer the handle.** Keep the `Process` object or tool shell/session id for
   as long as the process runs. A bare PID is only a fallback.
3. **Stop it as soon as it is no longer needed.** Do not let a log tail, dev
   server, watcher, or one-off build client ride to handoff.
4. **Clean up on success, failure, cancellation, and timeout.** Use
   `try`/`finally` or the tool equivalent. This is best-effort only; the
   pre-archive checklist is the backstop for cancelled or dead sessions.
5. **Stop by exact identity only.** Use the recorded handle or PID for the
   specific process you started. Never stop by executable name: no
   `Stop-Process -Name`, no `taskkill /IM`, and no broad image-name kill.
6. **Verify termination with a bounded wait.** A stuck process must not hang the
   agent forever. After the wait, prove the exact identity is gone.
7. **Windows reuses PIDs.** Across turns, or when reading a ledger, PID alone is
   never identity. PID, image name, and start time must all match before a live
   process is treated as the one you started. A mismatch is a different process
   and must not be stopped.
8. **Shut down only what you own.** Shared or ambient daemons are not leaks and
   are not yours to stop. Never run an unqualified `adb kill-server`, bare
   `.\gradlew.bat --stop` against the default Gradle home, or unqualified
   `adb emu kill`.
   - **adb.** The common case is the ambient server plus a client such as
     `adb logcat`; stop only your client. If you truly need an owned server,
     start it on a session-specific port, record the port, use it for every
     client command, and stop only it: `adb -P <recorded-port> kill-server`.
     If more than one device may be attached, pin the target serial and use it
     for every command: `adb -s <recorded-serial> logcat`.
   - **Gradle.** Prefer `--no-daemon` for one-off builds. For warm-daemon
     speed, set `GRADLE_USER_HOME` to a session-specific directory outside the
     worktree, record it, and run `.\gradlew.bat --stop` only with that same
     environment. The isolated cache is
     about 2.8 GB and is deleted only after archival by the checklist below.
   - **Emulator.** Record the emulator serial from `adb devices` after boot and
     stop only that serial: `adb -s <recorded-serial> emu kill`.
   - **Kotlin compile daemon.** `KotlinCompileDaemon` is shared, outlives the
     build, survives `gradlew --stop`, and idles out. Leave it alone.
9. **Keep long-running working directories and logs outside the worktree.** Use
   a directory such as `$env:TEMP`. A process rooted in the worktree can hold
   the directory open and block removal.
10. **Stop tool-managed background commands through their returned handle.** If
    the environment gave you an async shell id or native handle, use that tool's
    stop call. Do not hunt for its PID.
11. **Nothing long-running survives a turn boundary unattended.** Re-verify it
    by PID, image, and start time at the next turn, or stop it. ADB/logcat
    capture is never a multi-turn exception: start logger, capture, stop,
    analyze, and delete in one synchronous foreground turn, with a declared
    wall-clock deadline and no `ask_user` or other suspend point. Source probes
    are separate commits and are not governed by this process rule.
12. **A capture file is its own cleanup obligation.** If a process writes a file
    for later reading, most concretely an `adb logcat` smoke trace, analyze it,
    delete it in the same turn, verify the exact path is gone, and record that
    verification. Failed or unverified deletion blocks archival exactly like a
    failed process stop. The file still lives outside the worktree while it
    exists.

## Other sessions are normal

Several sessions may run at once. Seeing processes you did not start is normal.

- Scope cleanup sweeps to your own worktree. Broad listings such as every
  `java.exe`, `Get-Process gradle`, or image-name matches are diagnostic only;
  their rows are not findings and do not gate archival.
- Attribute before acting. A process is yours only if it matches your ledger by
  PID, image name, and start time. A command line naming a different worktree is
  another session's. A shared daemon is ambient. An inaccessible command line or
  executable path cannot be attributed to you, so leave it and report it.
- When in doubt, leave it running and say so.

Use this for diagnostic attribution, not as a kill list:

```powershell
# Diagnostic only. Attribution, not a kill list.
# Windows paths are case-insensitive, so compare that way or a real match is missed.
$mine = (Resolve-Path $myWorktreePath).ProviderPath.TrimEnd('\')
Get-CimInstance Win32_Process |
  Where-Object { $_.Name -match 'java|gradle|adb|node' } |
  Select-Object ProcessId, Name, CreationDate,
    @{n='mine';e={ $_.CommandLine -and $_.CommandLine.IndexOf($mine, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 }},
    @{n='cmd'; e={ if ($_.CommandLine) { $_.CommandLine } else { '(inaccessible)' } }}
```

## Canonical PowerShell pattern

```powershell
$process = Start-Process adb -ArgumentList @('logcat') -PassThru -NoNewWindow -WorkingDirectory $env:TEMP
$identity = @{ Id = $process.Id; Name = $process.ProcessName; StartTime = $process.StartTime }
$primaryError = $null
$cleanupError = $null
try {
    # Use the process.
}
catch {
    $primaryError = $_
}
finally {
    try {
        if (-not $process.HasExited) {
            # Stop through the retained handle's own Id, not a re-looked-up PID.
            Stop-Process -Id $process.Id -ErrorAction Stop
            if (-not $process.WaitForExit(30000)) {
                throw "Process $($identity.Id) ($($identity.Name)) did not exit within the bounded wait."
            }
        }
        # This example only used the ambient/shared adb server through a plain
        # client — nothing daemon-side is stopped here (see rule 8). Add an
        # `adb -P <recorded-port> kill-server` only if this session started
        # and owns that specific port; never an unqualified `adb kill-server`.
    }
    catch {
        $cleanupError = $_
    }
}
if ($primaryError -and $cleanupError) {
    Write-Warning "Cleanup also failed: $cleanupError"
    throw $primaryError
}
elseif ($primaryError) {
    throw $primaryError
}
elseif ($cleanupError) {
    throw "Process $($identity.Id) ($($identity.Name)) did not clean up: $cleanupError; session must not be archived."
}
```

Notes: `-WorkingDirectory $env:TEMP` keeps the process off the worktree.
`WaitForExit(30000)` is bounded; never call parameterless `WaitForExit()`.
Verification reads the retained handle, not a fresh PID lookup. Primary work
errors and cleanup errors are preserved separately. Cleanup-only failure blocks
archival. No unconditional `adb kill-server` appears because the ambient server
is shared.

## Process ledger format

One row per background item, whether it is a native OS process, an owned
daemon, or a tool-managed async/background shell. There is no partial credit
for a vague entry — a row missing a required field is treated the same as a
missing ledger (see below).

```
Processes: <none>
```
or, one block per item:
```
- kind:      adb-client | logcat | gradle-daemon | dev-server | watcher |
             emulator | tool-managed-shell | other
  started by: <this session/branch — so a later reader knows who owns it>
  identity:   <tool-native handle or async-shell session id>
              OR <PID>, <image name>, <start time>, <path/cwd>
  owned resource: <daemon-specific resource: adb port, emulator serial, or for
              a gradle-daemon row, the isolated GRADLE_USER_HOME cache path when
              used for daemon isolation> | n/a — used the shared/ambient resource
              and did not stop it (rule 8)
  capture artifact: <absolute $env:TEMP path> — deleted & verified at <timestamp>
              | n/a — this row produced no capture file (rule 12)
  purpose:    <why it was started>
  stop result: stopped | left running (justified: <why>) | failed to stop
  verified at: <timestamp of the identity-matched termination check> | not
              yet verified
```

The `capture artifact` field applies to any row whose process wrote a file
meant for later reading — most concretely an `adb-client`/`logcat` row backing
a smoke-trace capture. A row of that kind reporting `n/a` when a capture file
actually exists, or reporting `deleted & verified` without a timestamp, is
malformed the same way a missing field is (rule 12). **This field is a claim,
not proof** — it records what the starter believes it did, but the pre-archive
checklist below independently confirms the path
is actually gone before archiving; a starter's `deleted & verified`
disposition never substitutes for that independent check.

**Separate from the process ledger**, include this mandatory cache cleanup field in your handback:

```
Isolated GRADLE_USER_HOME: <absolute child-owned path> | <none>
```

Always include this field: report the absolute cache path if you used an
isolated `GRADLE_USER_HOME` for any build (regardless of daemon mode), or
report `<none>` if you used a shared/default Gradle home or no Gradle build.
This field is distinct from the `owned resource` field in the process ledger for
non-Gradle processes (which records items like adb ports or emulator serials).
When a gradle-daemon process row exists in the ledger **and** you used an
isolated home, the `owned resource` value in that row should record the same
cache path for consistency with this field.

**Tool-managed async/background shells go in this ledger too** — record their
returned handle/session id under `identity` and their `stop result` from
calling the tool's own stop function against that handle (rule 10), not a PID.

## Starter responsibility

If you started it, you own recording and stopping it. Before reporting done or
letting the session go idle:

- Every process or daemon you started is stopped and verified gone, explicitly
  still needed with a reason, or identified as a shared/ambient resource you do
  not own.
- Every capture artifact is analyzed, deleted, and verified gone. Undeleted or
  unverified capture files are hard archive blocks.
- Daemon shutdown ran only for isolated owned resources: never unqualified
  `adb kill-server`, default-home `.\gradlew.bat --stop`, or unscoped
  `adb emu kill`.
- Your handback includes the process ledger and `Isolated GRADLE_USER_HOME`
  field, always present even when empty. The ledger is reported in the handback
  only; never write it into a repository file. A missing ledger, malformed row,
  failed stop, unresolved capture disposition, or `not yet verified` row is a
  hard archive block.

## Pre-archive checklist for a direct implementer child

Run this from the main clone, not inside the child worktree. A coordinator
archives only direct implementer children it created and recorded.

1. Read the direct child's process ledger from its handback. A missing ledger,
   malformed row, `stop result: failed to stop`, unverified row, or missing,
   unresolved, or timestamp-free `capture artifact` disposition is a **hard
   block**. Treat every such item as still running. This strict rule applies to
   any session that reached `RUNNING` and owed a ledger. A session that never
   reached `RUNNING` owes none; for a pre-`RUNNING` mis-dispatch, a clean
   zero-diff worktree-filtered sweep can be the `Processes: <none>` evidence.
2. Stop any tool-managed background shell you dispatched for that session
   through its returned handle/session id, never by PID search.
3. Re-verify every OS-level identity yourself, including rows the ledger marks
   stopped. Confirm PID, image name, and start time no longer match. For every
   `capture artifact` path, independently confirm the exact literal path is
   gone. If it exists, is inaccessible, or is otherwise unverifiable, that is a
   hard block. Do not delete it yourself; return cleanup to the owning session
   so its claim is not hidden.
4. If a row is still running: shared/ambient daemons such as the default adb
   server or default Gradle daemon registry are not stopped. If it is a
   child-owned isolated resource that only the child can address, do not
   PID-hunt it; leave the session intact, send it back to the starter, and
   re-run this checklist after it stops and verifies the resource.
5. Do the residual sweep, from outside the worktree. It is a report and
   identity-confirmation step, never a kill list:

   ```powershell
   $worktree = (Resolve-Path $childWorktreePath).ProviderPath.TrimEnd('\')

   # Exclude this shell and its ancestors before matching. The worktree path is
   # normally a literal in the invoking command line, so an unfiltered query
   # matches its own process and returns at least one row every time it runs.
   # That is worse than it sounds: a check with a guaranteed false positive
   # teaches you to explain rows away, and the habit does not distinguish the
   # self-match from a real leak.
   $selfChain = @(); $walk = $PID
   while ($walk) {
       $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $walk" -ErrorAction SilentlyContinue
       if (-not $proc) { break }
       $selfChain += $proc.ProcessId; $walk = $proc.ParentProcessId
       if ($selfChain.Count -gt 8) { break }
   }

   Get-CimInstance Win32_Process | Where-Object {
       $selfChain -notcontains $_.ProcessId -and (
           ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($worktree, [System.StringComparison]::OrdinalIgnoreCase)) -or
           ($_.CommandLine -and $_.CommandLine.IndexOf($worktree, [System.StringComparison]::OrdinalIgnoreCase) -ge 0))
   } | Select-Object ProcessId, Name, CreationDate, ExecutablePath, CommandLine
   ```

   Every row must be explained: ledger-matched and identity-checked, or
   investigated as a leak. An unexplained result blocks archival. This query is
   worktree-filtered, so a row here touches the tree being removed. It may be a
   ledgered process or a shared/ambient daemon that merely references the path;
   anything else is a leak. A broad machine-wide listing is diagnostic only:
   other sessions' rows are expected, are not leaks, carry no blocking weight,
   and are not yours to stop. Anything stopped from this check still follows
   exact identity and ownership rules. If `handle.exe` or `handle64.exe` is
   already installed, you may run it against the exact worktree path; do not
   install tooling just for this.
6. The final release step depends on who owns the worktree; do not mix them:
   - **App-managed child session** (`create_session`, `open_pr_session`,
     `open_issue_session`, or `fork_session`): after steps 1-5 pass, call
     `archive_session` exactly once as the final operation. It stops the CLI
     process and removes the worktree as one unit. Never run
     `git worktree remove`, `git worktree prune`, or delete the directory
     first. If
     `archive_session` fails or only partially removes the worktree, the failure
     is terminal: do not call it again, manually repair, prune, or force
     anything. Report the exact failure, process, and handle evidence, and leave
     the app session record intact for manual recovery.
   - **Manually managed worktree** (not owned by an app session): after steps
     1-5 pass, `git worktree remove`, run from outside the worktree, is the
     final release check. A sharing violation or other failure blocks cleanup;
     do not force recursive delete or retry blindly. `git worktree prune`
     recovery is only for this manual case, never a substitute for
     `archive_session`.
7. Archive only after all prior steps pass clean. If a missing ledger, remaining
   or unverified process, unexplained sweep/handle result, capture artifact, or
   removal/`archive_session` failure blocks any step, do not archive. Report the
   exact `Id`, `Name`, `Path`, `StartTime`, and command line where available,
   and leave the session or worktree intact.
8. **Post-archive cache cleanup for isolated `GRADLE_USER_HOME` only.** After
   `archive_session` succeeds for a child that recorded an isolated
   `GRADLE_USER_HOME`, and only then, clean up that session-specific cache
   directory:
   - Confirm the path from the handback is child-owned, outside the removed
     worktree, and not the shared/default `%USERPROFILE%\.gradle` or another
     active session's home.
   - Run an exact-path process-use check against that directory, excluding the
     probe's own process. If any unexplained process references it, do not
     delete; report it and leave the cache for manual recovery.
   - Delete only the resolved literal directory path from the handback, without
     wildcards, globs, or broad-root variables. Never use a blind
     `Remove-Item -Recurse`; use a tool that confirms deletion or reports
     exact-path failure evidence.
   - Do not stop shared Gradle or Kotlin daemons to make deletion pass. If a
     daemon blocks it, leave the cache intact and report the block.
   - If cache deletion fails, report the exact path and error; do not retry
     destructively.
   - If the handback says `Isolated GRADLE_USER_HOME: <none>`, no cache cleanup
     is needed.

## Self-cleanup for the ordinary one-session change

An implementer session normally is **not** archived by another session, so
anything whose cleanup is deferred to an archive is never cleaned at all. That
session therefore owns its own teardown: stop and verify every process it
started, delete and verify every capture file, leave shared daemons and the
shared `%USERPROFILE%\.gradle` alone, and report the ledger in its handoff.

**If it created an isolated `GRADLE_USER_HOME`, it deletes it itself** once its
last build is done — applying the same safety checks as the archive path above
(resolve the literal path, confirm it is the session's own isolated directory
and not the shared or default home, run the exact-path process-use check, delete
only that resolved path, never stop a shared daemon to force it through). An
isolated home runs to several GB, so leaving one behind against an archive that
will not happen is a real cost, not a tidy-up detail.

If deletion fails or a process still holds it, report the exact path and error
and leave it for manual recovery. Do not stop ambient daemons or delete shared
caches just to make the session look empty.

## Coverage and upkeep

Applies to `adb` clients and owned servers, `logcat`, Gradle, emulators, dev
servers, watchers, and async/detached shells. Read-only roles are bound if they
start one. When dazewell changes this, edit here first and remove duplicated
drift. New sessions read it after pulling; running sessions may need restart.