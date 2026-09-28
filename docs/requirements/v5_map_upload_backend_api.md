# Robot 地图上传后端接口对接文档

本文用于后端和 APP 负责人对接 Robot 地图上传链路。目标是把临时联调中的手工上传地图 JSON 正式化：Robot 自动把地图 JSON 上传到阿里云服务器，服务器返回公网可访问的 `mapJsonUrl`，Robot 再通过 MQTT `map` topic 通知 APP。

> 当前状态（2026-07-26）：Robot 端原始地图文件读取、上传调用、缓存和 MQTT 通知代码
> 已实现；地图后端尚未配置，本文定义的真实 HTTP 上传、公网 URL 下载、持久化和
> APP 展示闭环均未测试。API V2 的其他接口已经联调通过，地图是当前唯一未完成的
> 端到端联调项。

## 1. 总体目标

Robot、后端、APP 的完整链路如下：

```text
map_upload_source_file 指定的原始地图 JSON
  -> cloud_comm
  -> POST /api/maps/upload
  -> 阿里云服务器保存地图 JSON
  -> 返回 mapJsonUrl
  -> cloud_comm 发布 MQTT map topic
  -> APP 收到 mapJsonUrl 后下载并显示地图
```

后端需要完成：

- 提供 `POST /api/maps/upload` 接口；
- 接收 Robot 上传的地图 JSON；
- 保存地图文件到服务器静态目录；
- 生成 APP 可公网访问的 `mapJsonUrl`；
- 通过 Nginx 暴露 `/maps/...` 静态下载地址；
- 返回 `mapJsonUrl` 给 Robot。

Robot 只有在上传成功后才会发布 MQTT `map` topic。上传失败时不得通知 APP 使用新地图。

## 2. 当前配置状态

截至 2026-07-25，地图上传后端尚未配置。当前不能确认：

- `POST /api/maps/upload` 已部署；
- API 容器和 Nginx 已挂载共享持久化目录；
- `/maps/...` 已作为公网静态资源路径开放；
- 后端已配置并校验上传 token；
- APP 所在网络可以下载 `mapJsonUrl`；
- 后端或容器重启后地图文件仍然存在。

计划使用的开发服务器：

```text
公网 IP: 47.103.157.213
```

先前环境记录中包含以下容器，正式配置前需要由后端/运维重新确认：

```text
vgsolar-api        FastAPI 后端
vgsolar-nginx      Nginx 反向代理和静态文件服务
vgsolar-postgres   PostgreSQL
robot-emqx         MQTT Broker
```

先前检查曾确认基础健康接口可访问：

```bash
curl -i http://47.103.157.213/health
```

返回：

```json
{"status":"ok"}
```

该结果只说明当时公网到 Nginx 和 API 的基础链路可访问，不能证明当前状态，也不能
证明地图接口已经部署。

先前检查地图路由时：

```bash
curl -i http://47.103.157.213/api/maps/upload
```

返回：

```json
{"detail":"Not Found"}
```

当时返回 `404 Not Found`。结合当前“后端尚未配置”的状态，地图联调必须从重新
确认路由、存储挂载和公网下载能力开始，不能沿用先前检查作为完成证据。

## 3. 后端接口要求

### 3.1 接口地址

```http
POST /api/maps/upload
```

完整公网地址：

```text
http://47.103.157.213/api/maps/upload
```

### 3.2 请求头

临时联调阶段：

```http
Content-Type: application/json
```

正式阶段建议增加：

```http
Authorization: Bearer <token>
Content-Type: application/json
```

建议环境变量：

| 端 | 环境变量 | 说明 |
|---|---|---|
| Robot | `ROBOT_MAP_UPLOAD_TOKEN` | Robot 读取后放入 `Authorization` 请求头 |
| 后端 | `MAP_UPLOAD_TOKEN` | 后端校验 Robot 上传 token |

如果 `MAP_UPLOAD_TOKEN` 为空，可以暂时跳过鉴权，便于联调。

### 3.3 请求体

Robot 上传的 JSON 格式如下：

