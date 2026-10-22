param(
    [ValidateSet('cpu','cuda')][string]$Device = 'cpu',
    [string]$Python = '',
    [switch]$DownloadBaseModel,
    [switch]$DownloadModels,
    [switch]$CudaRuntimeDlls,
    [ValidateSet('fp32','fp16','int8')][string]$ModelVariant = 'fp32'
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$taskVoiceRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$taskVenv = Join-Path $taskVoiceRoot '.venv'
$taskPythonExe = Join-Path $taskVenv 'Scripts\python.exe'
$taskManifest = Join-Path $taskVoiceRoot 'environment.json'
$taskDownloads = Join-Path $taskVoiceRoot 'downloads'
$taskModels = Join-Path $taskVoiceRoot 'models'
$taskPacks = Join-Path $taskVoiceRoot 'voicepacks'
$env:PIP_CONFIG_FILE = 'NUL'
$env:PIP_CACHE_DIR = Join-Path $taskDownloads 'pip-cache'
$env:PIP_DISABLE_PIP_VERSION_CHECK = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONDONTWRITEBYTECODE = '1'
# Keep every write under the owned voice directory, without registry, PATH or global pip changes.
foreach ($taskOwnedPath in @($taskVoiceRoot,$taskVenv,$taskDownloads,$taskModels,$taskPacks)) {
    $taskCheckPath = [IO.Path]::GetFullPath($taskOwnedPath)
    if ($taskCheckPath -ne $taskVoiceRoot -and -not $taskCheckPath.StartsWith($taskVoiceRoot + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid local voice destination.' }
    if (Test-Path -LiteralPath $taskCheckPath) {
        if ((Get-Item -LiteralPath $taskCheckPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refusing linked voice destination: $taskCheckPath" }
    }
}
foreach ($taskDirectory in @($taskDownloads,$taskModels,$taskPacks)) { New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null }
Write-Host "Voice environment: $taskVoiceRoot"
Write-Host "Downloaded files: $taskDownloads"
Write-Host "Place matching voice pack (.bin / .npz) in: $taskPacks"
Write-Host 'Preparing Python and local dependencies; no speech synthesis is performed.'

function Download-VoiceFile([string]$Url,[string]$Destination,[long]$Minimum=1024) {
    if (-not $Url.StartsWith('https://',[StringComparison]::OrdinalIgnoreCase)) { throw 'Downloads require HTTPS.' }
    $taskAbsolute = [IO.Path]::GetFullPath($Destination)
    if (-not $taskAbsolute.StartsWith($taskVoiceRoot + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Download destination is outside the voice directory.' }
    $taskPart = $taskAbsolute + '.partial'
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $taskPart -TimeoutSec 900 -MaximumRedirection 8
    if ((Get-Item -LiteralPath $taskPart).Length -lt $Minimum) { throw "Incomplete download: $Url" }
    Move-Item -LiteralPath $taskPart -Destination $taskAbsolute -Force
}
function Test-VoicePython([string]$Candidate) {
    if (-not $Candidate -or -not (Test-Path -LiteralPath $Candidate -PathType Leaf)) { return $false }
    try {
        $taskVersion = @(& $Candidate -I -B -c "import sys; print('compatible' if (3,10) <= sys.version_info[:2] < (3,14) and sys.maxsize > 2**32 else 'unsupported')" 2>$null)
        return $LASTEXITCODE -eq 0 -and $taskVersion.Count -gt 0 -and $taskVersion[-1].Trim() -eq 'compatible'
    } catch { return $false }
}
function Install-PortableVoicePython {
    $taskPythonZip = Join-Path $taskDownloads 'python-3.12.10-embed-amd64.zip'
    # Official release table digest catches incomplete or wrong-version cached archives.
    $taskExpectedMd5 = 'fe8ef205f2e9c3ba44d0cf9954e1abd3'
    if (-not (Test-Path -LiteralPath $taskPythonZip -PathType Leaf) -or (Get-FileHash -LiteralPath $taskPythonZip -Algorithm MD5).Hash.ToLowerInvariant() -ne $taskExpectedMd5) {
        Download-VoiceFile 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip' $taskPythonZip 8000000
    }
    if ((Get-FileHash -LiteralPath $taskPythonZip -Algorithm MD5).Hash.ToLowerInvariant() -ne $taskExpectedMd5) { throw 'The official Python archive digest does not match.' }
    $taskScripts = Join-Path $taskVenv 'Scripts'
    New-Item -ItemType Directory -Path $taskScripts -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskZip = [IO.Compression.ZipFile]::OpenRead($taskPythonZip)
    try {
        foreach ($taskEntry in $taskZip.Entries) {
            $taskEntryPath = [IO.Path]::GetFullPath((Join-Path $taskScripts $taskEntry.FullName))
            if (-not $taskEntryPath.StartsWith($taskScripts + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe archive entry.' }
            if ($taskEntry.FullName.EndsWith('/')) { New-Item -ItemType Directory -Path $taskEntryPath -Force | Out-Null; continue }
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($taskEntryPath)) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry,$taskEntryPath,$true)
        }
    } finally { $taskZip.Dispose() }
    New-Item -ItemType Directory -Path (Join-Path $taskScripts 'Lib\site-packages') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $taskScripts 'python312._pth'),"python312.zip`r`n.`r`nLib\site-packages`r`nimport site`r`n",[Text.Encoding]::ASCII)
    $taskGetPip = Join-Path $taskDownloads 'get-pip.py'
    Download-VoiceFile 'https://bootstrap.pypa.io/get-pip.py' $taskGetPip 1000000
    & $taskPythonExe -I -B $taskGetPip --no-warn-script-location --index-url 'https://pypi.org/simple' --timeout 30 --retries 2
    if ($LASTEXITCODE -ne 0) { throw 'Preparing pip in portable Python failed.' }
    Get-FileHash -LiteralPath $taskPythonZip -Algorithm SHA256 | Format-List
}

if (-not (Test-VoicePython $taskPythonExe)) {
    if (Test-Path -LiteralPath $taskVenv) {
        # Preserve unsupported/interrupted environments without recursive deletion.
        $taskArchive = Join-Path $taskVoiceRoot ('.venv.unsupported-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
        if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($taskVenv)) -ne $taskVoiceRoot -or [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($taskArchive)) -ne $taskVoiceRoot) { throw 'Unsafe environment move.' }
        Move-Item -LiteralPath $taskVenv -Destination $taskArchive
        Write-Host "Preserved previous environment: $taskArchive"
    }
    if ($Python -and (Test-VoicePython $Python)) {
        & $Python -I -B -m venv $taskVenv
        if ($LASTEXITCODE -ne 0) { throw 'Creating the dedicated environment from the chosen Python failed.' }
    } else {
        if ($Python) { Write-Host 'The chosen base Python is unsupported; using isolated portable Python instead.' }
        Install-PortableVoicePython
    }
    if (-not (Test-VoicePython $taskPythonExe)) { throw 'The dedicated 64-bit Python 3.10-3.13 could not be prepared.' }
}
if (Test-Path -LiteralPath $taskManifest -PathType Leaf) { Remove-Item -LiteralPath $taskManifest -Force }
& $taskPythonExe -I -B -m pip install --upgrade pip --no-input --disable-pip-version-check --index-url 'https://pypi.org/simple' --timeout 30 --retries 2
if ($LASTEXITCODE -ne 0) { throw 'pip setup failed.' }
& $taskPythonExe -I -B -m pip install 'kokoro-onnx==0.6.1' 'misaki-fork[zh]' 'librosa>=0.10,<0.12' --no-input --disable-pip-version-check --index-url 'https://pypi.org/simple' --timeout 30 --retries 2
if ($LASTEXITCODE -ne 0) { throw 'Kokoro, local Chinese G2P or pitch dependencies could not be installed.' }
# CPU and GPU wheels share their module name: keep exactly one backend.
& $taskPythonExe -I -B -m pip uninstall -y onnxruntime onnxruntime-gpu
if ($LASTEXITCODE -ne 0) { throw 'Could not select a single ONNX Runtime backend.' }
if ($Device -eq 'cuda') {
    if ($CudaRuntimeDlls) { $taskOrtPackage = 'onnxruntime-gpu[cuda,cudnn]>=1.21,<1.27' }
    else { $taskOrtPackage = 'onnxruntime-gpu>=1.21,<1.27' }
} else { $taskOrtPackage = 'onnxruntime>=1.21,<1.27' }
& $taskPythonExe -I -B -m pip install $taskOrtPackage --no-input --disable-pip-version-check --index-url 'https://pypi.org/simple' --timeout 30 --retries 2
if ($LASTEXITCODE -ne 0) { throw 'Selected ONNX Runtime backend installation failed.' }
& $taskPythonExe -I -B -m pip freeze | Set-Content -LiteralPath (Join-Path $taskVoiceRoot 'environment-packages.txt') -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'Could not record installed dependency versions.' }

$taskModelName = if ($ModelVariant -eq 'fp32') { 'kokoro-v1.1-zh.onnx' } else { "kokoro-v1.1-zh.$ModelVariant.onnx" }
$taskModelPath = Join-Path $taskModels $taskModelName
$taskRelease = 'https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/'
if ($DownloadBaseModel -or $DownloadModels) {
    if (-not (Test-Path -LiteralPath $taskModelPath -PathType Leaf) -or (Get-Item -LiteralPath $taskModelPath).Length -lt 1048576) { Download-VoiceFile ($taskRelease + $taskModelName) $taskModelPath 1048576 }
    Get-FileHash -LiteralPath $taskModelPath -Algorithm SHA256 | Format-List
    foreach ($taskNotice in @(
        @('https://raw.githubusercontent.com/thewh1teagle/kokoro-onnx/main/LICENSE','kokoro-onnx-MIT-LICENSE.txt'),
        @('https://www.apache.org/licenses/LICENSE-2.0.txt','Kokoro-Apache-2.0-LICENSE.txt'),
        @('https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh/raw/main/README.md','Kokoro-model-card.md')
    )) { Download-VoiceFile $taskNotice[0] (Join-Path $taskModels $taskNotice[1]) 100 }
}
if ($DownloadModels) {
    $taskPack = Join-Path $taskPacks 'voices-v1.1-zh.bin'
    if (-not (Test-Path -LiteralPath $taskPack -PathType Leaf) -or (Get-Item -LiteralPath $taskPack).Length -lt 1048576) { Download-VoiceFile ($taskRelease + 'voices-v1.1-zh.bin') $taskPack 1048576 }
    Get-FileHash -LiteralPath $taskPack -Algorithm SHA256 | Format-List
}
$taskReport = Join-Path $taskVoiceRoot 'environment-install-check.json'
& $taskPythonExe -I -B (Join-Path $taskVoiceRoot 'check-environment.py') --device $Device --model $taskModelPath --report $taskReport --manifest $taskManifest
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $taskManifest -PathType Leaf)) { throw 'Local environment check failed. See environment-install-check.json and setup.log.' }
Write-Host "Prepared Python: $taskPythonExe"
Write-Host "Prepared base model: $taskModelPath"
Write-Host "Place matching voices.bin or .npz in: $taskPacks"
Write-Host 'Environment checks passed without speech synthesis. Missing voice packs are reported separately.'
