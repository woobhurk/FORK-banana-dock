# Banana Dock

## 项目描述

Banana Dock 是运行在 Android 车机上的悬浮 Dock 应用，提供常用车辆控制、音乐信息、快捷应用、歌词悬浮窗、应用隐藏黑名单和诊断日志等入口，帮助用户在车机桌面快速访问常用功能。

- 应用包名：`com.car.dockbar`
- 应用名称：`Banana Dock`

## Dock 显示与隐藏

- 「悬浮 Dock 栏 → 显示与唤出 → 使用原厂 Dock」默认开启，不主动改变升级前的悬浮栏设置。手动关闭时先挂载 Banana Dock 再请求隐藏原厂栏；打开时请求恢复原厂栏，收到回执后停用 Banana Dock。回执不代表真实显隐，请以屏幕为准。
- 设置页“隐藏 Dock 应用”采用纯黑名单：勾选表示该应用在前台时隐藏 Dock，取消勾选则保持默认显示。
- 前台应用不在黑名单中，或暂时拿不到前台包名（例如未开启无障碍检测）时，保持默认显示，不回退到全局空闲自动隐藏。
- 全局空闲自动隐藏已经移除。底部手势或弹窗触发的临时显示倒计时仍保留，到时仅在前台应用仍要求隐藏时收起，否则保持显示。

## 项目构建

构建需 JDK 17 或兼容版本，使用 `JAVA_HOME` 指定。Android SDK、NDK 和 Gradle 版本见[构建环境](docs/build-and-release.md#1-本地环境)。

### 常用命令

Windows：

```bat
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:testDebugUnitTest
```

Git Bash、Linux 或 macOS：

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

APK 路径、签名和 GitHub Actions 发布规则见[构建、测试与发布参考](docs/build-and-release.md)。

开发协作与相关资料见 [AGENTS.md](AGENTS.md)。
