# NyaDeskPet Mobile

<div align="center">
  <img src="logo.png" alt="logo" width="400"/>
  <p>基于 Live2D + AI Agent 的移动端桌宠应用</p>
  <p>
    <a href="https://github.com/gameswu/NyaDeskPet">原项目 (桌面端)</a> 的 Android / iOS 移植版本
  </p>
</div>

---

本项目是 [NyaDeskPet](https://github.com/gameswu/NyaDeskPet) 的移动端移植，使用 Kotlin Multiplatform + Compose Multiplatform 重写，目标平台为 Android 和 iOS。核心功能与原项目保持一致，针对移动端进行了适配。

## 特性

- 🎭 **Live2D 模型渲染** — 原生 OpenGL ES 渲染，支持动作、表情、物理演算与 Pose 系统
- 🤖 **内置 AI Agent** — 支持多种 LLM 供应商，内置 Agent Pipeline 架构
- 🧩 **插件体系** — 与原项目对齐的插件架构，支持表情/动作/命令等能力

## 与原项目功能对比

| | 原项目 (桌面端) | 本项目 (移动端) |
|---|---|---|
| 前端插件 | 完整支持 | 不支持 |
| 内置 Agent 插件 | 完整支持 | 仅支持内置插件 |
| MCP 协议 | 完整支持 | 仅支持 SSE |
| 桌宠 | 完整支持 | 仅 Android 悬浮窗，iOS 受系统限制不支持 |
| 帮助文档 | 完整支持 | 不支持 |

API 协议、插件接口、数据格式等与原项目保持对齐。

## 项目结构

```
├── composeApp/          # 共享代码（Compose Multiplatform）
│   └── src/
│       ├── commonMain/  # 跨平台通用代码（UI、Agent、插件）
│       ├── androidMain/ # Android 平台实现 + Live2D Native (C++)
│       └── iosMain/     # iOS 平台实现
├── androidApp/          # Android 应用入口
├── iosApp/              # iOS 应用入口
└── gradle/              # Gradle 配置
```

## 构建

### 环境要求

- JDK 17+
- Android Studio (Arctic Fox+) 或 IntelliJ IDEA
- Android SDK 24+
- Xcode 15+（iOS 构建）

推荐直接使用 Android Studio 打开整个项目，Gradle 会自动配置多平台构建环境。

iOS 端的 Live2D 渲染通过 Kotlin/Native cinterop 调用 C 桥接层，需要预先将 C/C++ 源码编译为 `.a` 静态库。源码位于 `composeApp/src/nativeInterop/cinterop/live2d/`。 `libLive2DCubismCore.a`（Cubism Native SDK 官方静态库）也需要放到对应目录中，从 [Live2D Cubism SDK for Native](https://www.live2d.com/sdk/download/native/) 下载获取。

## 支持

如果喜欢这个项目，欢迎点个 Star ⭐！

问题和建议请提交 [Issue](https://github.com/gameswu/NyaDeskPetAPP/issues)，也欢迎 Pull Request。

💗 [赞助](https://afdian.com/a/gameswu) 💗

## 许可证

[MIT License](LICENSE)
