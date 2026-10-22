"""Local faster-whisper adapter. No model or audio upload/download is performed."""
import argparse
import ctypes
import json
import os
import threading
import time
import wave

parser = argparse.ArgumentParser()
for name in ("wave", "model", "language", "device", "owner", "timeout"):
    parser.add_argument("--" + name, required=True)
args = parser.parse_args()
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"

def watchdog():
    expires = time.monotonic() + int(args.timeout) + 10
    while time.monotonic() < expires:
        if os.name == "nt":
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.OpenProcess.restype = ctypes.c_void_p
            kernel.OpenProcess.argtypes = (ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong)
            kernel.CloseHandle.argtypes = (ctypes.c_void_p,)
            kernel.WaitForSingleObject.argtypes = (ctypes.c_void_p, ctypes.c_ulong)
            kernel.WaitForSingleObject.restype = ctypes.c_ulong
            handle = kernel.OpenProcess(0x100000, False, int(args.owner))
            if not handle:
                try: os.unlink(args.wave)
                except OSError: pass
                os._exit(0)
            alive = kernel.WaitForSingleObject(handle, 0) == 0x102
            kernel.CloseHandle(handle)
            if not alive:
                try: os.unlink(args.wave)
                except OSError: pass
                os._exit(0)
        else:
            try:
                os.kill(int(args.owner), 0)
            except OSError:
                os._exit(0)
        time.sleep(1)
    try: os.unlink(args.wave)
    except OSError: pass
    os._exit(0)

threading.Thread(target=watchdog, daemon=True).start()

def result(**data):
    print(json.dumps(data, ensure_ascii=False), flush=True)

try:
    from faster_whisper import WhisperModel
except ImportError:
    result(ok=False, error="dependency")
    raise SystemExit(0)
if not os.path.isdir(args.model):
    result(ok=False, error="model")
    raise SystemExit(0)
try:
    import numpy as np
    with wave.open(args.wave, "rb") as recording:
        audio = np.frombuffer(recording.readframes(recording.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
    os.unlink(args.wave)
    model = WhisperModel(args.model, device=args.device,
                         compute_type="float16" if args.device == "cuda" else "int8",
                         local_files_only=True)
    segments, _ = model.transcribe(audio, language=args.language,
                                   beam_size=5, vad_filter=True)
    text = " ".join(segment.text.strip() for segment in segments).strip()
    result(ok=bool(text), text=text, error="" if text else "empty")
except Exception:
    result(ok=False, error="recognizer")
