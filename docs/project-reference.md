# 项目实现与源码索引

> 本文迁移自原 `AGENTS.md`，用于按需查阅项目快照、源码入口、设置页、Dock、音乐和歌词实现。拆分日期：2026-11-18；本次未重新验证实现摘要、版本号、默认值或真车效果。
>
> 当前源码、资源和构建配置优先。下文的版本号和行为描述保留迁移前记录，不能仅凭“当前”字样认定已经核验。除另有说明，源码和配置路径均相对仓库根目录，不相对 `docs/`。

协作规约见 [AGENTS.md](../AGENTS.md)；构建流程见 [构建与发布](build-and-release.md)；车控、启动补权、日志和未验证事项见 [车控与诊断](vehicle-and-diagnostics.md)；厂商静态资料见 [历史与 oneOS 参考](history-and-oem-reference.md)。

## 1. 项目快照（迁移记录）

- 项目：`Banana_Dock`，面向 Android 车机的悬浮 Dock 应用。
- `namespace` / `applicationId`：`com.car.dockbar`。
- 应用显示名：`Banana_Dock`。
- 原快照记录版本：`versionName 4.5.0`，`versionCode 450`，来源 `app/build.gradle`；使用时以当前配置为准。
- 最低 Android 版本：API 28；compile/target SDK：34。
- 最新重要变更：设置页已从旧的单体实现拆为独立页面；当前入口由 `MainActivity`、`SettingsPageRegistry` 和各页面类共同管理。
- 不得提交真实 keystore、密码、Base64 内容或用户设备数据；仓库已有 `.github/signing/ci-test.jks` 是历史测试文件，不能据此认定它就是当前发布证书。
- 车控、车型适配、平台权限和真实车辆效果不能仅凭 APK 能构建或模拟器/逻辑测试通过来确认。

## 2. 权威源码索引

### 2.1 构建和发布

| 主题 | 权威位置 |
| --- | --- |
| Android/Kotlin 版本、包名、版本号、ABI、构建类型 | `app/build.gradle` |
| 根插件版本和 clean 任务 | `build.gradle` |
| Gradle 仓库、模块和项目名 | `settings.gradle` |
| Gradle wrapper 版本 | `gradle/wrapper/gradle-wrapper.properties` |
| CI 构建、重签、元数据和 GitHub Release | `.github/workflows/release-apk.yml` |
| 权限、Activity、服务、通知监听、开机接收器 | `app/src/main/AndroidManifest.xml` |

### 2.2 当前应用结构

除显式写出的仓库根路径外，以下 `ui/`、`dock/`、`model/`、`media/`、`vehicle/`、`config/`、`util/` 路径均相对 `app/src/main/java/com/car/dockbar/`。

| 领域 | 当前入口 |
| --- | --- |
| 设置页宿主、导航和缩放 | `app/src/main/java/com/car/dockbar/ui/MainActivity.kt` |
| 页面生命周期和通用页面契约 | `ui/SettingsPage.kt` |
| 页面注册和切换 | `ui/SettingsPageRegistry.kt` |
| 悬浮 Dock 页面 | `ui/FloatingDockSettingsPage.kt`、`ui/FloatingDockSettingsLayout.kt` |
| 外观与风格页面 | `ui/AppearanceSettingsPage.kt`、`ui/AppearanceSettingsLayout.kt` |
| Dock 图标管理页面 | `ui/IconsSettingsPage.kt`、`ui/IconsSettingsLayout.kt`、`DockEntriesActivity.kt` |
| 歌词悬浮窗页面 | `ui/LyricsSettingsPage.kt` |
| 隐藏 Dock 应用（黑名单）页面 | `ui/ResidentSettingsPage.kt` |
| 诊断与日志页面 | `ui/LogsSettingsPage.kt`、`ui/LogsSettingsLayout.kt` |
| Dock 条目模型与默认布局 | `model/DockEntry.kt` |
| 快捷应用固定槽位持久化 | `model/AppDrawerApp.kt` |
| 控件目录、尺寸和挂载 | `dock/DockCatalog.kt`、`dock/DockWidget.kt`、`dock/widgets/BuiltinWidgets.kt` |
| 动作分发 | `dock/DockActions.kt` |
| 悬浮 Dock 服务和窗口 | `ui/DockOverlayService.kt`、`ui/DockLayout.kt` |
| 显示/隐藏状态机 | `ui/state/DockStateMachine.kt` |
| 离屏编辑预览、300ms 长按与排序 | `ui/DockEditorPreviewView.kt`、`ui/DockEditorLongPress.kt`、`model/DockEditorOrder.kt` |
| 歌词服务和歌词数据源 | `ui/LyricOverlayService.kt`、`media/LyricMonitor.kt`、`media/LyricDownloader.kt` |
| 媒体会话和通知补充信息 | `media/MediaBridge.kt`、`media/DockNotificationListener.kt` |
| 车控通道配置 | `config/VehicleChannels.kt` |
| 车控业务门面、状态协调与反射适配 | `vehicle/VehicleController.kt`、`vehicle/VehicleBridge.kt` |
| 空调共享 I/O 队列与写入门禁 | `vehicle/HvacCommands.kt` |
| 空调写入策略、组合动作与温度兼容 | `vehicle/HvacWriteActions.kt` |
| 操作、真实原生写入与观测确认流水 | `vehicle/HvacCommandTrace.kt` |
| 空调真实观测、显示目标与到期回落 | `vehicle/HvacState.kt` |
| 车控分组轮询、合并和公平调度 | `vehicle/VehicleRefreshQueue.kt` |
| 空调纯编码/解码与事件证明降频 | `vehicle/HvacChannels.kt`、`vehicle/HvacRefreshPolicy.kt` |
| 空调通道观测证据与导出 | `vehicle/HvacChannelEvidence.kt`、`util/DiagnosticLogExporter.kt` |
| 原车接口验证会话与结果报告 | `vehicle/HvacDiagnostics.kt`、`ui/LogsSettingsPage.kt` |
| 传感器 | `vehicle/SensorBridge.kt` |
| 日志、崩溃和上传 | `util/Logx.kt`、`util/LogUploader.kt`、`util/DiagnosticLogExporter.kt`、`util/NativeCrashHandler.kt` |
| 偏好设置兼容入口与启动 | `util/Prefs.kt`、`App.kt` |
| Dock 黑名单默认值、迁移与存储 | `util/DockBlacklistDefaults.kt`、`util/DockBlacklistMigration.kt`、`util/DockBlacklistPreferences.kt`、`util/DockBlacklistRules.kt` |
| 规则、迁移与控件单元测试 | `app/src/test/java/com/car/dockbar/util/`、`app/src/test/java/com/car/dockbar/dock/` |

