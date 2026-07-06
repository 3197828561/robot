# 预联调检查（周三）

硬件组发布真实 telemetry 样例后，App 组按本清单逐项核对。**P0 必须当日清零**方可进入周四现场联调。

## 硬件组交付物

- [ ] 3 组真实 JSON：`telemetry-idle` / `telemetry-moving` / `telemetry-fault`（可对比 [samples/](./samples/)）
- [ ] 确认 `device_id` 最终值
- [ ] 确认 Broker 账号（App 用 `app_user_001`，设备用 `robot_device_001`）
- [ ] 确认 remote 线速度/角速度单位与 App 默认值（0.15 m/s、0.4 rad/s）是否可接受

## App 侧核对

| 字段 | 硬件 JSON 键名 | App UI | 通过 |
|------|---------------|--------|------|
| 电量 | `battery_percent` | 电池电量 % | [ ] |
| 在线 | `fcu_connected` / connection | 设备连接 | [ ] |
| 温度 | `temperature_c` | 设备温度 | [ ] |
| 里程 | `total_mileage_m` | 总里程 | [ ] |
| 清洁行数 | `cleaned_rows` | 已清洁行数 | [ ] |
| 气压 | `pressure_kpa` | 大气气压 | [ ] |
| 防摔 | `anti_fall_left_m` / `right` | 防摔距离 | [ ] |
| 运动 | `motion_state` 或 `linear_velocity_mps` | 运动状态 | [ ] |
| 故障 | `fault_code` | 设备状态 | [ ] |
| 作业 | `nav_status.nav_state` | 作业状态 | [ ] |

## 字段名不一致时的处理

在 [MqttModels.kt](../app/src/main/java/com/robot/solar/network/mqtt/MqttModels.kt) 的 `RoverStatus` 上增加 `@SerializedName("硬件实际键名")` 别名，**不要**改硬件固件（联调期优先 App 适配）。

## device_id 过滤

- [ ] App 发布 remote/cmd 时 JSON 内 `device_id` 与硬件一致
- [ ] 硬件忽略其他 device_id 的指令

## 预联调通过标准

- [ ] 桌面环回 [desktop-mqtt-test.md](./desktop-mqtt-test.md) 11 项已通过
- [ ] 真实 telemetry 至少 1 组在 App 上正确展示
- [ ] P0 问题数 = 0
