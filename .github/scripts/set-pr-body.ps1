#requires -Version 7

# Writes a pull request body and reads it back, so a body never ships with its
# newlines collapsed, its backticks or $ eaten by the shell, or a second
# handoff stacked on the first. Every body write goes through here.
#
# Why it breaks by hand: gh output captured in PowerShell is an array of
# lines, and anything that turns that array back into one string (a [string]
# parameter, "$x", WriteAllText) joins it with spaces. Text passed with
# --body "..." also loses backticks and $ to the shell. This script reads the
# body as one JSON string, takes new text only from files, writes LF UTF-8
# without a BOM, and fails unless GitHub then holds exactly what was meant.
#
# Modes (pick one):
#   -BodyFile f            replace the whole body
#   -AppendFile f          add f's text after the body, one blank line between
#   -HandoffFile f         replace the <!-- handoff --> block (marker to end of
#                          body) with f's text, or append it if there is none
#   -Replace old -With new replace one exact occurrence; fails on zero or many
#   -Create -Title t -Head branch -BodyFile f   open a PR into -Base (dev)
#
# Prints one JSON object: status (updated | unchanged | created), number,
# lines, chars.

[CmdletBinding(DefaultParameterSetName = 'Body')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Body')]
    [Parameter(Mandatory, ParameterSetName = 'Append')]
    [Parameter(Mandatory, ParameterSetName = 'Handoff')]
    [Parameter(Mandatory, ParameterSetName = 'Replace')]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$PullRequest,
    [ValidatePattern('^[^/]+/[^/]+$')]
    [string]$Repository,
    [Parameter(Mandatory, ParameterSetName = 'Body')]
    [Parameter(Mandatory, ParameterSetName = 'Create')]
    [string]$BodyFile,
    [Parameter(Mandatory, ParameterSetName = 'Append')]
    [string]$AppendFile,
    [Parameter(Mandatory, ParameterSetName = 'Handoff')]
    [string]$HandoffFile,
    # [object], not [string]: a [string] parameter would silently space-join
    # an array of lines, which is the bug this script exists to stop.
    [Parameter(Mandatory, ParameterSetName = 'Replace')]
    [object]$Replace,
    [Parameter(Mandatory, ParameterSetName = 'Replace')]
    [AllowEmptyString()]
    [object]$With,
    [Parameter(Mandatory, ParameterSetName = 'Create')]
    [switch]$Create,
    [Parameter(Mandatory, ParameterSetName = 'Create')]
    [string]$Title,
    [Parameter(Mandatory, ParameterSetName = 'Create')]
    [string]$Head,
    [Parameter(ParameterSetName = 'Create')]
    [string]$Base = 'dev'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$Utf8 = [System.Text.UTF8Encoding]::new($false)
$Marker = '<!-- handoff -->'

function Invoke-Gh {
    param([string[]]$Arguments)
    $Output = & gh @Arguments
    if ($LASTEXITCODE -ne 0) { throw "gh $($Arguments -join ' ') failed with exit code $LASTEXITCODE." }
    $Output
}

function ConvertTo-Text {
    param($Value)
    if ($null -eq $Value) { return '' }
    $Text = if ($Value -is [array]) { $Value -join "`n" } else { [string]$Value }
    $Text.Replace("`r`n", "`n").Replace("`r", "`n")
}

function Read-TextFile {
    param([string]$Path)
    if (-not (Test-Path $Path -PathType Leaf)) { throw "No such file: $Path" }
    ConvertTo-Text ([System.IO.File]::ReadAllText((Resolve-Path $Path).Path, $Utf8))
}

function Get-Body {
    param([int]$Number)
    # One JSON string, so the lines arrive joined by their own newlines.
    $Pr = (Invoke-Gh @('api', "repos/$Repository/pulls/$Number")) -join "`n" | ConvertFrom-Json
    ConvertTo-Text $Pr.body
}

function Assert-Body {
    param([string]$Text)
    if (-not $Text.Trim()) { throw 'Refusing to write an empty body.' }
    # AGENTS.md: no attribution lines in shipped history. Naming a tool in a
    # process PR is fine and has precedent; signing the work is not.
    $Banned = '(?im)^\s*co-authored-by:|generated with \[|🤖'
    if ($Text -match $Banned) { throw "Body carries an attribution line ('$($Matches[0].Trim())'); remove it." }
    # Collapsed newlines look like markdown structure stuck mid-line.
    $Glued = @(($Text -split "`n") | Where-Object { $_ -match '\S\s+(#{1,6} |\| ?-{3}|- \*\*|\*\*\w[^*]*\*\*\s+- )' -and $_.Length -gt 400 })
    if ($Glued.Count) { throw "Body looks space-joined (a long line holds headings, list items or a table rule): '$($Glued[0].Substring(0, 120))...'" }
}

function Write-Body {
    param([int]$Number, [string]$Text)
    $File = Join-Path ([System.IO.Path]::GetTempPath()) "pr-body-$Number-$PID.md"
    try {
        [System.IO.File]::WriteAllText($File, $Text, $Utf8)
        Invoke-Gh @('pr', 'edit', $Number, '--repo', $Repository, '--body-file', $File) | Out-Null
    } finally {
        Remove-Item $File -ErrorAction SilentlyContinue
    }
}

function Assert-Stored {
    param([int]$Number, [string]$Text)
    $Stored = Get-Body $Number
    if ($Stored.TrimEnd() -ne $Text.TrimEnd()) {
        throw "PR #$Number body does not match what was written ($($Stored.Length) chars stored, $($Text.Length) meant). Read it back before retrying."
    }
    $Stored
}

if (-not $Repository) {
    $Origin = git remote get-url origin
    if ($LASTEXITCODE -ne 0) { throw 'No origin remote here; pass -Repository.' }
    $Repository = (Invoke-Gh @('repo', 'view', $Origin, '--json', 'nameWithOwner', '-q', '.nameWithOwner')) -join ''
}
if (-not $Repository) { throw 'Could not resolve the repository from origin.' }

if ($Create) {
    $New = Read-TextFile $BodyFile
    Assert-Body $New
    $File = Join-Path ([System.IO.Path]::GetTempPath()) "pr-body-new-$PID.md"
    try {
        [System.IO.File]::WriteAllText($File, $New, $Utf8)
        $Url = (Invoke-Gh @('pr', 'create', '--repo', $Repository, '--base', $Base, '--head', $Head, '--title', $Title, '--body-file', $File)) -join ''
    } finally {
        Remove-Item $File -ErrorAction SilentlyContinue
    }
    $Number = [int]($Url -replace '^.*/pull/(\d+).*$', '$1')
    $Stored = Assert-Stored $Number $New
    @{ status = 'created'; number = $Number; url = $Url; lines = ($Stored -split "`n").Count; chars = $Stored.Length } | ConvertTo-Json
    return
}

$Old = Get-Body $PullRequest
$New = switch ($PSCmdlet.ParameterSetName) {
    'Body' { Read-TextFile $BodyFile }
    'Append' { $Old.TrimEnd() + "`n`n" + (Read-TextFile $AppendFile).Trim() + "`n" }
    'Handoff' {
        $Block = (Read-TextFile $HandoffFile).Trim()
        if (-not $Block.StartsWith($Marker)) { $Block = "$Marker`n$Block" }
        $At = $Old.IndexOf($Marker)
        if ($At -ge 0 -and $Old.IndexOf($Marker, $At + 1) -ge 0) {
            throw "PR #$PullRequest already has two handoff blocks; merge them by hand with -BodyFile."
        }
        $Kept = if ($At -ge 0) { $Old.Substring(0, $At) } else { $Old }
        $Kept.TrimEnd() + "`n`n" + $Block + "`n"
    }
    'Replace' {
        $From = ConvertTo-Text $Replace
        $To = ConvertTo-Text $With
        if (-not $From) { throw '-Replace is empty.' }
        $Count = ([regex]::Matches($Old, [regex]::Escape($From))).Count
        if ($Count -ne 1) { throw "-Replace text occurs $Count times in PR #$PullRequest; it must occur exactly once." }
        $Old.Replace($From, $To)
    }
}

if ($New.TrimEnd() -eq $Old.TrimEnd()) {
    @{ status = 'unchanged'; number = $PullRequest; lines = ($Old -split "`n").Count; chars = $Old.Length } | ConvertTo-Json
    return
}
Assert-Body $New
Write-Body $PullRequest $New
$Stored = Assert-Stored $PullRequest $New
@{ status = 'updated'; number = $PullRequest; lines = ($Stored -split "`n").Count; chars = $Stored.Length } | ConvertTo-Json
