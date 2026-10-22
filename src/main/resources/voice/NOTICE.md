# 本地语音第三方来源

本模组的 Java 队列/取消/播放和 Python JSON-lines worker 为本项目新增的适配层。模组 JAR 不包含下列项目的源代码、二进制依赖或模型权重；准备脚本只在玩家明确运行时安装或下载。

| 项目 | 作者/上游 | 上游声明许可 | 用途 |
|---|---|---|---|
| kokoro-onnx | thewh1teagle；Copyright (c) 2025 github.com/thewh1teagle | MIT | 本机 ONNX 推理公开 API，支持二次集成 |
| Kokoro-82M / Kokoro-82M-v1.1-zh | hexgrad；模型卡鸣谢架构作者 yl4579、训练者 rzvzn、中文数据 LongMaoData | Apache-2.0（以模型卡声明为准） | 玩家自行选择的语音模型和配套声音向量 |
| ONNX Runtime | Microsoft 与项目贡献者 | MIT（以安装包许可证为准） | CPU / NVIDIA CUDA 执行提供者 |

主要来源（核对日期：2026-10-14，客户端日期）：

- kokoro-onnx 项目及 API：https://github.com/thewh1teagle/kokoro-onnx
- kokoro-onnx MIT 原文：https://github.com/thewh1teagle/kokoro-onnx/blob/main/LICENSE
- 官方模型导出/速度输入修复/音色包：https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.1
- 官方自定义 ONNX session 示例：https://github.com/thewh1teagle/kokoro-onnx/blob/main/examples/with_session.py
- 官方中文 G2P 示例：https://github.com/thewh1teagle/kokoro-onnx/blob/main/examples/chinese.py
- 中文模型卡/Apache 声明：https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh
- 模型及音色原始目录：https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh/tree/main
- CUDA 设备/依赖/预加载官方文档：https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html
- ONNX Runtime 原始许可：https://github.com/microsoft/onnxruntime/blob/main/LICENSE

NumPy、Misaki/Misaki-fork、librosa、phonemizer、espeakng-loader 及其依赖由 pip 单独安装，许可证和可能携带的外部语音库说明保存在各自安装包中。它们不被嵌入本模组 JAR；若玩家选择再分发专用环境，应随环境保留完整许可，而不能只复制这个 NOTICE。任意自定义音色包也须以其来源单独附带的许可为准。

2026-10-16：一键配置可从 [Python 官方 3.12.10 发布页](https://www.python.org/downloads/release/python-31210/) 下载 Windows 64 位可嵌入包，按发布页摘要检查归档完整性；包内 Python 原许可随解压保留。pip 由 [PyPA 官方 get-pip](https://pip.pypa.io/en/stable/installation/) 准备，依赖从 PyPI 安装到专用环境。安装器与环境检测器为独立适配代码，JAR 不再分发这些第三方运行时二进制。参考 [Python 可嵌入包文档](https://docs.python.org/3.12/using/windows.html#the-embeddable-package)；可嵌入包由本适配器管理依赖而非系统级安装。
