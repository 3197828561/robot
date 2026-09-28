# 地图后端与 APP 实施交接

本文只回答两个问题：

1. 后端需要提供哪些能力，才能接收 Robot 地图并向 APP 提供当前地图；
2. APP 的地图模块和 MQTT 订阅需要怎样修改。

本文中的接口路径、字段名和响应结构是联调契约。后端和 APP 若要修改，必须先与 Robot
接口共同确认，不能单端改变。

## 1. 最终链路

```text
Robot
  ├─ POST /api/maps/upload
  │    上传一个不可变地图版本
  └─ PUT /api/devices/{productType}/{deviceId}/active-map
       登记设备当前实际使用的地图

APP
  ├─ GET /api/devices/{productType}/{deviceId}/maps/current
  │    获取设备当前地图元数据
  ├─ GET /api/devices/{productType}/{deviceId}/maps/{mapId}/versions/{mapVersion}
  │    查询精确版本元数据
  ├─ GET .../maps/{mapId}/versions/{mapVersion}/content
  │    下载原始地图 JSON
  └─ MQTT .../pose
       获取实时地图编号、地图版本和 Robot 位置
```

必须区分：

- `MapArtifact`：已经上传并保存的不可变地图版本；
- `DeviceActiveMap`：某台设备当前实际使用的地图指针。

上传成功不能自动更新 `DeviceActiveMap`。只有 Robot 调用 `active-map` 接口后，后端才更新
设备当前地图。

## 2. MQTT 调整

APP 不需要新增地图 MQTT Topic。

### 2.1 APP 保留的订阅

Topic 基础格式：

```text
device/{productType}/{deviceId}/{topicType}
```

| Topic | QoS | retain | APP 用途 |
| --- | ---: | --- | --- |
| `.../heartbeat` | 0 | false | 在线状态 |
| `.../status` | 0 | false | 任务、设备和安全状态 |
| `.../cmd_ack` | 1 | false | 命令同步回执 |
| `.../pose` | 0 | false | 地图身份、定位和方向 |

APP 继续发布：

| Topic | QoS | retain |
| --- | ---: | --- |
| `.../cmd` | 1 | false |
| `.../remote` | 0 | false |

### 2.2 APP 删除的订阅

删除以下依赖：

```text
device/{productType}/{deviceId}/map
```

APP 不再：

- 等待 MQTT `map` 才加载地图；
- 从 MQTT Payload 读取 `mapJsonUrl`；
- 把 retained `map` 当作设备当前地图；
- 根据 Robot 信息拼接匿名地图 URL。

地图发现和下载全部通过后端 HTTP API 完成。`pose` 只负责实时一致性检查和位置更新，
不是 APP 首次查询地图的前置条件。

## 3. 后端需要完成的模块

建议至少拆分为以下职责：

| 模块 | 职责 |
| --- | --- |
| RobotAuth | 校验 Robot token，并绑定允许操作的 `productType/deviceId` |
| AppAuth | 校验 APP access token 和设备读取权限 |
| MapUploadService | 校验并保存不可变地图版本 |
| MapActivationService | 幂等更新设备当前地图指针 |
| MapQueryService | 提供 current、精确版本和内容下载接口 |
| MapStorage | 原子保存原始 JSON 字节，可使用文件系统或对象存储 |
| MapRepository | 持久化地图元数据、当前指针和激活幂等记录 |

不要让 APP 使用 Robot token，也不要把 Robot token 放入 APP 安装包。

## 4. 后端数据模型

### 4.1 MapArtifact

建议字段：

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `id` | bigint/UUID | 内部主键 |
| `productType` | string | 非空 |
| `deviceId` | string | 非空 |
| `mapId` | uint32 | 与地图正文 `map_id` 一致 |
| `mapVersion` | uint32 | 与地图正文 `version` 一致 |
| `mapName` | string/null | 展示名称 |
| `checksum` | string | `sha256:` 加 64 位小写十六进制 |
| `fileSizeBytes` | uint64 | 原始地图 JSON 字节数 |
| `storageKey` | string | 后端生成，不接受客户端路径 |
| `status` | enum | `UPLOADING/READY/FAILED` |
| `createdAt` | timestamp | 创建时间 |

