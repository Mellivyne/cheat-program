"""Persistent, local-only ASR for selected application PCM. Never uses a microphone or network."""
import argparse
import base64
import json
import os
import sys

parser = argparse.ArgumentParser()
for name in ("model", "language", "device", "owner"):
    parser.add_argument("--" + name, required=True)
args = parser.parse_args()
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)


def emit(**data):
    print(json.dumps(data, ensure_ascii=False), flush=True)


try:
    from faster_whisper import WhisperModel
    import numpy as np
except ImportError:
    emit(ready=False, error="dependency")
    raise SystemExit(0)
if not os.path.isdir(args.model):
    emit(ready=False, error="model")
    raise SystemExit(0)
try:
    model = WhisperModel(args.model, device=args.device,
                         compute_type="float16" if args.device == "cuda" else "int8",
                         local_files_only=True)
except Exception:
    emit(ready=False, error="recognizer")
    raise SystemExit(0)
emit(ready=True, engine="whisper")
while True:
    line = sys.stdin.buffer.readline(350001)
    if not line or line.strip() == b"Q":
        break
    sequence = 0
    try:
        if len(line) > 350000 or not line.endswith(b"\n"):
            raise ValueError("request limit")
        request = json.loads(line)
        sequence = int(request["sequence"])
        pcm = base64.b64decode(request["pcm"], validate=True)
        if not 3200 <= len(pcm) <= 256000 or len(pcm) % 2:
            raise ValueError("audio limit")
        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        language = None if args.language == "auto" else args.language
        segments, _ = model.transcribe(audio, language=language, beam_size=3,
                                       vad_filter=True, condition_on_previous_text=False,
                                       no_speech_threshold=0.65)
        text = " ".join(segment.text.strip() for segment in segments).strip()[:2048]
        emit(sequence=sequence, ok=bool(text), text=text, error="" if text else "empty")
    except Exception:
        emit(sequence=sequence, ok=False, error="recognizer")
