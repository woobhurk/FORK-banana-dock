# 项目实现与源码索引

> 除另有说明，文中的源码和配置路径均相对仓库根目录。车机能力和效果的证据要求见[车控与诊断](vehicle-and-diagnostics.md#5-未验证事项和交付边界)。

协作规约见 [AGENTS.md](../AGENTS.md)；构建流程见 [构建与发布](build-and-release.md)；车控、启动补权、日志和未验证事项见 [车控与诊断](vehicle-and-diagnostics.md)；厂商静态资料见 [历史与 oneOS 参考](history-and-oem-reference.md)。

## 1. 项目信息

Banana Dock 是面向 Android 车机的悬浮 Dock 应用，`namespace` 和 `applicationId` 均为 `com.car.dockbar`。版本和 SDK 由 `app/build.gradle` 配置。

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
| 快捷应用固定槽位持久化 | `model/AppDrawerApps.kt` |
| 控件目录、尺寸和挂载 | `dock/DockCatalog.kt`、`dock/DockWidget.kt`、`dock/widgets/BuiltinWidgets.kt` |
| 动作分发 | `dock/DockActions.kt` |
| 悬浮 Dock 服务和窗口 | `ui/DockOverlayService.kt`、`ui/DockLayout.kt` |
| 显示/隐藏状态机 | `ui/state/DockStateMachine.kt` |
| Dock 图标长按延迟与拖动触发 | `util/Prefs.kt`、`dock/GestureInput.kt`、`ui/DockDragLongPress.kt` |
| 离屏编辑预览与排序 | `ui/DockEditorPreviewView.kt`、`model/DockEditorOrder.kt` |
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
| 日志、崩溃和上传 | `util/Logx.kt`、`util/RuntimeLogStore.kt`、`util/LogUploader.kt`、`util/DiagnosticLogExporter.kt`、`util/NativeCrashHandler.kt` |
| 偏好设置与启动 | `util/Prefs.kt`、`App.kt` |
| Dock 黑名单默认值与存储 | `util/DockBlacklistDefaults.kt`、`util/DockBlacklistPreferences.kt`、`util/DockBlacklistRules.kt` |
| 规则与控件单元测试 | `app/src/test/java/com/car/dockbar/util/`、`app/src/test/java/com/car/dockbar/dock/` |

## 3. 设置页和 UI 结构

### 3.1 七个独立页面

`MainActivity.pageSpecs` 按以下顺序注册左侧导航。

1. 悬浮 Dock 栏：`FloatingDockSettingsPage`。
2. 外观与风格：`AppearanceSettingsPage`。
3. Dock 图标管理：`IconsSettingsPage`。
4. 歌词悬浮窗：`LyricsSettingsPage`。
5. 隐藏 Dock 应用：`ResidentSettingsPage`。
6. 配置备份：`ConfigBackupSettingsPage`。
7. 诊断与日志：`LogsSettingsPage`。

`MainActivity` 先创建左侧导航和目标页，普通入口默认显示「悬浮 Dock 栏」。`SettingsPageRegistry` 在首次进入页面时构建并挂载其控件，同一设置窗口内复用已创建的页面。诊断导出结果注册与 Dock、歌词等服务恢复由宿主管理，不依赖提前创建对应页面。

设置页采用 B 方案的青绿深色主题。`app/src/main/res/values/settings.xml` 集中管理强调色 `#78DAC7`、卡片、输入框和控件状态，作用域为 `MainActivity`、`DockEntriesActivity` 及其确认框。背景形态和歌词源使用原生单选组，背景形态另有样块和底色预览。

设置页左上角品牌图标使用 8dp 圆角。

左侧导航随可用宽度调整。悬浮 Dock 栏、外观与风格、Dock 图标管理、歌词悬浮窗和诊断与日志使用等宽内容双列，内容区低于 560dp 时按原分组顺序上下排列。图标编辑在矮屏将预览固定于候选区下方，便于滚动候选时拖入 Dock。

歌词悬浮窗页标题横跨双列：左列为「窗口与运行」和「歌词来源」，右列为「显示效果」。

当前宿主保留的布局约束：

- 设置内容底部让出 **128 物理像素**给实际 Dock，不能把它误写成 128dp。
- 主设置宿主的缩放链为旧上下文比例 1.2 再乘页面 UI_SCALE 0.9，即当前约 1.08；图标管理页的独立密度约 0.9。不要修改系统全局 DPI 来实现页面缩放。
### 3.2 Dock 外观

- Dock 固定贴屏幕底部。默认窗口宽 1920 px、高 128 px，屏幕高度配置默认为 1080 px，底部距边默认为 0 px。
- Dock 默认圆角为 0px，配置键为 `corner_radius_px_square`。
- 主栏按压高亮使用 `res/drawable/bg_item_press.xml`；圆角弹窗控件使用独立的按压反馈。
- 外观模式包含纯色、透明、渐变和“模糊（测试中）”。其中模糊实现是透明底上叠加灰色层和合成透明度（`GrayOverlayDrawable`），不是抓屏后的高斯模糊，也不申请录屏权限。
- Dock 主栏与编辑预览共用底板、圆角、分隔线和图标尺寸规则；预览的「模拟前台」只切换离屏的暗底／亮底样例，不写入配置，也不改变运行主栏的日夜反色。
- 音乐卡片的封面和无封面占位共用圆角。常规尺寸采用 8dp 上限，小尺寸按边长的 16% 缩小。
- Dock 弹窗共用设置页的青绿深色实底与描边，底板圆角为 14dp。`DockPopupStyle` 集中定义标题字号、按钮底色、边框、按压和选中反馈。默认强调色为青绿，显式保存的强调色通过对比度检查后继续生效。音量轨道随弹窗强调色着色，`MAX`／`SYNC` 缺少状态时仍显示「未知」。`DockPopupChrome` 负责贴近触发图标、屏幕边界和窗外点击，面板尺寸按各自控件布局适配。
- Dock 自带弹窗统一使用 180 ms 淡入和淡出。快捷应用与座椅加热／通风在退场开始时停止接收触控，重开时会移除正在淡出的旧窗口。
- 弹窗常规图标复用主栏的像素尺寸（默认 65px），快捷应用复用主栏应用图标尺寸（默认 55px）。图标不随面板放大，只有控件空间不足时才缩小。
- 返回桌面、快捷应用和各风向的显示比例由矢量路径和描边决定。风向弹窗沿用主栏的吹脸、吹脚、吹前挡素材。快捷应用弹窗为两行三列，应用名称和空位文字的基准字号为 11sp，随面板缩放。空位使用虚线边框。弹窗标题共用字重与字号上限，空间不足时随面板缩放。
- 温度弹窗以实心图标显示急速降温、极速升温、双温同步和车内温度。音量弹窗的导航、电话和语音也使用实心图标。自动通风与加热直接复用座椅 3 档图标。
- 主栏音量按钮沿用标准喇叭图标尺寸，媒体音量数字贴在喇叭右下角，字号比例与风量档位相同。
- 「隐藏 Dock 应用」首次打开时在后台按已保存的包名查找已安装条目，仍按系统／个人分组，支持应用名或包名搜索。添加应用弹窗按需枚举全部已安装应用，窗口内重用目录，收到安装、卸载、变更或更新广播时使缓存失效。候选列表使用 `RecyclerView`，宽版等宽双列并留出 24dp 的左右内边距，窄版单列并留出 16dp。已选条目在各组优先显示，右侧打勾，底部的说明和取消按钮不随列表滚动。加入名单后，该应用在前台时隐藏 Dock；移除后恢复默认显示。
- 黑名单复用快捷应用设置的应用卡片。卡片高 98dp，居中显示 42dp 图标和 13sp 单行应用名称，右上角的 × 用于移除。系统和个人分组各按可用宽度排布，最多三列，超出可视高度时在各自分组内滚动。

### 3.3 配置备份

- 在「设置 → 配置备份」选择配置组，通过系统文件选择器导出 UTF-8 JSON。导入先预览变更及无法启动的应用，再勾选并确认。布局、快捷应用槽位、黑名单按整组替换，其他设置按文件中给出的字段覆盖。缺装应用仍保留原位置。
- 备份包含 Dock 顺序与手势、六个快捷应用槽位、黑名单及上滑显隐、背景与图文缩放、交互时长和歌词显示样式。屏幕与窗口尺寸、位置、圆角、原厂 Dock 选择、权限、车控保护、日志和歌词联网及启动设置由本机管理。备份会包含所选应用的包名，分享前可取消勾选相关组。
- 文件标识 `format` 为 `banana-dock-config`。初始 `schemaVersion` 为 `5`，`sourceAppVersion` 用于展示来源。今后文件结构或语义发生变化时，在 `ConfigBackupFormat` 添加从当前版本到下一版的迁移，再提高版本号。现版拒绝 V1–V4 和 V6 以上的文件。应用自身的 `versionCode` 不参与文件迁移。
- 缺少字段时保留目标车机的配置。源端未保存黑名单时，导出省略 `visibility.hiddenPackages`。显式清空则导出 `[]`。`layout.entries: []` 表示空布局，`layout.entries: null` 表示跟随目标版本默认布局。`quickApps.slots` 与本机 `app_drawer_apps_json` 使用相同的六槽结构：非空槽写 `{"package":"…"}`，空槽写 `null`。手势中的 `null` 表示禁用该动作，省略单个手势则继承控件默认。
- 配置读取上限为 256 KiB。预览跳过不再受支持的控件，并禁用不再受支持的显式动作。如果非空布局中的控件全被跳过，保留本机布局。确认后保存本机恢复点，一次提交选中项，写盘失败时回写旧值。回写仍失败时，可从「本机恢复点」重试。现有页面和窗口只在保存成功后重读。

下面的 V5 备份片段将示例播放器放在第 2 槽。

```json
{
  "format": "banana-dock-config",
  "schemaVersion": 5,
  "groups": {
    "quickApps": {
      "slots": [null, {"package": "com.example.player"}, null, null, null, null]
    }
  }
}
```

## 4. Dock 条目、默认布局和交互

### 4.1 默认布局（12 项）

未保存布局、保存的 JSON 无法解析或用户主动恢复默认时使用以下默认布局。已保存的自定义顺序和显式空布局不会在读取时改写。

1. `HOME`：返回桌面。
2. `SETTINGS`：车机设置。
3. `APP_DRAWER`：快捷应用。
4. `MUSIC`：音乐信息卡。
5. `VOLUME`：音量。
6. `TEMP_DRIVER_COMBO`：主驾温度组合。
7. `FAN_COMBO`：风速组合。
8. `TEMP_PASSENGER_COMBO`：副驾温度组合。
9. `AIR`：空调风向。
10. `VENT`：主驾座椅通风。
11. `RECIRC`：内外循环。
12. `SEAT`：座椅设置。

歌词、主驾座椅加热、前后挡除霜、方向盘加热等可从图标管理中手动添加。

### 4.2 当前尺寸模型

尺寸的权威值在 `dock/DockWidget.kt` 的 `DockMetrics`：

- 单槽固定宽度：98px。
- 三槽固定宽度：290px。
- 风速组合：176px（两侧箭头点击列各 48px，数值列 80px），箭头图标 40px。
- 音乐卡：最小 295px、最大 500px，按可用空间弹性伸缩。
- 条目数量变化时，布局重新计算可用占位；不要把旧文档的“始终等宽”当作现状。

### 4.3 条目编辑和快捷应用

- 图标管理页上方展示尚未加入的内置控件，候选使用实际 Dock 控件的绘制和当前外观。
- Dock 主栏和图标管理预览共用实心矢量。播放、暂停和下一首使用圆角轮廓，前后挡除霜通过镂空显示圆角热流箭头，后挡采用面积更大的矩形窗面。主驾温度弹窗与主栏共用 `ic_defrost`、`ic_rear_defrost`，各自按控件尺寸和状态着色。空调风向的吹脸和吹脚箭头采用圆角，保留各方向组合、风向 `OFF` 和空调关机斜线。风向和座椅弹窗复用对应图标，副驾温度弹窗的双温同步使用成对的圆角箭头。第三方应用继续使用包管理器返回的原图标。
- 座椅设置、通风和加热共用实心侧视轮廓。头枕尖端略向后收，前缘沿平滑弧线接入下段外凸的靠背，两者连为一体；坐垫独立，保留透底缝。设置和记忆保留底部滑轨。通风和加热在 Dock、图标管理预览和两个座椅弹窗共用 0 档的「座椅＋短横」图标。Dock 和编辑预览的座椅主体按深浅底使用白／黑前景。1–3 档保留蓝色风叶与橙色热纹，方向盘加热同样按档位点亮热纹。
- 内外循环采用车轮向下突出的实心侧车身，箭尖和转折均作圆角。内循环用车内回转箭头；外循环用透底镂空的短单弧箭头，箭尖朝向车身中部，箭尾与前挡开口连成一条填充轮廓，随车身一起抗锯齿。
- 「Dock 图标管理 → 交互与反馈 → 长按延迟」默认 350 ms，可按 50 ms 步进在 200–1000 ms 之间调整。主栏图标动作、图标编辑拖动、快捷应用弹窗与设置页中的槽位换位共用此延迟；调整后从下一次按下起生效，无需重启 Dock。原厂 Dock 和 Android 系统控件的长按、显示动画及弹窗收起计时不受影响。
- 编辑时，长按后拖入预览添加、预览内拖动插入排序、拖出松手移除，取消拖动不保存。
- 预览为离屏绘制，不把触摸转发给真实控件，因此编辑时不会写车控、播放媒体或启动应用。
- 主副驾温度和风速均使用组合控件。音乐卡和这三种组合控件各按完整条目移动。
- 第三方应用走独立入口，系统/个人分类和搜索；删除入口不卸载应用。
- 快捷应用按 2×3 的固定位置保存。本机 `app_drawer_apps_json` 的非空项只写 `package`，空项写 `null`。旧对象按包名读取，首次保存时整数组重写，旧名称和 Activity 随之移除。批量保存保留不在可启动列表中的应用及其槽位。
- 设置页和弹窗按包名读取系统当前的显示名称和图标，缺装时显示包名和占位图。弹窗点击应用时查找本机的默认启动入口。空槽用虚线标记，可在管理页点击添加。

### 4.4 当前动作约束

动作集中在 `dock/DockActions.kt` 和 `dock/widgets/BuiltinWidgets.kt`。当前应保留的行为包括：返回桌面/最近任务、启动播放器、播放暂停/下一曲、车机设置、空调/温度/风量/循环/座椅操作、歌词窗口切换和应用启动。音乐卡无法打开播放器时提示“当前没有可打开的播放器”，不要恢复旧的无提示跳转。

旧交接中仍有价值的 UI 规则（但须以当前代码为最终依据）：

- 弹窗贴近触发图标，点外关闭，无额外关闭按钮，按当前配置自动消失。
- 弹窗触发应是 toggle 语义，不能用时间戳抑制后续切换。
- 主副驾温度默认短按中间值均切换 AC，长按打开对应温度弹窗；双温同步保留在副驾温度弹窗中；具体文案和实现以 `BuiltinWidgets.kt`、`DockActions.kt` 为准。
- 主副驾温度弹窗 `ui/TemperaturePopup.kt` 的功能按钮使用 `ui/DockPopupIconButton.kt`：前后挡、极速冷暖和方向盘加热以图标展示；极速冷暖保留 `MAX`，方向盘保留档位。副驾保留 `SYNC`、车内温度读数及 `>25℃ AUTO`／`<15℃ AUTO`，两项 AUTO 仍只切换原有自动规则，不代表立即调节座椅。温度滚轮、主副驾标题、原车控回调及窗口生命周期不变，图标具备中文无障碍说明，选中态同时显示边框。
- 主驾温度弹窗的冷色「MAX」在日常模式直接开关原厂急速降温。高亮跟随有效事件或读回，未知状态不写；不再使用单独设置 A/C、风量和温度的降温预设。极速升温按钮不变。
- 副驾弹窗的车内温度为无独立底色的只读区域，保留图标、读数及无数据时的 `--`；弹窗卡片底色不变。
- “座椅设置”弹窗 `ui/SeatPopup.kt` 保留三个记忆位置，用座椅图标加 `1／2／3` 表示；下方保留主驾／副驾列名，通风、加热分别显示实际档位图标和数字，`0` 表示关闭。原记忆读回高亮与各通道独立的关→最高档→逐级降低→关循环不变。
- 风向控件短按按同一车控连接内最近的有效原厂电源 `0/1` 观测翻转 `HVAC_FUNC_POWER (0x10010100)`，只写全局区 `0x80000000` 的裸值 `0/1`，不附带写风量、风向、A/C 或 AUTO；状态未知、连接变化或上一次写入未确认时不写。长按打开 `ui/AirDirectionPopup.kt` 的原弹窗，吹前挡、吹脸、吹脚仍可组合，至少保留一项。
- 风量组合仍为 176px，中间使用四叶风扇，右下角标档位（停风显示 `0`），AUTO 时右上角显示 `A`。左右箭头及中间短按的手动／AUTO 切换不变；长按中间打开车机空调页面。
- 主驾座椅控件短按在 `关 → 3 档` 与 `有通风 → 关` 间切换，图标随主驾加热、通风或关闭状态切换；两项同时有档位时图标优先显示加热。长按打开 `ui/SeatClimatePopup.kt`，主副驾各显示加热、通风和关闭；合并后的车控状态驱动高亮，8 秒无操作收起。非零档位默认只写目标通道，中间关闭项用裸值 `0` 关闭同侧可用通道。诊断验证窗口可显式选择 ECarX 直写、ECarX 组合或 GlyCar 整型写入。调用接受不等于车辆物理效果。
- 车机明确报告某侧座椅通风或方向盘加热不可用时，Dock 运行界面隐藏对应的主栏条目及弹窗按钮。主、副驾分别判断；副驾温度弹窗中的高温自动通风针对主驾，随主驾通风显隐。座椅加热、记忆位与座椅设置入口保留；隐藏条目不占主栏宽度，不改保存的布局、手势或编辑预览。
- 真正修改车控前必须经过当前 `VehicleController` 的写入保护；编辑预览不能触发真实动作。
- 当前默认淡入淡出时长为 350ms；底部手势临时唤出 Dock 默认显示 5000ms。淡入淡出时长可由设置覆盖，改动前应以 `Prefs.kt` 为准。
- 音量弹窗用专用音符、带居中镂空的圆角导航箭头、电话和语音图标区分四个通道。通道图标沿用主栏尺寸，保留音量数值、加减号及中文无障碍说明。面板最多放大 1.8 倍，并按锚点或屏幕可用宽高收敛。媒体、导航、电话、语音分别使用系统流 3、15、0、11，显示和写入使用系统原始流索引。电话最低音量为 1，弹窗默认 6 秒收起。

### 4.5 Dock 显隐与临时显示（当前）

- 「使用原厂 Dock」保存用户选择，默认勾选但不在首次升级或打开设置时写入原厂状态。手动切换走 oneOS `IDockBarService.showDock(1/3)`；显隐请求异常时不改已保存的选择，也不卸掉原先启用的栏。已选 Banana 模式时，其窗口可见才会发出隐藏请求，服务重启时至多尝试一次。接口不提供实际显隐查询，设置页和 `OEM_DOCK` 日志只报告回执或错误。
- 原厂桌面恢复时会重新显示 Dock。Banana 模式下，回到桌面或收到桌面自身的无障碍窗口事件后，合并连续事件并延迟补发一次 `showDock(3)`。发送前确认当前活动应用仍是桌面、Banana Dock 可见且没有手动切换；不轮询、不改设置或黑名单，也不绕过原厂的 360 全景处理。已在桌面再次按 Home 是否触发、实际重叠和闪现情况取决于车机事件与时序。
- 前台显隐入口为 `ui/DockOverlayService.kt` 的 `applyKeepShownForForeground()`，唯一名单来源为 `dock_hide_apps`：命中则隐藏，未命中则保持默认显示。
- 前台包名为空、空白或尚未取得（例如未开启无障碍）时，设置 `hiddenByApp=false`，不因前台应用隐藏 Dock。不存在“拿不到前台应用就交还全局空闲自动隐藏”的回退逻辑。
- 全局空闲自动隐藏已经移除，`SHOWN` 状态不会因为无操作而倒计时消失；`pauseAutoHide()`、`onUserIdle()` 等沿用的名称不能作为该功能仍存在的依据。
- 底部手势或弹窗触发的 `TEMP_SHOW` 临时显示倒计时仍保留。`DockStateMachine.onTempShowTimeout()` 到时仅在 `hiddenByApp=true` 时隐藏，否则转为 `SHOWN`，不得将其与已删除的全局空闲自动隐藏混淆。
- 尚未保存 `dock_hide_apps` 时使用 `util/DockBlacklistDefaults.kt` 的 29 项默认名单；保存后以所选名单为准，显式清空不会恢复默认。日常读写由 `Prefs` 转发至 `DockBlacklistPreferences`。

## 5. 音乐、歌词和权限

### 5.1 当前模块关系

- 音乐卡通过 `MediaBridge` 获取活跃媒体会话，通知使用权可作为媒体信息的补充来源。每轮对候选会话各采样一次播放状态和元数据，选取最高分，同分优先保留当前会话。
- 音乐卡的封面按短边居中裁切，以抗锯齿圆弧绘制；封面和音符占位共用随封面尺寸缩放的圆角，封面占位大小不变。歌名和歌手各一行。文字未超出宽度时静止显示；超出时在卡片内左右来回滚动，首尾停顿单独计时，不随系统动画倍率缩短。`Prefs.musicScrollHoldMs` 默认 2 秒，范围 0–5 秒、0.1 秒步进，可在外观与风格的「音乐信息」中调整。实现位于 `ui/DockItemViews.kt` 的 `DockMusicView`。
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

