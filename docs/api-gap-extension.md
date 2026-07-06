# API 缺口与扩展规划（说明书 vs cloud_comm API.md）

## 优先级说明

| 级别 | 含义 |
|------|------|
| **P0** | 本周联调必需 |
| **P1** | 界面可占位，有数据后替换 |
| **P2** | 后续版本扩展 |

## 字段对照表

| 说明书 / App 展示 | cloud_comm API.md | 优先级 | 扩展建议 |
|-------------------|-------------------|--------|----------|
| 电池电量 | `telemetry.status.battery_percent` | **P0** | 已实现 |
| 设备在线 | `connection.state` / `status.fcu_connected` | **P0** | 已实现 |
| GPS / 航向 / 速度 | `telemetry.status.*` | **P1** | 综合信息卡绑定 |
| 导航状态 | `telemetry.nav_status` | **P1** | 绑定 nav_state 等 |
| 遥控 | `device/{id}/remote` | **P0** | 已实现 |
| 启停急停 | `device/{id}/cmd` | **P0** | 已实现 |
| 总里程 | 无 | **P2** | 扩展 `telemetry.status.total_distance_m` |
| 设备温度 | 无 | **P2** | 扩展 `telemetry.status.device_temp_c` |
| 大气气压 | 无 | **P2** | 扩展或 HTTP 设备详情 |
| 已清洁行数 | 无 | **P2** | HTTP `/api/jobs` 或 telemetry 扩展 |
| 防摔距离 L/R | 无 | **P2** | FCU → cloud_comm 新字段 |
| 组件/栅线/尺寸/片数 | 无 | **P2** | `config` + param_index 文档 |
| 停泊/清洁模式/行数 | 无 | **P2** | `config` + 枚举表 |
| 用户/设备绑定 | 无 | **P0** | **HTTP** `/api/devices`（已实现） |
| 作业记录 | 无 | **P0** | **HTTP** `/api/jobs`（已实现） |
| 固件升级 | 无 | **P0** | **HTTP** `/api/firmware/*`（占位+联调） |
| WiFi 设置 | 无 | **P0** | **HTTP** `/api/devices/{id}/wifi`（已实现） |

## 扩展 telemetry 示例（供硬件组参考）

```json
{
  "schema": "vgsolar.cloud_comm.v1",
  "type": "telemetry",
  "status": {
    "battery_percent": 80.5,
    "total_distance_m": 12856,
    "device_temp_c": 42.0,
    "cleaned_rows": 156,
    "anti_fall_left_m": 2.1,
    "anti_fall_right_m": 2.05
  }
}
```

建议在 `schema` 不变前提下 **向后兼容** 增加可选字段；App 端对缺失字段隐藏或显示 `--`。

## App 当前策略

- **P0 字段**：真实绑定 MQTT / HTTP  
- **P1 字段**：有 telemetry 则显示，否则 `--`  
- **P2 字段**：主界面保留布局占位，待扩展文档发布后对接
