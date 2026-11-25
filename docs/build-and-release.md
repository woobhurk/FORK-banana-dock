# 构建、测试与发布参考

> 文中的构建路径均相对仓库根目录。SDK、版本和构建选项以 Gradle 配置为准，发布规则以 `.github/workflows/release-apk.yml` 为准。

协作规约见 [AGENTS.md](../AGENTS.md)；源码入口见 [项目实现与源码索引](project-reference.md)。

## 1. 本地环境

- Android API 最低 28，compile SDK 和 target SDK 均为 34。
- Gradle wrapper：8.9。
- Android Gradle Plugin：8.7.3。
- Kotlin Android plugin：2.0.21。
- Java/Kotlin 编译目标为 17。使用 `JAVA_HOME` 指定 JDK 17 或兼容版本（如 JDK 21）。CI 使用 JDK 17。
- NDK：`28.2.13676358`，通过 `ndk-build` 使用 `app/src/main/jni/Android.mk`。
- ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`。
- Release 开启 R8 和资源缩减；本地 Gradle release 配置仍使用 `signingConfigs.debug`，CI 会在发布前用 Secrets 重签。

在仓库根目录执行 [README 中的构建和测试命令](../README.md#常用命令)。

### 本地产物

- `app/build/outputs/apk/debug/Banana_Dock-<versionName>-debug.apk`
- `app/build/outputs/apk/release/Banana_Dock-<versionName>.apk`

`<versionName>` 取自 `app/build.gradle`。相应构建成功后才能交付对应的 APK。

## 2. GitHub Actions

- workflow 接收所有分支的 push 和 `workflow_dispatch`，但实际 `detect` 只在默认分支且 ref 未被删除时运行。
- push 会检查本次推送中的每条提交说明。说明须以 `ci` 开头，后接分隔符（冒号、中文冒号或空白）或直接结束，并包含 `发布`、`新版`、`release` 或 `new version` 一类关键词。英文匹配忽略大小写。
- 手动 dispatch 直接请求发布，但仍受默认分支条件约束。
- 发布任务使用 Java 17、Android SDK 34、Build Tools 34.0.0 和 NDK 28.2.13676358，执行 `:app:assembleRelease --no-daemon`。
- 构建目录中必须恰好有一个 APK；随后用四个 Secrets 重签并用 `apksigner verify` 校验。
- Secrets 名称：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。不得把 keystore、密码或 Base64 内容提交到仓库。
- CI 将 Gradle 产物复制到 `dist/`。自动发布使用 `CarDockBar-<versionName>.apk`，手动发布使用 `CarDockBar-<versionName>-r<run>.apk`，同一次 run 重跑时使用 `CarDockBar-<versionName>-r<run>-a<attempt>.apk`。CI 校验 APK 与 Gradle 配置的 `versionName`、`versionCode` 一致。每次发布还生成 `update.json` 和 `SHA256SUMS`。
- 自动发布创建正式 Release，tag 为 `v<versionName>`，标题为 `CarDockBar <versionName>`。同一 tag 已存在时拒绝重复发布。手动发布创建 Pre-release，标题为 `CarDockBar <versionName> (r<run>)`，tag 为 `v<versionName>-r<run>`，重跑时为 `v<versionName>-r<run>-a<attempt>`。
- 更新日志以当前提交最近的同类 Release 祖先 tag 为起点。找不到同类祖先时使用全部提交。提交列表跳过以 `ci` 开头且符合前缀格式的说明，随后附上 GitHub 从同一起点生成的更新日志。正文保留 `<!-- android-version-code: ... -->`，供后续正式发布判断能否标为 Latest。

