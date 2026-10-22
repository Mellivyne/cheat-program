# Cheat Program Open v1.0

独立 Fabric 开源版。只提供模组 JAR，没有注入器，也没有额外启动器。

## 功能范围

左侧只有收藏夹和功能；顶部保留功能与配置，移除好友、AI 控制及 AI 玩家入口。

- 原视觉分类的全部 31 项功能。
- 建筑导入、机器人、训练机器人、重复发言、发言追踪。
- 自动穿装备、背包整理、自动拿取。
- 按键显示、信息面板、后视镜、网络与性能趋势、时间修改、缩放、声音方向提示。
- 死亡点与回收路线。

共 47 项注册功能。共享引擎、类型和兼容代码仍保留在源码中；其余模块没有注册，不会出现在功能、搜索或配置导入中。

## 界面调整

右上设置抽屉中的界面按键可以重新绑定，默认右 Shift。普通面板开关和 3D 面板双击退出使用相同按键，提示文字同步显示实际按键。

模型穿戴预览始终显示当前玩家的皮肤，模型和玩家在同一个三维空间旋转、缩放与遮挡；进入世界后使用。未导入模型也可预览玩家。

灵动岛使用清晰背景采样、低白色遮罩和边缘折射，保留原有内容与动画。

## 安装

从 `outputs/fabric/<Minecraft版本>/` 选择对应 JAR，放入该 Fabric 实例的 mods 目录。不要跨版本使用。

本版和完整版使用相同 Fabric mod id，不能同时安装。安装本版前移出完整版 JAR；请保留备份。项目不会自动替换现有游戏安装。

开源版使用独立的 `config/cheatprogram-open/`。不会自动复制完整版音乐登录、API 密钥、配置或存档。首次使用需自己登录平台；凭据保持本地存储，不包含在源码发布包。

## 构建

Windows：准备 Python 3 与兼容的 Gradle 运行 JDK，执行：

```powershell
python tools/build_fabric.py 1.21.4
python tools/package_fabric.py
python tools/package_source.py
```

不传版本时构建矩阵中的全部非 pending 版本。当前包含 1.21.4、1.21.10、1.21.11、26.1、26.1.1、26.1.2、26.2。26.3 尚未完成，不能发布为支持版本。

每个版本单独一个 `cheat-program-open-v1.0-fabric-mc<版本>.jar`。Java 工具链要求见 `compatibility/matrix.json`。

Windows 原生采集及音频助手的 C#、PowerShell 源码在 `src/main/resources/assets/cheatprogram/`。对应 `*-*.ps1 -CompileOnly` 脚本可重建助手；这些可执行文件用于投屏及音频路由，不是注入器。

可选的 1.21.4 本地预览验证：

```powershell
./gradlew.bat runClient -Popen_checks "-Dcheatprogram.dev=open-check"
```

该检查只在本项目 run 目录创建测试世界并保存截图，不读取现有游戏存档。验证源不进入发布包。编译通过不代表所有版本的游戏效果均已实测。

## 许可证

本项目原创代码采用 MIT，见 LICENSE。第三方组件保持原许可证，见 THIRD_PARTY_NOTICES.md。Minecraft、Fabric 及各音乐平台商标归各自权利人所有。

源码发布包为 `outputs/cheat-program-open-v1.0-source.zip`，按白名单生成，不包含构建缓存、运行日志、测试存档和本地配置。`verification/open-edition-checks.json` 记录验证范围。
