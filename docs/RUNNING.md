# 运行说明

## 服务端(server/)

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
