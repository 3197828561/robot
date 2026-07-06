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
| 默认 device_id | `rk3588` |
| MQTT Schema | `vgsolar.cloud_comm.v1` |
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
mqtt.default_device_id=rk3588
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
  -d '{"email":"test@vgsolar.local","password":"你的API密码"}'
```

## 4. MQTT 接口（App ↔ cloud_comm）

完整协议见 [cloud-comm-api.md](./cloud-comm-api.md) 与 [integration-protocol-v1.md](./integration-protocol-v1.md)。

### 4.1 设备上行，App 订阅

| Topic | Payload type | 用途 | App 处理 |
|-------|--------------|------|----------|
| `device/{device_id}/telemetry` | `telemetry` | 电量、速度、姿态、导航状态 | 主监控页实时刷新 |
| `device/{device_id}/connection` | `connection` | `online/offline/connection_broken` | 顶部在线状态 |
| `device/{device_id}/event` | `cmd_feedback` / `param_feedback` | 指令/参数反馈 | Toast + 本地日志 |

App 会先校验：

- `schema == "vgsolar.cloud_comm.v1"`
- `device_id == 当前选中设备`
- telemetry/connection 的 `type` 与 topic 匹配

### 4.2 App 下行，设备订阅

| Topic | Payload type | 用途 | App 入口 |
|-------|--------------|------|----------|
| `device/{device_id}/remote` | `remote` | 摇杆遥控速度 | `publishRemote()` |
| `device/{device_id}/cmd` | `cmd` | start/stop/pause/resume/estop | `publishCmd()` |
| `device/{device_id}/config` | `config` | 参数下发 | `publishConfig()` |

遥控映射：

| 操作 | `linear_velocity_mps` | `angular_velocity_radps` |
|------|------------------------|--------------------------|
| 前进 | `+0.15` | `0.0` |
| 后退 | `-0.15` | `0.0` |
| 左转 | `0.0` | `+0.4` |
| 右转 | `0.0` | `-0.4` |
| 停止 | `0.0` | `0.0` |
| 急停 | 发布 `cmd.action = "estop"` | - |

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
| 硬件真实 `device_id` 是否为 `rk3588` | 硬件组 | 待确认 |
| `cloud_comm` 是否使用 `device/{device_id}/...` 主题 | 硬件组 | 待确认 |
| telemetry 是否含 `schema/type/device_id/timestamp_ms/status` | 硬件组 | 待确认 |
| HTTP API 是否部署并通过 `/health` | 后端/App | 待验证 |
| App `local.properties` 是否填入真实 MQTT 密码 | App | 本机填写 |

