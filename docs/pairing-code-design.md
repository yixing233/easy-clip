# 设备配对码 + 设备专属 Token 改造方案(草案 v1)

> 目标:把当前"全局共享 Token"认证改为"**临时配对码 + 每设备独立 Token**"认证,
> 实现设备可单独吊销、新设备安全接入、泄漏最小影响面。
> 适用端:node 服务端 / C# 服务端 / 桌面端(WinUI3) / Android / Web 管理页 / 测试 mock。

---

## 1. 背景与问题

### 现状
- 认证:全部设备共享一个静态 Token(`clipsync-demo-token`,见 `server-node/config.json` / `server/appsettings.json`)。
- 认证方式:Bearer header 或 `?access_token=`,中间件比对固定值(`server-node/src/auth.ts` / `server/Auth/TokenAuthMiddleware.cs`)。
- 设备登记:SignalR 连接 URL 带 `deviceId` 或上传时 `touchDevice` 写入 `Devices` 表。

### 问题
| # | 问题 | 后果 |
|---|------|------|
| 1 | 无法单独吊销设备 | 设置页"移除设备"只是删列表记录,设备仍持有 Token 可继续访问 |
| 2 | 全局共享密钥 | 任一设备泄漏 = 全部设备暴露,只能全局换 Token 全员重配 |
| 3 | 新设备接入体验差 | 手动复制共享 Token,无法区分来源 |
| 4 | 无防爆破机制 | 无失败锁定/速率限制 |

---

## 2. 目标设计

### 2.1 总体流程

```
┌─────────┐   ① 管理员生成配对码(全局Token)     ┌──────────┐
│  管理端   │ ─────────────────────────────────→ │  服务端   │
│ (CLI/Web)│   POST /api/pairing-codes           │ 生成一次性 │
└─────────┘                                      │ 配对码     │
                                                 └────┬─────┘
┌─────────┐   ② 新设备输入 服务器地址+配对码        ┌────▼─────┐
│  新设备   │ ─────────────────────────────────→  │  服务端   │
│ (桌面/手机)│   POST /api/pair                     │ 校验码(未用/未过期) │
│         │   { pairingCode, deviceId, deviceName }│ 生成 deviceToken    │
│         │ ←──────────────────────────────────   │ 存入 Devices.Token  │
└─────────┘   ③ { deviceId, deviceToken }         └──────────┘
             ④ 之后所有请求 Bearer deviceToken
             ⑤ 移除设备 → DELETE /api/devices/{id} → Token 失效
```

### 2.2 核心概念
- **配对码(PairingCode)**:一次性、短时效(默认 10 分钟)、一设备一码。
- **设备 Token(DeviceToken)**:配对成功后签发,每设备唯一、长期有效,可单独吊销。
- **管理 Token(AdminToken)**:保留全局 Token 作为管理凭据(生成配对码、管理设备),仅限管理接口。

---

## 3. 服务端设计

### 3.1 数据模型

**node 端(`server-node/src/db.ts`)与 C# 端(`server/Models.cs`)同步:**

```sql
-- Devices 表新增列
ALTER TABLE Devices ADD COLUMN Token TEXT NULL;      -- 设备专属 Token(哈希存储)
ALTER TABLE Devices ADD COLUMN PairedAt TEXT NULL;   -- 配对时间

-- 新增 PairingCodes 表
CREATE TABLE IF NOT EXISTS PairingCodes (
    Code        TEXT PRIMARY KEY,     -- 配对码(明文存储,短时效)
    ExpiresAt   TEXT NOT NULL,        -- 过期时间(UTC ISO)
    UsedAt      TEXT NULL,            -- 使用时间;NULL=未使用
    UsedBy      TEXT NULL             -- 使用设备 ID
);
```

