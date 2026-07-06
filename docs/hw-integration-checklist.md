# D4 硬件联调检查清单

联调前请硬件组与 App 组共同填写 [deploy-aliyun.md](./deploy-aliyun.md) 中的 **联调信息表**，并阅读 [integration-protocol-v1.md](./integration-protocol-v1.md)。

## 环境检查

- [ ] ECS 上 EMQX、API、Nginx 均已 `docker compose ps` 为 Up（或已有 EMQX + HTTP-only 部署）
- [ ] 安全组已放行：22、80、1883（及硬件组出口 IP 白名单，若启用）
- [ ] 机器人 `cloud_comm` 与 App 使用 **同一 Broker**
- [ ] `device_id` 一致（默认联调：`rk3588`）
- [ ] App `local.properties` 已配置 `mqtt.host` / `mqtt.username` / `mqtt.password`

## MQTT 检查

- [ ] MQTTX 订阅 `device/rk3588/telemetry` 每秒有 JSON
- [ ] MQTTX 订阅 `device/rk3588/connection` 上线为 `online`
- [ ] App 发布 `device/rk3588/remote` 后机器人有动作
- [ ] App 发布 `device/rk3588/cmd` action=`estop` 后机器人急停
- [ ] App 收到 `device/rk3588/event` type=`cmd_feedback`

## HTTP 检查

- [ ] `POST /api/auth/login` 返回 token
- [ ] App 登录后设备列表可见 `rk3588`
- [ ] 作业记录 / 固件 / WiFi 页面至少各成功 1 次请求

## 周四现场联调顺序（Day 1）

### 1. 连通性（约 15 min）

- [ ] 手机 4G/WiFi 可达 Broker
- [ ] App 登录 → 选设备 → 主页 MQTT「已连接」
- [ ] 设备连接从「等待状态上报」→「在线」

### 2. 状态上报（约 30 min）

- [ ] 机器人开机后 telemetry ~1Hz
- [ ] 电量、温度、里程、运动状态 UI 非占位「--」
- [ ] UI 刷新延迟 < 2s（记录实测值：____ s）

### 3. 运动控制（约 45 min）

- [ ] 前进 / 后退 / 左转 / 右转 / 停止
- [ ] 急停：行驶中按急停，机器人立即停 + App 有日志
- [ ] MQTT 断开时按钮发送失败且有日志

### 4. 可靠性（约 30 min）

- [ ] 弱网/断网恢复后自动重连
- [ ] App 切后台再回前台 MQTT 正常
- [ ] 连续运行 30 min 无崩溃

### 5. 日志审计（约 15 min）

- [ ] 「日志信息」页有遥控、命令、系统事件

## 记录模板

| 项 | 值 |
|----|-----|
| 联调日期 | |
| Broker | tcp:// |
| device_id | |
| API_BASE_URL | |
| 遥控是否正常 | 是/否 |
| telemetry 频率 | ~1s |
| UI 刷新延迟 | s |
| 问题记录 | |

## 相关文档

- [桌面环回测试](./desktop-mqtt-test.md)
- [预联调检查](./preflight-checklist.md)
- [联调报告模板](./integration-report-template.md)
