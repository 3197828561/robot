# 第三版 APP 接口

> 本文依据当前分支中的 Android APP 实际代码整理，不以规划稿或第二版旧行为作为实现依据。
>
> 当前 APP 版本：`1.2.0 (3)`。本文名称中的“第三版”是文档版本；当前 MQTT 消息中的
> `version` 仍固定为 `"1.0"`，`BuildConfig.MISSION_COMMAND_API_CAPABILITY` 仍为
> `mission_command_v2`。Robot 端不得因为本文名称而把消息版本改成 `"3.0"`。
>
> 主要实现位置：
>
> - HTTP：`network/http/ApiClient.kt`、`network/http/dto/HttpDtos.kt`
> - MQTT：`network/mqtt/CloudCommMqttManager.kt`、`network/mqtt/MqttModels.kt`
> - 指令状态机：`viewmodel/MainViewModel.kt`、`viewmodel/ManualControlPolicy.kt`
> - 地图格式：`map/PvMapModels.kt`、`map/PvMapParser.kt`
> - 本地数据：`data/session/`、`database/`、`repository/Repositories.kt`

# 二、接口设计

## 1. MQTT Topic 格式定义

当前 Topic 统一格式：

```text
device/{productType}/{deviceId}/{topicType}
```

其中：

| 参数 | 类型 | 当前 APP 含义 |
| --- | --- | --- |
| `productType` | string | 设备类型，当前识别 `crawler`、`hanging`、`installer` |
| `deviceId` | string | Robot 硬件设备编号 |
| `topicType` | string | `cmd`、`remote`、`heartbeat`、`status`、`cmd_ack`、`map`、`pose` |

当前方向和 QoS：

| 方向 | Topic | QoS | retain | 用途 |
| --- | --- | ---: | --- | --- |
| APP → Robot | `device/{productType}/{deviceId}/cmd` | 1 | false | 任务、模式和安全命令 |
| APP → Robot | `device/{productType}/{deviceId}/remote` | 0 | false | 手动遥控速度 |
| Robot → APP | `device/{productType}/{deviceId}/heartbeat` | 1 | 由 Robot 决定 | 在线心跳 |
| Robot → APP | `device/{productType}/{deviceId}/status` | 1 | 由 Robot 决定 | 设备和任务状态 |
| Robot → APP | `device/{productType}/{deviceId}/cmd_ack` | 1 | 由 Robot 决定 | 命令同步受理结果 |
| Robot → APP | `device/{productType}/{deviceId}/map` | 1 | 由 Robot 决定 | 地图版本与下载地址通知 |
| Robot → APP | `device/{productType}/{deviceId}/pose` | 1 | 由 Robot 决定 | Robot 地图离散位姿 |

APP 绑定设备后一次性订阅全部 5 个下行 Topic。APP 发布的 MQTT 消息全部
`retain=false`。

### 1.1 MQTT 设备身份选择

APP 从服务器设备列表选择设备后，按以下顺序生成 MQTT Topic 身份：

1. 若所选 `deviceId` 的下划线前缀属于 `crawler/hanging/installer`，直接使用该
   `deviceId`。
2. 否则回退到 `BuildConfig.MQTT_DEFAULT_DEVICE_ID`。
3. 优先使用服务器返回的非空 `productType`。
4. 未返回 `productType` 时，从硬件 `deviceId` 前缀推断。
5. 仍无法推断时，使用 `BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE`。

因此，服务器设备列表中的 `device_id` 应直接返回 Robot 的真实硬件编号，避免 APP
回退到默认 MQTT 设备。

### 1.2 MQTT 通用消息外壳和校验

所有 MQTT JSON 使用 camelCase。当前 APP 对每条 Robot 上行消息先执行以下公共校验：

| 字段 | 类型 | 必填 | 当前校验 |
| --- | --- | --- | --- |
| `version` | string | 是 | 必须严格等于 `"1.0"` |
| `productType` | string | 是 | 必须严格等于当前绑定的 `productType` |
| `deviceId` | string | 是 | 必须严格等于当前绑定的 `deviceId` |
| `timestamp` | string | 接口定义必填 | APP 当前仅解析和保存，不校验格式或新旧 |

任一身份字段不匹配时，APP 忽略整条消息并记录协议告警。JSON 无法解析时同样忽略。

APP 发送的时间戳格式固定为 UTC：

```text
yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
```

示例：

```text
2026-07-30T08:30:00.123Z
```

## 2. MQTT Broker 连接参数

| 参数 | 当前实现 |
| --- | --- |
| 协议 | MQTT 3.1.1 |
| Transport | `tcp://`，当前未启用 TLS |
| Host | `local.properties` 的 `mqtt.host` → `BuildConfig.MQTT_HOST` |
| Port | `local.properties` 的 `mqtt.port` → `BuildConfig.MQTT_PORT`，默认 `1883` |
| Username | `mqtt.username` → `BuildConfig.MQTT_USERNAME` |
| Password | `mqtt.password` → `BuildConfig.MQTT_PASSWORD`，不得写入本文或提交 Git |
| Client ID | `solar_app_` + UUID 前 8 位，每次创建客户端重新生成 |
| Clean Session | `true` |
| Keep Alive | 20 秒 |
| Connection Timeout | 15 秒 |
| Automatic Reconnect | Paho 自动重连关闭，由 APP 自行调度 |
| Persistence | `MemoryPersistence`，不做 MQTT 离线持久化 |

连接失败或连接丢失后，APP 等待 3 秒再发起一次连接；若仍失败，连接回调会继续安排下一次
3 秒后的尝试。Android 网络恢复时也会立即尝试补连。

MQTT 或网络断开时，APP 会清除只能由当前 Robot 会话证明的状态：

- 在线状态、心跳时间；
- 电量、完整状态和任务状态；
- Robot 位姿；
- 已确认的手动模式和正在发送的遥控；
- 正在等待的命令进入 `CONNECTION_LOST`。