唯一键：

```text
productType + deviceId + mapId + mapVersion
```

规则：

- 唯一键相同且 checksum 相同：幂等成功；
- 唯一键相同但 checksum 不同：返回 409；
- `READY` 文件不能被原地覆盖；
- 地图内容变化必须增加 `mapVersion`。

### 4.2 DeviceActiveMap

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `productType` | string | 联合主键 |
| `deviceId` | string | 联合主键 |
| `artifactId` | foreign key | 指向 `READY` MapArtifact |
| `activeRevision` | uint64 | 首次为 1，真实切图时递增 |
| `activationRequestId` | string | 最近一次成功激活请求 ID |
| `activatedAt` | timestamp | 当前地图激活时间 |
| `lastReportedAt` | timestamp | 同版本重复报告时间 |

同一地图重复激活不得增加 `activeRevision`。

### 4.3 MapActivationRequest

至少保存：

```text
productType + deviceId + requestId
mapId + mapVersion + checksum
result + activeRevision + createdAt
```

相同 `requestId`、相同 Payload 必须返回第一次处理结果；相同 `requestId`、不同 Payload
必须返回 409 `IDEMPOTENCY_CONFLICT`。

## 5. 接口一：Robot 上传地图

### 5.1 请求

```http
POST /api/maps/upload
Content-Type: application/json
Authorization: Bearer <robot-token>
```

```json
{
  "productType": "crawler",
  "deviceId": "crawler_00000001",
  "mapId": 320,
  "mapVersion": 1,
  "mapName": "320",
  "checksum": "sha256:<64位小写十六进制>",
  "fileSizeBytes": 2589,
  "map": {
    "map_id": 320,
    "version": 1,
    "frame": {},
    "cell_model": {},
    "blocks": [],
    "cells": [],
    "bridges": []
  }
}
```

`mapName` 允许为空字符串。`mapId` 和 `mapVersion` 必须在 uint32 范围内。

### 5.2 原始字节校验

`checksum` 和 `fileSizeBytes` 针对请求中 `map` 值的原始 JSON 字节，而不是整个 HTTP
请求，也不是解析后重新序列化的 JSON。

后端必须从原始 HTTP body 中取得 `map` 值对应的字节区间：

1. 先限制 HTTP body 最大尺寸，超限返回 413；
2. 使用能返回 token offset 的 JSON tokenizer/stream parser 定位顶层 `map` 字段；
3. `map` 是 Robot 请求的最后一个顶层字段，从其值起点截取到外层请求最后一个 `}`
   之前；这段范围要保留地图正文末尾的空格和换行；
4. 对这些字节计算长度和 SHA-256；
5. 再解析地图正文并检查 `map.map_id/map.version`。

不能使用以下方式计算 checksum：

```text
parse(map) -> stringify(map) -> sha256
```

重新序列化会改变空格、换行或数字格式，从而与 Robot checksum 不一致。也不要用正则
表达式解析嵌套 JSON。

### 5.3 后端处理顺序

1. 校验 token；
2. 校验 token 绑定的设备与 `productType/deviceId`；
3. 校验字段类型、范围和 checksum 格式；
4. 提取并校验原始地图字节；
5. 校验正文 `map_id == mapId`、`version == mapVersion`；
6. 查询唯一业务键；
7. 相同 checksum 时直接幂等返回；
8. 不同 checksum 时返回 409，不覆盖旧文件；
9. 将文件写入临时位置；
10. 原子提交文件和数据库元数据；
11. 只有内容可下载后才将状态设为 `READY` 并返回成功。

### 5.4 成功响应

首次创建返回 201：

```json
{
  "result": "created",
  "artifact": {
    "productType": "crawler",
    "deviceId": "crawler_00000001",
    "mapId": 320,
    "mapVersion": 1,
    "checksum": "sha256:<64位小写十六进制>",
    "fileSizeBytes": 2589,
    "status": "READY"
  }
}
```

相同内容重复上传返回 200，响应结构与首次创建相同，只把 `result` 改为
`already_exists`。

后端应始终返回完整 `artifact`，便于联调和审计。不要要求 Robot 提供或接收地图下载 URL。

### 5.5 冲突响应

