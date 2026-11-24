# Saves a long-lived claude token for invoke-bug-hunt.ps1 only, so the bug hunt
# stops failing when the shared CLI login's rotating refresh token is renewed
# by another claude process. Windows-only: the token is DPAPI-encrypted for
# this user on this machine and is never put in an environment variable.
#
#   .\.github\scripts\set-bug-hunt-token.ps1 -Install   # once: adds Set-BugHuntToken to $PROFILE
#   Set-BugHuntToken                                    # opens the token page, saves the token
#   Set-BugHuntToken -Remove                            # deletes the saved token
[CmdletBinding()]
param([switch]$Install, [switch]$Remove)
$ErrorActionPreference = 'Stop'

if (-not $IsWindows -and $PSVersionTable.PSEdition -ne 'Desktop') {
    throw 'Windows-only: the token is stored with DPAPI.'
}
$TokenFile = Join-Path $HOME '.claude\bug-hunt-token.txt'

if ($Install) {
    $Self = (Resolve-Path $PSCommandPath).Path
    $Line = "function Set-BugHuntToken { & '$($Self -replace "'", "''")' @args }"
    if (-not (Test-Path $PROFILE)) { New-Item -ItemType File -Path $PROFILE -Force | Out-Null }
    $Lines = @(Get-Content $PROFILE | Where-Object { $_ -notmatch '^function Set-BugHuntToken ' })
    $Lines += $Line
    Set-Content -Path $PROFILE -Value $Lines
    Write-Host "Set-BugHuntToken added to $PROFILE. Restart the terminal or run: . `$PROFILE"
    return
}

if ($Remove) {
    if (Test-Path $TokenFile) { Remove-Item $TokenFile; Write-Host "Removed $TokenFile." }
    else { Write-Host "No token saved at $TokenFile." }
    return
}

if ((Test-Path $TokenFile) -and
    (Read-Host "A token is already saved at $TokenFile. Overwrite? (y/N)") -notmatch '^y') {
    Write-Host 'Kept the existing token.'
    return
}

# setup-token is interactive (browser sign-in), so its output is not captured;
# copy the printed token into the hidden prompt below.
& claude setup-token
$Secure = Read-Host 'Paste the token (input hidden)' -AsSecureString
if ($Secure.Length -eq 0) { throw 'No token entered; nothing saved.' }
New-Item -ItemType Directory -Path (Split-Path $TokenFile) -Force | Out-Null
$Secure | ConvertFrom-SecureString | Set-Content -Path $TokenFile
Write-Host "Saved to $TokenFile (DPAPI, this user and machine only). invoke-bug-hunt.ps1 will use it."