已下载的地图缓存不属于 Robot 运行会话，断线后保留。

## 3. APP—Robot 上行接口

### 3.1 `cmd` 控制命令接口

#### 1. Topic 名称

```text
device/{productType}/{deviceId}/cmd
```

#### 2. 通用字段、含义、取值

| 字段 | 类型 | 必填 | 当前 APP 取值/规则 |
| --- | --- | --- | --- |
| `version` | string | 是 | 固定 `"1.0"` |
| `cmdId` | string | 是 | `cmd_{deviceId}_{毫秒时间}_{UUID前8位}` |
| `deviceId` | string | 是 | 当前 MQTT 绑定设备 |
| `productType` | string | 是 | 当前 MQTT 绑定设备类型 |
| `timestamp` | string | 是 | APP 生成的 UTC 毫秒时间 |
| `cmd` | string | 是 | 当前支持 9 种命令，见下表 |
| `params` | object | 是 | 随命令变化；无参数命令发送 `{}` |

当前支持命令：

| `cmd` | 含义 | `params` |
| --- | --- | --- |
| `start` | 开始 coverage 任务 | `taskKind` + `coverage` |
| `stop` | 停止当前任务 | `targetMissionId` |
| `pause` | 暂停当前任务 | `targetMissionId` |
| `resume` | 恢复当前暂停任务 | `targetMissionId` |
| `replan` | 对当前 coverage 任务重新规划 | `targetMissionId` |
| `manual` | 进入手动模式 | `{}` |
| `auto` | 返回自动模式 | `{}` |
| `estop` | 紧急停止 | `{}` |
| `clear_estop` | 发起解除急停 | `{}` |

未知命令在 APP 端直接拒绝，不会发布到 MQTT。

#### 3. 通用发送和回执规则

1. `cmd` 使用 QoS 1、`retain=false`。
2. APP 对普通操作做 400 ms 防抖。
3. 同一时刻只允许一条命令等待回执。
4. 发布成功后等待相同 `cmdId` 且相同 `cmd` 的 `cmd_ack`。
5. 5 秒未收到匹配回执，APP 将命令标记为 `TIMEOUT`。
6. MQTT 断开时，等待中的命令标记为 `CONNECTION_LOST`。
7. `FAILED`、`TIMEOUT`、`CONNECTION_LOST` 可重试。
8. 重试复用原 `PreparedCommand`，即 `cmdId`、`timestamp`、命令参数和完整 payload 均不变。
9. 超时后到达的相同 `cmdId`、相同 `cmd` 的晚到 ACK 仍会收敛原命令状态。
10. `cmd_ack.success` 只表示 Robot 任务层同步接受命令，不表示任务执行完成。

#### 4. 通用空参数命令示例

以下结构适用于 `manual`、`auto`、`estop`、`clear_estop`，仅替换 `cmd`：

```json
{
  "version": "1.0",
  "cmdId": "cmd_crawler_00000001_1785398400123_a1b2c3d4",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:00:00.123Z",
  "cmd": "manual",
  "params": {}
}
```

### 3.2 `start` coverage 任务命令

#### 1. `params` 字段

```json
{
  "taskKind": "coverage",
  "coverage": {
    "mapId": 7,
    "mapVersion": 3,
    "useCurrentPose": false,
    "start": {
      "blockId": 2,
      "cellRow": 4,
      "cellCol": 5,
      "innerRow": 0,
      "innerCol": 0,
      "heading": 1
    },
    "targetBlockIds": [2, 4],
    "globalPlan": true
  }
}
```

| 字段 | 类型 | 必填 | 当前 APP 规则 |
| --- | --- | --- | --- |
| `taskKind` | string | 是 | 固定 `coverage` |
| `coverage.mapId` | uint32 | 是 | 来自当前已验证地图，范围 `0..4294967295` |
| `coverage.mapVersion` | uint32 | 是 | 来自当前已验证地图，范围 `0..4294967295` |
| `coverage.useCurrentPose` | boolean | 是 | 是否使用 Robot 当前位姿作为起点 |
| `coverage.start` | object/null | 条件必填 | `useCurrentPose=false` 时必填；为 true 时发送 `null` |
| `coverage.start.blockId` | positive integer | 是 | 必须存在于当前地图 |
| `coverage.start.cellRow` | integer | 是 | 对应 block 中必须存在该 cell |
| `coverage.start.cellCol` | integer | 是 | 对应 block 中必须存在该 cell |
| `coverage.start.innerRow` | integer | 是 | `0..cell_model.inner_rows-1` |
| `coverage.start.innerCol` | integer | 是 | `0..cell_model.inner_cols-1` |
| `coverage.start.heading` | integer | 是 | `0..3`，定义与 pose 的 `headingCode` 相同 |
| `coverage.targetBlockIds` | integer[] | 是 | 至少 1 项；每项大于 0、无重复、存在且 `cleanable=true` |
| `coverage.globalPlan` | boolean | 是 | 由任务设置界面传入 |

注意：Gson 当前会把 `start=null` 序列化时省略该字段，而不是发送 JSON `null`。

#### 2. 完整示例

```json
{
  "version": "1.0",
  "cmdId": "cmd_crawler_00000001_1785398400123_a1b2c3d4",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:00:00.123Z",
  "cmd": "start",
  "params": {
    "taskKind": "coverage",
    "coverage": {
      "mapId": 7,
      "mapVersion": 3,
      "useCurrentPose": false,
      "start": {
        "blockId": 2,
        "cellRow": 4,
        "cellCol": 5,
        "innerRow": 0,
        "innerCol": 0,
        "heading": 1
      },
      "targetBlockIds": [2, 4],
      "globalPlan": true
    }
  }
}
```

成功 ACK 后，APP 进入“启动请求已受理，等待任务状态”，继续等待 `status` 中出现非空
`missionId`，并以 `runState` 判断任务最终状态。

### 3.3 目标任务命令

适用于：

```text
stop pause resume replan
```

