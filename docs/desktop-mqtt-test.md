# 桌面 MQTT 环回测试指南（周二）

在无真机条件下，用 PC 上的 MQTTX 或 Mosquitto 客户端模拟硬件侧，验证 App 与 Broker 双向通信。

## 前置条件

1. `local.properties` 已配置 Broker（见 `local.properties.example`）
2. App 已安装到真机或模拟器（模拟器需能访问公网 Broker）
3. 已登录并选择设备 `crawler_00000001`（或使用 `mqtt.default_device_id` fallback）

## 1. 订阅 App 下发的指令

在 PC 上（替换 `{device_id}`、`{user}`、`{pass}`）：

```bash
mosquitto_sub -h 47.103.157.213 -p 1883 -u app_user_001 -P "你的密码" \
  -t "device/crawler/crawler_00000001/remote" -t "device/crawler/crawler_00000001/cmd" -v
```

## 2. 模拟 telemetry 上报

```bash
mosquitto_pub -h 47.103.157.213 -p 1883 -u robot_device_001 -P "设备密码" \
  -t "device/crawler/crawler_00000001/status" -m @docs/samples/status-idle.json
```

循环发送（约 1Hz）：

```bash
while true; do
  mosquitto_pub -h 47.103.157.213 -p 1883 -u robot_device_001 -P "设备密码" \
    -t "device/crawler/crawler_00000001/status" -m @docs/samples/status-moving.json
  sleep 1
done
```

## 3. App 侧验证项

| # | 操作 | 预期 |
|---|------|------|
| 1 | 打开主页 | MQTT 显示「已连接」 |
| 2 | PC 发 status-idle.json | 电量 82%、运动「静止」 |
| 3 | PC 发 status-moving.json | 运动「移动中」、里程/行数更新 |
| 4 | App 点前进 | PC sub 收到 `linearSpeedCms>0` |
| 5 | App 点后退/左转/右转/停止 | remote JSON 速度字段正确 |
| 6 | App 点急停 | cmd JSON `action=estop` |
| 7 | App 点远程启动 | cmd JSON `action=start` |
| 8 | PC 发 event cmd_feedback | App Toast「指令执行成功/失败」 |
| 9 | 手机关 WiFi 10s 再开 | MQTT 自动重连，无需重启 App |
| 10 | 短按方向按钮 | 前 500ms 不发送 remote |
| 11 | 长按方向按钮 | 500ms 后按 20Hz 发送固定速度 |
| 12 | 同时按两个方向 | 发送零速度并提示“请勿同时按多个方向按钮” |
| 13 | 打开「日志信息」 | 有遥控/命令/系统日志记录 |

## 4. 模拟 heartbeat 上线

```bash
mosquitto_pub -h 47.103.157.213 -p 1883 -u robot_device_001 -P "设备密码" \
  -t "device/crawler/crawler_00000001/heartbeat" \
  -m '{"version":"1.0","deviceId":"crawler_00000001","productType":"crawler","timestamp":"2026-07-08T07:51:00.123Z","online":true}'
```

App「设备连接」应显示「在线」。

## 5. 模拟 cmd_ack

```bash
mosquitto_pub -h 47.103.157.213 -p 1883 -u robot_device_001 -P "设备密码" \
  -t "device/crawler/crawler_00000001/cmd_ack" \
  -m '{"version":"1.0","deviceId":"crawler_00000001","productType":"crawler","timestamp":"2026-07-08T07:51:00.223Z","cmdId":"cmd_20260708_000001","cmd":"start","ackStatus":"success","message":"Start command success","errorCode":null}'
```

## 6. 问题记录模板

| 优先级 | 现象 | 复现步骤 | 负责人 |
|--------|------|----------|--------|
| P0 | | | |
| P1 | | | |

通过标准：**上表 1–11 全部通过**，且无 P0 未关闭。
