# cloud_comm MQTT API 速查

完整联调协议见 [integration-protocol-v1.md](./integration-protocol-v1.md)。

Schema：`vgsolar.cloud_comm.v1`

## 主题

```
device/{device_id}/telemetry    # 设备 → App，~1Hz
device/{device_id}/event        # 设备 → App，事件
device/{device_id}/connection   # 设备 → App，在线状态
device/{device_id}/cmd          # App → 设备，系统命令
device/{device_id}/remote       # App → 设备，遥控速度
device/{device_id}/config       # App → 设备，参数（v2）
```

## App 配置

见项目根目录 `local.properties.example`。

## 代码入口

- MQTT 管理：[CloudCommMqttManager.kt](../app/src/main/java/com/robot/solar/network/mqtt/CloudCommMqttManager.kt)
- 数据模型：[MqttModels.kt](../app/src/main/java/com/robot/solar/network/mqtt/MqttModels.kt)