## 3. 设置页和 UI 结构

### 3.1 六个独立页面

`MainActivity.pageSpecs` 当前注册以下六页，顺序也是左侧导航顺序：

1. 悬浮 Dock 栏：`FloatingDockSettingsPage`。
2. 外观与风格：`AppearanceSettingsPage`。
3. Dock 图标管理：`IconsSettingsPage`。
4. 歌词悬浮窗：`LyricsSettingsPage`。
5. 隐藏 Dock 应用：`ResidentSettingsPage`。
6. 诊断与日志：`LogsSettingsPage`。

各页面由 `SettingsPageRegistry.createAll()` 创建并管理生命周期；后续新增设置应优先增加独立页面，而不是把所有控件重新塞回 `MainActivity`。

当前宿主保留的布局约束：

- 设置内容底部让出 **128 物理像素**给实际 Dock，不能把它误写成 128dp。
- 主设置宿主的缩放链为旧上下文比例 1.2 再乘页面 UI_SCALE 0.9，即当前约 1.08；图标管理页的独立密度约 0.9。不要修改系统全局 DPI 来实现页面缩放。
- 服务状态、版本号和诊断入口的视觉约束以当前布局资源和页面代码为准；旧文档中“全部仍在 MainActivity 里”的路径已经过时。

### 3.2 Dock 外观

- 默认 Dock 宽度配置为 1920px，默认屏幕高度配置为 1080px，默认高度 128px、底部距边 0；实际服务仍会结合当前显示尺寸计算窗口位置。
- 最近提交将默认圆角改为 **0px 直角**，配置键改为 `corner_radius_px_square`；旧文档中的默认 14px 圆角已失效。
- 按压高亮使用 `res/drawable/bg_item_press.xml`，不要恢复已移除的旧样式。
- 外观模式包含纯色、透明、渐变和“模糊（测试中）”。其中模糊实现是透明底上叠加灰色层和合成透明度（`GrayOverlayDrawable`），不是抓屏后的高斯模糊，也不申请录屏权限。
- `DockStatusLabel.kt` 虽仍在源码中，旧交接中的“上方状态小字已全部移除”不能单独作为判断依据；是否显示以 `DockOverlayService` 当前可执行调用为准，新增 UI 时不要根据旧文档擅自恢复常驻状态文字。
- 当前“隐藏 Dock 应用”页从 `getInstalledApplications` 取真实已安装应用，分系统/个人两栏；每栏 5 列、至少 4 行，支持应用名/包名搜索，绿色勾选表示加入黑名单，该应用在前台时隐藏 Dock；取消勾选则恢复默认显示。

## 4. Dock 条目、默认布局和交互

### 4.1 当前默认十项

