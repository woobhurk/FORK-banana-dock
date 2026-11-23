# 构建、测试与发布参考

> 本文迁移自原 `AGENTS.md` 的构建发布章节。拆分日期：2026-11-18；本次未运行构建或重新核验 CI 行为，保留的版本号、产物名和本地环境描述是迁移前记录，不代表当前构建结果。
>
> 使用前以 `app/build.gradle`、根 `build.gradle`、`settings.gradle`、Gradle wrapper 和 `.github/workflows/release-apk.yml` 为准；下文路径均相对仓库根目录。

协作规约见 [AGENTS.md](../AGENTS.md)；源码入口见 [项目实现与源码索引](project-reference.md)。

## 1. 本地环境

- Gradle wrapper：8.9。
- Android Gradle Plugin：8.7.3。
- Kotlin Android plugin：2.0.21。
- Java/Kotlin 编译目标：17；Gradle 运行需 JDK 17 或兼容版本（如 JDK 21），用 `JAVA_HOME` 指定。CI 使用 JDK 17，本次本地验证环境为 JDK 21。旧文档中“JDK 11+ 即可”不满足当前 AGP 要求；也不要求必须使用某个固定安装路径下的 JBR。
- NDK：`28.2.13676358`，通过 `ndk-build` 使用 `app/src/main/jni/Android.mk`。
- ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`。
- Release 开启 R8 和资源缩减；本地 Gradle release 配置仍使用 `signingConfigs.debug`，CI 会在发布前用 Secrets 重签。

常用命令：

```bash
# Windows
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:testDebugUnitTest

# Git Bash / Linux / macOS
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

本地产物：

- `app/build/outputs/apk/debug/Banana_Dock-4.5.0-debug.apk`
- `app/build/outputs/apk/release/Banana_Dock-4.5.0.apk`

以上路径只有在相应构建实际成功后才能写入交付说明；不要沿用历史的 `CarDockBar-debug.apk` 或 `Banana_Dock-4.0.apk`。

## 2. GitHub Actions

`.github/workflows/release-apk.yml` 的行为如下：

- workflow 接收所有分支的 push 和 `workflow_dispatch`，但实际 `detect` 只在默认分支且 ref 未被删除时运行。
- push 会逐条检查本次 push 的 commit message。只有说明以 `ci:` 开头（大小写不敏感，也接受中文冒号或空格），并且包含中文 `发布` / `新版`，或英文 `release` / `new version` 时才请求发布。例如 `ci: 发布新版本 v4.5.6`、`ci: New version v4.5.6 released`。
- 手动 dispatch 直接请求发布，但仍受默认分支条件约束。
- 发布任务使用 Java 17、Android SDK 34、Build Tools 34.0.0 和 NDK 28.2.13676358，执行 `:app:assembleRelease --no-daemon`。
- 构建目录中必须恰好有一个 APK；随后用四个 Secrets 重签并用 `apksigner verify` 校验。
- Secrets 名称：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。不得把 keystore、密码或 Base64 内容提交到仓库。
- CI 将 Gradle 产物复制到 `dist/`。关键词触发的自动发布文件名为 `CarDockBar-<versionName>.apk`（当前版本形如 `CarDockBar-4.5.0.apk`）。手动 `workflow_dispatch` 仍使用 `CarDockBar-<versionName>-r<run>.apk`（形如 `CarDockBar-4.5.0-r123.apk`），同一次 run 重跑时为 `CarDockBar-<versionName>-r<run>-a<attempt>.apk`。`versionName` / `versionCode` 仍只由 APK 与 `app/build.gradle` 交叉校验，CI 不改写它们，因此同一版本的新旧包可以互相覆盖安装。每次构建另写自己的 `update.json` 和 `SHA256SUMS`。
- 关键词触发的自动发布是正式 Release，tag 为 `v<versionName>`，标题为 `CarDockBar <versionName>`。同一 `versionName` 的自动 tag 已存在时拒绝再次发布，不会覆盖或删除已有 Release。手动 `workflow_dispatch` 发布为 Pre-release，不标为 Latest；tag 为 `v<versionName>-r<run>`（重跑时为 `v<versionName>-r<run>-a<attempt>`），标题为 `CarDockBar <versionName> (r<run>)`。发布资产名与本地 `Banana_Dock-4.5.0.apk` 有意不同，不要混写。
- 稳定 Release 正文先列出相对最近一个稳定 Release 的提交说明。稳定 Release 指非 Pre-release，且其 tag 是当前提交的祖先。预览版（手动 `workflow_dispatch` 的 Pre-release）正文先列出相对最近一个 Pre-release 的提交说明，起点同样要求该 tag 是当前提交的祖先。没有更早的同类 Release 时，更新日志包含全部提交。以 `ci` 开头的发布提交不写入，再由 GitHub 自动 Changelog 从同一起点接在后面。正文开头保留不可见的 `<!-- android-version-code: ... -->`，供下次发布判断能否标为 Latest。
- 普通文档提交不要写成 `ci:` 开头并带「发布 / 新版」或英文 `release` / `new version`，避免触发 Release。

