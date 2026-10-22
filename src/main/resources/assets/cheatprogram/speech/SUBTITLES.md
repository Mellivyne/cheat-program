# Local application dialogue subtitles

`SpeechSubtitleService` observes only the process explicitly selected for a projected application. Its independent `AppAudioCapture` instance requests PCM with `muteOriginal=false`; it never opens a microphone, changes application audio routing, plays sound, or uploads a recording. It does not change AI voice input settings.

PCM48k stereo is downmixed and resampled to PCM16k mono in memory. Segments are bounded to 2–8 seconds with 250ms overlap. A quiet segment is discarded. While recognition is busy, one newest segment can replace the pending segment; no old transcription backlog grows. Both workers persist between segments so a recognizer/model is loaded once per session. Audio is sent over the local child process's stdin and is not written to WAV files.

The Windows worker uses an installed `System.Speech` dictation recognizer matching the selected language. It reports a missing language explicitly. The Whisper worker uses the existing local voice-input Python/model/device preferences, disables model network access, and requires an already present local model and dependencies. It does not install or download anything.

Source/configuration changes and stop cancel capture, kill the owned recognizer process and clear text. Initialization checks the persistence guard before extracting helper resources or starting a worker. Transient failures retry at most twice; missing language/dependency/model is permanent until reconfiguration/reload. Successful text alone changes the caption timestamp and sequence; listening/level/status updates do not extend caption lifetime.

Recognition is approximate and delayed by the selected segment length plus local processing time. Windows dictation quality, supported languages and CPU/GPU model speed vary. This is speech recognition, not access to a player's unreceived chat or a site's hidden subtitle feed.
