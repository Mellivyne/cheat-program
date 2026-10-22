# AI 玩家离线语音

这是基于开源 [kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx) 的本地适配后端。Java 负责角色队列、取消和播放；Python 在独立进程中执行 ONNX 推理。模组没有打包或预装 Python、模型权重、音色包、CUDA 库，也不会在朗读时下载依赖。尚未配置时应显示“未准备”，不能视为已经可以朗读。

## 准备

先在 AI 玩家语音设置中点“准备文件”或打开语音目录。`install-voice.ps1`、worker 和说明会释放到当前游戏实例的 `config/cheatprogram/voice`。

在“本地引擎”页点击“**一键配置本地语音环境**”。它会自动准备专用 Python、NumPy、Kokoro、中文读音前端、音高处理与所选 CPU/GPU 依赖，并下载基础 ONNX 模型。若没有可用的 Python，它从 Python 官方下载 64 位 Python 3.12.10 可嵌入包，放在这个语音目录中；不安装系统 Python、不改注册表或 PATH、不改动全局 Python 包。默认不下载音色包。

完成后把与模型匹配的 `.bin` / `.npz` 音色包直接放入 `config/cheatprogram/voice/voicepacks`。未选择有效音色包时会自动发现该文件夹中的包；每个角色仍可用自己的包覆盖。CPU/GPU 选择保留；GPU 配置同时下载 CUDA/cuDNN 运行库，但显卡驱动仍由系统提供。若想用官方中文包，可点“另下载官方中文音色包”。

“**检测环境配置**”在后台使用实际所选解释器检查版本、原生依赖、中文前端资源、CPU/CUDA 提供者、GPU 驱动及 DLL、模型文件结构与可选词表。检查不会执行模型推理或合成。环境全部符合要求时，即使尚未放入音色包也显示“通过”；音色包另行提示。模型与音色包之间的声学匹配仍需用户实际试听确认。

环境位置始终显示在界面：`.venv/Scripts/python.exe` 为专用解释器，`downloads/` 为下载及 pip 缓存，`models/` 为基础模型及许可，`voicepacks/` 为用户音色包，`setup.log` 为配置日志，`environment-packages.txt` 为实际安装的包版本清单。配置可取消，单次操作最长 45 分钟；中断后再次点击可重新准备。所有自动下载文件都位于本客户端自己的语音目录中，外部手动选择的模型与字体等原文件不被改动。

也可检查脚本后在语音目录中使用 PowerShell 显式运行：

```powershell
# CPU 环境及基础模型；只需另放入音色包
.\install-voice.ps1 -Device cpu -DownloadBaseModel

# 另外明确选择下载官方中文模型与音色包
.\install-voice.ps1 -Device cpu -DownloadModels

# NVIDIA GPU；已安装匹配 CUDA/cuDNN 时使用
.\install-voice.ps1 -Device cuda -DownloadModels

# 同时安装 CUDA/cuDNN 运行库，下载和磁盘需求会更大
.\install-voice.ps1 -Device cuda -CudaRuntimeDlls -DownloadModels

# 可显式指定 Python 或选择低精度模型
.\install-voice.ps1 -Python 'C:\Python312\python.exe' -Device cpu -DownloadModels -ModelVariant int8
```

脚本完成后自动选择 `.venv/Scripts/python.exe` 与下载的基础模型。放入配套 `voices.bin`，然后“读取音色包”获取真实音色列表。中文 v1.1 的示例音色为 `zf_001`、`zm_010`；界面列表以实际导入的包为准，模型与音色包必须属于同一版本。手动命令行可选 `-Python` 基于兼容的既有 Python 创建专用环境；不支持的 Python 3.14 会自动改用专用可嵌入版本。

当前官方 [model-files-v1.1](https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.1) 提供中文/英文模型及配套音色。新版导出已把词表放入 graph metadata；`config.json` 因此可留空，也支持用户显式选择原版词表。旧版整数 speed 输入存在小数语速问题，worker 会明确拒绝；应重新下载该 release 的修正版。不同精度会改变速度和音质，CPU 默认优先完整精度以避免 GPU 特定算子问题。

## 设备与离线行为

CPU 使用 `CPUExecutionProvider`；GPU 使用 NVIDIA `CUDAExecutionProvider`，并核实 session 已实际启用 CUDA。GPU 不可用会报错，不把 CPU 回退展示成 GPU。ONNX Runtime 仍可把少量不适合 GPU 的辅助算子分配给 CPU。脚本固定使用 CUDA 12.8/cuDNN 9 对应的 ORT 1.21–1.26 范围；显卡驱动与 DLL 不匹配时需按 [官方 CUDA 文档](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html) 修复。CPU 模式也始终可独立选择。

worker 禁止网络连接，HF/Transformers 设为 offline。中文分词/读音走 [官方 Chinese 示例](https://github.com/thewh1teagle/kokoro-onnx/blob/main/examples/chinese.py) 使用的本地 Misaki G2P，缺依赖时报告缺少项。语音文本不会发给任何外部 TTS 接口。配置中的 localEndpoint 目前只是保留字段，不会发请求；当前可用引擎是 Kokoro ONNX。

## 角色与情绪

每个角色可独立选择模型、音色包、音色 ID、语言和 CPU/GPU，空模型/包路径继承全局设置。语速 0.5–2，音高以半音计，音量为本地播放倍率。音高由本地 librosa 处理，保留语速所决定的时长；语音按实际完成顺序排队，避免多个角色的声音互相覆盖。

Kokoro 没有本适配器可调用的原生情绪训练开关。情绪联动是透明的音色映射及语速、音高、音量预设，强度可调，支持 neutral、happy、sad、angry、excited、fearful、calm。可以给各情绪指定当前音色包中的另一个音色 ID。还可在音色 ID 中输入 `zf_001:0.7+zm_010:0.3` 混合兼容音色；这不等同于上传任意录音进行音色克隆。

聊天文本分成短句逐段合成和播放，没有固定的整段对话字符上限；每段有独立的挂起超时保护。关闭角色语音、收回角色、离开世界会取消队列、停止播放并停止正在执行的本地 worker。合成 WAV 放在专用 cache 中，播放后删除，不保存聊天音频档案。

## 官方来源和许可

参见 [NOTICE.md](NOTICE.md)。本适配器未复制 kokoro-onnx 推理实现或再分发权重；运行时依赖其公开 API。安装脚本会保留环境包版本清单，下载模型时同时保存模型卡和 MIT/Apache 许可文本。若后续分发整个 Python 环境或模型包，应保留各依赖原有许可与说明。