默认只在首次使用或恢复默认时生效，升级不能覆盖用户自定义顺序。`DockEntry.kt` 中的当前顺序是：

1. `HOME`：返回桌面。
2. `MUSIC`：音乐信息卡。
3. `SETTINGS`：车机设置。
4. `APP_DRAWER`：快捷应用。
5. `TEMP_DRIVER_COMBO`：主驾温度组合。
6. `AIR`：空调风向。
7. `FAN_COMBO`：风速组合。
8. `RECIRC`：内外循环。
9. `SEAT`：座椅设置。
10. `TEMP_PASSENGER_COMBO`：副驾温度组合。

歌词、音量、座椅通风/加热、前后挡、方向盘加热等可从图标管理中手动添加，不要把它们写成当前默认项。

### 4.2 当前尺寸模型

尺寸的权威值在 `dock/DockWidget.kt` 的 `DockMetrics`：

- 单槽固定宽度：98px。
- 三槽固定宽度：290px。
- 风速组合：176px（两侧箭头点击列各 48px，数值列 80px），箭头图标 40px。
- 音乐卡：最小 295px、最大 500px，按可用空间弹性伸缩。
- 条目数量变化时，布局重新计算可用占位；不要把旧文档的“始终等宽”当作现状。

### 4.3 条目编辑和快捷应用

- 图标管理页上方展示尚未加入的内置控件，候选使用实际 Dock 控件的绘制和当前外观。
- 编辑长按固定 300ms（`DockEditorLongPress.TIMEOUT_MS`）；拖入预览添加、预览内拖动插入排序、拖出松手移除，取消拖动不保存。
- 预览为离屏绘制，不把触摸转发给真实控件，因此编辑时不会写车控、播放媒体或启动应用。
- 音乐卡、温度组合和风速组合按完整条目移动。
- 第三方应用走独立入口，系统/个人分类和搜索；删除入口不卸载应用。
- 快捷应用是独立的 2×3 固定槽位数据。空位保留，替换不压缩其它位置，批量管理和 Dock 弹窗共用同一份槽位数据。
- `dock_entries_explicit_layout` 用于区分旧配置迁移和用户主动删除；用户清空或删除后不能每次读取又自动补回。

### 4.4 当前动作约束

动作集中在 `dock/DockActions.kt` 和 `dock/widgets/BuiltinWidgets.kt`。当前应保留的行为包括：返回桌面/最近任务、启动播放器、播放暂停/下一曲、车机设置、空调/温度/风量/循环/座椅操作、歌词窗口切换和应用启动。音乐卡无法打开播放器时提示“当前没有可打开的播放器”，不要恢复旧的无提示跳转。

旧交接中仍有价值的 UI 规则（但须以当前代码为最终依据）：

- 弹窗贴近触发图标，点外关闭，无额外关闭按钮，按当前配置自动消失。
- 弹窗触发应是 toggle 语义，不能用时间戳抑制后续切换。
- 主副驾温度默认短按中间值均切换 AC，长按打开对应温度弹窗；双温同步保留在副驾温度弹窗中；具体文案和实现以 `BuiltinWidgets.kt`、`DockActions.kt` 为准。
- 主副驾温度弹窗 `ui/TemperaturePopup.kt` 的功能按钮使用 `ui/DockPopupIconButton.kt`：前后挡、极速冷暖和方向盘加热以图标展示；极速冷暖保留 `MAX`，方向盘保留档位。副驾保留 `SYNC`、车内温度读数及 `>25℃ AUTO`／`<15℃ AUTO`，两项 AUTO 仍只切换原有自动规则，不代表立即调节座椅。温度滚轮、主副驾标题、原车控回调及窗口生命周期不变，图标具备中文无障碍说明，选中态同时显示边框。
- “座椅设置”弹窗 `ui/SeatPopup.kt` 保留三个记忆位置，用座椅图标加 `1／2／3` 表示；下方保留主驾／副驾列名，通风、加热分别显示实际档位图标和数字，`0` 表示关闭。原记忆读回高亮与各通道独立的关→最高档→逐级降低→关循环不变。
- 风向弹窗 `ui/AirDirectionPopup.kt` 的图标自上而下为吹前挡、吹脸、吹脚；仍为可组合的独立开关，至少保留一项开启。
- 主驾座椅控件短按仍按 `关 → 3 → 2 → 1 → 关` 切换通风，图标随主驾加热、通风或关闭状态切换；两项同时有档位时图标优先显示加热。长按打开 `ui/SeatClimatePopup.kt`，主副驾各显示加热、通风和关闭；合并后的车控状态驱动高亮，8 秒无操作收起。非零档位默认只写目标通道，中间关闭项用裸值 `0` 关闭同侧可用通道。诊断验证窗口可显式选择 ECarX 直写、ECarX 组合或 GlyCar 整型写入。调用接受不等于车辆物理效果。
- 车机明确报告某侧座椅通风或方向盘加热不可用时，Dock 运行界面隐藏对应的主栏条目及弹窗按钮。主、副驾分别判断；副驾温度弹窗中的高温自动通风针对主驾，随主驾通风显隐。座椅加热、记忆位与座椅设置入口保留；隐藏条目不占主栏宽度，不改保存的布局、手势或编辑预览。
- 真正修改车控前必须经过当前 `VehicleController` 的写入保护；编辑预览不能触发真实动作。
- 当前默认淡入淡出时长为 350ms；底部手势临时唤出 Dock 默认显示 5000ms。淡入淡出时长可由设置覆盖，改动前应以 `Prefs.kt` 为准。
- 音量弹窗使用音符、导航箭头、电话和语音图标区分四个通道，保留音量数值、加减号及中文无障碍说明；最多放大 1.8 倍，并按锚点或屏幕可用宽高收敛。媒体、导航、电话、语音分别使用系统流 3、15、0、11，显示和写入使用系统原始流索引，不再额外加减 5；电话最低音量保持为 1；默认 6 秒收起。

