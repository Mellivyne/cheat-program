# NetEase versions without SMTC: read only the current song caption of their known music window.
# No UI automation, file/history reads, playback control or inferred playback timing.
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class CheatProgramCloudMusicWindows {
    public delegate bool EnumProc(IntPtr window, IntPtr data);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumProc callback, IntPtr data);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern int GetWindowText(IntPtr window, StringBuilder text, int maximum);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern int GetClassName(IntPtr window, StringBuilder text, int maximum);
    public static string CurrentCaption(int[] processIds) {
        var allowed = new HashSet<int>(processIds);
        string primary = null;
        string mirror = null;
        EnumProc callback = delegate(IntPtr window, IntPtr data) {
            uint owner;
            GetWindowThreadProcessId(window, out owner);
            if (!allowed.Contains((int)owner)) return true;
            var name = new StringBuilder(128);
            GetClassName(window, name, name.Capacity);
            string windowClass = name.ToString();
            if (windowClass != "OrpheusBrowserHost" && windowClass != "icon") return true;
            var text = new StringBuilder(1024);
            GetWindowText(window, text, text.Capacity);
            string caption = text.ToString();
            int separator = caption.LastIndexOf(" - ", StringComparison.Ordinal);
            if (separator <= 0 || separator >= caption.Length - 3) return true;
            if (windowClass == "OrpheusBrowserHost") primary = caption;
            else mirror = caption;
            return true;
        };
        EnumWindows(callback, IntPtr.Zero);
        GC.KeepAlive(callback);
        return primary ?? mirror;
    }
}
'@

function Get-CloudMusicTrack {
    try {
        $processes = @([System.Diagnostics.Process]::GetProcessesByName('cloudmusic'))
        if ($processes.Count -eq 0) { return $null }
        try {
            $ids = [int[]]@($processes | ForEach-Object { $_.Id })
            $caption = [CheatProgramCloudMusicWindows]::CurrentCaption($ids)
        } finally {
            foreach ($process in $processes) { $process.Dispose() }
        }
        if ([string]::IsNullOrWhiteSpace($caption)) { return $null }
        $separator = $caption.LastIndexOf(' - ', [StringComparison]::Ordinal)
        $title = Clean-Text ($caption.Substring(0, $separator)) 256
        $artist = Clean-Text ($caption.Substring($separator + 3)) 256
        if ([string]::IsNullOrWhiteSpace($title) -or [string]::IsNullOrWhiteSpace($artist)) { return $null }
        return @{
            provider = 'cloudmusic.exe'
            displayName = 'NetEase CloudMusic'
            title = $title
            artist = $artist
            album = ''
            position = 0.0
            duration = 0.0
            timelineAvailable = $false
            sampledUtcMillis = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            status = 'Opened'
            mediaType = 'Music'
            playing = $false
        }
    } catch { return $null }
}
