"""Offline, explicit environment check. Does not synthesize, record, or load the speech model."""
import argparse
import contextlib
import ctypes
import importlib
import importlib.metadata
import json
import os
from pathlib import Path
import socket
import sys

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"


def no_network(*args, **kwargs):
    raise RuntimeError("Environment checks are offline")


socket.create_connection = no_network
socket.socket.connect = no_network
socket.socket.connect_ex = no_network


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--model", default="")
    parser.add_argument("--voices", default="")
    parser.add_argument("--vocab", default="")
    parser.add_argument("--report", required=True)
    parser.add_argument("--manifest", default="")
    args = parser.parse_args()
    checks = []
    modules = {}

    def record(name, function):
        try:
            detail = function()
            checks.append({"id": name, "ok": True, "detail": str(detail or "")[:400]})
            return True
        except Exception as error:
            checks.append({"id": name, "ok": False, "detail": (type(error).__name__ + ": " + str(error))[:400]})
            return False

    def python_version():
        if not (3, 10) <= sys.version_info[:2] < (3, 14) or sys.maxsize <= 2**32:
            raise RuntimeError("64-bit Python 3.10-3.13 is required")
        return sys.version.split()[0]

    record("python", python_version)
    for name in ("numpy", "onnxruntime", "kokoro_onnx", "librosa", "misaki.zh", "phonemizer"):
        def load_module(name=name):
            module = importlib.import_module(name)
            modules[name] = module
            return getattr(module, "__version__", "")
        record(name.replace(".", "_"), load_module)

    def speech_library():
        loader = importlib.import_module("espeakng_loader")
        library, data = Path(loader.get_library_path()), Path(loader.get_data_path())
        if not library.is_file() or not data.is_dir():
            raise RuntimeError("Missing local eSpeak library or dictionary")
        ctypes.CDLL(str(library))
        return "Local eSpeak library and dictionary"

    record("espeak", speech_library)

    def chinese_dictionary():
        zh = modules.get("misaki.zh")
        if zh is None:
            raise RuntimeError("Chinese G2P import failed")
        # Initialize packaged dictionaries only, without passing any speech text.
        zh.ZHG2P(version="1.1")
        return "Local Chinese frontend"

    record("chinese_data", chinese_dictionary)

    def backend():
        ort = modules.get("onnxruntime")
        if ort is None:
            raise RuntimeError("ONNX Runtime import failed")
        ort.disable_telemetry_events()
        providers = ort.get_available_providers()
        if args.device == "cpu":
            if "CPUExecutionProvider" not in providers:
                raise RuntimeError("CPU provider is unavailable")
            return "CPUExecutionProvider"
        if "CUDAExecutionProvider" not in providers:
            raise RuntimeError("CUDAExecutionProvider is unavailable")
        # Verify DLL loading and driver/device availability without running inference.
        if hasattr(ort, "preload_dlls"):
            ort.preload_dlls(directory="")
        if os.name != "nt":
            raise RuntimeError("This automatic CUDA environment supports Windows")
        cuda = ctypes.WinDLL("nvcuda.dll")
        cuda.cuInit.argtypes, cuda.cuInit.restype = (ctypes.c_uint,), ctypes.c_int
        cuda.cuDeviceGetCount.argtypes, cuda.cuDeviceGetCount.restype = (ctypes.POINTER(ctypes.c_int),), ctypes.c_int
        count = ctypes.c_int()
        if cuda.cuInit(0) != 0 or cuda.cuDeviceGetCount(ctypes.byref(count)) != 0 or count.value < 1:
            raise RuntimeError("NVIDIA driver or CUDA device is unavailable")
        provider = Path(ort.__file__).parent / "capi" / "onnxruntime_providers_cuda.dll"
        if not provider.is_file():
            raise RuntimeError("CUDA provider DLL is missing")
        ctypes.WinDLL(str(provider))
        return "CUDAExecutionProvider; " + str(count.value) + " device(s)"

    record("device", backend)

    def model_file():
        model = Path(args.model) if args.model else None
        if model is None or not model.is_file() or model.stat().st_size < 1024 * 1024:
            raise RuntimeError("Select a valid local Kokoro ONNX model or run one-click setup")
        # Check the top-level ONNX protobuf framing by seeking through tensor data;
        # this catches HTML downloads and truncated models without loading or running a model.
        size = model.stat().st_size
        with model.open("rb") as stream:
            def varint():
                value = 0
                for shift in range(0, 70, 7):
                    data = stream.read(1)
                    if not data:
                        raise ValueError("Truncated ONNX framing")
                    value |= (data[0] & 127) << shift
                    if data[0] < 128:
                        return value
                raise ValueError("Invalid ONNX varint")
            graph, ir_version = False, 0
            while stream.tell() < size:
                tag = varint()
                field, wire = tag >> 3, tag & 7
                if field == 0:
                    raise ValueError("Invalid ONNX field")
                if wire == 0:
                    value = varint()
                    if field == 1:
                        ir_version = value
                elif wire in (1, 2, 5):
                    length = varint() if wire == 2 else (8 if wire == 1 else 4)
                    if field == 7 and wire == 2:
                        graph = length > 1024
                    if length < 0 or stream.tell() + length > size:
                        raise ValueError("Truncated ONNX field")
                    stream.seek(length, 1)
                else:
                    raise ValueError("Unsupported ONNX framing")
            if not graph or not 3 <= ir_version <= 100:
                raise ValueError("Not a framed ONNX graph")
        if args.vocab:
            content = json.loads(Path(args.vocab).read_text(encoding="utf-8-sig"))
            if not isinstance(content.get("vocab"), dict):
                raise RuntimeError("The vocabulary JSON has no vocab dictionary")
        return str(model.resolve())

    record("model", model_file)
    # Pack presence is reported separately from prerequisites, as requested.
    try:
        pack_present = bool(args.voices and Path(args.voices).is_file())
    except (ValueError, OSError):
        pack_present = False
    pack_detail = ""
    if pack_present:
        try:
            numpy = modules.get("numpy")
            if numpy is not None:
                with numpy.load(args.voices, allow_pickle=False) as pack:
                    if not hasattr(pack, "files") or not pack.files:
                        raise RuntimeError("Voice pack contains no voices")
                    pack_detail = str(len(pack.files)) + " voice(s)"
        except Exception as error:
            pack_present = False
            pack_detail = (type(error).__name__ + ": " + str(error))[:400]
    result = {
        "schema": 2, "environmentReady": all(item["ok"] for item in checks),
        "voicePackPresent": pack_present, "voicePackDetail": pack_detail,
        "checks": checks, "python": str(Path(sys.executable).resolve()),
        "pythonVersion": sys.version.split()[0], "device": args.device,
        "model": args.model, "providers": modules["onnxruntime"].get_available_providers() if "onnxruntime" in modules else [],
    }
    report = Path(args.report)
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.manifest and result["environmentReady"]:
        result["dependenciesReady"] = True
        result["packages"] = {}
        for name in ("numpy", "kokoro-onnx", "onnxruntime-gpu" if args.device == "cuda" else "onnxruntime", "librosa", "misaki-fork"):
            try:
                result["packages"][name] = importlib.metadata.version(name)
            except importlib.metadata.PackageNotFoundError:
                pass
        manifest = Path(args.manifest)
        temporary = manifest.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(manifest)
    return 0 if result["environmentReady"] else 1


if __name__ == "__main__":
    with contextlib.redirect_stdout(sys.stderr):
        sys.exit(main())