#### 1. `params` 字段

| 字段 | 类型 | 必填 | 当前 APP 规则 |
| --- | --- | --- | --- |
| `targetMissionId` | string | 是 | 必须取自当前 Robot 会话最新 `status.missionId` |

完整示例：

```json
{
  "version": "1.0",
  "cmdId": "cmd_crawler_00000001_1785398401123_b1c2d3e4",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:00:01.123Z",
  "cmd": "pause",
  "params": {
    "targetMissionId": "mission-42"
  }
}
```

若当前 `status.missionId` 为空，APP 直接拒绝发送。MQTT 断线时 APP 会清空旧
`missionId`，避免重连后向新 Robot 会话发送过期任务编号。

当前按钮约束：

| 命令 | APP 启用条件 |
| --- | --- |
| `stop` | `missionId` 非空，且 `runState=starting/running/paused` |
| `pause` | 活动任务，且 `runState=starting/running` |
| `resume` | 活动任务，且 `runState=paused` |
| `replan` | 活动任务，且 `taskKind=coverage` |

### 3.4 `remote` 遥控接口

#### 1. Topic 名称

```text
device/{productType}/{deviceId}/remote
```

#### 2. 字段、含义、取值

| 字段 | 类型 | 必填 | 当前 APP 取值/规则 |
| --- | --- | --- | --- |
| `version` | string | 是 | 固定 `"1.0"` |
| `deviceId` | string | 是 | 当前 MQTT 绑定设备 |
| `productType` | string | 是 | 当前 MQTT 绑定设备类型 |
| `timestamp` | string | 是 | 每条 remote 重新生成 |
| `linearSpeedCms` | number | 是 | 发布层强制夹紧到 `[-50, 50]` cm/s |
| `angularSpeedRadps` | number | 是 | 发布层强制夹紧到 `[-0.5, 0.5]` rad/s |
| `durationMs` | integer | 是 | 当前 APP 固定默认 `300`；Robot 不应把它作为正常停车机制 |

#### 3. 速度设置和方向规则

APP 界面只让用户设置非负速度大小：

| 设置 | UI 范围 | 步进 | 默认值 |
| --- | ---: | ---: | ---: |
| 线速度 | `0..50 cm/s` | `1 cm/s` | `30 cm/s` |
| 角速度 | `0..0.5 rad/s` | `0.1 rad/s` | `0.3 rad/s` |

预设：

| 预设 | 线速度 | 角速度 |
| --- | ---: | ---: |
| 慢速 | 10 cm/s | 0.1 rad/s |
| 标准 | 30 cm/s | 0.3 rad/s |
| 高速 | 50 cm/s | 0.5 rad/s |

方向键自动绑定正负号：

| 方向 | `linearSpeedCms` | `angularSpeedRadps` |
| --- | ---: | ---: |
| 前进 | `+设定线速度` | `0` |
| 后退 | `-设定线速度` | `0` |
| 左转 | `0` | `+设定角速度` |
| 右转 | `0` | `-设定角速度` |
| 停止 | `0` | `0` |

速度设置按 MQTT `deviceId` 保存到本地，下次进入时恢复。

#### 4. 遥控状态机

非零 remote 只有同时满足以下条件才允许发送：

1. MQTT 已连接；
2. Robot 心跳在线；
3. `manual` 命令已收到成功 ACK；
4. 最新 `status.operationalMode == "manual"`；
5. 最新 `status.safetyState == "normal"`。

操作流程：

```text
点击进入手动模式
  → APP 发送 cmd=manual
  → 等待匹配的成功 cmd_ack
  → 等待 status.operationalMode=manual 且 safetyState=normal
  → 长按方向键 500 ms
  → 每 50 ms 发布一次 remote（约 20 Hz）
  → 松开/取消/离开页面/APP onPause/模式或安全状态变化时主动发送零速
  → 返回自动模式前先发送零速，再发送 cmd=auto
```

`remote` 使用 QoS 0、`retain=false`，没有单独 ACK。普通停止只发一条零速
`remote`，不等价于 `cmd=stop`；紧急停止使用 `cmd=estop`。

#### 5. 完整示例

前进：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:10:00.123Z",
  "linearSpeedCms": 30.0,
  "angularSpeedRadps": 0.0,
  "durationMs": 300
}
```

右转：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:10:00.173Z",
  "linearSpeedCms": 0.0,
  "angularSpeedRadps": -0.3,
  "durationMs": 300
}
```

主动停车：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:10:01.000Z",
  "linearSpeedCms": 0.0,
  "angularSpeedRadps": 0.0,
  "durationMs": 300
}
```

## 4. Robot—APP 下行接口

### 4.1 `heartbeat` 心跳接口

#### 1. Topic 名称

```text
device/{productType}/{deviceId}/heartbeat
```

#### 2. 用途

用于判断 Robot 是否在线。APP 日志不会逐条保存重复的在线心跳，只在在线状态发生变化时
记录“设备上线/离线”事件。

#### 3. 字段、含义、取值

| 字段 | 类型 | 必填 | 当前 APP 处理 |
| --- | --- | --- | --- |
| `version` | string | 是 | 必须为 `"1.0"` |
| `deviceId` | string | 是 | 必须匹配绑定设备 |
| `productType` | string | 是 | 必须匹配绑定设备类型 |
| `timestamp` | string | 是 | 解析但在线超时以 APP 本地接收时间计算 |
| `online` | boolean | 是 | `true` 刷新在线时间；其他值按离线处理 |

#### 4. 规则

- APP 每 500 ms 检查一次在线状态。
- 距最后一条 `online=true` 心跳超过 3 秒，Robot 判定离线。
- `online=false` 会立即判定离线并清除心跳状态。
- MQTT 或 Android 网络断开也会立即判定离线。
- APP 不使用 Robot 的 `timestamp` 计算 3 秒超时，避免两端时钟偏差。

#### 5. 完整示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:20:00.000Z",
  "online": true
}
```

