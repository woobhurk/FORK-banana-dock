# 日志接收服务

服务使用 Python 标准库接收 JSON 日志。带有效 `appId` 的记录写入 `logs/<appId>/YYYY-MM-DD.jsonl`，其余写入 `logs/YYYY-MM-DD.jsonl`。Banana Dock 上传时省略 `appId`。

## 本地运行

```bash
PORT=8080 python server.py
```

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | 任意路径（如 `/upload`） | 接收日志并返回落盘文件名 |
| GET | `/` | 日志查看页，支持应用筛选，每 30 秒刷新 |
| GET | `/health` | 健康检查，包含应用列表 |
| GET | `/apps` | 已接入的应用及记录数 |
| GET | `/logs` | 返回 JSON，默认最近 200 条，可传 `date`、`app`、`limit`。正数 `limit` 最大 5000，`0` 返回全部匹配记录 |
| GET | `/download` | 下载文本日志，可传 `date` 和 `app` |

`date=YYYY-MM-DD` 匹配该日主文件及所有分片，`date=YYYY-MM-DD.2` 可指定分片。`app` 按应用标识筛选。

## 部署

- 运行 `python server.py`，将脚本所在目录的 `logs/` 挂载为持久化目录。
- 服务绑定 `0.0.0.0`，从 `PORT` 读取端口，默认 8080。
- 服务端未实现鉴权。查看、查询和下载接口可读取上传内容，部署时应限制读写访问。

## 车机侧配置

在 Banana Dock 的「诊断与日志 → 高级排查 → 上传设置」填写接收地址，例如部署地址加 `/upload`。保存后在「诊断与日志」点「测试上传」，再查看服务端记录。其他应用见[客户端接入指南](INTEGRATE.md)。

## 容量和保留

| 配置 | 默认 | 作用 |
| --- | --- | --- |
| `LOG_KEEP_DAYS` | 30 天 | 启动时和跨天后首次写入时清理过期日志，`0` 表示全保留 |
| `LOG_SPLIT_BYTES` | 16 MiB | 文件达到阈值后，下一条记录续写到 `.1.jsonl`、`.2.jsonl` 等分片 |

服务端每次最多读取 8 MiB 请求正文。Banana Dock 和 `LogKit` 对超长日志正文保留合计最多 22,000 个原文字符，另插入省略标记。
