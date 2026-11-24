#requires -Version 7

# Runs the bug-hunt review (.claude/skills/repo-bug-hunt) on the checked-out
# head in a clean, headless claude session that carries only that skill and a
# review packet: the code diff, with each hunk headed by its enclosing method,
# and a table of the changed files. No AGENTS.md, no CLAUDE.md, no
# conversation, no MCP servers; the session gets Read, Grep and Glob over this
# checkout and nothing else, so it cannot edit or run anything.
#
# The session starts in a scratch folder outside the repo so CLAUDE.md
# discovery finds nothing, and reaches the checkout through --add-dir. It uses
# the CLI's own login (claude auth login), not an API key: --bare would skip
# CLAUDE.md too but accepts only ANTHROPIC_API_KEY.
#
# Prints one JSON object. status is one of:
#   reviewed        findings (possibly empty), coverage and usage are set
#   no-code         the diff touches no code; nothing was run
#   packet          -PacketOnly: the packet was written, nothing was run
#   budget-spent    this branch already had $Budget runs; nothing was run
#   already-reviewed  this head was already reviewed; nothing was run
# Refusals and failures throw instead.

[CmdletBinding()]
param(
    # Pull request to review. Its head must be what is checked out here,
    # because the session reads the files on disk.
    [ValidateRange(1, [int]::MaxValue)]
    [int]$PullRequest,
    [ValidatePattern('^[^/]+/[^/]+$')]
    [string]$Repository,
    [string]$Base = 'origin/dev',
    # Another commit to classify or packet. A review itself needs it checked
    # out, so anything but HEAD works only with -PacketOnly.
    [string]$Head = 'HEAD',
    # One line on what the change is meant to do. Defaults to the PR title.
    # Keep the reasoning out: the reviewer reads cold on purpose.
    [string]$Intent,
    # Narrow the review to some paths when the whole diff is too big.
    [string[]]$Path,
    # auto reads the risk off the diff; see $Levels and $Signals below.
    [ValidateSet('auto', 'low', 'standard', 'high', 'max')]
    [string]$Level = 'auto',
    # Overrides of what the level picks, for when dazewell asks for one.
    [ValidateSet('opus', 'sonnet')]
    [string]$Model,
    [ValidateSet('low', 'medium', 'high', 'xhigh', 'max')]
    [string]$Effort,
    [ValidateRange(0, 60)]
    [int]$Context = 15,
    [ValidateRange(16, 1024)]
    [int]$MaxPacketKB = 160,
    [switch]$PacketOnly,
    # Past the per-branch budget or onto a reviewed head only when dazewell
    # asked for another run by name.
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$Utf8 = [System.Text.UTF8Encoding]::new($false)

$Budget = 2
$LargeFileLines = 3000

# Model aliases, not versions: the CLI resolves opus and sonnet to the newest
# of each, so a new model release needs no edit here.
$Levels = [ordered]@{
    low      = @{ model = 'sonnet'; effort = 'medium' }
    standard = @{ model = 'opus'; effort = 'medium' }
    high     = @{ model = 'opus'; effort = 'high' }
    max      = @{ model = 'opus'; effort = 'xhigh' }
}

# What makes a change risky here, matched against its added and removed lines.
# The weight is what a mistake in that area costs: losing data or sending
# wrong outranks a layout slip. Each area counts once however often it hits.
$Signals = [ordered]@{
    data        = @{ weight = 3; pattern = 'SharedPreferences|\.edit\(\)|MessagesStorage|SQLite|\bFile\b|FileOutputStream|\.delete\(\)|renameTo|Draft|serializeToStream|NativeByteBuffer' }
    send        = @{ weight = 3; pattern = 'SendMessagesHelper|sendMessage|sendRequest|ConnectionsManager|TL_messages_send|updateStatus|scheduleDate' }
    media       = @{ weight = 3; pattern = 'MediaCodec|MediaMuxer|AudioRecord|\bEGL|GLES\d*|SurfaceTexture|InstantCamera|CameraSession|VideoEncoder' }
    concurrency = @{ weight = 2; pattern = 'runOnUIThread|postRunnable|Utilities\.\w+Queue|new Thread\b|synchronized|volatile|Atomic\w+|Executor|postDelayed|CountDownLatch' }
    lifecycle   = @{ weight = 2; pattern = 'onFragmentDestroy|onFragmentCreate|onPause|onResume|onAttachedToWindow|onDetachedFromWindow|addObserver|removeObserver|ViewTreeObserver|onActivityResult|onDestroy|onBecomeFullyHidden' }
    account     = @{ weight = 2; pattern = 'currentAccount|selectedAccount|MAX_ACCOUNT_COUNT|\bstatic\s+(?!final\b)[\w<>,\[\] ]+\s\w+\s*[=;]' }
    text        = @{ weight = 1; pattern = 'substring|subSequence|charAt|getMaxMessageLength|codePoint|Surrogate' }
}
# Upstream files every core flow runs through; a hook there reaches everyone.
$CoreFiles = 'ChatActivity|ChatActivityEnterView|MessagesController|SendMessagesHelper|MessagesStorage|ConnectionsManager|NotificationCenter|LaunchActivity|MediaController|FileLoader|InstantCameraView'
$CoreWeight = 2
# Score at or above each bound picks that level; below standard is low.
# Calibrated on PRs 640-655 against where outside review found real bugs.
$Bounds = [ordered]@{ max = 12; high = 5; standard = 2 }
# A diff this big with any risk at all goes up a level.
$BigDiffLines = 800

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    $Output = & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Exe $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
    $Output
}