### 4.2 `status` 设备与任务状态接口

#### 1. Topic 名称

```text
device/{productType}/{deviceId}/status
```

#### 2. 用途

该接口是 APP 展示 Robot 状态、判断任务最终结果、控制任务按钮和授权手动遥控的权威数据源。
`cmd_ack` 不能替代 `status`。

#### 3. 字段、含义、取值

所有业务字段在当前 Kotlin DTO 中均为 nullable；Robot 应按下表完整发送，以保证界面和按钮
状态正确。

通用和基础状态：

| 字段 | 类型 | APP 用途 |
| --- | --- | --- |
| `version` | string | 公共协议校验，必须 `"1.0"` |
| `deviceId` | string | 公共设备校验 |
| `productType` | string | 公共设备类型校验 |
| `timestamp` | string | 状态时间 |
| `workStatus` | string/null | 旧版工作状态摘要展示 |
| `controlMode` | string/null | 旧版控制模式摘要展示 |
| `batteryPercent` | number/null | 电量；APP 转为整数并夹紧到 `0..100` |
| `linearSpeedCms` | number/null | 当前线速度，cm/s |
| `angularSpeedRadps` | number/null | 当前角速度，rad/s |
| `deviceStatus` | string/null | 设备状态摘要 |
| `movementStatus` | string/null | 运动状态摘要 |
| `yawDeg` | number/null | 偏航角，度 |
| `pitchDeg` | number/null | 俯仰角，度 |
| `temperatureC` | number/null | 温度，℃ |
| `totalMileageM` | number/null | 累计里程，m |
| `cleanedRows` | integer/null | 已清洁行数 |
| `pressureKpa` | number/null | 压力，kPa |
| `antiFallLeftM` | number/null | 左防跌落距离，m |
| `antiFallRightM` | number/null | 右防跌落距离，m |

任务状态扩展：

| 字段 | 类型 | 当前 APP 用途/已识别值 |
| --- | --- | --- |
| `missionId` | string/null | 当前任务唯一编号；目标任务命令的数据源 |
| `taskKind` | string/null | 当前实现识别 `coverage` |
| `runState` | string/null | `idle/starting/running/paused/succeeded/failed/canceled/unknown` |
| `operationalMode` | string/null | `auto/manual`；手动遥控授权依赖此字段 |
| `safetyState` | string/null | `normal/low_battery/fault/estop/clearing_estop/unknown` |
| `phase` | string/null | `none/waiting_for_robot/resolving_start/planning/executing/placeholder/unknown` |
| `activeAction` | string/null | 当前 Robot 动作，展示与日志使用 |
| `waypointIndex` | integer/null | 当前航点索引 |
| `waypointCount` | integer/null | 总航点数 |
| `errorCode` | integer/null | 任务状态错误码；当前 DTO 为整数 |
| `errorRetryable` | boolean/null | 当前错误是否可重试 |
| `errorSource` | string/null | 错误来源 |
| `errorMessage` | string/null | 错误文本，用于展示和日志 |

注意：`status.errorCode` 是整数；`cmd_ack.errorCode` 是字符串，两者类型不同。

#### 4. APP 状态规则

- 活动任务定义：`missionId` 非空且 `runState` 为
  `starting/running/paused`。
- `start` 成功 ACK 后，等待非空 `missionId` 和任务 `runState`。
- `clear_estop` 成功 ACK 后，继续等待 `safetyState=normal`。
- `manual` 成功 ACK 后，仍需等待 `operationalMode=manual` 才允许发非零 remote。
- `operationalMode` 不为 `manual` 或 `safetyState` 不为 `normal` 时立即停止遥控。
- 安全状态优先于普通任务状态展示。
- MQTT 断线后清空该状态，等待新会话的 Robot 重新上报。

#### 5. 完整示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:21:00.000Z",
  "workStatus": "cleaning",
  "controlMode": "auto",
  "batteryPercent": 82.0,
  "linearSpeedCms": 30.0,
  "angularSpeedRadps": 0.0,
  "deviceStatus": "normal",
  "movementStatus": "forward",
  "yawDeg": 0.0,
  "pitchDeg": 1.2,
  "temperatureC": 38.5,
  "totalMileageM": 1250.4,
  "cleanedRows": 12,
  "pressureKpa": 98.1,
  "antiFallLeftM": 0.45,
  "antiFallRightM": 0.47,
  "missionId": "mission-42",
  "taskKind": "coverage",
  "runState": "running",
  "operationalMode": "auto",
  "safetyState": "normal",
  "phase": "executing",
  "activeAction": "cross_panel",
  "waypointIndex": 3,
  "waypointCount": 9,
  "errorCode": 0,
  "errorRetryable": false,
  "errorSource": "",
  "errorMessage": ""
}
```

### 4.3 `cmd_ack` 命令回执接口

#### 1. Topic 名称

```text
device/{productType}/{deviceId}/cmd_ack
```

#### 2. 用途

表示 Robot 任务层是否在同步边界接受某条命令。成功 ACK 不表示任务已经运行成功或完成。

#### 3. 字段、含义、取值

| 字段 | 类型 | 必填 | 当前 APP 处理 |
| --- | --- | --- | --- |
| `version` | string | 是 | 必须 `"1.0"` |
| `deviceId` | string | 是 | 必须匹配绑定设备 |
| `productType` | string | 是 | 必须匹配绑定设备类型 |
| `timestamp` | string | 是 | Robot 回执时间 |
| `cmdId` | string | 是 | 必须与等待中或最后重试命令的 `cmdId` 相同 |
| `cmd` | string | 是 | 必须与原命令类型相同 |
| `ackStatus` | string | 是 | 只有严格等于 `success` 视为成功；其他值均视为失败 |
| `message` | string/null | 否 | 保存在 ACK 模型中；晚到 ACK 可写入日志，不参与业务分支 |
| `errorCode` | string/null | 失败时应提供 | 用于失败提示和重试判断 |

当前 APP 已映射的 `errorCode`：

```text
INVALID_PAYLOAD
UNSUPPORTED_VERSION
DEVICE_MISMATCH
UNSUPPORTED_CMD
MISSION_SERVICE_UNAVAILABLE
MISSION_SERVICE_TIMEOUT
MISSION_SERVICE_ERROR
MISSION_INVALID_COMMAND
MISSION_INVALID_REQUEST
MISSION_BUSY
MISSION_NOT_FOUND
MISSION_ILLEGAL_STATE
MISSION_INTERNAL_ERROR
MISSION_REJECTED
```

未知错误码仍会作为“Robot 返回错误（原错误码）”展示。

#### 4. 规则

- `cmdId` 匹配但 `cmd` 不匹配：忽略并记录 `ack_type_mismatch`。
- 无法关联当前或最后命令：忽略并记录 `unmatched_ack`。
- 5 秒后到达的晚到 ACK，只要匹配最后一个命令，仍更新命令结果。
- `manual` 成功 ACK 只完成第一层授权，还需要 `status.operationalMode=manual`。
- `start` 成功 ACK 后等待 `status.missionId/runState`。
- `clear_estop` 成功 ACK 后等待 `status.safetyState=normal`。

#### 5. 成功示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:22:00.223Z",
  "cmdId": "cmd_crawler_00000001_1785398400123_a1b2c3d4",
  "cmd": "start",
  "ackStatus": "success",
  "message": "accepted",
  "errorCode": null
}
```

