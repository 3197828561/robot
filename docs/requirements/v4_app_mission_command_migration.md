# APP 任务接口迁移交接

本文单独记录 develop 分支相对 APP 当前对接版本的变化，只列 APP 负责人需要同步修改的
内容。APP 当前已能发送完整 coverage START，包括显式起点和清扫范围；这部分字段在 develop
版 cloud_comm 中继续正常解析转发，不要求删除或改由 Robot 本地参数提供。

Robot develop 分支已删除旧字符串命令链路，改为把 MQTT JSON 转换成新版
`MissionCommand` Service；APP 的 MQTT topic 不变，但 ACK 语义、状态字段、目标任务选择和
遥控流程发生了不兼容变化。下述事项完成前，只能称为 Robot 端已实现，不能称为新版 APP
已经联调通过。

## 1. 本次 develop 更新：APP 必改项

mission_planner 新增了“根任务 + 内部子任务”的任务栈。Robot 的 MQTT topic 和命令种类
没有变化，但 `status.missionId` 的语义已经变化，APP 必须同步以下修改：

| APP 位置 | 原处理 | 必须修改为 |
| --- | --- | --- |
| status 数据模型 | 只保存 `missionId/taskKind/runState` | 新增 `rootMissionId/orchestrationState/taskStackDepth/interruptionReason` |
| 任务关联 | 把 `missionId` 当作用户启动的任务 | `rootMissionId` 表示用户根任务；`missionId` 表示当前正在执行的栈顶任务 |
| STOP/PAUSE/RESUME/REPLAN | `targetMissionId=status.missionId` | 优先使用非空 `status.rootMissionId`；兼容旧 Robot 时才回退到 `status.missionId` |
| 低电回充显示 | 仍显示 coverage 正在直接执行 | `orchestrationState=running_child` 时显示根任务被打断、当前正在执行内部子任务 |
| REPLAN 按钮 | 活动 coverage 时始终可用 | 仅根任务是 coverage 且 `taskStackDepth=1` 时可用；内部子任务期间禁用 |
| 任务最终结果 | 只观察当前 `runState` | 用 `orchestrationState` 判断整个根任务流程，`runState` 只描述当前栈顶任务 |
| coverage 起点和范围 | APP 发送完整字段并允许调节 | 保持现状；cloud_comm 校验、转换并转发，不另设本地覆盖值 |

目标命令 ID 建议统一封装，禁止各页面自行选择：

```text
controlMissionId =
  status.rootMissionId 非空 ? status.rootMissionId : status.missionId
```

这是兼容新旧 Robot 的读取策略。新 Robot 存在内部子任务时，若仍发送当前子任务
`missionId`，mission_planner 会返回 `MISSION_NOT_FOUND`。

## 2. 发布兼容性结论

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
现有完整 coverage START payload 可以继续使用；它不是本次 develop 迁移的破坏性变化。

## 3. MQTT topic

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

## 4. APP 命令模型

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
  rootMissionId
  taskKind
  runState
  orchestrationState
  taskStackDepth
  interruptionReason
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

其中：

- `rootMissionId`：APP 用户启动的根任务 ID，也是目标任务命令使用的 ID；
- `missionId`：当前栈顶任务 ID，低电回充等内部子任务运行时会发生变化；
- `taskKind/runState/phase/activeAction`：描述当前栈顶任务；
- `orchestrationState`：描述整个根任务工作流；
- 当前 MQTT status 未提供 `rootTaskKind`，APP 应在 START 后保存根任务类型；当前 MQTT
  START 只开放 coverage，因此根任务类型可保存为 `coverage`。

## 5. 通用 cmd 外壳

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

## 6. START coverage