**模型类(node `db.ts` / C# `Models.cs`)**:
- `PairingCode { Code, ExpiresAt, UsedAt?, UsedBy? }`
- `Device` 增加 `Token? / PairedAt?`

### 3.2 新增接口

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | `/api/pairing-codes` | AdminToken | 生成配对码,返回 `{ code, expiresAt }` |
| POST | `/api/pair` | 无(配对本身) | 校验码→签发设备 Token |
| DELETE | `/api/pairing-codes/{code}` | AdminToken | 作废配对码(可选) |

**POST /api/pair 请求/响应**:
```json
// 请求
{ "pairingCode": "A3F9K2Q7", "deviceId": "test-desktop-0001", "deviceName": "XXCCBB" }
// 响应 200
{ "deviceId": "test-desktop-0001", "deviceToken": "a1b2c3...64hex" }
// 错误
400 { "error": "配对码无效或已过期" }
409 { "error": "该设备已配对" }
```

**服务端逻辑(node `service.ts` / C# `Services.cs`)**:
```ts
async pair(pairingCode, deviceId, deviceName) {
  const c = db.PairingCodes.find(p => p.Code === pairingCode);
  if (!c || c.ExpiresAt < now || c.UsedAt) throw PairCodeInvalid;
  c.UsedAt = now; c.UsedBy = deviceId;            // 标记已用(一码一设备)
  const token = randomHex(32);                      // 64 hex 字符
  db.Devices.upsert({ Id: deviceId, Name: deviceName, Token: sha256(token), PairedAt: now });
  return { deviceId, deviceToken: token };
}
```

### 3.3 认证中间件改造

**node `auth.ts` / C# `TokenAuthMiddleware.cs`:**

```ts
// 现行:token === cfg.authToken
// 改为两级:
function checkAuth(req, cfg, db): boolean {
  const token = extractToken(req);
  if (token === cfg.authToken) return true;          // ① 管理 Token(全接口,兼容期)
  const d = db.Devices.find(x => x.Token === sha256(token));
  if (d) return true;                                // ② 设备 Token(数据接口)
  return false;
}
// 例外:POST /api/pair 无需认证(配对本身);但可加 IP 限速
```

**SignalR 连接**(node `signalr.ts` / C# `ClipboardHub`):
- 连接 URL 传 `?access_token=deviceToken`(或 header),中间件同样两级校验。
- 连接时 `deviceId` 仍用于在线登记(已有逻辑不变)。

### 3.4 设备吊销
- 已有 `DELETE /api/devices/{id}`(node `service.ts` / C# `DevicesController`):改为**同时清除 Token 列**,设备立即失效。
- 认证中间件查不到 Token → 401。

---

## 4. 客户端设计

### 4.1 桌面端(WinUI3, `desktop/`)

**设置页(`Views/SettingsPage.xaml` + `ViewModels/SettingsViewModel.cs`)**:
- "服务端连接"卡片改为:
  - 服务器地址(不变)
  - **配对码输入框**(替换"访问令牌"输入框)
  - "配对"按钮 → `POST /api/pair` → 成功保存 `deviceToken` 到 SettingsStore
  - 状态提示:配对成功/失败原因
- 保存后自动重连(`Engine.ReconfigureAsync`)

**SettingsStore(`Services/SettingsStore.cs`)**:
- `AuthToken` 语义改为 `DeviceToken`(字段名可保留 `AuthToken` 减少改动,注释说明)
- 配对成功后写回 `settings.json`(DPAPI 加密不变)

**ServerApi(`Services/ServerApi.cs`)**:
- 新增 `PairAsync(serverUrl, pairingCode, deviceId, deviceName)` → `{ deviceToken }`
- 认证头保持 `Bearer deviceToken`

### 4.2 Android 端(`android/`)
- `SettingsPage.kt`:输入框"访问令牌"→"配对码"+ "配对"按钮
- `SyncApi.kt`:新增 `pair()`,请求携带 `deviceToken`
- 配对成功存 `SharedPreferences`(现 token 存储位置不变,换值)

### 4.3 Web 管理页(`web/`,可选)
- 设备页增加"生成配对码"按钮(调 `POST /api/pairing-codes`,展示一次性码+倒计时)
- 设备列表"移除"已有(调 `DELETE /api/devices/{id}`,现在真正生效)

---

## 5. 迁移与兼容

### 5.1 兼容期策略
- **管理 Token 继续有效**:全接口可用(它现在是"管理员钥匙"),老设备如果仍用管理 Token 也能连——但**推荐所有设备尽快重新配对**。
- 版本过渡:新客户端(桌面/Android)一律走配对;老客户端混用期靠管理 Token 兜底。

### 5.2 现有设备迁移
- 已登记的 `Devices`(如 `test-desktop-0001`、手机):Token 列为 NULL。
- 处理:设置页检测到当前凭据是"管理 Token 且无设备 Token"时,提示"重新配对"引导。
- 简化版:不做自动迁移,统一手动重新配对一次(设备少,成本低)。

### 5.3 测试 mock(`tmp/test-server/Program.cs`)
- 增加 `POST /api/pair` + `POST /api/pairing-codes`(内存态即可)
- 认证中间件改为:管理 Token(`test-token-123`)或已配对设备 Token

---

## 6. 安全细节

| 项 | 设计 |
|----|------|
| 配对码格式 | 8 位大写字母+数字(去掉 0/O/1/I 易混淆字符),如 `A3F9K2Q7` |
| 配对码 TTL | 默认 10 分钟,过期作废 |
| 一码一设备 | `UsedAt` 非空即失效;需配多设备就生成多个码 |
| 设备 Token | `randomHex(32)`(64 hex),服务端只存 **SHA-256 哈希** |
| 防爆破 | `/api/pair` 失败 5 次/IP 锁 10 分钟(可选中间件) |
| 传输 | 建议启用 HTTPS(自签/内网可接受),防局域网嗅探 |
| 管理 Token | 仅管理接口建议收窄(node/C# 各自实现时可将管理接口与数据接口路径分离) |

---

## 7. 实施步骤(按依赖排序)

1. **node 端**(`server-node/`):
   - `db.ts`:PairingCodes 表 + Devices 加列(SQLite ALTER + 建表)
   - `service.ts`:`createPairingCode()` / `pair()` / 吊销清 Token
   - `auth.ts` + `server.ts`:两级认证 + `/api/pair`、`/api/pairing-codes` 路由
   - `controllers.ts`:接入新接口
2. **测试联调**:curl 走通 生成码→配对→带设备 Token 访问→吊销失效
3. **桌面端**(`desktop/`):SettingsPage 配对 UI + ServerApi.PairAsync + SettingsStore
4. **Android 端**(`android/`):SettingsPage 配对 UI + SyncApi.pair
5. **C# 服务端**(`server/`):同步 1 的改动(EF 迁移 + 控制器 + 中间件)
6. **Web 管理页**(可选):生成配对码入口
7. **测试 mock**:补配对接口
8. **端到端验证**:桌面+手机配对→互推→移除一台→确认其失效

---

## 8. 验收标准

- [ ] 新设备用配对码配对成功,获得独立 Token
- [ ] 设备 A 移除后,其 Token 立即 401,设备 B 不受影响
- [ ] 配对码过期/复用被拒
- [ ] 管理 Token 仍可生成配对码、管理设备
- [ ] 桌面/Android 设置页完成配对流程,无需手动复制共享 Token
- [ ] 测试 mock 与真实服务端行为一致
