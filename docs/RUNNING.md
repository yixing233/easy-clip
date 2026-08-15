# 运行说明

## 服务端(server-node/ · Node.js 版,推荐)

> 2026-08-15 起服务端由 ASP.NET Core 重写为 Node.js(TypeScript),API 契约与 SignalR 线协议完全兼容,
> Web / WinUI3 桌面端 / Android 三个客户端零改动。原 `server/`(.NET)保留作回退备份。

```powershell
cd server-node
npm install
npm run build
node dist/server.js   # 默认 :5033
```

- 配置 `server-node/config.json`(AuthToken / 端口 / 数据库路径 / 历史上限 / 图片目录 / webDist)。
- 环境变量覆盖:`SC_PORT` `SC_AUTH_TOKEN` `SC_DB_PATH` `SC_IMAGE_PATH` `SC_MAX_HISTORY`
  `SC_MAX_IMAGE_BYTES` `SC_ONLINE_THRESHOLD_SECONDS` `SC_WEB_DIST`。
- 数据库直接复用 `server/data/syncclipboard.db`(表结构与 EF Core 生成一致,数据零迁移)。
- 依赖仅 `ws`;SQLite 用 Node 内置 `node:sqlite`(Node >= 22.5,推荐 24+)。
- 实时推送:实现 ASP.NET Core SignalR JSON 线协议(negotiate + WebSocket + 0x1E 消息分隔符),
  支持 `ClipboardUpdated` / `ClipboardCleared` 与按 deviceId 定向推送;传输仅 WebSocket。

## 服务端(server/ · .NET 旧版,仅作备份)

```powershell
cd server
dotnet build
dotnet run --urls http://*:5033
```

- 端口 5033;Web 管理页由服务端托管(`web/dist` 或 `wwwroot`,非 API 路径回退 SPA)。
- 配置 `server/appsettings.json` → `AppSettings`:
  - `AuthToken`:访问令牌(所有端共用,默认 `clipsync-demo-token`,上线前务必修改)
  - `MaxHistoryCount`:历史上限(默认 1000)
  - `DatabasePath` / `ImageStoragePath`:SQLite 库与图片目录(`data/`)
  - `OnlineThresholdSeconds`:在线判定阈值(120s;hub 连接存活时每 45s 刷新心跳)

## API(全部需 `Authorization: Bearer <token>`)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/clipboard | 当前剪贴板(204=空) |
| PUT | /api/clipboard | 上传文本;同内容返回 `unchanged:true` |
| POST | /api/clipboard/image | 上传图片(multipart) |
| GET | /api/clipboard/history?offset=&limit= | 历史分页 |
| GET/DELETE | /api/clipboard/{id} | 单条 / 删除 |
| DELETE | /api/clipboard/history | 清空历史(广播 ClipboardCleared) |
| GET | /api/images/{ref} | 图片二进制 |
| GET | /api/devices + PUT/DELETE /api/devices/{id} | 设备列表/重命名/移除 |
| GET | /api/stats · /api/activities · /api/health | 统计/活动/健康 |
| POST | /api/clipboard/send | 发送到指定设备:写入共享剪贴板,实时通知只推送给 deviceIds 目标(未指定则广播全员) |
| GET/PUT | /SyncClipboard.json | 旧协议兼容 |

## 实时推送

- Hub:`/hubs/clipboard`,认证 `?access_token=` 或 Bearer;建议带 `?deviceId=` 登记在线。
- 事件:`ClipboardUpdated(entry)`、`ClipboardCleared()`。
- 回显抑制:推送含来源 deviceId,各端忽略自身。

## 客户端

| 端 | 目录 | 说明 |
|---|---|---|
| Web | web/ | Vite + React 19 + antd 6;dev `npx vite --port 5173 --host`(代理 →5033) |
| Windows | desktop/ | WinUI3;联调 harness 见 tmp/deskharness |
| Android | android/ | miuix + LSPosed 模块;服务器地址/令牌在设置页 |