```http
HTTP/1.1 409 Conflict
```

```json
{
  "error": {
    "code": "MAP_VERSION_CONFLICT",
    "message": "mapId/mapVersion exists with a different checksum",
    "retryable": false
  }
}
```

## 6. 接口二：Robot 激活地图

### 6.1 请求

```http
PUT /api/devices/{productType}/{deviceId}/active-map
Content-Type: application/json
Authorization: Bearer <robot-token>
```

```json
{
  "requestId": "activate-crawler_00000001-320-1-1785571200000-0",
  "mapId": 320,
  "mapVersion": 1,
  "checksum": "sha256:<64位小写十六进制>"
}
```

不要把 `expectedActiveRevision` 定义为必填字段。

### 6.2 事务处理

在一个数据库事务中：

1. 校验 Robot 只能修改自己的设备；
2. 按 `productType/deviceId/requestId` 查询幂等记录；
3. 幂等命中时返回第一次结果；
4. 查询精确 MapArtifact，要求为 `READY`；
5. 核对 checksum；
6. 锁定该设备的 `DeviceActiveMap` 行；
7. 目标与当前地图相同：更新 `lastReportedAt`，revision 不变；
8. 目标不同：更新 artifact，`activeRevision + 1`；
9. 保存幂等结果并提交事务。

### 6.3 成功响应

```json
{
  "result": "activated",
  "deviceId": "crawler_00000001",
  "activeRevision": 18,
  "activeMap": {
    "mapId": 320,
    "mapVersion": 1,
    "checksum": "sha256:<64位小写十六进制>"
  },
  "activatedAt": "2026-08-02T10:00:00.000Z"
}
```

同一地图重复激活时返回 `result: "already_active"`，其他字段结构不变。

响应必须包含：

- `result=activated` 或 `already_active`；
- 非负整数 `activeRevision`；
- 与请求完全一致的 `activeMap.mapId/mapVersion/checksum`。

## 7. 接口三：APP 查询设备当前地图

### 7.1 请求

```http
GET /api/devices/{productType}/{deviceId}/maps/current
Authorization: Bearer <app-access-token>
```

### 7.2 成功响应

```json
{
  "productType": "crawler",
  "deviceId": "crawler_00000001",
  "activeRevision": 18,
  "activeMap": {
    "mapId": 320,
    "mapVersion": 1,
    "mapName": "320",
    "checksum": "sha256:<64位小写十六进制>",
    "fileSizeBytes": 2589,
    "contentUrl": "/api/devices/crawler/crawler_00000001/maps/320/versions/1/content"
  },
  "activatedAt": "2026-08-02T10:00:00.000Z",
  "lastReportedAt": "2026-08-02T10:00:00.000Z"
}
```

建议响应头：

```http
Cache-Control: no-store
```

当前指针会变化，APP 不应长期缓存 current 响应。

### 7.3 尚未激活

```http
HTTP/1.1 404 Not Found
```

```json
{
  "error": {
    "code": "ACTIVE_MAP_NOT_SET",
    "message": "device has no active map",
    "retryable": true
  }
}
```

APP 显示“地图尚未同步”，不能自行构造 `mapId=0/mapVersion=0`。

## 8. 接口四：APP 查询精确地图版本

```http
GET /api/devices/{productType}/{deviceId}/maps/{mapId}/versions/{mapVersion}
Authorization: Bearer <app-access-token>
```

```json
{
  "productType": "crawler",
  "deviceId": "crawler_00000001",
  "mapId": 320,
  "mapVersion": 1,
  "mapName": "320",
  "checksum": "sha256:<64位小写十六进制>",
  "fileSizeBytes": 2589,
  "status": "READY",
  "contentUrl": "/api/devices/crawler/crawler_00000001/maps/320/versions/1/content"
}
```

只有 `READY` 版本可以返回给 APP。不存在时返回 404 `MAP_NOT_FOUND`。

## 9. 接口五：APP 下载地图内容

```http
GET /api/devices/{productType}/{deviceId}/maps/{mapId}/versions/{mapVersion}/content
Authorization: Bearer <app-access-token>
```

