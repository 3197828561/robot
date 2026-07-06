# 光伏机器人远程控制（正式版）

包名：`com.robot.solar`  
对标怪虫 AI-Kwun · 通信协议 `vgsolar.cloud_comm.v1` · HTTP 业务 API

## 文档

| 文档 | 说明 |
|------|------|
| [docs/deploy-aliyun.md](docs/deploy-aliyun.md) | 阿里云从零部署 EMQX + API + PostgreSQL |
| [docs/server-http-only-deploy.md](docs/server-http-only-deploy.md) | 当前服务器已部署 EMQX 时，只补 HTTP API |
| [docs/interfaces-summary.md](docs/interfaces-summary.md) | App / HTTP / MQTT / 硬件接口总览 |
| [docs/http-api-guide.md](docs/http-api-guide.md) | HTTP 接口协作与 App 联调 |
| [docs/openapi.yaml](docs/openapi.yaml) | REST 契约 |
| [docs/api-gap-extension.md](docs/api-gap-extension.md) | 说明书字段扩展规划 |
| [docs/integration-protocol-v1.md](docs/integration-protocol-v1.md) | **联调协议 v1**（App ↔ 硬件组） |
| [docs/cloud-comm-api.md](docs/cloud-comm-api.md) | MQTT 主题速查 |
| [docs/desktop-mqtt-test.md](docs/desktop-mqtt-test.md) | 桌面 MQTT 环回测试 |
| [docs/preflight-checklist.md](docs/preflight-checklist.md) | 周三预联调检查 |
| [docs/integration-report-template.md](docs/integration-report-template.md) | 联调报告模板 |
| [docs/integration-backlog.md](docs/integration-backlog.md) | 联调后迭代 backlog |
| [docs/hw-integration-checklist.md](docs/hw-integration-checklist.md) | D4 硬件联调清单（含现场步骤） |
| [docs/context-handoff.md](docs/context-handoff.md) | 当前上下文压缩摘要 |
| [local.properties.example](local.properties.example) | App 连接配置模板 |
| [deploy/](deploy/) | Docker Compose 一键后端 |

## App 配置

复制 `local.properties.example` 为 `local.properties`（勿提交 Git）：

```properties
api.base.url=http://47.103.157.213/api
mqtt.host=47.103.157.213
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=你的App MQTT密码
```

默认测试账号（API 首次启动自动创建）：见 `deploy/.env.example`

## 运行流程

1. 当前服务器已部署 EMQX，先按 `docs/server-http-only-deploy.md` 只补 HTTP API
2. Android Studio Sync → Run
3. 登录 → 选择设备 → 主监控页（MQTT telemetry + 摇杆 remote）

## 架构

- **HTTP**：登录、设备列表、作业记录、固件、WiFi  
- **MQTT**：telemetry / event / connection / cmd / remote  
- **Room**：本地操作日志（联调排障）
