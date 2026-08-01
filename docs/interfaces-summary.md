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
| `device/{productType}/{deviceId}/status` | `StatusMessage` | 设备摘要、任务状态、运行模式、安全状态 | 主监控页实时刷新并判定任务最终状态 |
| `device/{productType}/{deviceId}/cmd_ack` | `CmdAckMessage` | `cmd` 是否被任务层同步受理 | 命令受理反馈，不代表任务完成 |
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
| `device/{productType}/{deviceId}/remote` | `remote` | 手动模式下四向可配置速度控制，QoS 0 | `publishRemote()` |
| `device/{productType}/{deviceId}/cmd` | `cmd` | start/stop/pause/resume/replan/manual/auto/estop/clear_estop | `prepareCommand()` + `publishCmd()` |

遥控映射：

| 操作 | `linearSpeedCms` | `angularSpeedRadps` |
|------|------------------------|--------------------------|
| 前进 | `+L` | `0.0` |
| 后退 | `-L` | `0.0` |
| 左转 | `0.0` | `+A` |
| 右转 | `0.0` | `-A` |
| 停止 | `0.0` | `0.0` |
| 急停 | 发布 `cmd = "estop"` | - |

`L` 为 APP UI 设置的非负线速度大小，范围 `0..50 cm/s`；`A` 为非负角速度
大小，范围 `0..0.5 rad/s`。预设为慢速 `10/0.1`、标准 `30/0.3`、高速
`50/0.5`，默认标准档。自定义值按设备保存；线速度步进 `1 cm/s`，角速度
步进 `0.1 rad/s`。方向键只负责绑定正负号，最终下行范围分别为
`[-50, 50] cm/s` 与 `[-0.5, 0.5] rad/s`。长按过程中修改速度时，下一帧
周期消息即使用新值。

进入手动遥控前，App 先发送 `manual`，等待成功 ACK 及
`status.operationalMode=manual` 后才允许发送 `remote`。方向按钮按住前 `500ms`
不发送；超过后以约 `20Hz`、QoS 0 发送。松开、页面退出、应用进入后台、断连、
心跳超时或安全状态异常时，App 主动发送零速度并终止周期发送。退出遥控页后发送
`auto`，并等待 `status.operationalMode=auto`。Robot 的遥控超时停车只作为异常防线。

`cmd_ack.ackStatus=success` 只表示命令被任务层受理。`start` 的最终结果由
`status.missionId/runState` 驱动；`stop/pause/resume/replan` 必须携带最新
`status.missionId` 作为 `targetMissionId`。

`start` 在发送前由任务配置弹窗收集完整 coverage 参数：

```text
mapId / mapVersion
useCurrentPose
start.blockId / cellRow / cellCol / innerRow / innerCol / heading
targetBlockIds
globalPlan
```

`useCurrentPose=false` 时 `start` 必填；目标区域必须存在、可清洁、大于 0 且不能重复。
命令发送失败、ACK 失败、ACK 超时或连接中断后，重试复用原 `PreparedCommand`，因此
`cmdId/timestamp/payload` 均保持不变。

任务状态页展示以下 Robot 字段，安全状态显示优先于任务运行状态：

```text
missionId taskKind runState operationalMode safetyState phase activeAction
waypointIndex waypointCount errorCode errorRetryable errorSource errorMessage
```

### 4.3 发布兼容性

本次升级保持 MQTT topic 和 payload `version="1.0"` 不变，但任务命令 payload、
ACK 语义、任务状态字段和遥控前置流程不向后兼容。新版 App 必须和支持新版
`/mission/command` 的 Robot 成对发布，禁止向旧 `/mission/task_cmd` 双写，也禁止
新版 App 与旧 Robot 混用。

当前 MQTT payload 没有可用于自动识别新旧 Robot 的接口能力字段，因此版本绑定必须
由发布配置、设备固件白名单或服务端设备能力信息保证。真实发布前需记录：

```text
App version/versionCode
Robot image/firmware version
mission command API capability
MQTT cmd/cmd_ack/status 抓包
Robot MQTT/ROS 两侧日志
```

当前第三版 APP 发布标识固定为：

```text
App versionName/versionCode: 1.2.0 / 3
mission command API capability: mission_command_v2
```

上述标识已写入 `BuildConfig.MISSION_COMMAND_API_CAPABILITY` 并展示在状态详情页。
它用于发布记录和与 Robot 镜像成对验收，不代表 APP 可以自动识别旧 Robot；在
HTTP 设备接口增加能力字段之前，发布负责人仍必须执行 Robot 固件白名单校验。

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