成功响应：

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
ETag: "sha256:<64位小写十六进制>"
Cache-Control: private, max-age=31536000, immutable
```

响应正文必须是上传时保存的原始地图 JSON 字节，不能从数据库对象重新生成。

APP 对 HTTP 库解码后的响应字节计算大小和 SHA-256；`fileSizeBytes` 指未压缩的地图正文
字节数，不应直接拿可能经过传输压缩的 HTTP `Content-Length` 代替。

精确版本不可变，因此可使用长期缓存和 ETag。鉴权必须在返回 304 前完成。

## 10. 统一错误结构和重试语义

```json
{
  "error": {
    "code": "MAP_VERSION_CONFLICT",
    "message": "diagnostic text",
    "retryable": false,
    "details": {}
  },
  "requestId": "server-request-id"
}
```

程序只能根据 HTTP 状态和 `error.code` 分支，不能解析 `message`。

| HTTP | error.code | 说明 |
| ---: | --- | --- |
| 400 | `INVALID_REQUEST` | JSON 或字段错误 |
| 400 | `MAP_IDENTITY_MISMATCH` | 外层身份与地图正文不一致 |
| 400 | `MAP_CHECKSUM_MISMATCH` | checksum 或字节数错误 |
| 401 | `UNAUTHORIZED` | token 无效 |
| 403 | `FORBIDDEN` | 无设备权限 |
| 404 | `MAP_NOT_FOUND` | 精确版本不存在 |
| 404 | `ACTIVE_MAP_NOT_SET` | 设备没有当前地图 |
| 409 | `MAP_VERSION_CONFLICT` | 同版本不同内容 |
| 409 | `IDEMPOTENCY_CONFLICT` | 相同 requestId 携带不同内容 |
| 413 | `PAYLOAD_TOO_LARGE` | 地图请求超过限制 |
| 429 | `RATE_LIMITED` | 请求过快 |
| 500 | `MAP_STORAGE_ERROR` | 存储失败 |
| 503 | `MAP_NOT_READY` | 地图尚未达到 READY |
| 503 | `SERVICE_UNAVAILABLE` | 服务暂不可用 |

Robot 会自动重试传输错误、429 和 5xx。因此需要 Robot 稍后重试的错误必须使用 429 或
5xx；不要返回 `409 + retryable=true` 并期待 Robot 自动重试。

## 11. APP 代码改造

实际类名可按 APP 技术栈调整，但职责应保持分离。

### 11.1 MapApiClient

增加：

```text
getCurrentMap(productType, deviceId)
getMapVersion(productType, deviceId, mapId, mapVersion)
downloadMapContent(contentUrl)
```

所有请求使用 APP access token。`contentUrl` 若为相对路径，必须基于 API base URL 解析，
不能基于当前页面 URL 或 MQTT Broker 地址解析。

### 11.2 MapRepository

建议缓存键：

```text
productType/deviceId/mapId/mapVersion/checksum
```

缓存内容至少保存：

```text
原始 JSON 字节
解析后的地图对象
checksum
fileSizeBytes
校验完成时间
```

下载后按顺序检查：

1. 响应字节数等于 `fileSizeBytes`；
2. SHA-256 等于 `checksum`；
3. 根节点是 JSON object；
4. 正文 `map_id == mapId`；
5. 正文 `version == mapVersion`；
6. APP 渲染所需的 `blocks/cells/bridges` 类型合法。

全部通过后才原子替换当前地图。失败时保留旧缓存，不能写入半成品。

### 11.3 MapSyncController

建议状态：

```text
idle
loading-current
loading-content
ready
stale
offline-cache
error
```

打开设备地图页时：

```text
GET current
  -> 本地缓存 checksum 命中：直接加载缓存
  -> 未命中：下载 content
  -> 校验成功：进入 ready
  -> 404 ACTIVE_MAP_NOT_SET：显示“地图尚未同步”
  -> 网络失败且有已校验缓存：进入 offline-cache
  -> 网络失败且无缓存：显示地图不可用
