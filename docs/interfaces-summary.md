# 前后端与硬件通信接口总览

本文用于把 Android App、阿里云 HTTP API、EMQX MQTT Broker、硬件 `cloud_comm` 的接口统一到一张表里，避免联调时出现“连上了但不是同一套协议”的问题。

## 1. 当前联调环境

| 项 | 当前值 |
|----|--------|
| ECS 公网 IP | `47.103.157.213` |
| HTTP Base URL | `http://47.103.157.213/api` |
| MQTT Broker | `tcp://47.103.157.213:1883` |
| App MQTT 用户 | `app_user_001` |
| Robot MQTT 用户 | `robot_device_001` |
| 默认 productType | `crawler` |
| 默认 deviceId | `crawler_00000001` |
| MQTT Protocol Version | `1.0` |
| MQTT Version | `3.1.1` |

密码只允许写入服务器 `.env` 或本机 `local.properties`，不要提交 Git。

## 2. Android 配置入口

根目录 `local.properties`：

```properties
api.base.url=http://47.103.157.213/api
mqtt.host=47.103.157.213
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=私下保存的App密码
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

Gradle 会把这些值注入 `BuildConfig`，代码入口：

- HTTP：[ApiClient.kt](../app/src/main/java/com/robot/solar/network/http/ApiClient.kt)
- MQTT：[CloudCommMqttManager.kt](../app/src/main/java/com/robot/solar/network/mqtt/CloudCommMqttManager.kt)
- MQTT 模型：[MqttModels.kt](../app/src/main/java/com/robot/solar/network/mqtt/MqttModels.kt)

## 3. HTTP 接口（App ↔ 阿里云 API）

完整契约见 [openapi.yaml](./openapi.yaml)。

| 方法 | 路径 | 用途 | App 页面 |
|------|------|------|----------|
| `POST` | `/api/auth/login` | 用户登录，返回 JWT | 登录页 |
| `GET` | `/api/devices` | 当前用户设备列表 | 设备列表页 |
| `GET` | `/api/jobs?device_id=` | 作业记录 | 作业记录页 |
| `GET` | `/api/firmware/latest?device_id=` | 查询最新固件 | 固件升级页 |
| `POST` | `/api/firmware/upgrade` | 触发固件升级任务 | 固件升级页 |
| `GET` | `/api/devices/{device_id}/wifi` | 读取 WiFi 配置 | WiFi 设置页 |
| `PUT` | `/api/devices/{device_id}/wifi` | 下发 WiFi 配置 | WiFi 设置页 |

验证：

```bash
curl http://47.103.157.213/health
curl -X POST http://47.103.157.213/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@vgsolar.com","password":"你的API密码"}'
```

## 4. MQTT 接口（App ↔ cloud_comm）

完整协议见 [cloud-comm-api.md](./cloud-comm-api.md) 与 [integration-protocol-v1.md](./integration-protocol-v1.md)。

### 4.1 设备上行，App 订阅

| Topic | Payload | 用途 | App 处理 |
|-------|--------------|------|----------|
| `device/{productType}/{deviceId}/heartbeat` | `HeartbeatMessage` | 设备在线心跳 | 顶部在线状态 |
| `device/{productType}/{deviceId}/status` | `StatusMessage` | 电量、工作状态、控制模式、速度、设备状态、运动状态 | 主监控页实时刷新 |
| `device/{productType}/{deviceId}/cmd_ack` | `CmdAckMessage` | `cmd` 指令处理结果 | Toast + 本地日志 |
| `device/{productType}/{deviceId}/map` | `MapMessage` | 地图文件通知 | 后续地图展示/下载 |
| `device/{productType}/{deviceId}/pose` | `PoseMessage` | 地图离散位姿与朝向 | 机器人位置和最近 10 秒轨迹 |

App 会先校验：

- `version == "1.0"`
- `productType == 当前设备类型`
- `deviceId == 当前设备 ID`

第二版协议中，地图定位不再放入 `status`。`mapId` 为整数并与标准地图 JSON
的 `map_id` 对齐；`pose.mapId + pose.mapVersion` 必须与当前地图一致后才参与绘制。

### 4.2 App 下行，设备订阅

| Topic | Payload type | 用途 | App 入口 |
|-------|--------------|------|----------|
| `device/{productType}/{deviceId}/remote` | `remote` | 四向按钮固定速度手动控制 | `publishRemote()` |
| `device/{productType}/{deviceId}/cmd` | `cmd` | start/stop/estop/clear_estop | `publishCmd()` |

遥控映射：

| 操作 | `linearSpeedCms` | `angularSpeedRadps` |
|------|------------------------|--------------------------|
| 前进 | `+20.0` | `0.0` |
| 后退 | `-20.0` | `0.0` |
| 左转 | `0.0` | `-0.5` |
| 右转 | `0.0` | `+0.5` |
| 停止 | `0.0` | `0.0` |
| 急停 | 发布 `cmd = "estop"` | - |

`remote` 使用 `durationMs = 300`，硬件侧超过 `1000ms` 未收到新的 `remote` 应自动停车。
方向按钮按住前 `500ms` 不发送；超过后以 `20Hz` 发送。松开、页面退出、应用进入后台、
断连、心跳超时、自动运行、急停或设备异常时，App 发送零速度并终止周期发送。多个方向
按钮同时按下时同样发送零速度，并提示用户不能同时操作。

## 5. 服务器部署入口

当前服务器已经有 EMQX，**不要直接运行默认 Compose**。

| 文件 | 用途 |
|------|------|
| [server-http-only-deploy.md](./server-http-only-deploy.md) | 当前服务器只补 HTTP API |
| [../deploy/docker-compose.http-only.yml](../deploy/docker-compose.http-only.yml) | PostgreSQL + FastAPI + Nginx |
| [../deploy/docker-compose.yml](../deploy/docker-compose.yml) | 从零部署全栈（含 EMQX），当前服务器慎用 |

## 6. 联调前必须确认

| 问题 | 负责人 | 状态 |
|------|--------|------|
| 硬件真实 `productType/deviceId` 是否为 `crawler/crawler_00000001` | 硬件组 | 待确认 |
| HTTP 设备列表是否返回 `product_type/productType` 与新 `device_id` | 后端/App | 待同步 |
| Robot 是否按 `heartbeat/status/cmd_ack/map` 上报 | 硬件组 | 待确认 |
| HTTP API 是否部署并通过 `/health` | 后端/App | 待验证 |
| App `local.properties` 是否填入真实 MQTT 密码 | App | 本机填写 |
