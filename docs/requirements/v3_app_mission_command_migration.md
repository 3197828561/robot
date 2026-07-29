# APP 任务接口迁移交接

本文只列 APP 负责人需要同步修改的内容。Robot `cloud_comm` 已删除旧字符串命令链路，
改为把 MQTT JSON 转换成新版 `MissionCommand` Service；APP 的 MQTT topic 不变，但命令
payload、ACK 语义、状态字段和遥控流程发生了不兼容变化。

## 1. 发布兼容性结论

以下旧 payload 不再可用：

```json
{
  "cmd": "start",
  "params": {}
}
```

以下行为也不再可用：

- STOP 不带目标任务 ID；
- 只支持 `start/stop/estop/clear_estop`；
- 收到成功 ACK 就显示“任务完成”；
- 不保存 `status.missionId`；
- 直接发送 remote 而不先切换 MANUAL；
- 依赖 `durationMs` 控制 Robot 停车。

Robot 与 APP 应作为同一接口版本同步发布，不保留 `/mission/task_cmd` 双写兼容。

## 2. MQTT topic

topic 不变：

```text
APP -> Robot
device/{productType}/{deviceId}/cmd
device/{productType}/{deviceId}/remote

Robot -> APP
device/{productType}/{deviceId}/cmd_ack
device/{productType}/{deviceId}/status
device/{productType}/{deviceId}/heartbeat
device/{productType}/{deviceId}/pose
```

Payload `version` 当前仍为 `"1.0"`，不要因为文档名为 API V2 改成 `"2.0"`。

## 3. APP 命令模型

建议 APP 定义：

```text
CommandState
  cmdId
  cmd
  transportState: pending | acknowledged | timed_out
  ackStatus: success | failed | null
  errorCode: string | null

MissionState
  missionId
  taskKind
  runState
  operationalMode
  safetyState
  phase
  waypointIndex
  waypointCount
  errorCode
  errorRetryable
```

命令 ACK 与任务状态必须分开保存：

- `cmd_ack`：某一条命令是否被任务层同步接受；
- `status`：任务是否正在运行、暂停、成功、失败或取消。

## 4. 通用 cmd 外壳

```json
{
  "version": "1.0",
  "cmdId": "cmd_20260726_000001",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-26T08:30:00.123Z",
  "cmd": "manual",
  "params": {}
}
```

APP 要求：

- 每个新操作生成非空且设备范围内唯一的 `cmdId`；
- 网络超时重试同一操作时，复用原 `cmdId` 和完全相同的 payload；
- 不得把同一个 `cmdId` 用于不同命令或不同参数；
- `timestamp` 固定为 `YYYY-MM-DDTHH:mm:ss.SSSZ`；
- 不发送文档未定义的 `params` 字段。

## 5. START coverage

```json
{
  "version": "1.0",
  "cmdId": "cmd_start_000001",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-26T08:30:00.123Z",
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

校验：

- `mapId/mapVersion` 为 `0..4294967295` 的整数；
- `useCurrentPose=false` 时 `start` 必填；
- `useCurrentPose=true` 时可以省略 `start`；
- `heading` 为 `0..3`；
- `targetBlockIds` 每项大于 0 且不能重复；
- 当前只发送 `taskKind=coverage`。

START 成功 ACK 后进入“启动中”，不能直接显示“任务完成”。等待 `status.missionId`
出现，并用 `runState` 驱动后续状态。

## 6. 目标任务命令

从最新 `status.missionId` 读取目标 ID：

```json
{
  "cmd": "pause",
  "params": {
    "targetMissionId": "mission-42"
  }
}
```

同一结构用于：

```text
stop pause resume replan
```

APP 按钮规则建议：

| 命令 | 可用条件 |
| --- | --- |
| `stop` | 当前有活动 `missionId` |
| `pause` | 当前任务运行或启动中 |
| `resume` | `runState=paused` |
| `replan` | 当前为活动 coverage 任务 |

不要用 APP 本地旧 mission ID 发送目标命令。设备重连后，先等待新的 status。

## 7. 模式和安全命令

以下命令的 `params` 必须为空对象：

```text
manual auto estop clear_estop
```

语义：

- `manual`：取消活动自动任务并进入手动模式，不等价于 pause；
- `auto`：切回自动并停车，不恢复被 manual 取消的任务；
- `estop`：发起急停；
- `clear_estop`：发起解除流程，成功 ACK 不代表已经解除完成。

APP 必须以 `status.operationalMode` 和 `status.safetyState` 显示最终模式与安全状态。

## 8. cmd_ack

成功：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-26T08:30:00.223Z",
  "cmdId": "cmd_start_000001",
  "cmd": "start",
  "ackStatus": "success",
  "message": "accepted",
  "errorCode": null
}
```

`success` 表示 mission_planner 已在同步边界接受命令，不表示任务最终完成。

APP 稳定分支只使用：

```text
ackStatus
errorCode
```

不要解析 `message`。可能的 errorCode：

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

## 9. status 新字段

Robot 保留旧摘要字段，同时新增：

```json
{
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

`runState`：

```text
idle starting running paused succeeded failed canceled unknown
```

`safetyState`：

```text
normal low_battery fault estop clearing_estop unknown
```

`phase`：

```text
none waiting_for_robot resolving_start planning executing placeholder unknown
```

显示优先级：

1. `safetyState=estop/clearing_estop` 显示急停；
2. `safetyState=low_battery/fault` 或 `runState=failed` 显示故障；
3. 其他情况再显示任务运行状态。

`errorMessage` 只用于展示和日志；业务分支使用 `errorCode/errorRetryable`。

## 10. 遥控流程

APP 状态机：

```text
点击进入遥控
  -> 发送 manual
  -> 等待成功 cmd_ack
  -> 等待 status.operationalMode=manual
  -> 开始约 20Hz remote
  -> 松开时主动发送零速
  -> 退出页面前发送零速
  -> 发送 auto
  -> 等待 status.operationalMode=auto
```

Robot 有 `remote_timeout_ms` 超时零速和 ESTOP 零速，但这是异常防线。APP 仍必须：

- 持续控制期间约 20Hz 发送；
- 松开、页面失焦、切后台、网络切换、退出遥控页时主动发送零速；
- remote 使用 QoS 0、retain=false；
- ESTOP 或离线时禁用非零控制；
- 不依赖 `durationMs` 安排停车。

## 11. APP 验收清单

- [ ] 九种命令都有独立 UI/业务映射。
- [ ] START 发送完整 coverage 参数。
- [ ] 目标任务命令使用最新 `status.missionId`。
- [ ] 相同操作重试复用相同 `cmdId` 和 payload。
- [ ] ACK 成功不会直接标记任务完成。
- [ ] `runState` 驱动任务最终状态。
- [ ] 安全状态显示优先于运行状态。
- [ ] MANUAL 成功后才开始发送 remote。
- [ ] 松开、失焦、后台和退出时主动发送零速。
- [ ] AUTO 不显示为“恢复原任务”。
- [ ] CLEAR_ESTOP 等待 `safetyState=normal`。
- [ ] Service 不可用、超时和任务层拒绝均有明确提示。
- [ ] 发布配置将新版 APP 与支持 `/mission/command` 的 Robot 版本绑定，禁止和旧 Robot 混用。

APP 负责人完成后，把 payload、MQTT 抓包、对应 `cmd_ack/status` 和 UI 结果交给 Robot
负责人联合验收。没有 MQTT 与 ROS 两侧证据时，不能只根据 UI 判断 Robot 接口是否通过。
