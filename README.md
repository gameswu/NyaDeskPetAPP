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

## 与原项目的关系

| | 原项目 (桌面端) | 本项目 (移动端) |
|---|---|---|
| 技术栈 | Electron + TypeScript | Kotlin Multiplatform + Compose |
| 平台 | Windows / macOS / Linux | Android / iOS |
| Live2D | PixiJS + Cubism Web SDK | OpenGL ES + Cubism Native SDK |
| Agent | Node.js 内置服务器 | Kotlin 内置 Agent |
| 通信 | WebSocket | 进程内直接调用 |

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

### Android

```bash
./gradlew :androidApp:assembleDebug
```

### iOS

在 Xcode 中打开 iosApp.xcodeproj，选择目标设备后构建运行。

## 支持

如果喜欢这个项目，欢迎点个 Star ⭐！

问题和建议请提交 [Issue](https://github.com/gameswu/NyaDeskPetAPP/issues)，也欢迎 Pull Request。

💗 [赞助](https://afdian.com/a/gameswu) 💗

## 许可证

[MIT License](LICENSE)
