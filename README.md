# 远程ADB助手 (AdbRemoteAssistant) - 专为 Android 16 (澎湃 OS 3) 深度定制

基于 **Shizuku API** 驱动的纯 64 位现代化远程 ADB 调试助手，彻底解决原版甲壳虫 ADB 助手在 Android 16（小米澎湃 3）上由于 32位 ELF 拦截、16KB 内存分页、SELinux W^X 执行封锁导致的无法启动问题。

---

## 核心设计与特性

1. **零外部 ELF 进程侵入（Shizuku 原生进程管道）**：
   * 不释放任何本地可执行二进制，绝不触发 Android 16 的私有目录执行拦截（W^X 机制）；
   * 通过 Shizuku IPC 跨进程特权管道直接下发 `adb connect`、`am force-stop`、`pm list packages` 及 `adb install`。
2. **全架构双向兼容（64位 + 32位 armhf / armeabi-v7a）**：
   * 采用双 ABI 过滤机制（`abiFilters "arm64-v8a", "armeabi-v7a"`）；
   * 既能在纯 64 位的现代新手机（如小米澎湃 3 / 骁龙 8G3/8G4）上完美运行，也能向下兼容老旧 32 位（armhf/armeabi-v7a）安卓平板、车机、智能手持终端与电视盒子。
3. **操作交互完全对齐原 App**：
   * **Tab 1: 设备连接**：输入远程目标设备的 IP 与端口（默认 5555），一键连接/断开，状态提示醒目；
   * **Tab 2: 程序管理**：实时读取已连接远程设备的第三方应用包名，支持关键词即时过滤，并提供一键【强行停止】功能；
   * **Tab 3: 安装应用**：支持系统标准 SAF 文件选择器，从手机本地读取任何 APK 安装包，自动化推送到远程目标并执行静默安装（`adb install -r`），带实时进度反馈。

---

## 目录结构

```text
/home/zhang/adb_remote_assistant/
├── app/
│   ├── build.gradle             # Android 16 (API 35/36) 目标 SDK、Shizuku 官方 API 依赖
│   └── src/main/
│       ├── AndroidManifest.xml   # 标准权限与应用清单
│       ├── java/com/hermes/adbremote/
│       │   ├── shizuku/
│       │   │   └── ShizukuShell.kt      # Shizuku Binder 授权与非阻塞命令执行核心
│       │   ├── adb/
│       │   │   └── RemoteAdbManager.kt  # 远程 ADB 连接维护、应用列表拉取、停用与 APK 推送安装
│       │   ├── model/
│       │   │   └── RemoteAppInfo.kt     # 远程设备包信息模型
│       │   └── ui/
│       │       ├── MainActivity.kt      # 三标签页主控制器与 Shizuku 权限监听
│       │       └── RemoteAppAdapter.kt  # 应用列表 RecyclerView 适配器与搜索过滤
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml    # 现代化 Material 经典卡片与三 Tab 布局
│           │   └── item_remote_app.xml  # 应用条目卡片
│           └── values/
│               ├── colors.xml           # 继承自原版的 Teal/绿色经典主色调
│               ├── strings.xml          # 界面多语言文本
│               └── themes.xml           # MaterialComponents 主题样式
├── build.gradle
├── settings.gradle
└── README.md
```

---

## 编译与打包方式

在具备 Android SDK / Android Studio 环境的机器上运行：

```bash
cd /home/zhang/adb_remote_assistant
./gradlew assembleRelease
```
产物将输出在 `app/build/outputs/apk/release/app-release.apk`。

---

## 手机端使用前提

1. 在手机上安装并启动 **Shizuku**（澎湃 3 下建议通过“无线调试”配对启动）；
2. 打开本 App，首次打开将自动弹出 Shizuku 授权请求，点击【一律允许】；
3. 在首页输入远程盒子的 IP 地址并点击【连接】，即可无感享受原汁原味的远程应用管理与推送安装！
