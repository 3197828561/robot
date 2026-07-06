# 当前上下文压缩摘要

更新时间：2026-07-06

## 项目定位

正式开发一款对标怪虫 AI-Kwun 的 Android 光伏清洁机器人 App。

- 本地项目：`c:\Users\31978\AndroidStudioProjects\project01`
- GitHub：`https://github.com/3197828561/robot`
- 正式包名：`com.robot.solar`
- 最低 Android：API 26

## 关键资料

- 怪虫说明书：`c:\Users\31978\Desktop\堒S500-使用说明书-20250806.pdf`
- cloud_comm MQTT API：`f:\新建文件夹\xwechat_files\wxid_s09dkz9rx9tu22_46a3\msg\file\2026-07\API.md`
- 阿里云服务器记录：`f:\新建文件夹\xwechat_files\wxid_s09dkz9rx9tu22_46a3\msg\file\2026-07\阿里云服务器(1).pdf`

## 已知服务器状态

- ECS 公网 IP：`47.103.157.213`
- 系统：Ubuntu 24.04.4 LTS
- 日常用户：`robot`
- 项目目录：`/opt/robot-platform`
- Docker / Docker Compose 已安装
- EMQX 5.8.6 已部署并通过 Docker Compose 管理
- MQTT 1883 已公网测试通过
- EMQX Dashboard 通过 SSH 隧道访问：`ssh -L 18083:127.0.0.1:18083 robot@47.103.157.213`
- 已关闭匿名 MQTT
- 已创建 MQTT 账号：`app_user_001`、`robot_device_001`

注意：不要把密码写入 Git。PDF 中出现过 root、robot、EMQX、MQTT 密码，联调完成后应轮换。

## 当前最大风险

1. 服务器已有 EMQX，但仓库 `deploy/docker-compose.yml` 也包含 EMQX，直接运行会产生第二套 EMQX 或 1883 端口冲突。
2. HTTP API（FastAPI + PostgreSQL + Nginx）可能尚未在服务器部署；App 登录、设备列表、作业记录等依赖 HTTP。
3. 需要确认硬件 `cloud_comm` 是否严格使用 `device/{device_id}/telemetry`、`device/{device_id}/remote`、`device/{device_id}/cmd` 等 API.md 主题。
4. `device_id` 仍需硬件组确认，当前默认按 `rk3588`。

## 当前工程已做

- `docs/integration-protocol-v1.md`：联调协议 v1（App ↔ 硬件组）
- `docs/desktop-mqtt-test.md`：桌面 MQTT 环回测试步骤
- `docs/preflight-checklist.md`：周三预联调检查
- `docs/integration-report-template.md` / `integration-backlog.md`：周五收尾
- `docs/samples/telemetry-*.json`：三组 telemetry 样例
- App：`CloudCommMqttManager` 使用 `BuildConfig` 配置 Broker；`RoverStatus` 扩展联调字段；主页核心数据 UI 绑定

## 下一步推荐顺序

1. 先用 `docs/server-http-only-deploy.md` 在现有服务器上只补 HTTP，不动已有 EMQX。
2. 填写 `local.properties`，使用 `47.103.157.213` 和 `app_user_001`，密码只在本机填写，不提交。
3. 用 MQTTX 抓一条真实 telemetry，确认主题和 JSON 与 `docs/cloud-comm-api.md` 一致。
4. Android Studio Sync / Make Project，若有编译错误先修编译，再联调。
5. D4 与硬件组联调遥控：App 发布 remote/cmd，机器人反馈 event/telemetry。
