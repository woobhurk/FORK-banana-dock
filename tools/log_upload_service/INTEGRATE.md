# 接入指南：让其它应用也用这个日志服务器

本指南适用于其他 Android 应用和能发送 JSON POST 请求的客户端。

---

## 一、原理

服务端收到一条 JSON 后：

- 带有效 `appId` 的记录写入 `logs/<appId>/YYYY-MM-DD.jsonl`。
- 省略 `appId` 时写入 `logs/YYYY-MM-DD.jsonl`。Banana Dock 使用这条路径。

---

## 二、Android 应用接入（推荐）

### 1. 拷贝客户端文件

把 `LogKit.kt` 拷到你的项目：

```
app/src/main/java/com/car/logkit/LogKit.kt
```

**零第三方依赖**，只用 Android 框架 API + `org.json`，不需要改 build.gradle。

### 2. 加权限

`AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

> `ACCESS_NETWORK_STATE` 是可选的（用来判断有没有网），加上体验更稳。

### 3. 初始化

`Application.onCreate`：

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogKit.init(this, "mytool")   // ← 你的应用标识，每个应用必须不同
    }
}
```

**`appId` 规则**（服务端强校验，非法会被拒绝）：
- 字母或数字开头
- 只允许字母、数字、`.`、`_`、`-`
- 长度 ≤ 64

### 4. 打日志

```kotlin
LogKit.log("这是一条日志")                    // 攒批上传（1.5 秒或 40 行发一次）
LogKit.flushNow("手动上传")                    // 立刻发送当前缓冲
LogKit.uploadText("diag", "诊断信息")           // 上传一段指定文本
```

### 5. 崩溃时上传（可选但推荐）

```kotlin
Thread.setDefaultUncaughtExceptionHandler { thread, t ->
    LogKit.uploadCrash("主线程崩溃", t)   // 同步上传，尽量抢救
    // 然后交回默认处理（或自行结束进程）
}
```

> 注意：native 崩溃（SIGSEGV）Java 层收不到，需要像本项目那样用 NDK 信号处理器写文件、
> 下次启动再回捞。LogKit 本身不含 native 部分。

---

## 三、最简接入示例（不想拷整个文件时）

只要能发 HTTP POST 就行，5 行代码：

```kotlin
val payload = JSONObject().apply {
    put("appId", "mytool")        // ← 关键：应用标识
    put("kind", "live")
    put("device", "dev-001")
    put("app", "1.0(1)")
    put("time", "2026-11-02 17:00:00")
    put("log", "hello")
}.toString()

Thread {
    val c = URL("https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/upload")
        .openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    c.connectTimeout = 10000
    c.readTimeout = 10000
    OutputStreamWriter(c.outputStream, "UTF-8").use {
        it.write(payload); it.flush()
    }
    c.responseCode
}.start()
```

---

## 四、服务端接口

HTTP 接口、分片和保留规则见[日志接收服务说明](README.md#接口)。

### 查询示例

```bash
# 指定日期的全部记录
curl "https://.../logs?date=2026-11-02&limit=0"

# 只看某个应用
curl "https://.../logs?app=mytool"

# 某应用某天，拉 500 条
curl "https://.../logs?app=mytool&date=2026-11-02&limit=500"

# 有哪些应用接入
curl "https://.../apps"
```

---

## 五、容量与限制

`LogKit` 从收到首条待发日志起最多等 1.5 秒或等满 40 行，取出时以 8,000 字符为分批阈值。超长正文保留合计最多 22,000 个原文字符及省略标记。离线队列超过 512 KiB 后裁剪旧内容。服务端限制见[容量和保留](README.md#容量和保留)。

---

## 六、日志访问

服务端未实现鉴权。查看、查询和下载接口可读取上传内容，其中可能含设备标识、车型、App 版本及调用方提供的敏感信息。部署时应限制上传和读取的访问范围，上传前去除敏感内容。

---

## 七、常见问题

**Q：能更改 `appId` 吗？**
A：可以。更改后新日志写入新的应用目录，旧记录留在原目录。建议每个应用使用固定标识。

**Q：上传地址怎么换？**
A：`LogKit.serverUrl = "..."`，或改 `LogKit.kt` 里的默认值。

**Q：可以用明文 HTTP 吗？**
A：自建接收端使用明文 HTTP 时，接入的 Android 应用需在自身网络安全配置中允许该目标。以 API 28 或更高版本为目标的应用默认禁用明文流量。