```json
{
  "version": "1.0",
  "cmdId": "debug_start_position_001",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-30T08:30:00.123Z",
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

- `mapId` 为 `1..4294967295` 的整数，`mapVersion` 为 `0..4294967295` 的整数；
- `useCurrentPose`、`targetBlockIds` 和 `globalPlan` 必填；
- `useCurrentPose=false` 时必须发送完整 `start`，APP 可修改该对象来调节显式起点；
- `start.blockId` 必须非零，`heading` 范围为 `0..3`；其余位置字段为 int32；
- `useCurrentPose=true` 时可省略 `start`，mission_planner 将使用新鲜 ROS 定位；
- `targetBlockIds` 是清扫范围，APP 可修改该数组；数组不能为空，元素必须大于 0 且不能重复；
- coverage 没有“终点”字段；
- 当前只发送 `taskKind=coverage`。

cloud_comm 严格解析上述字段并转换为 ROS `CoverageMissionGoal`。它不会替换 APP 的 `start`
或 `targetBlockIds`。mission_planner 在任务创建边界冻结任务输入，所以修改后的 payload 只影响
新发起的 START，不会改变已经运行的任务。

START 成功 ACK 后进入“启动中”，不能直接显示“任务完成”。等待
`status.rootMissionId` 出现，并用 `orchestrationState` 驱动整个任务流程状态。

## 7. 目标任务命令

从最新 status 计算 `controlMissionId`，优先读取非空 `rootMissionId`：

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
| `stop` | 当前有活动 `controlMissionId` |
| `pause` | 当前任务运行或启动中 |
| `resume` | `runState=paused` |
| `replan` | 根任务为 coverage 且 `taskStackDepth=1` |

不要把内部子任务的 `missionId` 用作目标 ID，也不要使用 APP 本地旧 ID。设备重连后，先等待
新的 status。兼容旧 Robot 时，仅当 `rootMissionId` 为空才回退到 `missionId`。

## 8. 模式和安全命令

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

## 9. cmd_ack

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

## 10. status 新字段

Robot 保留旧摘要字段，同时新增：

```json
{
  "missionId": "mission-43",
  "rootMissionId": "mission-42",
  "taskKind": "return_to_charge",
  "runState": "starting",
  "orchestrationState": "running_child",
  "taskStackDepth": 2,
  "interruptionReason": "LOW_BATTERY",
  "operationalMode": "auto",
  "safetyState": "normal",
  "phase": "none",
  "activeAction": "STARTING",
  "waypointIndex": 0,
  "waypointCount": 0,
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

`orchestrationState`：

```text
idle running paused_by_user paused_by_safety running_child resuming
succeeded failed canceled unknown
```

当 `taskStackDepth=2` 时，上例表示用户启动的 `mission-42` coverage 因低电被暂停，Robot
当前正在执行内部回充子任务 `mission-43`。APP 应以 `rootMissionId` 关联用户任务，以
`missionId/taskKind/runState/phase` 展示当前动作，并以 `orchestrationState` 判断整个流程状态。

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

## 11. 遥控流程

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

## 12. APP 验收清单

- [ ] 九种命令都有独立 UI/业务映射。
- [ ] START coverage 保留完整字段；显式起点和清扫范围均能从 APP 修改并正确到达 ROS request。
- [ ] status 模型已接收四个新增字段，严格 JSON 模型不会因新增字段解析失败。
- [ ] 目标任务命令优先使用 `status.rootMissionId`，为空才回退 `status.missionId`。
- [ ] 内部子任务期间能区分根 coverage 与当前回充任务，并禁用 REPLAN。
- [ ] 整个任务最终结果使用 `orchestrationState`，不把子任务成功误判为根任务完成。
- [ ] 相同操作重试复用相同 `cmdId` 和 payload。
- [ ] ACK 成功不会直接标记任务完成。
- [ ] `runState` 只驱动当前任务显示，`orchestrationState` 驱动根任务最终状态。
- [ ] 安全状态显示优先于运行状态。
- [ ] MANUAL 成功后才开始发送 remote。
- [ ] 松开、失焦、后台和退出时主动发送零速。
- [ ] AUTO 不显示为“恢复原任务”。
- [ ] CLEAR_ESTOP 等待 `safetyState=normal`。
- [ ] Service 不可用、超时和任务层拒绝均有明确提示。
- [ ] 发布配置将新版 APP 与支持 `/mission/command` 的 Robot 版本绑定，禁止和旧 Robot 混用。

APP 负责人完成后，把 payload、MQTT 抓包、对应 `cmd_ack/status` 和 UI 结果交给 Robot
负责人联合验收。没有 MQTT 与 ROS 两侧证据时，不能只根据 UI 判断 Robot 接口是否通过。
