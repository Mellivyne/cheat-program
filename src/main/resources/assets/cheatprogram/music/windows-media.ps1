param(
    [int]$ParentProcessId = 0,
    [int]$PollMillis = 1000,
    [switch]$Once
)

# Read-only Windows SMTC metadata bridge. No playback controls, files, history or network access.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$PollMillis = [Math]::Max(250, [Math]::Min(5000, $PollMillis))

function Write-Snapshot($track, [bool]$available, $sessions = @()) {
    [Console]::WriteLine((@{ available = $available; track = $track; sessions = @($sessions) } | ConvertTo-Json -Compress -Depth 6))
    [Console]::Out.Flush()
}

$compatAvailable = $false
try {
    . (Join-Path $PSScriptRoot 'cloudmusic-compat.ps1')
    $compatAvailable = $true
} catch { }
$parentProcess = $null
if ($ParentProcessId -gt 0) {
    try { $parentProcess = [System.Diagnostics.Process]::GetProcessById($ParentProcessId) } catch { exit 0 }
}
$winRtAvailable = $false
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
    $propertiesType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
    $appInfoType = [Windows.ApplicationModel.AppInfo, Windows.ApplicationModel, ContentType = WindowsRuntime]
    $asTaskMethod = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetGenericArguments().Count -eq 1 -and
        $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1
    if ($null -eq $asTaskMethod) { throw 'WinRT task adapter is unavailable.' }
    $winRtAvailable = $true
} catch { }

function Await-WinRT($operation, [Type]$resultType, [int]$timeoutMillis = 1500) {
    $task = $asTaskMethod.MakeGenericMethod($resultType).Invoke($null, @($operation))
    if (!$task.Wait($timeoutMillis)) {
        try { $operation.Cancel() } catch { }
        throw [System.TimeoutException]::new('Windows media metadata timed out.')
    }
    return $task.Result
}

function Clean-Text($value, [int]$maxLength) {
    $text = ([string]$value -replace '[\x00-\x1f\x7f]', ' ').Trim()
    if ($text.Length -gt $maxLength) { $text = $text.Substring(0, $maxLength) }
    return $text
}

$manager = $null
do {
    if ($null -ne $parentProcess) {
        try { if ($parentProcess.HasExited) { break } } catch { break }
    }
    try {
        if (!$winRtAvailable) { throw 'WinRT media metadata is unavailable.' }
        if ($null -eq $manager) { $manager = Await-WinRT ($managerType::RequestAsync()) $managerType 5000 }
        $current = $manager.GetCurrentSession()
        $candidates = [System.Collections.Generic.List[object]]::new()
        $ordered = [System.Collections.Generic.List[object]]::new()
        if ($null -ne $current) { $ordered.Add($current) }
        foreach ($session in $manager.GetSessions()) {
            if (![Object]::ReferenceEquals($session, $current)) { $ordered.Add($session) }
        }
        $position = 0
        foreach ($session in $ordered) {
            try {
                $status = [string]$session.GetPlaybackInfo().PlaybackStatus
                if ($status -eq 'Closed') { continue }
                # A playing session wins over every paused one. Within that state prefer Windows' current session.
                $rank = if ($status -eq 'Playing') { 0 } elseif ($status -eq 'Paused') { 1 } else { 2 }
                $candidates.Add([pscustomobject]@{ Session = $session; Status = $status; Rank = $rank; Order = $position })
                $position++
            } catch { }
        }
        $selected = $null
        $allTracks = [System.Collections.Generic.List[object]]::new()
        foreach ($candidate in ($candidates | Sort-Object Rank, Order | Select-Object -First 8)) {
            try {
                $properties = Await-WinRT ($candidate.Session.TryGetMediaPropertiesAsync()) $propertiesType
                $provider = Clean-Text $candidate.Session.SourceAppUserModelId 512
                $mediaType = [string]$properties.PlaybackType
                if ($mediaType -eq 'Image') { continue }
                $title = Clean-Text $properties.Title 256
                if ([string]::IsNullOrWhiteSpace($title)) { continue }
                $displayName = ''
                try { $displayName = Clean-Text ($appInfoType::GetFromAppUserModelId($provider).DisplayInfo.DisplayName) 128 } catch { }
                $sampledUtc = [DateTimeOffset]::UtcNow
                $position = 0.0
                $duration = 0.0
                $timelineAvailable = $false
                try {
                    $timeline = $candidate.Session.GetTimelineProperties()
                    $position = [Math]::Max(0.0, ($timeline.Position - $timeline.StartTime).TotalSeconds)
                    $duration = [Math]::Max(0.0, ($timeline.EndTime - $timeline.StartTime).TotalSeconds)
                    # Empty/default timelines do not provide song progress.
                    $timelineAvailable = $duration -gt 0 -or ($position -gt 0 -and $timeline.LastUpdatedTime.Year -ge 2000)
                    # Position is current as of LastUpdatedTime, not as of this poll (Microsoft's API contract).
                    if ($timelineAvailable -and $candidate.Status -eq 'Playing' -and $timeline.LastUpdatedTime.Year -ge 2000) {
                        $age = ($sampledUtc - $timeline.LastUpdatedTime).TotalSeconds
                        if ($age -ge 0 -and $age -le [Math]::Max(3600.0, $duration + 30.0)) { $position += $age }
                    }
                    if ($duration -gt 0) { $position = [Math]::Min($position, $duration) }
                } catch { }
                $record = @{
                    provider = $provider
                    displayName = $displayName
                    title = $title
                    artist = (Clean-Text $properties.Artist 256)
                    album = (Clean-Text $properties.AlbumTitle 256)
                    position = $position
                    duration = $duration
                    timelineAvailable = $timelineAvailable
                    sampledUtcMillis = $sampledUtc.ToUnixTimeMilliseconds()
                    status = $candidate.Status
                    mediaType = $mediaType
                    playing = ($candidate.Status -eq 'Playing')
                }
                $allTracks.Add($record)
                # Preserve the music-only choice used by the existing island announcement.
                $isMusic = $mediaType -ne 'Video' -and (($mediaType -and $mediaType -ne 'Unknown') -or
                    $provider -match 'cloudmusic|netease|qqmusic|spotify|applemusic|apple-music|zunemusic|foobar2000|aimp|musicbee')
                if ($null -eq $selected -and $isMusic) { $selected = $record }
            } catch { }
        }
        if ($null -eq $selected -and $compatAvailable) { $selected = Get-CloudMusicTrack }
        Write-Snapshot $selected $true $allTracks.ToArray()
    } catch {
        $manager = $null
        $fallback = if ($compatAvailable) { Get-CloudMusicTrack } else { $null }
        Write-Snapshot $fallback ($null -ne $fallback)
    }
    if (!$Once) { Start-Sleep -Milliseconds $PollMillis }
} while (!$Once)
