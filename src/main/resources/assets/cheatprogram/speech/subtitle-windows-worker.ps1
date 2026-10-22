param([string]$Culture='zh-CN',[long]$OwnerPid=0)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$engine=$null
function Emit($value){[Console]::WriteLine(($value|ConvertTo-Json -Compress -Depth 3));[Console]::Out.Flush()}
function OwnerAlive {return $OwnerPid -le 0 -or $null -ne (Get-Process -Id $OwnerPid -ErrorAction SilentlyContinue)}
try {
 Add-Type -AssemblyName System.Speech
 $available=@([System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers())
 $selected=$available|Where-Object {$_.Culture.Name -ieq $Culture}|Select-Object -First 1
 if($null -eq $selected){$prefix=$Culture.Split('-')[0];$selected=$available|Where-Object {$_.Culture.TwoLetterISOLanguageName -ieq $prefix}|Select-Object -First 1}
 if($null -eq $selected){Emit @{ready=$false;error='language'};exit 0}
 $engine=[System.Speech.Recognition.SpeechRecognitionEngine]::new($selected)
 $engine.LoadGrammar([System.Speech.Recognition.DictationGrammar]::new())
 $engine.InitialSilenceTimeout=[TimeSpan]::FromSeconds(2)
 $engine.EndSilenceTimeout=[TimeSpan]::FromMilliseconds(250)
 $engine.EndSilenceTimeoutAmbiguous=[TimeSpan]::FromMilliseconds(400)
 $format=[System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
 Emit @{ready=$true;engine='windows';culture=$selected.Culture.Name}
 while(OwnerAlive){
  $line=[Console]::ReadLine();if($null -eq $line -or $line -eq 'Q'){break}
  $sequence=0;$memory=$null
  try {
   if($line.Length -gt 350000){throw 'request limit'}
   $request=$line|ConvertFrom-Json;$sequence=[long]$request.sequence
   $bytes=[Convert]::FromBase64String([string]$request.pcm)
   if($bytes.Length -lt 3200 -or $bytes.Length -gt 256000 -or ($bytes.Length%2) -ne 0){throw 'audio limit'}
   $memory=[IO.MemoryStream]::new($bytes,$false)
   $engine.SetInputToAudioStream($memory,$format)
   $phrases=[Collections.Generic.List[string]]::new()
   for($i=0;$i -lt 32 -and (OwnerAlive);$i++){
    try {$found=$engine.Recognize([TimeSpan]::FromSeconds(8))} catch {if($phrases.Count -gt 0){break}else{throw}}
    if($null -eq $found){break}
    if($found.Confidence -ge 0.15 -and -not [string]::IsNullOrWhiteSpace($found.Text)){$phrases.Add($found.Text)}
   }
   $text=($phrases -join ' ').Trim();if($text.Length -gt 2048){$text=$text.Substring(0,2048)}
   Emit @{sequence=$sequence;ok=($text.Length -gt 0);text=$text;error=$(if($text.Length -gt 0){''}else{'empty'})}
  } catch {Emit @{sequence=$sequence;ok=$false;error='recognizer'}}
  finally {try {$engine.SetInputToNull()}catch{};if($null -ne $memory){$memory.Dispose()}}
 }
} catch {Emit @{ready=$false;error='recognizer'}}
finally {if($null -ne $engine){$engine.Dispose()}}
