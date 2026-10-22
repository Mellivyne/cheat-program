param([switch]$CompileOnly)
$ErrorActionPreference='Stop'
# Reproducible local build only. Minecraft launches the bundled window-capture.exe directly;
# piping binary capture through PowerShell's object output would corrupt frame bytes.
if(-not $CompileOnly){throw 'Use window-capture.exe for capture. This script only rebuilds it with -CompileOnly.'}
$taskCompiler=Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$taskFramework=Split-Path -Parent $taskCompiler
$taskMetadata=Join-Path $env:SystemRoot 'System32\WinMetadata'
$taskSource=Join-Path $PSScriptRoot 'window-capture.cs'
$taskScroll=Join-Path $PSScriptRoot 'window-scroll.cs'
$taskOutput=Join-Path $PSScriptRoot 'window-capture.exe'
& $taskCompiler /nologo /target:winexe /platform:x64 /optimize+ "/out:$taskOutput" "/reference:$taskMetadata\Windows.Graphics.winmd" "/reference:$taskMetadata\Windows.Foundation.winmd" "/reference:$taskFramework\System.Runtime.WindowsRuntime.dll" "/reference:$taskFramework\System.Runtime.dll" "/reference:$taskFramework\System.Runtime.InteropServices.WindowsRuntime.dll" $taskSource $taskScroll
if($LASTEXITCODE -ne 0){throw 'Capture helper compilation failed'}
