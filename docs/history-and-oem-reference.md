# 历史交接与 oneOS 厂商参考

> 本文迁移自原 `AGENTS.md` 的历史交接、资料来源和厂商接口附录。拆分日期：2026-11-18；原汇总基线为 2026-11-12 的设置页重构（`c79db57`），后续章节曾分别更新，本次不将该基线视为当前代码状态。
>
> 以下接口、ID、区域、权限和布局描述来自历史记录或厂商 APK 静态分析，未逐项重新验证，不代表本应用获得系统权限或真实车辆效果。当前行为以源码、构建配置和设备证据为准。文中的外部绝对路径仅用于历史定位，不是仓库依赖。

协作规约见 [AGENTS.md](../AGENTS.md)；当前实现入口见 [项目实现与源码索引](project-reference.md)；车控编码和安全边界见 [车控与诊断](vehicle-and-diagnostics.md#2-车控接口和安全边界)。

按需导航：[历史交接](#1-历史车控和逆向参考历史不是当前实现) · [资料来源](#2-文档来源与拆分归属) · [厂商接口附录](#附录-aoneos-厂商接口与静态索引历史)。

## 1. 历史车控和逆向参考（历史，不是当前实现）

### 1.1 厂商接口契约

原 `反编译参考.md` 的来源是用户提供的 oneOS 系统 APK（`oneOS_Dock`、`oneOS_Hvac`、`oneOS_Launcher3`），产出时间约为 2026-10-15。它用于理解厂商接口，不替代当前源码。

从该资料保留的稳定要点：

- `ICar.getICarFunction()`、`getSensorManager()`、`getCarInfoManager()` 是厂商入口；`ICarFunction` 同时存在无 zone 和带 zone 的 int 读写方法，以及 Customize float 读写方法。
- watcher 必须按精确方法名分派：`onFunctionChanged`、`onFunctionValueChanged`、`onCustomizeFunctionValueChanged`、`onSupportedFunctionStatusChanged`、`onSupportedFunctionValueChanged` 的参数个数/类型不同，不能用 `contains("Changed")` 猜参数。
- `FunctionStatus` 应按 `name()` 识别 `notactive`、`error`、`notavailable`、`available`，不要依赖 ordinal。
- 系统 Dock 的区域分工：风量/风向使用 `ZONE_ROW_1_ALL = 0x8`；温度/座椅使用主驾 1、副驾 4；整车级使用 `0x80000000`。
- 通用无效值为 `0xfd`、`0xfe`、`0xff`；它们与某些外部设备日志里的 10/11 开关编码不是同一层。
- 温度理论范围为 15.5..28.5℃、步进 0.5℃；但当前车型是否真的返回有效浮点值仍需当前日志/真车确认。
- 座椅通风、加热、按摩的官方功能号分别包括 `0x10050100`、`0x10050200`、`0x10050700`；早期项目曾整体错位一档，不能复用旧值。

### 1.2 oneOS 空调和 Dock 跳转

- `com.geely.hvac` 的主面板是 `GlyMainActivity`，`enter_hvac_page=0` 表示空调主面板，`1` 表示座椅页；`SeatSetDetailActivity` 是可选的独立座椅页。
- Provider `com.geely.hvac` 的 `projection[0]` 可为 `open`、`show`、`close`、`getVisibility`，但当前项目的目标 Activity 路径通常优先命中，Provider 是备用路径。
- oneOS 系统应用拥有平台级权限；普通应用不能把这些权限当作自己的能力，只能通过当前允许的反射/跳转/系统授权路径工作。
- 原厂布局使用数字 itemId、区域、位置、图标、是否可移动、初始值和启动包名等字段；当前项目已经改为 `DockEntry`/`Builtin` 模型，不应把原厂 JSON 当作当前持久化格式。
- 原厂车控 itemId 的历史映射包括：AUTO `400002`、前/后除霜 `400003/400004`、AUTO 风量 `400005`、循环 `400006`、座椅加热/通风 `400009..400012`、主驾温度 `400013`、双温 `400015`、手动风量 `400018`、风向 `400019`、AC `400020`、方向盘加热 `400022`、座椅入口 `400034`。这些是逆向索引，不是当前 `DockEntry` action 名。

### 1.3 历史 Handoff 中的用户约束和已知坑

以下内容来自 2026-10 的旧交接，保留为背景并已按当前代码纠偏：

- 不随意更改包名、签名、车控能力、实验开关和用户排序；直接使用正式包覆盖测试环境，不额外创造不同包名的测试应用。
- 真实设备操作应保持只读诊断原则：不替用户 tap、swipe、解锁或修改系统设置；用户授权的 APK 安装和日志读取另行执行。
- 早期 1.4.x 版本曾出现 native 崩溃、温度通道和座椅 functionId/zone 配置错误；这些版本的修复时间线不能当作当前 bug 状态。
- 早期记录把内外循环写成 zone 8、把前挡写成 `0x10040100` 或 `0x10040200` 的混合版本，也出现过 10/11 开关值和 `getCustomizeFunctionValue` 是否可用的相互冲突。当前行为以[车控接口和安全边界](vehicle-and-diagnostics.md#2-车控接口和安全边界)及源码为准。
- 旧文档中“当前遗留问题”包括前后挡/方向盘加热真车效果、AQI 值域、内外循环回读、座椅记忆 `0x2d400200`、极速降温组合动作和歌词兼容性；除非有新日志，不要把它们改写成已解决或仍必现。
- “极速降温”没有独立信号时只能作为 AC 开、风量最大、温度最低的组合动作假设；不能声称有单一车控通道。
- 外部资料曾放在用户桌面/工作区的绝对路径下；这些路径不是仓库依赖，换机器后不要假设存在。

### 1.4 历史原始参考表与资料定位

下面的附录保留原 `反编译参考.md` 的非重复静态接口、ID、区域和布局结论，便于离线查找；它们是厂商 APK 静态分析结果，未逐项重新验证。与本项目现状冲突时，以[车控接口和安全边界](vehicle-and-diagnostics.md#2-车控接口和安全边界)及当前代码为准。

外部原始素材曾位于 `C:/Users/17900/Desktop/DBPLAY改/车机系统app/`；Dock smali 曾位于 `C:/Users/17900/Documents/MuMuSharedFolder/oneOS_Dock/oneOS_Dock_src/`，Hvac 解包目录曾位于 `C:/Users/17900/WorkBuddy/2026-10-15-20-30-09/work/apkdec/s_oneOS_Hvac/`。桌面 `DBPLAY改/研究结果/` 下另有 Dock/桌面/车辆信号和空调研究报告，`饺子互联/DOCCCK/` 下曾有三张弹窗设计图。这些都只是历史定位线索，不是构建依赖，也不保证本机存在。

原“改造说明”描述的是歌词融合和条目驱动 Dock 的早期迁移。当前保留的模块包括 `media/LrcParser.kt`、`media/LrcFinder.kt`、`media/LyricDownloader.kt`、`media/LyricMonitor.kt`、`ui/LyricOverlayService.kt`、`ui/state/DockStateMachine.kt`；旧 `DockEntryViewFactory.kt` 已由 `dock/` 控件模块替代。不要按旧的“17 个默认控件”“上下移动排序”恢复已被十项默认布局和拖拽编辑器替代的功能形态。


## 2. 文档来源与拆分归属

原 `AGENTS.md` 曾吸收以下根目录资料的有效内容，并将旧版本号、APK 名称、工程路径和未经验证的当前性声明标为历史；这些文件名是来源记录，不是仍存在的阅读入口：

- `CURRENT_STATUS.md`：当前状态、默认布局、设置页、日志与模糊入口。
- `HANDOFF.md`：车控接口、崩溃时间线、日志上传、安全模式和用户约束。
- `HANDOVER.md`：更早的车控实测、UI 规范、设备诊断和遗留项。
- `反编译参考.md`：oneOS/ECarX/HVAC/Dock 的逆向接口、区域、功能号和跳转参考。
- `改造说明.md`：歌词悬浮窗、条目驱动 Dock、渐变/模糊入口和安装权限背景。

本次将原第 1、2、4、5.1～5.5、7.1～7.2 节迁往[项目实现与源码索引](project-reference.md)，原第 3 节迁往[构建与发布](build-and-release.md)，原第 5.6～5.9、6、7.3、8、10 节迁往[车控与诊断](vehicle-and-diagnostics.md)，原第 9、11 节和附录 A 留在本文。协作规约及证据要求仍由根目录 `AGENTS.md` 维护；历史补充注明日期、来源和验证状态，不混入当前实现摘要。


## 附录 A：oneOS 厂商接口与静态索引（历史）

> 以下从原 `反编译参考.md` 迁入，来源为 2026-10-15 的厂商 APK 静态分析，不是当前项目源码描述，也不代表第三方 App 获得系统权限。凡文中“官方”“权威”“已修正”等词仅指该次历史材料。与[车控接口和安全边界](vehicle-and-diagnostics.md#2-车控接口和安全边界)冲突时，以当前可执行代码为准。

> 特别纠偏：系统 App 的 Customize 浮点温度示例不能证明普通 App 安全；AUTO 实际使用 Controller 的乱序映射；两参 `setFunctionValue` 的第二参是 value；`0x7d8` 只记录原厂使用的窗口值，不能说与本项目 `TYPE_APPLICATION_OVERLAY` 数值相等或权限等价。

### A.1 ECarX adaptapi 权威接口签名

**来源**：`oneOS_Dock` → `com.geely.dockbar.domain.vehicleusecase.SignalHelper2`
（该类持有 `ICar mCar` / `ICarFunction mCarFunction` / `ISensor mSensorManager` 三个字段）

```java
// 入口
Car.create(Context)                 → ICar
ICar.getICarFunction()              → ICarFunction
ICar.getSensorManager()             → ISensor
ICar.getCarInfoManager()            → ICarInfo

// ICarFunction
int     getFunctionValue(int functionId)
int     getFunctionValue(int functionId, int areaId)
float   getCustomizeFunctionValue(int functionId)
float   getCustomizeFunctionValue(int functionId, int areaId)
boolean setFunctionValue(int functionId, int value)          // ★ 2 参：第二参是 value，不是 zone！
boolean setFunctionValue(int functionId, int areaId, int value)
boolean setCustomizeFunctionValue(int functionId, float value)
boolean setCustomizeFunctionValue(int functionId, int areaId, float value)
int[]   getSupportedFunctionValue(int functionId)
FunctionStatus isFunctionSupported(int functionId) / (int, int)
boolean registerFunctionValueWatcher(int[] functionIds, ICarFunction.IFunctionValueWatcher)  // ★ 只有数组模式
boolean unregisterFunctionValueWatcher(ICarFunction.IFunctionValueWatcher)

// 异步连接（可选；系统 App 两种写法都存在）
ICar implements IConnectable
  IConnectable.registerConnectWatcher(IConnectable.IConnectWatcher)
  IConnectable.connect()
```

> 注：`AdaptapiHelper` 走 `registerConnectWatcher` + `connect()` 回调后再用；
> `SignalHelper2` 则直接 `Car.create()` 后立即取 `getICarFunction()`。两种都可行。

#### A.1.1 IFunctionValueWatcher 回调契约（必须按名精确分派）

```java
void onFunctionChanged(int id)
void onFunctionValueChanged(int id, int area, int val)                    // int 通道
void onCustomizeFunctionValueChanged(int id, int area, float val)
void onSupportedFunctionStatusChanged(int id, int area, FunctionStatus st)
void onSupportedFunctionValueChanged(int id, int[] ints)
```

> ⚠️ 反面教材：CarDockBar 早期用 `method.name.contains("Changed")` 猜回调，然后取
> `args[0]=fid / args[1]=zone / args[2]=value`。这个写法对 5 个方法里有 2 个是错的
> （`onFunctionChanged` 只有 1 个参数；`onSupportedFunctionValueChanged` 第二参是 `int[]`）。
> **必须按方法名精确匹配。**

#### A.1.2 FunctionStatus 枚举

`notactive` / `error` / `notavailable` / `available`（用 `name()` 判断，别依赖 ordinal）

---

### A.2 官方属性 ID 全表（`com.geely.os.car.GlyCarPropertyIds`）

**来源**：`oneOS_Hvac` → `smali/com/geely/os/car/GlyCarPropertyIds.smali`（共 156 条 HVAC 项）

#### A.2.1 空调（HVAC_FUNC\_*）

| 常量                                     | 值            | 说明                     |
| -------------------------------------- | ------------ | ---------------------- |
| `HVAC_FUNC_POWER`                      | `0x10010100` | 空调总开关                  |
| `HVAC_FUNC_AUTO`                       | `0x10010200` | AUTO                   |
| `HVAC_FUNC_AC`                         | `0x10010300` | A/C 压缩机                |
| `HVAC_FUNC_AC_MAX`                     | `0x10010400` | MAX A/C                |
| `HVAC_FUNC_CLIMATE_ZONE`               | `0x10010500` | 分区                     |
| `HVAC_FUNC_POWER_VR`                   | `0x10011100` | 语音开关                   |
| `HVAC_FUNC_FAN_SPEED`                  | `0x10020100` | **风量（手动）**             |
| `HVAC_FUNC_AUTO_FAN_SETTING`           | `0x10020200` | **风量（自动）**             |
| `HVAC_FUNC_CIRCULATION`                | `0x10030100` | **内外循环**               |
| `HVAC_FUNC_CIRCULATION_TIMER`          | `0x10030200` | 循环定时                   |
| `HVAC_FUNC_CIRCULATION_LONG_TOUCH`     | `0x10030300` | 长按循环                   |
| `HVAC_FUNC_DEFROST_FRONT`              | `0x10040100` | 前除霜                    |
| `HVAC_FUNC_DEFROST_FRONT_MAX`          | `0x10040200` | 前除霜最大                  |
| `HVAC_FUNC_DEFROST_REAR`               | `0x10040300` | 后除霜                    |
| `HVAC_FUNC_AUTO_DEFROST_FRONT`         | `0x10040500` | 自动前除霜                  |
| `HVAC_FUNC_AUTO_DEFROST_REAR`          | `0x10040400` | 自动后除霜                  |
| `HVAC_FUNC_TEMP`                       | `0x10060100` | **温度（float ℃）**        |
| `HVAC_FUNC_TEMP_MAX`                   | `0x10060200` | 温度上限                   |
| `HVAC_FUNC_TEMP_MIN`                   | `0x10060300` | 温度下限                   |
| `HVAC_FUNC_TEMP_STEP`                  | `0x10060400` | 温度步进                   |
| `HVAC_FUNC_TEMP_DUAL`                  | `0x10060500` | 主副驾同步                  |
| `HVAC_FUNC_TEMP_UNIT`                  | `0x10060600` | 温度单位（`0x10060602` = ℉） |
| `HVAC_FUNC_BLOWING_MODE`               | `0x10070100` | **风向（位掩码 1..7）**       |
| `HVAC_FUNC_SWEEPING_MODE`              | `0x10070200` | 扫风                     |
| `HVAC_FUNC_DIRECTION_MODE`             | `0x10070300` | 风向模式                   |
| `HVAC_FUNC_ECO_SWITCH`                 | `0x10080100` | ECO                    |
| `HVAC_FUNC_IONS_SWITCH`                | `0x10080500` | 负离子                    |
| `HVAC_FUNC_INTELLIGENT_DEODORIZATION`  | `0x10080600` | 智能除味                   |
| `HVAC_FUNC_STEERING_WHEEL_HEAT`        | `0x10090100` | 方向盘加热                  |
| `HVAC_FUNC_AUTO_STEERING_WHEEL_HEAT`   | `0x10090200` | 自动方向盘加热                |
| `HVAC_FUNC_PRE_CLIMATISATION`          | `0x100a0100` | 预空调                    |
| `HVAC_FUNC_POST_CLIMATISATION`         | `0x100a0200` | 后空调                    |
| `HVAC_FUNC_AI_POWER`                   | `0x100a0400` | AI 空调                  |
| `HVAC_FUNC_AIR_FRAGRANCE`              | `0x100b0100` | 香氛                     |
| `HVAC_FUNC_AIR_FRAGRANCE_TYPE`         | `0x100b0200` | 香氛类型                   |
| `HVAC_FUNC_AIR_FRAGRANCE_LEVEL`        | `0x100b0300` | 香氛浓度                   |
| `HVAC_FUNC_AIR_FRAGRANCE_SLOT`         | `0x100b0400` | 香氛槽位                   |
| `HVAC_FUNC_AIR_FRAGRANCE_TYPE_ID`      | `0x100b0500` | 香氛类型 ID                |
| `HVAC_FUNC_AUTO_ION`                   | `0x100c0100` | 自动负离子                  |
| `HVAC_FUNC_CLIMATE_LOCK`               | `0x10100200` | 空调锁定                   |
| `HVAC_FUNC_G_CLEAN`                    | `0x10100400` | G-Clean                |
| `HVAC_FUNC_VENTILATION_ONTIME`         | `0x10100700` | 通风时长                   |
| `HVAC_FUNC_INTELLIGENT_RECOMMENDATION` | `0x10120100` | 智能推荐                   |
| `HVAC_FUNC_ELECTRICAL_AIR_VENT`        | `0x10140100` | 电动出风口                  |
| `HVAC_FUNC_FILTER_ELEMENT_LIFE`        | `0x10140300` | 滤芯寿命                   |
| `FUNC_UNIT_TEMPERATURE`                | `0x25010300` | 温度单位（通用）               |

#### A.2.2 座椅（★ 关键修正）

| 常量                                     | 值                | 说明     |
| -------------------------------------- | ---------------- | ------ |
| `HVAC_FUNC_SEAT_VENTILATION`           | **`0x10050100`** | 座椅通风   |
| `HVAC_FUNC_SEAT_HEATING`               | **`0x10050200`** | 座椅加热   |
| `HVAC_FUNC_AUTO_SEAT_VENTILATION_TIME` | `0x10050400`     | 自动通风时长 |
| `HVAC_FUNC_AUTO_SEAT_HEATING`          | `0x10050500`     | 自动加热   |
| `HVAC_FUNC_AUTO_SEAT_HEATING_TIME`     | `0x10050600`     | 自动加热时长 |
| `HVAC_FUNC_SEAT_MASSAGE`               | `0x10050700`     | 座椅按摩   |
| `HVAC_FUNC_AUTO_SEAT_MASSAGE_TIME`     | `0x10050900`     | 自动按摩时长 |
| `HVAC_FUNC_SEAT_MASSAGE_SWITCH`        | `0x10050a00`     | 按摩开关   |
| `HVAC_FUNC_SEAT_MASSAGE_PROGRAM`       | `0x10050b00`     | 按摩程序   |

> ⚠️ **早期项目把通风写成 `0x10050000`、加热写成 `0x10050100`，两者整体错位一档，
> 导致座椅功能全部读写无效。已于 2026-10-15 修正。**

#### A.2.3 座椅位置 / 舒适（`SETTING_FUNC_*`，来自座椅调节模块）

`SETTING_FUNC_SEAT_BACKREST=0x2d030200`、`SEAT_CUSHION_TILT=0x2d030100`、`SEAT_HEIGHT=0x2d020200`、
`SEAT_LENGTH=0x2d020100`、`SEAT_LUMBAR_HEIGHT=0x2d060100`、`SEAT_LUMBAR_EXTENDED=0x2d060200`、
`SEAT_LEG_SUPPORT_HEIGHT=0x2d080100`、`SEAT_LEG_SUPPORT_LENGTH=0x2d080200`、
`SEAT_POSITION_SAVE=0x2d400100`、`SEAT_POSITION_SET=0x2d400200`、`SEAT_NUM=0x2d410f00`、
`SEAT_PHYSIOTHERAPY_SWITCH=0x2d500100`、`SEAT_PHYSIOTHERAPY_PROGRAM=0x2d500200`

---

### A.3 官方区号全表（`com.geely.os.car.GlyCarAreaId`）

**来源**：`oneOS_Hvac` → `smali/com/geely/os/car/GlyCarAreaId.smali`

#### A.3.1 座椅区（**温度 / 座椅 / 车门都用这一套位掩码**）

| 常量                  | 值       | 说明          |
| ------------------- | ------- | ----------- |
| `SEAT_ROW_1_LEFT`   | `0x1`   | **前左 = 主驾** |
| `SEAT_ROW_1_CENTER` | `0x2`   | 前中          |
| `SEAT_ROW_1_RIGHT`  | `0x4`   | **前右 = 副驾** |
| `SEAT_ROW_2_LEFT`   | `0x10`  | 后左          |
| `SEAT_ROW_2_CENTER` | `0x20`  | 后中          |
| `SEAT_ROW_2_RIGHT`  | `0x40`  | 后右          |
| `SEAT_ROW_3_LEFT`   | `0x100` | 三排左         |
| `SEAT_ROW_3_CENTER` | `0x200` | 三排中         |
| `SEAT_ROW_3_RIGHT`  | `0x400` | 三排右         |


#### A.3.2 分区（Zone）

| 常量                                     | 值                             |
| -------------------------------------- | ----------------------------- |
| `ZONE_ALL`                             | `-0x80000000`（即 `0x80000000`） |
| `ZONE_ROW_1_ALL`                       | `0x8` ← **风量 / 风向用这个**        |
| `ZONE_ROW_1_LEFT`                      | `0x1`                         |
| `ZONE_ROW_1_CENTER`                    | `0x2`                         |
| `ZONE_ROW_1_RIGHT`                     | `0x4`                         |
| `ZONE_ROW_2_ALL`                       | `0x80`                        |
| `ZONE_ROW_2_LEFT` / `CENTER` / `RIGHT` | `0x10` / `0x20` / `0x40`      |
| `ZONE_ROW_3_ALL`                       | `0x800`                       |
| `ZONE_ROOF_TOP`                        | `0x10000000`                  |

> ✅ **两套区号的分工已确认**：
>
> - **风量 / 风向** → `ZONE_ROW_1_ALL = 0x8`
> - **温度 / 座椅** → `SEAT_ROW_1_LEFT = 1`（主驾）、`SEAT_ROW_1_RIGHT = 4`（副驾）

#### A.3.3 其他

`DRIVER_SIDE_LEFT=0x100301`、`DRIVER_SIDE_RIGHT=0x100302`、
`DOOR_ROW_1_LEFT=0x1`…、`WINDOW_FRONT_WINDSHIELD=0x1`、`WINDOW_ROOF_TOP=0x4`、
`VEHICLE_AREA_TYPE_SEAT=0x3`、`VEHICLE_AREA_TYPE_DOOR=0x4`

---

### A.4 HVAC 取值编码（`com.geely.dockbar.domain.vehicleusecase.HvacConstants`）

**来源**：`oneOS_Dock` → `.../HvacConstants.smali`

#### A.4.1 通用哨兵 / 状态

| 常量                              | 值       | 含义 |
| ------------------------------- | ------- | -- |
| `FUNCTION_COMMON_VALUE_OFF`     | `0x0`   | 关  |
| `FUNCTION_COMMON_VALUE_ON`      | `0x1`   | 开  |
| `FUNCTION_COMMON_VALUE_ERROR`   | `0xfd`  | 错误 |
| `FUNCTION_COMMON_VALUE_NONE`    | `0xfe`  | 无  |
| `FUNCTION_COMMON_VALUE_UNKNOWN` | `0xff`  | 未知 |
| `STATUS_ERROR`                  | `-1`    |    |
| `STATUS_NOT_ACTIVE`             | `-2`    |    |
| `STATUS_ACTIVE`                 | `-3`    |    |
| `STATUS_NOT_AVAILABLE`          | `-0xff` |    |

> ⚠️ 读到 `0xfd / 0xfe / 0xff` 一律当「无有效读数」，**不要覆盖已有状态**。
> 注意这与第三方 ESP32 破解读到的 `power:11 / ac:10 / recirc:11` 是**不同层**的编码，
> `boolOf()` 两套都兼容即可。

#### A.4.2 温度

| 常量                          | 值                     |
| --------------------------- | --------------------- |
| `TEMP_C_MIN`                | `"15.5"` ← **可调下限 ℃** |
| `TEMP_C_MAX`                | `"28.5"` ← **可调上限 ℃** |
| `TEMP_F_MIN` / `TEMP_F_MAX` | `"59"` / `"85"`       |
| `MAX_SHOW_TAMP`             | `"HI"`                |
| `MIN_SHOW_TAMP`             | `"LO"`                |

**写入方式**：`setCustomizeFunctionValue(0x10060100, areaId, ℃ float)`，步进 **0.5℃**（华氏 1.0）。
**读取方式**：`getCustomizeFunctionValue(0x10060100, areaId)` → 直接是 ℃。

温度调节实证（`TempSignalClick.addLeftTemp`）：

```java
float cur = leftTempSignal.getValue();        // getCustomizeFunctionValue(0x10060100, 1)
float max = maxTempSignal.getValue();         // 上限
if (cur >= max) return;                       // 到顶不写
if (tempUnit == 0x10060602) cur += 1.0f;      // 华氏步进
else                        cur += 0.5f;      // 摄氏步进
leftTempSignal.setValue(cur);                 // setCustomizeFunctionValue(0x10060100, 1, cur)
```

#### A.4.3 风量

- **手动**：`HVAC_FUNC_FAN_SPEED (0x10020100)`，值 = `0x10020101 .. 0x10020109`（1..9 档），`0` = 关
- **自动**：`HVAC_FUNC_AUTO_FAN_SETTING (0x10020200)`，值 = `0x10020201 .. 0x10020205`（1..5 档）
- 字符串常量：`MANUAL_FAN_SPEED_LEVEL_1..9`、`MANUAL_FAN_SPEED_LEVEL_OFF="off"`、`MANUAL_FAN_SPEED_LEVEL_AUTO="10"`

#### A.4.4 座椅（`SeatFanSignalClick` 实证）

```java
// 通风 0x10050100：读回来的值就是 0x1005010X
switch (cur) {
  case 0x10050101: next = 0x10050102; break;
  case 0x10050102: next = 0x10050103; break;
  case 0x10050103: next = 0x10050101; break;   // 3 档再点回 1 档
  default:         next = 0x10050103;          // 0（关）→ 3 档
}
// 加热 0x10050200 同构
```

字符串常量：`SEAT_LEVEL_1="level1"`、`SEAT_LEVEL_2="level2"`、`SEAT_LEVEL_3="level3"`

#### A.4.5 内部信号 ID 映射（`VehicleSignalManager` 稀疏表）

Dock 内部把 `funcId` 转成自己的信号编号 `0x61axx`：

| funcId                                     | areaId        | 内部信号                              | 含义                           |
| ------------------------------------------ | ------------- | --------------------------------- | ---------------------------- |
| `0x10010100`                               | `-0x80000000` | `0x61a81`                         | HVAC_POWER                   |
| `0x10010200`                               | `-0x80000000` | `0x61a82`                         | HVAC_AUTO                    |
| `0x10010300`                               | —             | `0x61a94`                         | HVAC_AC_MODE                 |
| `0x10010400`                               | —             | `0x61a88`                         | HVAC_AC_MAX                  |
| `0x10020100`                               | `0x8`         | `0x61a92`                         | HVAC_FAN_SPEED               |
| `0x10020200`                               | `0x8`         | `0x61a85`                         | HVAC_AUTO_FAN_SPEED          |
| `0x10030100`                               | —             | `0x61a86`                         | HVAC_CYCLE_MODE              |
| `0x10040200`                               | —             | `0x61a83`                         | HVAC_DEFROST_FRONT           |
| `0x10040300`                               | —             | `0x61a84`                         | HVAC_DEFROST_REAR            |
| `0x10050100`                               | `1` / `4`     | `0x61a8a` / `0x61a8c`             | **LEFT / RIGHT VENTILATION** |
| `0x10050200`                               | `1` / `4`     | `0x61a89` / `0x61a8b`             | **LEFT / RIGHT HEAT**        |
| `0x10050700`                               | `1` / `4`     | `0x61a98` / `0x61a99`             | LEFT / RIGHT MASSAGE         |
| `0x10050a00`                               | `1` / `4`     | `0x61a9a` / `0x61a9b`             | MASSAGE_SWITCH               |
| `0x10060100`                               | `1` / `4`     | `0x61a8d` / `0x61a8e`             | **LEFT / RIGHT TEMP**        |
| `0x10060200`                               | —             | `0x61a90`                         | HVAC_MAX_TEMP                |
| `0x10060300`                               | —             | `0x61a91`                         | HVAC_MIN_TEMP                |
| `0x10060500`                               | —             | `0x61a8f`                         | HVAC_TEMP_DUAL_SYNC          |
| `0x10060600`                               | —             | `0x61a9c`                         | HVAC_TEMP_UNIT               |
| `0x10070100`                               | `0x8`         | `0x61a93`                         | HVAC_BLOWING_MODE            |
| `0x10080100`                               | —             | `0x61a87`                         | HVAC_ECO                     |
| `0x10090100`                               | —             | `0x61a96`                         | STEERING_WHEEL_HEAT          |
| `0x100b0100`                               | —             | `0x61a97`                         | HVAC_FRAGRANCE               |
| `0x23030100` / `0x23040100` / `0x23100100` | —             | `0x61a94` / `0x61a95` / `0x61a96` | 其他                           |

---

### A.5 oneOS_Hvac（`com.geely.hvac`）对外接口

#### A.5.1 导出组件（Manifest 实测）

| 类型       | 组件                                                       | 说明                             |
| -------- | -------------------------------------------------------- | ------------------------------ |
| Activity | `com.geely.hvac.activity.GlyMainActivity`                | **主面板**（空调 + 座椅 + 香氛）          |
| Activity | `com.geely.hvac.activity.SeatSetDetailActivity`          | **座椅设置详情页**（独立导出，可直达）          |
| Activity | `com.geely.hvac.activity.BuyFragranceActivity`           | 香氛购买                           |
| Provider | `com.geely.hvac.provider.HvacCommunicateContentProvider` | `authorities = com.geely.hvac` |

#### A.5.2 深链 extra

```java
intent.setComponent(new ComponentName("com.geely.hvac",
        "com.geely.hvac.activity.GlyMainActivity"));
intent.putExtra("enter_hvac_page", 0);   // 0=主空调页  1=座椅页  2=香氛页
```

> ✅ **官方实证**（`OpenAirSeatClickFunction.openAirSeat()`）：系统 Dock 的「座椅」按钮就是
> `GlyMainActivity` + `enter_hvac_page=1`。此处保留原厂行为，不能据此推断本项目当前调用路径。

#### A.5.3 ContentProvider 指令（`hvac/query`）

调用方式：`query(uri, projection, ...)`，**用 `projection[0]` 当指令**。

| 指令                | 行为                                   |
| ----------------- | ------------------------------------ |
| `"open"`          | 打开空调主面板（车机 launcher / widget 用的官方接口） |
| `"show"`          | 显示                                   |
| `"close"`         | 仅触发应用内关闭回调，对外部无实际作用                  |
| `"getVisibility"` | 返回 Cursor，首列 long：`1`=面板可见 / `0`=不可见 |

`insert / update / delete` 均为空实现。
另有静态方法 `setHvacCommunicateListener(...)` / `setAcVisibilityListener(...)` 供同进程或系统侧使用。

> 上述是原厂 APK 的调用契约；本项目当前路由以 `hvac/HvacApp.kt` 和 `dock/DockActions.kt` 为准。

#### A.5.4 权限

`com.geely.hvac` 声明了大量系统级权限（`WRITE_SECURE_SETTINGS`、`CAR_POWER`、
`GRANT_RUNTIME_PERMISSIONS`、`MANAGE_APP_OPS_MODES` 等），是平台签名系统应用。
**普通第三方 App 无法复用这些权限**，车控只能走 adaptapi 反射。

---

### A.6 oneOS_Dock 的 Dock 布局模型

#### A.6.1 预设布局文件（`assets/`）

| 文件                        | 车型         |
| ------------------------- | ---------- |
| `dockPresetData_CX.json`  | CX（51 项）   |
| `dockPresetData_CS.json`  | CS（50 项）   |
| `dockPresetData_DCY.json` | DCY（51 项）  |
| `Ex11VehicleConfig.json`  | EX11（59 项） |

#### A.6.2 布局项字段

```json
{
  "itemId": "400013",          // 控件编码；系统功能为数字，第三方 App 为包名
  "itemName": "主驾温度",
  "itemArea": "dock_main",     // dock_main | dock_hvac_dialog_csd | dock_app_dialog_csd
  "itemPosition": 1,
  "itemIcon": "ic_temp",
  "itemText": "温度",
  "canMove": false,            // 能否拖动
  "editMode": false,           // 是否编辑态可见
  "isSwitch": true,            // 开关型控件
  "extraVal": "26",            // 初始值（温度=℃ 数值；开关=off）
  "extraStatus": "off",
  "clickData": "com.autonavi.amapauto",   // 第三方 App 的启动包名
  "dialogPosId": 2
}
```

#### A.6.3 CX 车型默认 Dock 主栏（`dock_main`）

| pos | itemId | 名称    | 图标            | clickData               |
| --- | ------ | ----- | ------------- | ----------------------- |
| 0   | 105    | MyCar | ic_mycar      | —                       |
| 1   | 400013 | 主驾温度  | ic_temp       | — (extraVal=26)         |
| 2   | 400034 | 座椅    | ic_snow       | —                       |
| 3   | 108    | 360全景 | ic_360        | —                       |
| 4   | 103    | 电话    | ic_phone      | —                       |
| 5   | 104    | 导航    | ic_navigation | `com.autonavi.amapauto` |
| 6   | 102    | 资源库   | all_app       | `com.android.launcher3` |


#### A.6.4 空调浮窗（`dock_hvac_dialog_csd`，CX 14 项）

前除霜(400003)、主驾座椅加热(400009)、主驾座椅通风(400010)、副驾座椅加热(400011)、
副驾座椅通风(400012)、A/C(400020)、循环模式(400006)、方向盘加热(400022)、节能(400007)、
后除霜(400004)、香氛(400023)、同步(400015)、风向切换(400019)、自动(400002)

EX11 车型额外含：Max AC(400008)、主驾按摩(400024)、副驾按摩(400025)、风量(400005)

#### A.6.5 完整 itemId → 处理类映射（`AppClickManager`）

**系统功能码（100–146）**

| code | 处理类                        |   | code        | 处理类                          |
| ---- | -------------------------- | - | ----------- | ---------------------------- |
| 101  | HomeClickFunction          |   | 125         | CommonClickFunction（行车记录仪）   |
| 102  | AllAppClickFunction        |   | 126         | CommonClickFunction（自动泊车）    |
| 103  | PhoneClickFunction         |   | 129         | CommonClickFunction（智能生活）    |
| 104  | MapClickFunction           |   | 131         | SceneModeClickFunction（场景模式） |
| 105  | SettingsClickFunction      |   | 133         | CommonClickFunction（用户手册）    |
| 107  | CommonClickFunction（音量）    |   | 134         | CommonClickFunction（灯光秀）     |
| 108  | CommonClickFunction（360全景） |   | 135         | CarLinkFunction（手机互联）        |
| 110  | CommonClickFunction（图库）    |   | 136         | CommonClickFunction（记忆泊车）    |
| 111  | CommonClickFunction（多媒体）   |   | 146         | GlobalSearchFunction         |
| 112  | AirConditionClickFunction  |   | 118/123/124 | CommonClickFunction          |
| 113  | CommonClickFunction（天气）    |   | 108/126     | CommonClickFunction          |

**车控功能码（4000xx）**

| code   | 处理类                          | 对应通道                                      |
| ------ | ---------------------------- | ----------------------------------------- |
| 400001 | AcPowerSignalClick           | `0x10010100`                              |
| 400002 | AutoSwitchSignalClick        | `0x10010200`                              |
| 400003 | DefrostFrontSignalClick      | `0x10040100`                              |
| 400004 | DefrostRearSignalClick       | `0x10040300`                              |
| 400005 | AutoFanSpeedClick            | `0x10020200`                              |
| 400006 | RecycleSignalClick           | `0x10030100`                              |
| 400007 | EcoSignalClick               | `0x10080100`                              |
| 400008 | AcMaxSignalClick             | `0x10010400`                              |
| 400009 | SeatHeatSignalClick（主驾）      | `0x10050200` / area 1                     |
| 400010 | SeatFanSignalClick（主驾）       | `0x10050100` / area 1                     |
| 400011 | SeatHeatSignalClick（副驾）      | `0x10050200` / area 4                     |
| 400012 | SeatFanSignalClick（副驾）       | `0x10050100` / area 4                     |
| 400013 | TempSignalClick（主驾温度）        | `0x10060100` / area 1                     |
| 400015 | TempDualSignalClick          | `0x10060500`                              |
| 400018 | FanSpeedSignalClick          | `0x10020100`                              |
| 400019 | BolwingModeSignalClick       | `0x10070100`                              |
| 400020 | AcModeSignalClick            | `0x10010300`                              |
| 400022 | SteeringWheelHeatSignalClick | `0x10090100`                              |
| 400023 | AirFragranceSignalClick      | `0x100b0100`                              |
| 400024 | LeftMassageSignalClick       | `0x10050700` / area 1                     |
| 400025 | RightMassageSignalClick      | `0x10050700` / area 4                     |
| 400034 | OpenAirSeatClickFunction     | 跳 `GlyMainActivity` + `enter_hvac_page=1` |

#### A.6.6 第三方 App 包名清单（预设里出现的）

`com.autonavi.amapauto`（高德）、`com.android.launcher3`、`com.geely.appstore`、
`com.geely.calendar`、`com.geely.scene.mode`、`com.geely.dvr`、`com.geely.settings`、
`com.netease.cloudmusic.iot`、`com.thunder.carplay`（雷石 KTV）、`com.autoegg`（今日电台）、
`com.baidu.netdisk.car`、`com.bytedance.byteautoservice`（火山车娱）、
`com.tencent.qqlive.audiobox`（腾讯视频）、`com.bilibili.bilithings`、
`com.ximalaya.ting.android.car.ecarx`、`com.arcvideo.car.video`（爱奇艺）、
`io.dushu.car`（帆书）、`com.migu.miguplay`、`cn.cmvideo.car.play`、
`com.flyme.auto.music`（QQ音乐）、`com.flyme.auto.browser`、
`com.tal.xesvideoapp`、`com.ecarx.pas.apa`（自动泊车）

#### A.6.7 Dock 的窗口实现

- 窗口类型：`0x7d8`（对应 `WindowManager.LayoutParams` 的悬浮层；本项目窗口类型需另看 `DockOverlayService.kt`）
- 常量：`0x7d0`（SCREENSAVER）、`"DOCK_BAR"`、`"STATUS_BAR_POP"`
- 多屏 displayId：`DISPLAY_ID_CSD=0`、`DISPLAY_ID_3D_ENGINE=2`、`DISPLAY_ID_PSD=3`、
  `DISPLAY_ID_DIM=4`、`DISPLAY_ID_LEFT_WIN=7`、`DISPLAY_ID_RIGHT_WIN=8`、`DISPLAY_ID_FULL=0x400`
- 车控信号类都通过 `androidx.lifecycle.MutableLiveData` 派发

#### A.6.8 车控信号类清单（`domain/vehicleusecase/signal/`）

`AbsSignal`（基类，字段 `funcId / areaId / extra`）、
`AirFragranceSignal`、`AirFragranceTypeIdSignal`、`AutoFanSpeedSignal`、`AutoSwitchSignal`、
`BolwingModeSignal`、`CycleModeSignal`、`DefrostFrontSignal`、`DefrostRearSignal`、`EcoSignal`、
`FanSpeedSignal`、`HvacAcMaxSignal`、`HvacAcSignal`、`HvacPowerSignal`、
`LeftMassageSignal`、`LeftMassageSwitchSignal`、`LeftSeatFanSignal`、`LeftSeatHeatSignal`、`LeftTempSignal`、
`MaxTempSignal`、`MinTempSignal`、`RightMassageSignal`、`RightMassageSwitchSignal`、
`RightSeatFanSignal`、`RightSeatHeatSignal`、`RightTempSignal`、
`SeatIntSignal`、`SeatSignal`、`SteeringWheelHeatSignal`、`TempDualSignal`、`TempUnitSignal`

**读写分派规则**（`AbsSignal` 子类统一逻辑）：

```java
// 读
if (areaId == -1) value = vehicleController.getFunctionValue(funcId);
else              value = vehicleController.getFunctionValue(funcId, areaId);
// 温度类（LeftTemp/RightTemp/MinTemp/MaxTemp）改用：
//   getCustomizeFunctionValue(funcId[, areaId]) → float

// 写
if (areaId == -1) vehicleController.setFunctionValue(funcId, value);
else              vehicleController.setFunctionValue(funcId, areaId, value);
// 温度类：
//   setCustomizeFunctionValue(funcId, value) / (funcId, areaId, value)
```

---

### A.7 oneOS_Launcher3（`com.android.launcher3`）

- `sharedUserId = android.uid.system`（平台签名，普通 App 无法复用）
- 导出 Provider：
  - `com.android.launcher3.LauncherProvider` → `authorities = com.android.launcher3.settings`
  - `com.geely.model.provider.InteractiveContentProvider` → `authorities = com.android.launcher3.interactive`
- 导出 Activity：`com.geely.view.carlink.CarLinkStatusActivity`
- 导出 Receiver：`com.geely.TestReceiver`（`android.intent.action.launcher.test`）、
  `com.geely.view.common.receiver.UnInstallResultReceiver`

> 对 CarDockBar 的用途有限：主要确认了「资源库」入口是 `com.android.launcher3`。

---
