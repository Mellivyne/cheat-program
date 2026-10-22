"""Local JSON-lines Kokoro runner. The adapter is independent glue, not vendored engine code.

See README.md and NOTICE.md beside this file. No model or dependency downloads occur here.
All stdout is reserved for the protocol; inference and dependency diagnostics go to stderr.
"""
import argparse
import contextlib
import gc
import importlib.util
import json
import math
import os
from pathlib import Path
import socket
import sys
import wave

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
os.environ.setdefault("HF_DATASETS_OFFLINE", "1")


def no_network(*args, **kwargs):
    raise RuntimeError("离线语音禁止网络访问。请显式运行准备脚本安装本地依赖及模型。")


# G2P dependencies must use their packaged local data, never fetch text/models at inference time.
socket.create_connection = no_network
socket.socket.connect = no_network
socket.socket.connect_ex = no_network

_engine = None
_engine_key = None
_g2p = None


def finite(value, default, low, high):
    try:
        value = float(value)
        return min(high, max(low, value)) if math.isfinite(value) else default
    except (ValueError, TypeError):
        return default


def required_file(value, description):
    if not value or not Path(value).is_file():
        raise ValueError("请先选择有效的本地" + description)
    return str(Path(value).resolve())


def options(request, *, require_model=False):
    profile, settings = request.get("profile", {}), request.get("settings", {})
    model = profile.get("modelPath") or settings.get("modelPath", "")
    # Check user-selected files before importing numerical/native packages. Missing models are not
    # a numpy installation error, and merely reading a voice pack does not need an ONNX model.
    if require_model:
        model = required_file(model, " ONNX 模型；也可在本地引擎页点“安装并下载中文模型／音色”")
    voices = required_file(profile.get("voicePackPath") or settings.get("voicesPath", ""), " voices.bin 音色包")
    config = profile.get("vocabConfigPath") or settings.get("vocabConfigPath", "")
    if not config and model:
        candidate = Path(model).parent / "config.json"
        if candidate.is_file():
            config = str(candidate)
    if config:
        config = required_file(config, " config.json 词表")
    device = profile.get("device", "inherit")
    if device == "inherit":
        device = settings.get("device", "cpu")
    if device not in ("cpu", "cuda"):
        raise ValueError("设备必须是 cpu 或 cuda，不能以 CPU 冒充 GPU")
    threads = int(finite(settings.get("threads"), 4, 1, 32))
    return profile, settings, model, voices, config, device, threads


def engine_for(model, voices, config, device, threads):
    global _engine, _engine_key
    model = required_file(model, " ONNX 模型")
    key = (model, Path(model).stat().st_mtime_ns, voices, Path(voices).stat().st_mtime_ns,
           config, Path(config).stat().st_mtime_ns if config else 0, device, threads)
    if key == _engine_key and _engine is not None:
        return _engine
    try:
        import onnxruntime as ort
        from kokoro_onnx import Kokoro
    except ImportError as error:
        raise RuntimeError("缺少本地 Kokoro/ONNX 依赖。请运行 install-voice.ps1；" + str(error)) from error
    ort.disable_telemetry_events()
    if device == "cuda":
        if hasattr(ort, "preload_dlls"):
            ort.preload_dlls()
        if "CUDAExecutionProvider" not in ort.get_available_providers():
            raise RuntimeError("请求 NVIDIA GPU，但当前 ONNX Runtime 无 CUDAExecutionProvider。请用脚本 -Device cuda 准备 GPU 依赖。当前: " + str(ort.get_available_providers()))
    # Only keep the active model in memory. Switching a voice within one pack reuses its session.
    _engine = None
    _engine_key = None
    gc.collect()
    session_options = ort.SessionOptions()
    session_options.intra_op_num_threads = threads
    session_options.inter_op_num_threads = 1
    providers = [("CUDAExecutionProvider", {"device_id": 0, "cudnn_conv_algo_search": "HEURISTIC", "cudnn_conv_use_max_workspace": "0"})] if device == "cuda" else ["CPUExecutionProvider"]
    session = ort.InferenceSession(model, sess_options=session_options, providers=providers)
    if device == "cuda" and "CUDAExecutionProvider" not in session.get_providers():
        raise RuntimeError("CUDA 初始化失败且运行时已回退 CPU；为避免假 GPU，本次不合成。请检查 CUDA/cuDNN DLL 和驱动版本。")
    if hasattr(session, "disable_fallback"):
        session.disable_fallback()
    speed_input = next((entry for entry in session.get_inputs() if entry.name == "speed"), None)
    if speed_input is not None and speed_input.type not in ("tensor(float)", "tensor(float16)", "tensor(double)"):
        raise RuntimeError("此旧版模型把 speed 导出为整数，无法准确调语速；请从官方 model-files-v1.1 release 重新选择修正版浮点速度模型。")
    _engine = Kokoro.from_session(session, voices, vocab_config=config or None)
    _engine_key = key
    return _engine


def voice_style(engine, expression):
    """Existing voice ID, or an optional blend such as zf_001:0.7+zm_010:0.3."""
    import numpy as np
    terms = expression.split("+")
    if len(terms) == 1 and ":" not in expression:
        if expression not in engine.get_voices():
            raise ValueError("该音色包不包含音色 " + expression + "；请重新读取音色包后选择")
        return expression
    if len(terms) > 8:
        raise ValueError("一次混音最多组合 8 个音色")
    total, mixed = 0.0, None
    for term in terms:
        name, _, weight = term.strip().partition(":")
        weight = finite(weight or 1, 1, 0, 10)
        if name not in engine.get_voices():
            raise ValueError("混合音色不在当前音色包: " + name)
        values = np.asarray(engine.get_voice_style(name), dtype=np.float32)
        mixed = values * weight if mixed is None else mixed + values * weight
        total += weight
    if total <= 0:
        raise ValueError("混合音色的总权重需要大于 0")
    return mixed / total


