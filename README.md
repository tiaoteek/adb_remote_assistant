# 远程ADB助手 (AdbRemoteAssistant) - 独立免Root/免Shizuku纯网络版

基于 **纯 Kotlin 内嵌式 ADB 协议引擎（dadb）** 实现的完全自包含远程安卓调试工具，彻底摆脱对本地外部二进制程序（原版甲壳虫）及外部 Shizuku App 的依赖，原生兼容 **Android 16（小米澎湃 OS 3）** 及老旧 32 位设备。

---

## 核心设计与特性

1. **完全自包含（Zero External Dependencies）**：
   * **不需要安装 Shizuku**，不需要 Root 权限；
   * App 内部使用纯 Java/Kotlin 实现完整的 ADB TCP Socket 握手与 RSA 证书密钥自签机制；
   * 零本地可执行二进制，绝不触发 Android 16 的私有目录执行拦截（W^X 机制）。
2. **全架构双向兼容（64位 + 32位 armhf / armeabi-v7a）**：
   * 同时适配现代纯 64 位芯片（骁龙 8 Gen 3/4 等）以及老旧 32 位电视盒子、车机、手持终端。
3. **操作交互完全对齐原版甲壳虫**：
   * **Tab 1: 设备连接**：输入远程目标设备的 IP 与端口（默认 5555），一键连接/断开；
   * **Tab 2: 程序管理**：实时读取已连接远程设备的第三方应用包名，支持关键词即时过滤，并提供一键【强行停止】功能；
   * **Tab 3: 安装应用**：支持系统标准 SAF 文件选择器，从手机本地读取任何 APK 安装包，流式推送到远程目标并执行静默安装，带实时进度反馈。

---

## 目录结构

```text
/home/zhang/adb_remote_assistant/
├── app/
│   ├── build.gradle             # 集成 dadb 纯 Kotlin ADB 协议引擎
│   └── src/main/
│       ├── AndroidManifest.xml   # 仅需要普通网络权限 INTERNET
│       ├── java/com/hermes/adbremote/
│       │   ├── adb/
│       │   │   └── RemoteAdbManager.kt  # 纯网络 ADB 客户端、RSA 密钥自管理、包管理、流式安装
│       │   ├── model/
│       │   │   └── RemoteAppInfo.kt     # 远程设备包信息模型
│       │   └── ui/
│       │       ├── MainActivity.kt      # 三标签页主控制器
│       │       └── RemoteAppAdapter.kt  # 应用列表 RecyclerView 适配器与搜索过滤
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml    # 现代化 Material 经典卡片与三 Tab 布局
│           │   └── item_remote_app.xml  # 应用条目卡片
│           └── values/
│               ├── colors.xml           # 原版 Teal/绿色经典主色调
│               ├── strings.xml          # 界面多语言文本
│               └── themes.xml           # MaterialComponents 主题样式
├── build.gradle
├── settings.gradle
└── README.md
```