function Out-Result {
    param([hashtable]$Result)
    $Result | ConvertTo-Json -Depth 8
}

$Root = (Invoke-Native git @('rev-parse', '--show-toplevel')) -replace '/', '\'
$HeadRef = $Head
$Head = Invoke-Native git @('rev-parse', "$HeadRef^{commit}")
if (-not $PacketOnly -and $Head -ne (Invoke-Native git @('rev-parse', 'HEAD'))) {
    throw "-Head $HeadRef is not what is checked out; a review reads files from disk. Use -PacketOnly, or check it out."
}
$Branch = Invoke-Native git @('rev-parse', '--abbrev-ref', 'HEAD')
$Skill = Join-Path $PSScriptRoot '..\..\.claude\skills\repo-bug-hunt\SKILL.md'

if ($PullRequest) {
    if (-not $Repository) {
        $Repository = Invoke-Native gh @('repo', 'view', (Invoke-Native git @('remote', 'get-url', 'origin')), '--json', 'nameWithOwner', '-q', '.nameWithOwner')
    }
    if (-not $Repository) { throw 'Could not resolve the repository from origin.' }
    $Pr = Invoke-Native gh @('pr', 'view', $PullRequest, '--repo', $Repository, '--json', 'headRefOid,title,baseRefName') | ConvertFrom-Json
    if ($Pr.headRefOid -ne $Head) {
        throw "PR #$PullRequest head is $($Pr.headRefOid) but HEAD here is $Head. Check out the PR head first; the reviewer reads files from disk."
    }
    if (-not $PSBoundParameters.ContainsKey('Base')) { $Base = "origin/$($Pr.baseRefName)" }
    if (-not $Intent) { $Intent = $Pr.title }
}
if (-not $Intent) { $Intent = Invoke-Native git @('log', '-1', '--format=%s', $Head) }

$MergeBase = Invoke-Native git @('merge-base', $Base, $Head)

# Process files are left to the architect; this pass is about running code.
$Spec = @('--', $(if ($Path) { $Path } else { '.' }),
    ':(exclude)*.md', ':(exclude)docs/**', ':(exclude).github/**',
    ':(exclude).claude/**', ':(exclude).githooks/**', ':(exclude).codex/**',
    ':(exclude).opencode/**')

$Numstat = @(Invoke-Native git (@('diff', '--numstat', '-M', $MergeBase, $Head) + $Spec) | Where-Object { $_ })
if ($Numstat.Count -eq 0) {
    Out-Result @{ status = 'no-code'; head = $Head; base = $MergeBase }
    return
}