失败示例：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:22:00.223Z",
  "cmdId": "cmd_crawler_00000001_1785398400123_a1b2c3d4",
  "cmd": "start",
  "ackStatus": "failed",
  "message": "mission is busy",
  "errorCode": "MISSION_BUSY"
}
```

### 4.4 `map` 地图通知接口

#### 1. Topic 名称

```text
device/{productType}/{deviceId}/map
```

#### 2. 用途

通知 APP 当前地图的编号、版本和 HTTP/HTTPS 下载地址。MQTT 消息本身不携带完整地图。

#### 3. 字段、含义、取值

| 字段 | 类型 | 必填 | 当前 APP 处理 |
| --- | --- | --- | --- |
| `version` | string | 是 | 必须 `"1.0"` |
| `deviceId` | string | 是 | 必须匹配绑定设备 |
| `productType` | string | 是 | 必须匹配绑定设备类型 |
| `timestamp` | string | 是 | 通知时间 |
| `mapId` | integer | 是 | 地图编号 |
| `mapName` | string/null | 否 | 地图名称，用于日志 |
| `mapVersion` | integer | 是 | 地图版本 |
| `mapJsonUrl` | string | 是 | 必须以 `http://` 或 `https://` 开头 |
| `fileSizeBytes` | integer/null | 否 | 提供时必须与响应体字节数完全一致 |
| `checksum` | string/null | 否 | SHA-256，可为纯 hex 或 `sha256:{hex}` |

#### 4. 下载和缓存规则

1. `mapId`、`mapVersion` 或 URL 缺失时，不发起下载；若有旧地图则继续显示旧地图。
2. APP 通过独立 OkHttpClient 对 `mapJsonUrl` 执行匿名 `GET`。
3. 仅接受 HTTP/HTTPS URL。
4. HTTP 状态必须为成功状态。
5. 响应体不得超过 20 MiB。
6. 若提供 `fileSizeBytes`，必须与实际字节数一致。
7. 若提供 `checksum`，必须通过 SHA-256 校验。
8. 先写入 `{mapId}_{mapVersion}.tmp`，再重命名为 JSON 缓存文件。
9. 地图 JSON 内的 `map_id/version` 必须与通知一致。
10. 缓存路径按 `productType/deviceId/mapId_version` 隔离。
11. 下载失败且已有旧地图时继续显示旧地图；无旧地图时进入失败状态。

地图下载请求当前不复用 Retrofit 的 Bearer Token，也没有显式复用 Retrofit 的超时配置。
因此 `mapJsonUrl` 必须是 APP 可直接访问的 URL；若服务器需要鉴权，应提供签名 URL，或后续修改
APP 下载鉴权实现。

#### 5. 完整示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:23:00.000Z",
  "mapId": 7,
  "mapName": "屋顶 A 区",
  "mapVersion": 3,
  "mapJsonUrl": "https://example.invalid/maps/7/3.json",
  "fileSizeBytes": 102400,
  "checksum": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

### 4.5 `pose` Robot 地图位姿接口

#### 1. Topic 名称

```text
device/{productType}/{deviceId}/pose
```

#### 2. 用途

在当前已加载地图上定位 Robot，并驱动地图页的当前位置和最近轨迹显示。

#### 3. 字段、含义、取值

| 字段 | 类型 | 必填 | 当前 APP 处理 |
| --- | --- | --- | --- |
| `version` | string | 是 | 必须 `"1.0"` |
| `deviceId` | string | 是 | 必须匹配绑定设备 |
| `productType` | string | 是 | 必须匹配绑定设备类型 |
| `timestamp` | string | 是 | 位姿时间 |
| `mapId` | integer | 是 | 必须等于当前地图 `map_id` 才能绘制 |
| `mapVersion` | integer | 是 | 必须等于当前地图 `version` 才能绘制 |
| `blockId` | integer | 建议必填 | block 编号 |
| `cellId` | integer/null | 条件必填 | 优先按 `cellId` 查找 cell |
| `cellRow` | integer/null | 条件必填 | `cellId` 无效时与 block/col 共同定位 |
| `cellCol` | integer/null | 条件必填 | `cellId` 无效时与 block/row 共同定位 |
| `innerRow` | integer | 是 | `0..inner_rows-1` |
| `innerCol` | integer | 是 | `0..inner_cols-1` |
| `headingCode` | integer/null | 二选一 | `0..3`，优先于 `heading` |
| `heading` | string/null | 二选一 | `headingCode` 缺失时按名称解析 |

