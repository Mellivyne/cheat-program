param([string]$Provider = '', [int]$OwnerPid = 0, [string]$TitleBase64 = 'IA==', [string]$ArtistBase64 = 'IA==', [int]$ProcessId = 0)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
    $framework = Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319'
    if (!(Test-Path -LiteralPath (Join-Path $framework 'csc.exe'))) { $framework = Join-Path $env:SystemRoot 'Microsoft.NET\Framework\v4.0.30319' }
    $compiler = Join-Path $framework 'csc.exe'
    $executable = Join-Path $PSScriptRoot 'player-lyrics.exe'
    $references = @('UIAutomationClient.dll', 'UIAutomationTypes.dll', 'WindowsBase.dll') | ForEach-Object { '/reference:' + (Join-Path $framework ('WPF\' + $_)) }
    & $compiler /nologo /target:exe /platform:anycpu /reference:System.Web.Extensions.dll @references ('/out:' + $executable) (Join-Path $PSScriptRoot 'player-lyrics.cs') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Lyrics adapter compilation failed' }
    & $executable run $Provider $OwnerPid $TitleBase64 $ArtistBase64 $ProcessId
    exit $LASTEXITCODE
} catch {
    [Console]::WriteLine('{"status":"failed","text":"","sequence":0}')
    [Console]::Out.Flush()
    exit 1
}