def synthesize(request, output_root):
    global _g2p
    profile, settings, model, voices, config, device, threads = options(request, require_model=True)
    try:
        import numpy as np
    except ImportError as error:
        raise RuntimeError("所选 Python 缺少 numpy。请在本地引擎页点“安装本地语音环境”，并使用该语音目录的 .venv/Scripts/python.exe。" + str(error)) from error
    engine = engine_for(model, voices, config, device, threads)
    voice = profile.get("voiceID", "zf_001")
    text = str(request.get("text", "")).strip()
    if not text:
        raise ValueError("没有可朗读文本")
    strength = finite(profile.get("strength"), 1, 0, 2) * finite(request.get("intensity"), 0, 0, 1) if profile.get("emotionEnabled", True) else 0
    emotion = request.get("emotion", "neutral") if strength else "neutral"
    mapped = profile.get("emotionVoices", {}).get(emotion, "")
    if mapped:
        voice = mapped
    # Kokoro has no native trained emotion switch: transparent, adjustable prosody presets only.
    presets = {"happy": (1.05, .6, 1.02), "sad": (.88, -.8, .92), "angry": (1.07, -.3, 1.08),
               "excited": (1.12, 1.0, 1.06), "fearful": (1.08, .7, .94), "calm": (.91, -.2, .94)}
    pace, tone, gain = presets.get(emotion, (1, 0, 1))
    speed = finite(profile.get("speed"), 1, .5, 2)
    speed = max(.5, min(2, speed * (1 + (pace - 1) * strength)))
    pitch = finite(profile.get("pitch"), 0, -12, 12) + tone * strength
    # The Java client sends the selected/device language; omitted protocol values use English.
    language = str(profile.get("language", "en-us")).lower()
    if language.startswith("zh") or language in ("z", "cmn"):
        try:
            if _g2p is None:
                from misaki import zh
                _g2p = zh.ZHG2P(version="1.1")
            phonemes, _ = _g2p(text)
        except ImportError as error:
            raise RuntimeError("中文需要本地 misaki-fork[zh] 数据依赖，请运行准备脚本。" + str(error)) from error
        samples, sample_rate = engine.create(phonemes, voice=voice_style(engine, voice), speed=speed, is_phonemes=True)
    else:
        samples, sample_rate = engine.create(text, voice=voice_style(engine, voice), speed=speed, lang=language)
    samples = np.asarray(samples, dtype=np.float32).reshape(-1)
    if abs(pitch) > .005:
        try:
            import librosa
            samples = librosa.effects.pitch_shift(samples, sr=sample_rate, n_steps=pitch)
        except ImportError as error:
            raise RuntimeError("音高处理需要本地 librosa，请运行准备脚本。" + str(error)) from error
    samples = np.nan_to_num(samples) * max(0, 1 + (gain - 1) * strength)
    fade = min(len(samples) // 2, int(sample_rate * .005))
    if fade:
        samples[:fade] *= np.linspace(0, 1, fade)
        samples[-fade:] *= np.linspace(1, 0, fade)
    output = Path(str(request.get("output", ""))).resolve()
    if output.parent != output_root or output.suffix.lower() != ".wav":
        raise ValueError("语音输出必须位于专用本地缓存目录")
    output_root.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
    with wave.open(str(output), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(int(sample_rate))
        wav.writeframes(pcm.tobytes())
    return {"ok": True, "provider": "CUDAExecutionProvider" if device == "cuda" else "CPUExecutionProvider"}


def dispatch(request, output_root):
    if request.get("command") == "probe":
        if not (3, 10) <= sys.version_info[:2] < (3, 14):
            raise RuntimeError("所选 Python 为 " + sys.version.split()[0] + "；本地语音需要 Python 3.10–3.13（推荐 3.12）。请点“安装本地语音环境”准备兼容的专用环境，或重新选择 Python。")
        if importlib.util.find_spec("numpy") is None:
            raise RuntimeError("所选 Python 未安装 numpy，尚未准备本地语音。请到本地引擎页点“安装本地语音环境”或“安装并下载中文模型／音色”；不要选择未安装语音依赖的全局 Python。")
        return {"ok": True, "python": sys.executable, "version": sys.version.split()[0]}
    if request.get("command") == "voices":
        _, _, _, voices, _, _, _ = options(request)
        try:
            import numpy as np
        except ImportError as error:
            raise RuntimeError("读取音色包需要 numpy。请在本地引擎页点“安装本地语音环境”，并使用专用 .venv。" + str(error)) from error
        with np.load(voices, allow_pickle=False) as pack:
            return {"ok": True, "voices": sorted(pack.files)}
    if request.get("command") == "speak":
        return synthesize(request, output_root)
    raise ValueError("未知本地语音命令")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-root", required=True)
    args = parser.parse_args()
    root = Path(args.output_root).resolve()
    for line in sys.stdin:
        try:
            with contextlib.redirect_stdout(sys.stderr):
                response = dispatch(json.loads(line), root)
        except Exception as error:
            response = {"ok": False, "error": type(error).__name__ + ": " + str(error)}
        sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
