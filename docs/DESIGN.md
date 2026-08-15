# SyncClipboard 服务端设计(草案 v1)

> 目标:自建剪贴板同步服务,统一对接 **Android 应用**、**WinUI3 桌面端**、**Web 管理页**。
> 同步模型:共享剪贴板(单一最新内容,含文本/图片);实时推送 + HTTP 兜底。

---

## 1. 总体架构

```
┌─────────────┐   HTTPS REST + WebSocket(SignalR)   ┌─────────────────────┐
│ Android App │◄───────────────────────────────────►│                     │
│  (Kotlin)   │                                     │  SyncClipboard       │
├─────────────┤◄───────────────────────────────────►│  Server             │
│ WinUI3 桌面 │                                     │  (ASP.NET Core 9)   │
│  (.NET 9)   │                                     │  ─────────────────  │
├─────────────┤◄───────────────────────────────────►│  • REST API         │
│ Web 管理页  │   浏览器(SignalR JS + fetch)         │  • SignalR Hub      │
└─────────────┘                                     │  • Web 管理页(静态)  │
                                                    └──────────┬──────────┘
                                                               │
                                                    ┌──────────▼──────────┐
                                                    │  SQLite(元数据)      │
                                                    │  data/images(图片)   │
                                                    └─────────────────────┘
```

- **技术栈**:ASP.NET Core 9、EF Core + SQLite、SignalR。
- **部署**:Docker,端口 5033(延续),数据目录挂载 volume。

## 2. 数据模型

### ClipboardEntry(剪贴板条目)

| 字段 | 类型 | 说明 |
|---|---|---|
| Id | long | 自增主键 |
| Type | string | `Text` / `Image` |
| Text | string? | 文本内容(Type=Text) |
| ImageRef | string? | 图片存储引用(Type=Image,如 `img/20260815/xxx.png`) |
| ContentHash | string | 内容指纹(去重:同内容不上传不推送) |
| DeviceId | string | 来源设备唯一 id |
| DeviceName | string? | 来源设备显示名(如 "Xiaomi 15 Pro") |
| CreatedAt | DateTime | 创建时间(UTC) |

- **"当前剪贴板" = CreatedAt 最新的条目**(共享模型,无需额外状态表)。
- 历史保留:服务端留存最近 N 条(默认 1000,可配)。
- 图片文件存磁盘,`data/images/{yyyyMMdd}/{guid}.png`,元数据引用相对路径。

## 3. API 规范

认证:所有 `/api` 请求需携带 `Authorization: Bearer <token>`(appsettings 配置共享 token)。

### 3.1 剪贴板

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/clipboard` | 获取当前剪贴板。无内容返回 `204`;有内容返回条目 JSON |
| PUT | `/api/clipboard` | 上传**文本**剪贴板。Body:{`type`,`text`,`deviceId`,`deviceName`}。内容与当前相同(按 hash)返回 `unchanged:true`,否则广播新条目 |
| POST | `/api/clipboard/image` | 上传**图片**剪贴板(multipart/form-data,字段 `file` + `deviceId`/`deviceName`) |
| GET | `/api/clipboard/history?offset=&limit=` | 历史条目列表(新→旧){`items`,`total`} |
| GET/DELETE | `/api/clipboard/{id}` | 按 id 获取条目 / 删除 |
| DELETE | `/api/clipboard/history` | 清空全部历史(含图片文件),广播 `ClipboardCleared` |
| GET | `/api/images/{ref}` | 图片二进制(Content-Type 按扩展名) |

条目 JSON:

```json
{
  "id": 42,
  "type": "Text",
  "text": "hello",
  "imageRef": null,
  "deviceId": "a1b2...",
  "deviceName": "Xiaomi 15 Pro",
  "createdAt": "2026-08-15T12:00:00Z"
}
```

### 3.2 实时推送(SignalR)

- Hub 路径:`/hubs/clipboard`,需带 token(query `access_token` 或 header,SignalR 标准做法);建议带 `?deviceId=` 用于设备在线登记(连接存活期间每 45s 心跳刷新)。
- 事件:
  - `ClipboardUpdated(entry)`:某设备上传新剪贴板后广播(客户端按 deviceId 忽略自身回显)。
  - `ClipboardCleared()`:清空剪贴板事件。
- 推送失败兜底:各端在收到推送前,也可按自己的节奏调 `GET /api/clipboard` 校准。

### 3.3 兼容旧协议(过渡)

- `GET/PUT /SyncClipboard.json`:保留,映射到当前剪贴板(仅文本),兼容旧 AutoX 脚本。

## 4. 存储与配置

```json
// appsettings.json
{
  "AppSettings": {
    "AuthToken": "change-me",
    "MaxHistoryCount": 1000,
    "ImageStoragePath": "data/images",
    "DatabasePath": "data/syncclipboard.db",
    "MaxImageSizeBytes": 10485760,
    "OnlineThresholdSeconds": 120
  }
}
```

- SQLite(EF Core)存条目;图片文件存磁盘;两者都在 `data/` 下。
- 历史上限:超限清理最旧条目并删除其图片文件。

## 5. 各端对接要点

| 端 | 对接方式 |
|---|---|
| **Android(Kotlin)** | OkHttp 调 REST(token);SignalR Java 客户端订阅推送;上传文本 PUT,图片 multipart POST |
| **WinUI3(.NET 9)** | `Microsoft.AspNetCore.SignalR.Client`;REST 用 HttpClient |
| **Web 管理页** | `@microsoft/signalr` JS 客户端 + fetch |

## 6. 实现里程碑

1. **M1 骨架**:项目 + EF Core SQLite + 认证中间件 + REST(文本上传/下载/历史)。
2. **M2 图片**:multipart 上传 + 磁盘存储 + 图片 GET + 历史上限清理。
3. **M3 实时**:SignalR Hub + 广播 + token 认证;静态托管 Web 管理页。
4. **M4 兼容与加固**:保留 `/SyncClipboard.json`;限流、日志、健康检查(`/health`)。
5. **M5 端对接**:Android 同步模块、WinUI3 切换新协议。
