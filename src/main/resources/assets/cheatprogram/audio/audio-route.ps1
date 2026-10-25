param([switch]$CompileOnly)
$ErrorActionPreference='Stop'
if(-not $CompileOnly){throw 'Run audio-route.exe directly. This script only rebuilds it.'}
$taskCompiler=Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$taskFramework=Split-Path -Parent $taskCompiler
$taskOutput=Join-Path $PSScriptRoot 'audio-route.exe'
$taskSource=Join-Path $PSScriptRoot 'audio-route.cs'
& $taskCompiler /nologo /target:winexe /platform:x64 /optimize+ "/out:$taskOutput" "/reference:$taskFramework\System.Runtime.Serialization.dll" $taskSource (Join-Path $PSScriptRoot 'application-tree.cs')
if($LASTEXITCODE -ne 0){throw 'Audio routing helper compilation failed'}