```

不要等待第一条 `pose` 后才执行 `GET current`。

建议在以下时机刷新 current：

- 进入设备页；
- APP 从后台恢复；
- 用户手动刷新；
- 地图页停留期间按合理周期轮询，例如 5 至 10 秒；
- 收到与当前地图不匹配的 `pose`。

当前链路不要求后端额外发布地图 MQTT 通知。

### 11.4 PoseController

`pose` 必有：

```json
{
  "mapId": 320,
  "mapVersion": 1
}
```

定位有效时可能增加：

```text
blockId cellId cellRow cellCol innerRow innerCol headingCode heading
```

处理规则：

1. `pose.mapId/mapVersion` 与已加载地图一致时，才允许绘制 Robot 位置；
2. 不一致时立即隐藏 Robot 标记并把地图状态设为 `stale`；
3. 对同一个错配版本只发起一个在途同步请求；
4. 查询精确版本并下载内容，同时刷新 current；
5. 新地图校验成功后再恢复位置显示；
6. 位置字段缺失时隐藏标记，不能把缺失字段解释成 0；
7. 切换设备或页面销毁时取消旧请求，旧响应不得覆盖新设备状态。

可使用如下请求键合并高频 pose：

```text
deviceId/mapId/mapVersion
```

### 11.5 防止异步响应覆盖

每次设备切换或地图同步生成一个 generation token：

```text
开始请求时保存 generation
响应返回时检查 generation 和当前 deviceId
不一致则丢弃响应
```

这能避免旧设备响应、旧地图响应或后发先至请求覆盖当前页面。

## 12. 后端安全与部署要求

- 生产环境使用 HTTPS；
- Robot token 只能上传和激活绑定设备；
- APP token 只能读取用户有权访问的设备；
- CORS 只允许受信 APP/Web 域名；
- 不在日志中记录 Authorization 头或完整 token；
- `storageKey` 由后端生成，防止路径穿越；
- 文件写入使用临时文件加原子 rename，或对象存储原子对象提交；
- 数据库提交失败时不得留下可被下载的 `READY` 记录；
- 存储失败时不得更新 activeMap；
- 地图版本应有备份和保留策略，不得因 current 切换删除旧版本。

## 13. 最小验收清单

### 13.1 后端

- [ ] 首次上传返回 201 `created`；
- [ ] 相同内容重复上传返回 200 `already_exists`；
- [ ] 同版本不同 checksum 返回 409，旧文件未改变；
- [ ] 外层 ID/版本与正文不一致时返回 400；
- [ ] 存储失败不会产生 `READY` 记录；
- [ ] 相同激活 requestId 重放返回相同结果；
- [ ] 相同 requestId 不同内容返回 409；
- [ ] 同地图重复激活不增加 activeRevision；
- [ ] 切换地图时 activeRevision 只增加一次；
- [ ] current、metadata 和 content 都执行设备权限校验；
- [ ] 服务和数据库重启后 current 与地图内容仍存在；
- [ ] content 字节数和 SHA-256 与上传值完全一致。

### 13.2 APP

- [ ] APP 不订阅 MQTT `map`；
- [ ] 打开设备页无需等待 pose 即可请求 current；
- [ ] 缓存 checksum 命中时不重复下载；
- [ ] checksum、字节数或正文身份错误时不替换旧地图；
- [ ] pose 地图身份匹配时正常显示位置；
- [ ] pose 地图身份不匹配时立即隐藏位置并同步地图；
- [ ] 高频重复错配 pose 只产生一个在途请求；
- [ ] pose 缺少定位字段时不把坐标显示为 0；
- [ ] 切换设备后旧请求不会污染新页面；
- [ ] 后端离线时只使用已经校验且身份匹配的缓存。

### 13.3 端到端

```text
Robot 上传 map 320/1
  -> 后端 MapArtifact=READY
Robot 激活 map 320/1
  -> 后端 activeRevision=1
APP GET current
  -> 获得 320/1 元数据
APP 下载并校验 content
  -> 地图 ready
APP 收到 pose 320/1
  -> 显示 Robot 位置
```

再执行一次新版本切换：

```text
上传 320/2，但尚未激活
  -> current 仍为 320/1
激活 320/2
  -> activeRevision 增加
APP current/pose 发现版本变化
  -> 隐藏旧位置、下载 320/2、校验后原子切换
```

上述两条链路全部通过后，才可以把地图后端和 APP 改造标记为验收完成。