```json
{
  "productType": "crawler",
  "deviceId": "crawler_00000001",
  "mapId": 2,
  "mapVersion": 1,
  "mapName": "example_map_complex",
  "checksum": "sha256:7aad0ed1e34f6317601003681b8ca158249600a49317b9b4dc2e021269d40216",
  "fileSizeBytes": 14587,
  "map": {
    "map_id": 2,
    "version": 1,
    "map_name": "example_map_complex",
    "regions": [],
    "paths": [],
    "points": []
  }
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `productType` | string | 是 | 产品类型，例如 `crawler` |
| `deviceId` | string | 是 | 设备 ID，例如 `crawler_00000001` |
| `mapId` | integer | 是 | 地图 ID |
| `mapVersion` | integer | 是 | 地图版本 |
| `mapName` | string | 否 | 地图名称 |
| `checksum` | string | 是 | 原始地图 JSON 文件字节的 SHA-256，格式为 `sha256:<hex>` |
| `fileSizeBytes` | integer | 是 | 原始地图 JSON 文件的字节数 |
| `map` | object | 是 | 原始地图 JSON 内容；Robot 直接嵌入文件字节 |

后端保存文件时只保存 `map` 字段，不需要把外层上传元数据一起写进地图文件。
Robot 固定把 `map` 作为请求体最后一个字段，并直接嵌入原始文件字节。后端不能
先把 `map` 解析成对象再 `json.dumps()` 后保存，否则格式化变化会导致
`fileSizeBytes/checksum` 与 APP 下载内容不一致。

## 4. 地图文件保存规则

推荐保存到宿主机目录：

```text
/opt/robot-platform/static/maps/{productType}/{deviceId}/map_{mapId}_v{mapVersion}.json
```

示例：

```text
/opt/robot-platform/static/maps/crawler/crawler_00000001/map_2_v1.json
```

对应公网访问 URL：

```text
http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json
```

命名规则：

```text
/maps/{productType}/{deviceId}/map_{mapId}_v{mapVersion}.json
```

注意事项：

- `productType` 和 `deviceId` 必须做路径安全校验，只允许字母、数字、下划线、短横线和点号；
- 不允许 `../`、斜杠或空字符串进入文件路径；
- 同一个 `mapId + mapVersion` 原则上不应被不同内容覆盖；
- 如果需要允许重复上传同一内容，可以幂等返回同一个 `mapJsonUrl`；
- 如果同一 `mapId + mapVersion` 已存在但 checksum 不一致，建议返回错误，要求 Robot 或地图管理模块递增版本号。

## 5. 成功响应

后端上传成功后必须返回 `mapJsonUrl`：

```json
{
  "mapJsonUrl": "http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json",
  "fileSizeBytes": 14587,
  "checksum": "sha256:7aad0ed1e34f6317601003681b8ca158249600a49317b9b4dc2e021269d40216"
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `mapJsonUrl` | 是 | APP 可公网下载的地图 JSON URL |
| `fileSizeBytes` | 建议 | 后端实际保存的文件大小 |
| `checksum` | 建议 | 后端实际保存内容计算得到的 checksum |

Robot 端依赖 `mapJsonUrl` 发布 MQTT `map` topic。如果响应中没有 `mapJsonUrl`，Robot 会视为上传失败。

## 6. 错误响应建议

### 6.1 缺少字段

```http
400 Bad Request
```

```json
{
  "detail": "missing fields: ['map']"
}
```

### 6.2 token 错误

```http
401 Unauthorized
```

```json
{
  "detail": "invalid token"
}
```

### 6.3 checksum 不一致

```http
400 Bad Request
```

```json
{
  "detail": {
    "message": "checksum mismatch",
    "expected": "sha256:...",
    "actual": "sha256:..."
  }
}
```

### 6.4 同版本地图内容冲突

```http
409 Conflict
```

```json
{
  "detail": {
    "message": "map version already exists with different checksum",
    "mapId": 2,
    "mapVersion": 1
  }
}
```

### 6.5 保存失败

```http
500 Internal Server Error
```

```json
{
  "detail": "failed to save map"
}
```

## 7. Nginx 静态资源要求

Nginx 需要暴露：

```text
GET /maps/{productType}/{deviceId}/map_{mapId}_v{mapVersion}.json
```

示例：

```text
http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json
```

推荐 Nginx 配置：

```nginx
location /maps/ {
    alias /usr/share/nginx/html/maps/;
    default_type application/json;
    add_header Access-Control-Allow-Origin *;
}
```

要求：

- 返回 `200 OK`；
- `Content-Type` 为 `application/json` 或浏览器可识别的 JSON 类型；
- 带 `Access-Control-Allow-Origin: *`，保证 APP WebView 或前端跨域下载不受阻；
- 文件不存在时返回 `404`。

## 8. Docker 部署要求

正式方案不能依赖 `docker cp` 把文件复制进 Nginx 容器。`docker cp` 只适合临时联调，容器重建后文件可能丢失。

推荐让 `vgsolar-api` 和 `vgsolar-nginx` 共享宿主机目录：

```text
/opt/robot-platform/static/maps
```

`docker-compose.yml` 推荐增加：

```yaml
services:
  api:
    volumes:
      - /opt/robot-platform/static/maps:/opt/robot-platform/static/maps
    environment:
      MAP_STATIC_ROOT: /opt/robot-platform/static/maps
      MAP_PUBLIC_BASE_URL: http://47.103.157.213/maps
      MAP_UPLOAD_TOKEN: ""

  nginx:
    volumes:
      - /opt/robot-platform/static/maps:/usr/share/nginx/html/maps:ro
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
```

说明：

- API 容器需要写地图文件，所以挂载为可写；
- Nginx 容器只负责下载，所以挂载为只读；
- `MAP_UPLOAD_TOKEN` 临时联调可以为空，正式环境应配置真实 token；
- 如果未来迁移 HTTPS 或对象存储，只需要改变 `MAP_PUBLIC_BASE_URL` 或后端保存逻辑，Robot 端接口契约可以保持不变。

## 9. FastAPI 参考实现

当前服务器中的 `vgsolar-api` 启动命令类似：

```text
uvicorn app.main:app
```

并且 `/health` 位于：

```text
/app/app/main.py
```

因此可以在 FastAPI 应用中增加如下逻辑。实际工程中应把代码放到现有路由结构合适的位置。

```python
from fastapi import Header, HTTPException, Request
from pathlib import Path
from typing import Optional
import hashlib
import json
import os
import re


MAP_STATIC_ROOT = Path(os.getenv("MAP_STATIC_ROOT", "/opt/robot-platform/static/maps"))
MAP_PUBLIC_BASE_URL = os.getenv("MAP_PUBLIC_BASE_URL", "http://47.103.157.213/maps")
MAP_UPLOAD_TOKEN = os.getenv("MAP_UPLOAD_TOKEN", "")


def _safe_path_part(value: str, field: str) -> str:
    value = str(value)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", value):
        raise HTTPException(status_code=400, detail=f"invalid {field}")
    return value


@app.post("/api/maps/upload")
async def upload_map(request: Request, authorization: Optional[str] = Header(default=None)):
    if MAP_UPLOAD_TOKEN:
        if authorization != f"Bearer {MAP_UPLOAD_TOKEN}":
            raise HTTPException(status_code=401, detail="invalid token")

    raw_body = await request.body()
    marker = b',\"map\":'
    map_marker = raw_body.rfind(marker)
    if map_marker < 0 or not raw_body.endswith(b"}"):
        raise HTTPException(status_code=400, detail="invalid upload envelope")

    # cloud_comm 固定将 map 放在外层对象最后；保留该值的原始 JSON 字节。
    map_content = raw_body[map_marker + len(marker):-1]
    try:
        payload = json.loads(raw_body)
        map_obj = json.loads(map_content)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail=f"invalid JSON: {exc}") from exc

    required = ["productType", "deviceId", "mapId", "mapVersion", "checksum", "fileSizeBytes", "map"]
    missing = [key for key in required if key not in payload]
    if missing:
        raise HTTPException(status_code=400, detail=f"missing fields: {missing}")

    product_type = _safe_path_part(payload["productType"], "productType")
    device_id = _safe_path_part(payload["deviceId"], "deviceId")
    map_id = int(payload["mapId"])
    map_version = int(payload["mapVersion"])
    expected_checksum = str(payload["checksum"])
    expected_size = int(payload["fileSizeBytes"])

    if not isinstance(map_obj, dict) or map_obj != payload["map"]:
        raise HTTPException(status_code=400, detail="map payload mismatch")
    if map_obj.get("map_id") != map_id or map_obj.get("version") != map_version:
        raise HTTPException(status_code=400, detail="map id/version mismatch")

    actual_checksum = "sha256:" + hashlib.sha256(map_content).hexdigest()

    if len(map_content) != expected_size or actual_checksum != expected_checksum:
        raise HTTPException(
            status_code=400,
            detail={
                "message": "map size or checksum mismatch",
                "expectedSize": expected_size,
                "actualSize": len(map_content),
                "expectedChecksum": expected_checksum,
                "actualChecksum": actual_checksum,
            },
        )

    target_dir = MAP_STATIC_ROOT / product_type / device_id
    target_dir.mkdir(parents=True, exist_ok=True)

    filename = f"map_{map_id}_v{map_version}.json"
    target_file = target_dir / filename

    if target_file.exists():
        existing_checksum = "sha256:" + hashlib.sha256(target_file.read_bytes()).hexdigest()
        if existing_checksum != actual_checksum:
            raise HTTPException(
                status_code=409,
                detail={
                    "message": "map version already exists with different checksum",
                    "mapId": map_id,
                    "mapVersion": map_version,
                },
            )

    target_file.write_bytes(map_content)

    map_json_url = f"{MAP_PUBLIC_BASE_URL}/{product_type}/{device_id}/{filename}"

    return {
        "mapJsonUrl": map_json_url,
        "fileSizeBytes": len(map_content),
        "checksum": actual_checksum,
    }
```

注意：如果后端工程已经通过 `include_router(..., prefix="/api")` 添加统一 `/api` 前缀，则路由函数里应写成：

```python
@router.post("/maps/upload")
```

而不是：

```python
@app.post("/api/maps/upload")
```

实际以现有后端路由结构为准。

## 10. APP 需要消费的 MQTT map topic

Robot 上传地图成功后会发布：

```text
device/{productType}/{deviceId}/map
```

示例：

```text
device/crawler/crawler_00000001/map
```

建议：

```text
QoS = 1
retain = true
```

payload 示例：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-20T10:50:00.000Z",
  "mapId": 2,
  "mapName": "example_map_complex",
  "mapVersion": 1,
  "mapJsonUrl": "http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json",
  "fileSizeBytes": 14587,
  "checksum": "sha256:7aad0ed1e34f6317601003681b8ca158249600a49317b9b4dc2e021269d40216"
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | string | MQTT payload 协议版本 |
| `deviceId` | string | 设备 ID |
| `productType` | string | 产品类型 |
| `timestamp` | string | Robot 发布时间，UTC RFC3339 格式 |
| `mapId` | integer | 地图 ID |
| `mapName` | string | 地图名称 |
| `mapVersion` | integer | 地图版本 |
| `mapJsonUrl` | string | APP 下载地图 JSON 的公网 URL |
| `fileSizeBytes` | integer | 地图文件大小 |
| `checksum` | string | 地图 JSON checksum |

APP 收到 `map` topic 后应执行：

1. 读取 `mapJsonUrl`；
2. 下载地图 JSON；
3. 计算下载内容的 SHA-256；
4. 与 payload 中的 `checksum` 对比；
5. 使用 `mapId` 和 `mapVersion` 与 `pose` topic 中的地图版本对齐；
6. 如果收到 retained `map` topic，应直接恢复当前地图显示。

## 11. Robot 端触发规则

Robot 不会周期性重复上传地图。`cloud_comm` 在启动时读取一次
`map_upload_source_file`；绝对路径直接使用，相对路径以启动进程时的当前工作
目录为基准。`/pv_map` 仅用于 pose 的地图版本和 `cellId` 映射，不参与上传校验。

会触发上传的情况：

- `cloud_comm` 启动后成功读取、解析原始 JSON，且本地没有相同地图缓存；
- 源文件中的 `mapId` 改变或 `mapVersion` 递增；
- 本地缓存不存在或 checksum 不匹配。

不会触发重复上传的情况：

- Robot 重启后源文件的 `mapId + mapVersion + checksum` 未变化；
- MQTT 断线重连后地图未变化。

MQTT 重连时，Robot 可以重发一次 `map` topic，但复用已经上传成功的 `mapJsonUrl`，不重新上传文件。

异常规则：

- 上传失败：Robot 不发布新的 `map` topic；
- 同一个 `mapId + mapVersion` 但 checksum 变化：视为地图版本管理错误，Robot 不覆盖旧 URL，不发布新 topic；
- 地图内容变化必须递增 `mapVersion`。

## 12. 验收步骤

### 12.1 确认接口存在

接口补完后执行：

```bash
curl -i http://47.103.157.213/api/maps/upload
```

由于接口是 `POST`，`GET` 请求可以返回：

```http
405 Method Not Allowed
```

但不能再返回：

```http
404 Not Found
```

### 12.2 准备测试请求体

在服务器或本地准备 `/tmp/map_upload_request.json`：

```json
{
  "productType": "crawler",
  "deviceId": "crawler_00000001",
  "mapId": 2,
  "mapVersion": 1,
  "mapName": "example_map_complex",
  "checksum": "sha256:需要替换为实际map字段checksum",
  "fileSizeBytes": 0,
  "map": {
    "map_id": 2,
    "version": 1,
    "map_name": "example_map_complex",
    "regions": [],
    "paths": [],
    "points": []
  }
}
```

正式测试时，`checksum` 和 `fileSizeBytes` 必须针对 `map` 值在请求体中保留的原始
JSON 字节计算。不要对 `map` 解析后重新格式化再计算。

### 12.3 POST 上传

```bash
curl -i -X POST http://47.103.157.213/api/maps/upload \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/map_upload_request.json
```

成功返回：

```http
HTTP/1.1 200 OK
```

响应体包含：

```json
{
  "mapJsonUrl": "http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json"
}
```

### 12.4 验证地图 URL 可下载

```bash
curl -I http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json
```

期望：

```http
HTTP/1.1 200 OK
Content-Type: application/json
Access-Control-Allow-Origin: *
```

### 12.5 验证手机 APP 外网可访问

在手机网络环境下访问：

```text
http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json
```

期望能正常下载 JSON。这个步骤用于确认 APP 和 Robot 不在同一网络时，APP 仍然能通过公网访问地图。

### 12.6 验证 MQTT map topic

APP 或测试终端订阅：

```bash
mosquitto_sub -h 47.103.157.213 -p 1883 -V mqttv311 \
  -u "$APP_MQTT_USER" -P "$APP_MQTT_PASSWORD" \
  -t 'device/crawler/crawler_00000001/map' \
  -F '%r %q %t %p'
```

Robot 上传成功后，应收到包含 `mapJsonUrl` 的 payload。

如果 topic 是 retained，订阅后即使 Robot 没有重新上传，也应该能收到最近一次地图通知。

## 13. 责任边界

后端负责：

- 实现 `POST /api/maps/upload`；
- 校验请求字段；
- 可选校验上传 token；
- 保存地图 JSON；
- 返回公网 `mapJsonUrl`；
- 保证 `/maps/...` 能被 APP 下载；
- 保证容器重启后地图文件不丢失。

Robot 负责：

- 从 `map_planner` 获取地图；
- 判断首次地图和地图版本变化；
- 调用后端上传接口；
- 上传成功后发布 MQTT `map` topic；
- 地图不变时不重复上传。

APP 负责：

- 订阅 MQTT `map` topic；
- 读取 `mapJsonUrl`；
- 下载地图 JSON；
- 校验 checksum；
- 渲染地图；
- 将地图版本与 `pose` 中的 `mapId/mapVersion` 对齐。

Nginx 负责：

- 代理 `/api/...` 到 `vgsolar-api`；
- 暴露 `/maps/...` 静态文件；
- 支持 APP 跨域下载地图 JSON。

## 14. 最小验收标准

当前以下闭环尚未执行，全部完成前地图功能保持“未测试/未通过验收”状态。

本功能验收只看以下闭环：

```text
Robot 调用 POST /api/maps/upload 成功
  -> 后端返回 mapJsonUrl
  -> curl 可以通过公网下载 mapJsonUrl
  -> Robot 发布 MQTT map topic
  -> APP 收到 mapJsonUrl
  -> APP 下载并渲染地图 JSON
```

只要以上链路稳定，地图上传后端接口即可认为满足联调要求。
