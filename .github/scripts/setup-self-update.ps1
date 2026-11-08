<#
.SYNOPSIS
    Points the self-update pipeline (#self-update) at a Telegram channel.

.DESCRIPTION
    Sets the two repo variables staging.yml reads:
      UPDATE_CHANNEL_ID        bare channel id baked into the app and posted to by the bot
      UPDATE_CHANNEL_USERNAME  public @username, so an account that never opened the channel can still find it

    With -CheckBot it also asks the Bot API whether the helper bot can post there, and
    resolves the id from the username when you only give -ChannelUsername. The token is
    read from $env:HELPER_BOT_TOKEN or prompted for. It is only sent to api.telegram.org
    and is never written anywhere.

    -Disable puts the placeholders back (0 and -), which builds an app whose updater reads
    Unavailable and makes CI skip the metadata posts.

.EXAMPLE
    .\.github\scripts\setup-self-update.ps1 -ChannelUsername dwellgram_updates -CheckBot

.EXAMPLE
    .\.github\scripts\setup-self-update.ps1 -ChannelId -1001234567890 -ChannelUsername dwellgram_updates

.EXAMPLE
    .\.github\scripts\setup-self-update.ps1 -Disable
#>
[CmdletBinding(DefaultParameterSetName = 'Set')]
param(
    [Parameter(ParameterSetName = 'Set')]
    [string]$ChannelId,

    [Parameter(ParameterSetName = 'Set')]
    [string]$ChannelUsername,

    [Parameter(ParameterSetName = 'Set')]
    [switch]$CheckBot,

    [Parameter(ParameterSetName = 'Disable')]
    [switch]$Disable,

    [string]$Repository = 'dazewell/DwellGram'
)

$ErrorActionPreference = 'Stop'

function Set-RepoVariable([string]$Name, [string]$Value) {
    gh variable set $Name --repo $Repository --body $Value | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "gh variable set $Name failed" }
    Write-Host "  $Name = $Value"
}

if ($Disable) {
    Write-Host "Disabling self-update on $Repository"
    Set-RepoVariable UPDATE_CHANNEL_ID '0'
    Set-RepoVariable UPDATE_CHANNEL_USERNAME '-'
    return
}

$username = ($ChannelUsername ?? '').Trim().TrimStart('@')
if ($username -and $username -notmatch '^[A-Za-z][A-Za-z0-9_]{3,31}$') {
    throw "'$ChannelUsername' is not a valid Telegram username"
}

$id = ($ChannelId ?? '').Trim()
if ($id.StartsWith('-100')) { $id = $id.Substring(4) }

if ($CheckBot -or (-not $id -and $username)) {
    $token = $env:HELPER_BOT_TOKEN
    if (-not $token) {
        $secure = Read-Host 'Helper bot token (same as the HELPER_BOT_TOKEN secret)' -AsSecureString
        $token = [System.Net.NetworkCredential]::new('', $secure).Password
    }
    $api = "https://api.telegram.org/bot$token"
    # Resolve by username when there is one, so a given id is checked against it below.
    $chatRef = if ($username) { "@$username" } else { "-100$id" }

    $chat = (Invoke-RestMethod "$api/getChat" -Method Post -Body @{ chat_id = $chatRef }).result
    if ($chat.type -ne 'channel') { throw "$chatRef is a $($chat.type), not a channel" }
    $resolved = ([string]$chat.id).Substring(4)
    if ($id -and $id -ne $resolved) { throw "id $id does not match $chatRef ($resolved)" }
    $id = $resolved
    if (-not $username -and $chat.username) { $username = $chat.username }
    Write-Host "Channel: $($chat.title) (id $id$(if ($username) { ", @$username" }))"

    $me = (Invoke-RestMethod "$api/getMe").result
    $member = (Invoke-RestMethod "$api/getChatMember" -Method Post -Body @{ chat_id = "-100$id"; user_id = $me.id }).result
    if ($member.status -notin 'administrator', 'creator' -or ($member.status -eq 'administrator' -and -not $member.can_post_messages)) {
        throw "@$($me.username) must be an admin of the channel with 'Post messages'. It is: $($member.status)"
    }
    Write-Host "@$($me.username) can post there."
}

if ($id -notmatch '^[1-9]\d{0,15}$') {
    throw 'Give -ChannelId (bare or -100 form), or -ChannelUsername so it can be resolved'
}
if (-not $username) {
    Write-Warning 'No username: only accounts that have already opened the channel will find updates.'
}

Write-Host "Configuring self-update on $Repository"
Set-RepoVariable UPDATE_CHANNEL_ID $id
Set-RepoVariable UPDATE_CHANNEL_USERNAME ($username ? $username : '-')
Write-Host 'Done. The next staging build on dev bakes this in and posts its metadata there.'
