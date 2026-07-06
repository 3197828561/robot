# 联调协议 v1（App ↔ 硬件组）

版本：`vgsolar.cloud_comm.v1`  
更新：2026-07-06  
状态：**待硬件组签字确认**

---

## 1. 联调 Kickoff 确认清单

联调前双方填写并确认：


| 项              | App 侧默认值                    | 硬件组确认值 |
| -------------- | --------------------------- | ------ |
| Broker 地址      | `tcp://47.103.157.213:1883` |        |
| MQTT 用户名（App）  | `app_user_001`              |        |
| MQTT 用户名（设备）   | `robot_device_001`          |        |
| device_id      | `rk3588`                    |        |
| HTTP API       | `http://47.103.157.213/api` |        |
| 手机网络           | 4G/5G 或 WiFi 可达公网 Broker    |        |
| telemetry 上报周期 | 约 1 Hz                      |        |


---



## 2. MQTT 主题（cloud_comm）


| 方向       | 主题                              | QoS | 说明               |
| -------- | ------------------------------- | --- | ---------------- |
| 设备 → App | `device/{device_id}/telemetry`  | 1   | 周期状态（约 1s）       |
| 设备 → App | `device/{device_id}/event`      | 1   | 指令反馈、告警          |
| 设备 → App | `device/{device_id}/connection` | 1   | online / offline |
| App → 设备 | `device/{device_id}/cmd`        | 1   |                  |



|          |                             |     |             |
| -------- | --------------------------- | --- | ----------- |
|          |                             |     | 系统命令        |
| App → 设备 | `device/{device_id}/remote` | 1   | 线速度/角速度遥控   |
| App → 设备 | `device/{device_id}/config` | 1   | 参数下发（联调范围外） |


**注意**：不再使用 Demo 旧主题 `solarbot/robot/cmd` 与 `solarbot/robot/status`。

---



## 3. 控制指令 JSON



### 3.1 遥控 `remote`（App 摇杆）

发布主题：`device/{device_id}/remote`

```json
{
  "schema": "vgsolar.cloud_comm.v1",
  "type": "remote",
  "cmd_id": "cmd-1717654321000",
  "device_id": "rk3588",
  "timestamp_ms": 1717654321000,
  "linear_velocity_mps": 0.15,
  "angular_velocity_radps": 0.0,
  "duration_ms": 300
}
```


| 操作  | linear_velocity_mps | angular_velocity_radps |
| --- | ------------------- | ---------------------- |
| 前进  | +0.15               | 0                      |
| 后退  | -0.15               | 0                      |
| 左转  | 0                   | +0.4                   |
| 右转  | 0                   | -0.4                   |
| 停止  | 0                   | 0                      |




### 3.2 系统命令 `cmd`

发布主题：`device/{device_id}/cmd`

```json
{
  "schema": "vgsolar.cloud_comm.v1",
  "type": "cmd",
  "cmd_id": "cmd-1717654321000",
  "device_id": "rk3588",
  "timestamp_ms": 1717654321000,
  "action": "estop"
}
```


| action        | 语义   | 优先级    |
| ------------- | ---- | ------ |
| `start`       | 远程启动 | 普通     |
| `stop`        | 停止作业 | 普通     |
| `pause`       | 暂停   | 普通     |
| `resume`      | 恢复   | 普通     |
| `estop`       | 急停   | **最高** |
| `clear_estop` | 解除急停 | 需人工确认  |


**急停语义**：`estop` 与 `remote(0,0)` 独立；硬件侧 `estop` 必须立即切断驱动。

---



## 4. 状态上报 JSON（telemetry 最小集）

订阅主题：`device/{device_id}/telemetry`

联调最小字段（App 已解析并绑定 UI）：

```json
{
  "schema": "vgsolar.cloud_comm.v1",
  "type": "telemetry",
  "device_id": "rk3588",
  "timestamp_ms": 1717654321000,
  "status": {
    "battery_percent": 82.5,
    "fcu_connected": true,
    "motion_state": 1,
    "fault_code": 0,
    "temperature_c": 42.0,
    "total_mileage_m": 12856.0,
    "cleaned_rows": 156,
    "pressure_kpa": 101.3,
    "anti_fall_left_m": 2.10,
    "anti_fall_right_m": 2.05,
    "yaw_deg": 188.0,
    "pitch_deg": 12.6,
    "linear_velocity_mps": 0.12
  },
  "nav_status": {
    "nav_state": 2
  }
}
```



### 字段对照表


| JSON 字段                      | 类型     | App UI 展示  | 备注             |
| ---------------------------- | ------ | ---------- | -------------- |
| `status.battery_percent`     | number | 电池电量 %     | 0–100          |
| `status.fcu_connected`       | bool   | 设备连接 在线/离线 |                |
| `status.motion_state`        | int    | 运动状态       | 0=静止 1=移动（可协商） |
| `status.fault_code`          | int    | 设备状态       | 0=正常           |
| `status.temperature_c`       | number | 设备温度 °C    | 联调扩展字段         |
| `status.total_mileage_m`     | number | 总里程 m      | 联调扩展字段         |
| `status.cleaned_rows`        | int    | 已清洁行数      | 联调扩展字段         |
| `status.pressure_kpa`        | number | 大气气压 kPa   | 联调扩展字段         |
| `status.anti_fall_left_m`    | number | 防摔距离左 m    | 联调扩展字段         |
| `status.anti_fall_right_m`   | number | 防摔距离右 m    | 联调扩展字段         |
| `status.yaw_deg`             | number | 阵列方向（航向）   |                |
| `status.pitch_deg`           | number | 坡度         |                |
| `status.linear_velocity_mps` | number | 运动状态辅助     | >0.01 视为移动     |
| `timestamp_ms`               | long   | 综合信息-时间戳   |                |
| `nav_status.nav_state`       | int    | 作业状态       |                |




### connection 消息

```json
{
  "schema": "vgsolar.cloud_comm.v1",
  "type": "connection",
  "device_id": "rk3588",
  "state": "online"
}
```



### event 指令反馈

```json
{
  "schema": "vgsolar.cloud_comm.v1",
  "type": "cmd_feedback",
  "received_cmd_type": 2,
  "exec_status": 1
}
```

`exec_status`：1=成功，2=失败。

---



## 5. device_id 过滤

- App 发布时 JSON 内 `device_id` 必须与当前选中设备一致。
- 硬件侧应只执行匹配本机 `device_id` 的 cmd/remote。

---



## 6. 网络拓扑

```text
Android App ──4G/WiFi──► 阿里云 EMQX :1883 ◄──cloud_comm── 机器人网关
                │
                └── HTTP :80/api（登录、设备列表、作业记录等）
```

手机 **无需** 与机器人同局域网；双方均连接同一 Broker 即可。

---



## 7. 不在 v1 联调范围

- WiFi 配网、固件升级、作业记录写入（HTTP 已预留，联调日以 MQTT 为主）
- 场景参数/控制模式卡片可编辑下发
- TLS / 8883 加密（上线期再切）

---



## 8. 签字确认


| 角色    | 姓名  | 日期  | 确认  |
| ----- | --- | --- | --- |
| App 组 |     |     |     |
| 硬件组   |     |     |     |