`headingCode` 定义：

| 值 | `heading` 名称 | 方向 |
| ---: | --- | --- |
| 0 | `block_u_positive` | block U 轴正向 |
| 1 | `block_u_negative` | block U 轴负向 |
| 2 | `block_v_positive` | block V 轴正向 |
| 3 | `block_v_negative` | block V 轴负向 |

#### 4. 规则

- MQTT 管理器在公共身份校验后保存最新 pose。
- 地图绘制层再次校验 `mapId/mapVersion`。
- 优先用 `cellId` 定位；找不到时使用 `blockId + cellRow + cellCol`。
- cell、内部行列或朝向无效时，该条位姿无法映射到地图坐标，不绘制。
- APP 使用 cell 四边形和内部网格中心做双线性插值，生成地图坐标。
- 地图页当前只保留 APP 本地接收时间最近 10 秒的可解析位姿轨迹。

#### 5. 完整示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:24:00.000Z",
  "mapId": 7,
  "mapVersion": 3,
  "blockId": 2,
  "cellId": 205,
  "cellRow": 4,
  "cellCol": 5,
  "innerRow": 0,
  "innerCol": 0,
  "headingCode": 0,
  "heading": "block_u_positive"
}
```

## 5. HTTP/REST 服务器接口

### 5.1 通用配置

| 项目 | 当前实现 |
| --- | --- |
| Base URL | `local.properties` 的 `api.base.url` → `BuildConfig.API_BASE_URL` |
| 开发默认值 | `http://10.0.2.2/api` |
| 序列化 | Retrofit + Gson |
| 认证 | Token 非空时自动添加 `Authorization: Bearer {token}` |
| 连接超时 | 20 秒 |
| 读取超时 | 30 秒 |
| HTTP 日志 | OkHttp `BASIC`，只记录请求/响应概要 |
| Cleartext | Manifest 当前允许 HTTP 明文流量 |

Base URL 在创建 Retrofit 时自动补末尾 `/`。当前没有 refresh token、统一响应 envelope、
统一业务错误码模型、自动 HTTP 重试或接口版本前缀补偿逻辑。非 2xx、网络错误和解析错误均以
异常返回到调用层。

认证拦截器对所有请求统一生效；若本地残留旧 Token，登录请求也会携带 Authorization，
服务器登录接口应忽略该 Header。

### 5.2 登录 API

#### 1. 请求

```http
POST {API_BASE_URL}/auth/login
Content-Type: application/json
```

```json
{
  "email": "operator@example.com",
  "password": "******"
}
```

| 字段 | 类型 | 必填 | APP 处理 |
| --- | --- | --- | --- |
| `email` | string | 是 | 发送前 `trim()` |
| `password` | string | 是 | 原样发送，不写日志 |

#### 2. 响应

```json
{
  "access_token": "token-value",
  "token_type": "bearer",
  "expires_in": 3600
}
```

| 字段 | 类型 | 当前 APP 处理 |
| --- | --- | --- |
| `access_token` | string | 必需；保存到本地会话 |
| `token_type` | string | 可缺省，DTO 默认 `bearer`；当前不参与 Header 拼接 |
| `expires_in` | integer | 可缺省，DTO 默认 `0`；当前不做主动过期和刷新 |

登录成功后 APP 保存 `access_token` 和去空格后的 email。登录失败不会修改当前 Token。

### 5.3 获取设备列表 API

#### 1. 请求

```http
GET {API_BASE_URL}/devices
Authorization: Bearer {access_token}
```

无查询参数。

#### 2. 响应

响应根节点必须直接是数组，不是 `{data: [...]}`：

```json
[
  {
    "device_id": "crawler_00000001",
    "display_name": "履带机器人01",
    "product_type": "crawler"
  }
]
```

| 字段 | 类型 | 必填 | 当前 APP 处理 |
| --- | --- | --- | --- |
| `device_id` | string | 是 | 列表主键，并用于选择 MQTT 身份 |
| `display_name` | string | 是 | UI 显示名称 |
| `product_type` | string/null | 否 | 同时兼容服务端字段名 `productType` |

用户选择设备后，APP 本地保存 `device_id/display_name/product_type`。

### 5.4 获取工作记录 API

#### 1. 请求

```http
GET {API_BASE_URL}/jobs?device_id={deviceId}
Authorization: Bearer {access_token}
```

#### 2. 响应

```json
[
  {
    "id": 1001,
    "device_id": "crawler_00000001",
    "started_at": "2026-07-30T08:00:00Z",
    "finished_at": "2026-07-30T08:30:00Z",
    "status": "completed",
    "cleaned_rows": 12,
    "note": "正常完成"
  }
]
```

| 字段 | 类型 | Kotlin 是否 nullable |
| --- | --- | --- |
| `id` | integer | 否 |
| `device_id` | string | 否 |
| `started_at` | string | 否 |
| `finished_at` | string/null | 是 |
| `status` | string | 否 |
| `cleaned_rows` | integer | 否 |
| `note` | string/null | 是 |

当前 APP 不解析时间格式为日期对象，也不限定 `status` 枚举，均按字符串展示。

### 5.5 查询最新固件 API

#### 1. 请求

```http
GET {API_BASE_URL}/firmware/latest?device_id={deviceId}
Authorization: Bearer {access_token}
```

#### 2. 响应

```json
{
  "version": "2.0.1",
  "download_url": "https://example.invalid/firmware/2.0.1.bin",
  "release_notes": "稳定性优化",
  "published_at": "2026-07-30T08:00:00Z"
}
```

| 字段 | 类型 | Kotlin 是否 nullable |
| --- | --- | --- |
| `version` | string | 否 |
| `download_url` | string | 否 |
| `release_notes` | string/null | 是 |
| `published_at` | string | 否 |

当前 APP 只查询和展示信息，不直接下载 `download_url`。

### 5.6 触发固件升级 API

#### 1. 请求