### 4.5 Dock 显隐与临时显示（当前）

- 前台显隐入口为 `ui/DockOverlayService.kt` 的 `applyKeepShownForForeground()`，唯一名单来源为 `dock_hide_apps`：命中则隐藏，未命中则保持默认显示。
- 前台包名为空、空白或尚未取得（例如未开启无障碍）时，设置 `hiddenByApp=false`，不因前台应用隐藏 Dock。不存在“拿不到前台应用就交还全局空闲自动隐藏”的回退逻辑。
- 全局空闲自动隐藏已经移除，`SHOWN` 状态不会因为无操作而倒计时消失；`pauseAutoHide()`、`onUserIdle()` 等沿用的名称不能作为该功能仍存在的依据。
- 底部手势或弹窗触发的 `TEMP_SHOW` 临时显示倒计时仍保留。`DockStateMachine.onTempShowTimeout()` 到时仅在 `hiddenByApp=true` 时隐藏，否则转为 `SHOWN`，不得将其与已删除的全局空闲自动隐藏混淆。
- 黑名单默认值和迁移分别见 `util/DockBlacklistDefaults.kt`、`util/DockBlacklistMigration.kt`；日常读写经 `Prefs` 转发至 `DockBlacklistPreferences`，不改变上述未知前台默认显示策略。

## 5. 音乐、歌词和权限

### 5.1 当前模块关系

- 音乐卡通过 `MediaBridge` 获取活跃媒体会话，通知使用权可作为媒体信息的补充来源。
- 音乐卡歌名和歌手各一行。文字未超出宽度时静止显示；超出时在卡片内左右来回滚动。滚到开头或结尾后，按 `Prefs.musicScrollHoldMs` 停顿再反向，默认 2 秒，范围 0–5 秒、0.1 秒步进，可在外观与风格的「音乐信息」中调整。实现位于 `ui/DockItemViews.kt` 的 `DockMusicView`。
- 歌词使用独立的 `LyricMonitor`、`LyricOverlayService` 和 `LyricWindows`，与 Dock 音乐卡的控制动作解耦。
- 歌词来源依次可包括广播/MediaSession 自带字段、本地 `.lrc`、联网服务和手动导入/粘贴；切歌会清理旧歌词，进度归零会延迟重新抓取。
- 歌词窗口支持行数、字号、颜色、透明度、尺寸、偏移、锁定穿透、开机自启和来源配置；详细 UI 以 `LyricsSettingsPage.kt` 当前实现为准。

### 5.2 安装权限

- Dock 和歌词窗口需要“显示在其他应用上层”权限。
- 音乐卡和歌词精确进度依赖通知使用权；安装新 APK 后如果系统重置该特殊授权，启动和恢复检查会尝试强制补齐，失败时仍可使用系统授权页。
- 本地 `.lrc` 兜底才需要相应存储访问；不是所有歌词来源都要求存储权限。
- Dock 黑名单前台检测使用无障碍服务；未取得前台包名时默认显示 Dock，不回退到空闲自动隐藏。Manifest 中另有通知监听服务、Dock/歌词前台服务和开机接收器。
- `AndroidManifest.xml` 同时声明 `QUERY_ALL_PACKAGES` 与对车机包/Launcher 的显式 `queries`；能否枚举或启动目标仍受 Android 版本、系统策略和实际授权影响，不能把声明权限等同于已经取得权限。
- Manifest 还声明 `WRITE_SECURE_SETTINGS`、`MANAGE_EXTERNAL_STORAGE` 等特殊权限；普通安装不会自动授予，不能将声明写成权限已生效。

