param([switch]$CompileOnly)
$ErrorActionPreference='Stop'
if(-not $CompileOnly){throw 'Run process-audio.exe directly. This script only builds the bundled helper.'}
$taskCompiler=Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$taskFramework=Split-Path -Parent $taskCompiler
$taskOutput=Join-Path $PSScriptRoot 'process-audio.exe'
$taskSource=Join-Path $PSScriptRoot 'process-audio.cs'
& $taskCompiler /nologo /target:winexe /platform:x64 /optimize+ "/out:$taskOutput" $taskSource
if($LASTEXITCODE -ne 0){throw 'Process audio helper compilation failed'}
