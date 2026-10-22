param([string]$Wave,[string]$Culture='zh-CN',[long]$OwnerPid=0)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$engine=$null
$waveStream=$null
try {
 Add-Type -AssemblyName System.Speech
 $recognizers=@([System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers())
 $recognizer=$recognizers | Where-Object {$_.Culture.Name -ieq $Culture} | Select-Object -First 1
 if($null -eq $recognizer){$prefix=$Culture.Split('-')[0];$recognizer=$recognizers | Where-Object {$_.Culture.TwoLetterISOLanguageName -ieq $prefix} | Select-Object -First 1}
 if($null -eq $recognizer){'{"ok":false,"error":"language"}';exit 0}
 $engine=[System.Speech.Recognition.SpeechRecognitionEngine]::new($recognizer)
 $engine.LoadGrammar([System.Speech.Recognition.DictationGrammar]::new())
 $waveStream=[IO.MemoryStream]::new([IO.File]::ReadAllBytes($Wave))
 Remove-Item -LiteralPath $Wave -Force -ErrorAction SilentlyContinue
 $engine.SetInputToWaveStream($waveStream)
 $engine.InitialSilenceTimeout=[TimeSpan]::FromSeconds(3)
 $engine.EndSilenceTimeout=[TimeSpan]::FromMilliseconds(350)
 $engine.EndSilenceTimeoutAmbiguous=[TimeSpan]::FromMilliseconds(600)
 $phrases=[Collections.Generic.List[string]]::new()
 for($i=0;$i -lt 256;$i++){
  if($OwnerPid -gt 0 -and $null -eq (Get-Process -Id $OwnerPid -ErrorAction SilentlyContinue)){exit 0}
  try{$result=$engine.Recognize([TimeSpan]::FromSeconds(3))}catch{if($phrases.Count -gt 0){break}else{throw}}
  if($null -eq $result){break}
  if(-not [string]::IsNullOrWhiteSpace($result.Text)){$phrases.Add($result.Text)}
 }
 if($phrases.Count -eq 0){'{"ok":false,"error":"empty"}'}else{@{ok=$true;text=($phrases -join ' ')} | ConvertTo-Json -Compress}
}catch{'{"ok":false,"error":"recognizer"}'}finally{if($null -ne $engine){$engine.Dispose()};if($null -ne $waveStream){$waveStream.Dispose()};Remove-Item -LiteralPath $Wave -Force -ErrorAction SilentlyContinue}