```http
POST {API_BASE_URL}/firmware/upgrade
Authorization: Bearer {access_token}
Content-Type: application/json
```

```json
{
  "device_id": "crawler_00000001",
  "target_version": "2.0.1"
}
```

| 字段 | 类型 | 必填 |
| --- | --- | --- |
| `device_id` | string | 是 |
| `target_version` | string/null | 否 |

Gson 默认不序列化 null；未指定目标版本时，请求体中会省略 `target_version`。

#### 2. 响应

```json
{
  "status": "accepted",
  "device_id": "crawler_00000001",
  "message": "upgrade scheduled",
  "target_version": "2.0.1"
}
```

| 字段 | 类型 | Kotlin 是否 nullable |
| --- | --- | --- |
| `status` | string | 否 |
| `device_id` | string | 否 |
| `message` | string | 否 |
| `target_version` | string/null | 是 |

APP 当前不限定 `status` 枚举，不轮询升级进度。

### 5.7 查询设备 Wi-Fi API

#### 1. 请求

```http
GET {API_BASE_URL}/devices/{device_id}/wifi
Authorization: Bearer {access_token}
```

#### 2. 响应

```json
{
  "device_id": "crawler_00000001",
  "ssid": "Robot-Network",
  "configured": true
}
```

| 字段 | 类型 | Kotlin 是否 nullable |
| --- | --- | --- |
| `device_id` | string | 否 |
| `ssid` | string/null | 是 |
| `configured` | boolean | 否 |

服务端响应不得返回 Wi-Fi 密码，APP DTO 也没有密码响应字段。

### 5.8 更新设备 Wi-Fi API

#### 1. 请求

```http
PUT {API_BASE_URL}/devices/{device_id}/wifi
Authorization: Bearer {access_token}
Content-Type: application/json
```

```json
{
  "ssid": "Robot-Network",
  "password": "******"
}
```

| 字段 | 类型 | 必填 |
| --- | --- | --- |
| `ssid` | string | 是 |
| `password` | string | 是 |

#### 2. 响应

响应模型与“查询设备 Wi-Fi API”相同：

```json
{
  "device_id": "crawler_00000001",
  "ssid": "Robot-Network",
  "configured": true
}
```

## 6. 地图 JSON 文件接口

`map` MQTT 通知下载的 JSON 必须能反序列化为以下根结构：

```json
{
  "map_id": 7,
  "version": 3,
  "source": {
    "type": "generated",
    "file_name": "roof_a.json",
    "generated_at": "2026-07-30T08:00:00Z"
  },
  "frame": {
    "unit": "centimeter",
    "origin": {
      "latitude_deg": 0.0,
      "longitude_deg": 0.0,
      "yaw_deg": 0.0
    }
  },
  "cell_model": {
    "inner_rows": 2,
    "inner_cols": 2
  },
  "blocks": [],
  "bridges": [],
  "cells": []
}
```

主要字段：

| 对象 | 字段 |
| --- | --- |
| 根 | `map_id`、`version`、`source?`、`frame`、`cell_model`、`blocks`、`bridges?`、`cells` |
| `frame` | `unit`、`origin?` |
| `cell_model` | `inner_rows`、`inner_cols` |
| block | `block_id`、`block_frame`、`rows`、`cols`、`grid`、`cell_ids`、`cleanable` |
| `block_frame` | `block_origin[2]`、`u_axis[2]`、`v_axis[2]` |
| cell | `cell_id`、`block_id`、`row`、`col`、`polygon[4][2]` |
| bridge | `bridge_id`、`source?`、`endpoints[2]`、`centerline?`、`polygon?` |
| bridge endpoint | `block_id`、`cell_row`、`cell_col`、`edge`、`inner_row`、`inner_col` |

当前 APP 强制校验：

- `map_id/version >= 0`；
- `frame.unit` 必须严格为 `centimeter`；
- `inner_rows/inner_cols > 0`；
- block 和 cell 均不能为空；
- `block_id/cell_id/bridge_id` 各自不能重复；
- block 的 `grid` 维度必须等于 `rows × cols`；
- 坐标向量必须为两个有限数；
- cell 必须引用存在的 block，且对应 `grid` 值为 1；
- cell polygon 必须恰好 4 点；
- block 的 `cell_ids` 与 grid 中值为 1 的数量及实际 cells 一致；
- bridge 必须恰好有 2 个 endpoint；
- endpoint 必须引用有效 cell 和内部网格；
- bridge `edge` 仅允许 `u_min/u_max/v_min/v_max`。

地图文件任一校验失败时，不会进入 `READY`，也不能用于 `start` coverage 参数。

## 7. APP 本地数据接口边界

本节不是云服务器 API，而是当前 APP 实际存在、会影响接口行为的本地持久化。

### 7.1 登录与设备会话

SharedPreferences：`solar_session`

| Key | 内容 |
| --- | --- |
| `token` | HTTP Bearer Token |
| `email` | 当前登录账号 |
| `device_id` | 当前选中设备 |
| `device_name` | 当前设备显示名 |
| `product_type` | 当前设备类型 |

退出登录会清空整个 `solar_session`，并重建 Retrofit Service。

### 7.2 手动速度设置

SharedPreferences：`manual_speed_settings`

按 MQTT `deviceId` 保存：

```text
{deviceId}.linear_speed_cms
{deviceId}.angular_speed_radps
```

读取和写入时均执行 UI 范围、步进归一化。

### 7.3 地图缓存

- 地图文件：APP `cacheDir/maps/{productType}/{deviceId}/{mapId}_{version}.json`
- 当前地图记录：SharedPreferences `map_cache`
- 记录键：`{productType}_{deviceId}`
- 启动或绑定设备时优先恢复通过 checksum 和地图模型校验的本地缓存。

### 7.4 结构化日志

Room 数据库：`solar_robot.db`，版本 2；表：`app_logs`。

日志包含：