# The budget is counted per branch; a detached head has its own bucket.
$Bucket = if ($Branch -eq 'HEAD') { "detached-$($Head.Substring(0, 12))" } else { $Branch -replace '[^\w.-]', '_' }
$RunRoot = Join-Path ([System.IO.Path]::GetTempPath()) "bug-hunt\$Bucket"
$RunDir = Join-Path $RunRoot $Head.Substring(0, 12)
if (-not $PacketOnly -and -not $Force) {
    if (Test-Path (Join-Path $RunDir 'result.json')) {
        Out-Result @{ status = 'already-reviewed'; head = $Head; result = (Join-Path $RunDir 'result.json') }
        return
    }
    $Done = @(Get-ChildItem $RunRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'result.json') })
    if ($Done.Count -ge $Budget) {
        Out-Result @{ status = 'budget-spent'; runs = $Done.Count; budget = $Budget }
        return
    }
}
New-Item -ItemType Directory -Force $RunDir | Out-Null

# File table: who owns each file and whether it may be read whole.
$Rows = foreach ($Line in $Numstat) {
    $Parts = $Line -split "`t"
    $File = $Parts[-1]
    if ($File -match '=> ') { $File = ($File -replace '\{[^}]*=> ([^}]*)\}', '$1') -replace '^.* => ', '' }
    $Full = Join-Path $Root ($File -replace '/', '\')
    $Lines = if (Test-Path $Full -PathType Leaf) { [System.IO.File]::ReadAllLines($Full).Count } else { 0 }
    $Owner = switch -Regex ($File) {
        'com/dazewell/gram/' { 'fork'; break }
        '/res/' { 'resource'; break }
        'org/telegram/' { 'upstream'; break }
        '(tw/nekomimi|xyz/nextalone|com/radolyn)/' { 'inherited'; break }
        '\.gradle' { 'build'; break }
        default { 'other' }
    }
    $Read = if ($Lines -eq 0) { 'deleted' } elseif ($Lines -gt $LargeFileLines) { 'grep only' } else { 'readable' }
    "| ``$File`` | +$($Parts[0]) -$($Parts[1]) | $Lines | $Owner | $Read |"
}

# Built-in java and kotlin hunk-header drivers name the enclosing method,
# which saves the reviewer a read per hunk. core.attributesFile applies them
# without touching the repo's .gitattributes.
$Attr = Join-Path $RunDir 'attributes'
[System.IO.File]::WriteAllText($Attr, "*.java diff=java`n*.kt diff=kotlin`n", $Utf8)
$Diff = Invoke-Native git (@('-c', "core.attributesFile=$Attr", 'diff', "-U$Context", '-M', '--no-color', $MergeBase, $Head) + $Spec)

# Risk: which areas the changed lines touch, not how many lines there are.
$Changed = @($Diff | Where-Object { $_ -match '^[+-](?![+-]{2} )' })
$Score = 0
$Reasons = [System.Collections.Generic.List[string]]::new()
foreach ($Name in $Signals.Keys) {
    $Hits = @($Changed | Where-Object { $_ -cmatch $Signals[$Name].pattern }).Count
    if ($Hits) { $Score += $Signals[$Name].weight; $Reasons.Add("$Name($Hits)") }
}
$CoreHits = @($Numstat | Where-Object { ($_ -split "`t")[-1] -match "/org/telegram/.*/($CoreFiles)\.java$" }).Count
if ($CoreHits) { $Score += $CoreWeight; $Reasons.Add("core-files($CoreHits)") }
$Picked = 'low'
foreach ($Name in $Bounds.Keys) { if ($Score -ge $Bounds[$Name]) { $Picked = $Name; break } }
if ($Changed.Count -gt $BigDiffLines -and $Score -gt 0 -and $Picked -ne 'max') {
    $Picked = @($Levels.Keys)[@($Levels.Keys).IndexOf($Picked) + 1]
    $Reasons.Add("big-diff($($Changed.Count) lines)")
}
if ($Level -eq 'auto') { $Level = $Picked } else { $Reasons.Add("level set by hand, auto was $Picked") }
if (-not $Model) { $Model = $Levels[$Level].model }
if (-not $Effort) { $Effort = $Levels[$Level].effort }

$Packet = @"
# Review packet

Intent: $Intent

Repository root: ``$Root`` (the working tree is at the reviewed head $Head; read and grep files there)
Base: merge-base with $Base, $MergeBase

## Changed files

| File | Lines | At head | Owner | Read |
|---|---|---|---|---|
$($Rows -join "`n")

Owner: fork = this fork's own code; upstream = Telegram's, carrying injected hooks; inherited = earlier forks' code.

## Diff

``````diff
$($Diff -join "`n")
``````
"@

$PacketFile = Join-Path $RunDir 'packet.md'
[System.IO.File]::WriteAllText($PacketFile, $Packet, $Utf8)
$PacketKB = [math]::Round($Utf8.GetByteCount($Packet) / 1KB, 1)

if ($PacketOnly) {
    Out-Result @{ status = 'packet'; packet = $PacketFile; packetKB = $PacketKB; files = $Numstat.Count
        level = $Level; score = $Score; reasons = @($Reasons); model = $Model; effort = $Effort }
    return
}
if ($PacketKB -gt $MaxPacketKB) {
    throw "Packet is $PacketKB KB, over $MaxPacketKB KB. Split the review with -Path, or raise -MaxPacketKB if dazewell agreed to the cost."
}
if (-not (Test-Path $Skill)) { throw "Skill not found at $Skill." }

# auth status exits non-zero when signed out, so read its JSON, not its code.
$Auth = try { & claude auth status 2>$null | Out-String | ConvertFrom-Json } catch { $null }
if (-not $Auth -or -not $Auth.loggedIn) {
    throw 'The claude CLI is not signed in. Run "claude auth login" once in a terminal, then retry.'
}

$Schema = @'
{"type":"object","additionalProperties":false,"required":["findings","coverage"],
 "properties":{
  "findings":{"type":"array","maxItems":10,"items":{"type":"object","additionalProperties":false,
   "required":["severity","confidence","file","line","title","scenario","evidence","fix"],
   "properties":{
    "severity":{"enum":["critical","important"]},
    "confidence":{"enum":["confirmed","plausible"]},
    "file":{"type":"string"},"line":{"type":"integer"},
    "title":{"type":"string"},"scenario":{"type":"string"},
    "evidence":{"type":"array","items":{"type":"string"}},
    "fix":{"type":"string"}}}},
  "coverage":{"type":"string"}}}
'@ -replace '\s+', ' '

$Started = Get-Date
Push-Location $RunDir
try {
    $Raw = $Packet | & claude -p `
        --model $Model --effort $Effort `
        --system-prompt-file $Skill `
        --tools 'Read,Grep,Glob' --allowedTools 'Read,Grep,Glob' `
        --add-dir $Root `
        --strict-mcp-config --setting-sources '' `
        --no-session-persistence `
        --json-schema $Schema `
        --output-format json
    $ExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
$Raw = $Raw | Out-String
[System.IO.File]::WriteAllText((Join-Path $RunDir 'raw.json'), $Raw, $Utf8)
$Run = $Raw | ConvertFrom-Json
if ($ExitCode -ne 0 -or $Run.is_error) {
    throw "Review session failed: $($Run.result). Raw output in $RunDir\raw.json."
}

$Review = if ($Run.PSObject.Properties['structured_output'] -and $Run.structured_output) {
    $Run.structured_output
} else {
    $Run.result | ConvertFrom-Json
}

$Result = @{
    status   = 'reviewed'
    head     = $Head
    level    = $Level
    score    = $Score
    reasons  = @($Reasons)
    model    = $Model
    effort   = $Effort
    packetKB = $PacketKB
    findings = @($Review.findings)
    coverage = $Review.coverage
    turns    = $Run.num_turns
    minutes  = [math]::Round(((Get-Date) - $Started).TotalMinutes, 1)
    usage    = @{
        input      = $Run.usage.input_tokens
        cacheRead  = $Run.usage.cache_read_input_tokens
        cacheWrite = $Run.usage.cache_creation_input_tokens
        output     = $Run.usage.output_tokens
        costUsd    = $Run.total_cost_usd
    }
}
$Json = Out-Result $Result
[System.IO.File]::WriteAllText((Join-Path $RunDir 'result.json'), $Json, $Utf8)
$Json
