# 地图上传后端实现交接说明

## 1. 输入与版本

- Robot 原始接口：`docs/requirements/map_upload_backend_api.md`
- 后端初始实现提交：`fd3b954 feat: add authenticated map upload backend`
- 原始地图字节兼容修复：`3f167a5 fix: preserve original map upload bytes`
- 当前公网地址约定：`http://47.103.157.213`

原始接口文档中允许临时空 Token；本项目实施决策更严格：`MAP_UPLOAD_TOKEN` 从首次上线起必填，空值时 API 容器启动失败。

## 2. 已完成代码

后端已实现：

```http
POST /api/maps/upload
Authorization: Bearer <MAP_UPLOAD_TOKEN>
Content-Type: application/json
```

请求字段：

```text
productType deviceId mapId mapVersion mapName checksum fileSizeBytes map
```

关键规则：

- `productType/deviceId` 仅允许字母、数字、点、下划线和短横线。
- `mapId/mapVersion` 必须是非负整数，并与 `map.map_id/map.version` 一致。
- `map` 必须是外层对象最后一个且唯一的同名顶层字段。
- 保存并校验的是 Robot 嵌入请求的原始 `map` JSON 字节，不重新序列化。
- 校验 `fileSizeBytes` 和 `sha256:<hex>`。
- 使用临时文件和原子替换，避免下载到半写入文件。
- 相同版本、相同内容幂等返回 200；相同版本、不同内容返回 409。

保存路径：

```text
/opt/robot-platform/static/maps/{productType}/{deviceId}/map_{mapId}_v{mapVersion}.json
```

下载地址：

```text
http://47.103.157.213/maps/{productType}/{deviceId}/map_{mapId}_v{mapVersion}.json
```

主要文件：

- `deploy/api/app/map_upload.py`
- `deploy/api/app/preflight.py`
- `deploy/api/app/main.py`
- `deploy/api/tests/test_map_upload.py`
- `deploy/docker-compose.http-only.yml`
- `deploy/nginx/conf.d/default.conf`
- `deploy/.env.example`

## 3. 服务器配置契约

`.env` 必须包含：

```dotenv
MAP_PUBLIC_BASE_URL=http://47.103.157.213
MAP_UPLOAD_TOKEN=<服务器生成的真实随机值>
```

注意：当前实现会自行追加 `/maps/...`，所以 `MAP_PUBLIC_BASE_URL` 不要写成 `http://47.103.157.213/maps`。

共享持久化目录：

```text
宿主机：/opt/robot-platform/static/maps
API：/opt/robot-platform/static/maps（可写）
Nginx：/usr/share/nginx/html/maps（只读）
```

Robot `cloud_comm` 必须使用同一个 Token，并以原始 UTF-8 地图字节计算大小和 SHA-256。Robot 端真实配置项名称不在本仓库中，应以其现有配置为准，不能自行发明变量名。

## 4. 自动化验证

后端测试覆盖 11 个场景，包括鉴权、字段校验、路径安全、ID/版本一致性、原始字节大小与 checksum、幂等、冲突和原子写入失败清理。验证结果：

```text
Python compileall：通过
deploy/api/tests：11 项通过
Android testDebugUnitTest：通过
Android assembleDebug：通过
```

## 5. 尚未完成

本次没有连接或修改服务器，因此以下状态仍为“待部署/待真实联调”：

- 服务器是否已运行包含上述提交的代码；
- `/api/maps/upload` 是否从 404 变为 POST 可用；
- API/Nginx 是否共享同一持久化目录；
- Robot 是否使用正确 Token 自动上传；
- MQTT retained `map` 消息是否包含真实 `mapJsonUrl`；
- APP 是否在真实手机网络完成下载、checksum、缓存和渲染；
- 容器重建后地图文件是否仍可下载。

只有取得 Robot POST、HTTP 下载、MQTT `map` 和 APP 显示四侧证据后，才能标记地图闭环联调通过。