```text
eventId, timestampMillis, deviceId, productType,
source, category, eventType, severity, direction,
topic, cmdId, missionId, action, result,
summary, detailJson, dedupeKey, repeatCount
```

日志是 APP 本地数据，不上传云服务器。当前策略：

- 日志页最多查询最新 2,000 条；
- 每写入 50 条触发一次清理；
- 删除 30 天以前的记录；
- 超过 2,000 条时只保留最新记录；
- 用户可在日志页确认后执行全部清除；
- 心跳只记录在线/离线变化，不逐条写入 Room。

## 8. APP 接口处理流程

### 8.1 登录和设备绑定

```text
LoginActivity
  → POST auth/login
  → 保存 access_token
  → GET devices（自动携带 Bearer Token）
  → 用户选择设备
  → 保存 device_id/display_name/product_type
  → MainActivity / MainViewModel.onScreenReady()
  → 生成 MQTT DeviceTopicIdentity
  → 连接 Broker
  → 订阅 heartbeat/status/cmd_ack/map/pose
```

### 8.2 Robot 状态接收

```text
Robot 发布 MQTT
  → CloudCommMqttManager.messageArrived()
  → JSON + version/productType/deviceId 公共校验
  → 按 Topic 反序列化
  → LiveData(status/missionState/pose/mapState/online)
  → MainViewModel 计算按钮可用性和命令后续状态
  → MainActivity 更新首页、地图、手动控制、状态详情
  → 状态变化按策略写入 Room 结构化日志
```

### 8.3 普通命令

```text
UI 点击
  → MainViewModel 校验连接、在线、状态和参数
  → 400 ms 防抖 + 单条 in-flight 限制
  → prepareCommand() 固化 cmdId/timestamp/payload
  → QoS 1 发布 cmd
  → 等待最多 5 秒
      ├─ 匹配 success ACK → 命令“已受理”
      ├─ 匹配 failed ACK → 显示 errorCode，可重试
      ├─ 超时 → TIMEOUT，可原 payload 重试
      └─ 断线 → CONNECTION_LOST，可原 payload 重试
  → start/clear_estop/manual 等继续等待 status 证明最终状态
```

### 8.4 coverage 任务

```text
map MQTT 通知
  → HTTP 下载地图
  → 大小/checksum/JSON 模型校验
  → 地图 READY
  → UI 选择起点、目标区域、全局规划
  → APP 再校验地图和选择
  → 发送 start coverage
  → cmd_ack 只确认受理
  → status.missionId + runState 驱动任务状态
  → stop/pause/resume/replan 使用最新 missionId
```

### 8.5 手动遥控

```text
发送 manual
  → 成功 cmd_ack
  → status: operationalMode=manual, safetyState=normal
  → 允许方向键
  → 长按 500 ms
  → 约 20 Hz remote
  → 松开/失焦/后台/离页/状态异常主动零速
  → 发送 auto
  → status.operationalMode=auto 后完成模式收敛
```

## 9. 当前实现限制与联调要求

1. APP 名称为第三版，但 MQTT payload `version` 必须保持 `"1.0"`。
2. `mission_command_v2` 是当前构建能力标识；新版 APP 必须与支持新版任务命令语义的 Robot
   同步发布，不能仅凭 MQTT `version=1.0` 判断兼容。
3. HTTP 接口没有统一响应 envelope，服务器必须直接返回本文所列对象或数组。
4. HTTP Token 无刷新机制，过期后当前 APP 只会收到接口失败。
5. 地图 URL 下载不携带 Retrofit Bearer Token。
6. MQTT 使用 TCP 明文连接，当前没有 TLS 证书校验。
7. APP 只按 `ackStatus=="success"` 判断成功，其余任何值都按失败处理。
8. Robot 必须持续发送 `status`；只有 ACK、没有状态不能完成任务、模式或安全状态闭环。
9. Robot 心跳建议保持第二版约定的 1 Hz；APP 固定以 3 秒无有效心跳判定离线。
10. `remote.durationMs` 只是兼容字段，APP 正常停车依靠主动零速；Robot 仍应保留自己的遥控超时
    和急停零速作为安全兜底。
11. 联调验收应同时保存 APP 发出的 payload、MQTT `cmd_ack/status` 和 Robot 任务层结果，不能只
    根据 APP 页面提示判断接口通过。

## 10. 接口与代码位置对照

| 接口/规则 | 当前权威代码 |
| --- | --- |
| Retrofit API 定义和 OkHttp 配置 | `app/src/main/java/com/robot/solar/network/http/ApiClient.kt` |
| HTTP 请求/响应 DTO | `app/src/main/java/com/robot/solar/network/http/dto/HttpDtos.kt` |
| HTTP Repository 和 MQTT 身份选择 | `app/src/main/java/com/robot/solar/repository/Repositories.kt` |
| MQTT Topic、连接、收发、地图下载 | `app/src/main/java/com/robot/solar/network/mqtt/CloudCommMqttManager.kt` |
| MQTT 消息和命令参数模型 | `app/src/main/java/com/robot/solar/network/mqtt/MqttModels.kt` |
| 命令 ACK、超时、重试和遥控状态机 | `app/src/main/java/com/robot/solar/viewmodel/MainViewModel.kt` |
| 任务按钮和手动控制准入 | `app/src/main/java/com/robot/solar/viewmodel/ManualControlPolicy.kt` |
| 手动速度范围、预设和方向符号 | `app/src/main/java/com/robot/solar/viewmodel/ManualSpeedSettings.kt` |
| 地图 JSON 模型和校验 | `app/src/main/java/com/robot/solar/map/PvMapModels.kt`、`PvMapParser.kt` |
| 会话和速度本地配置 | `app/src/main/java/com/robot/solar/data/session/` |
| 结构化日志存储 | `app/src/main/java/com/robot/solar/database/`、`entity/StructuredLogModels.kt` |
| BuildConfig 接口配置 | `app/build.gradle.kts`、根目录本地忽略的 `local.properties` |
