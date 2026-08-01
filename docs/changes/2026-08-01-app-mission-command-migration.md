# APP MissionCommand 升级实现交接说明

## 1. 输入与版本

- Robot 接口契约：`docs/requirements/v3_app_mission_command_migration.md`
- Robot 提供的聊天附件与仓库文档正文一致，文件哈希差异仅来自文本格式。
- 同事更新后的开发基线：`main@3a9d192`
- APP 实现提交：`e5de867 feat: migrate app mission commands`
- 协议 `version` 保持 `"1.0"`，MQTT topic 不变。

此版本必须与已经支持新版 `/mission/command` 的 Robot/cloud_comm 同步发布，不兼容旧 Robot 命令处理链路。

## 2. MQTT 接口

APP 发布：

```text
device/{productType}/{deviceId}/cmd       QoS 1, retain=false
device/{productType}/{deviceId}/remote    QoS 0, retain=false
```

APP 接收：

```text
device/{productType}/{deviceId}/cmd_ack
device/{productType}/{deviceId}/status
device/{productType}/{deviceId}/heartbeat
device/{productType}/{deviceId}/pose
```

支持九种命令：

```text
start stop pause resume replan manual auto estop clear_estop
```

每条命令生成包含设备 ID 和单调序号的非空 `cmdId`，时间戳固定输出三位毫秒 UTC 格式。当前 APP 不执行自动网络重试；未来若增加同一操作重试，必须复用原 `cmdId` 和完全相同的 payload。

## 3. START coverage

APP 仅在地图已成功加载且元数据与地图内容一致时允许 START。当前 payload 采用 Robot 文档允许的“使用当前位置”形式：

```json
{
  "cmd": "start",
  "params": {
    "taskKind": "coverage",
    "coverage": {
      "mapId": 7,
      "mapVersion": 3,
      "useCurrentPose": true,
      "targetBlockIds": [2, 4],
      "globalPlan": true
    }
  }
}
```

规则：

- `mapId/mapVersion` 来自当前已加载地图，必须在 uint32 范围内。
- 地图内部 `map_id/version` 必须与 MQTT 地图元数据一致。
- `targetBlockIds` 取所有 `cleanable=true` 且 ID 大于 0 的板块，去重并排序。
- `useCurrentPose=true`，所以不发送 `start` 字段。
- 没有有效地图或没有可清扫板块时，START 禁用。

## 4. 目标任务命令

以下四种命令只使用最新 `status.missionId`：

```json
{
  "cmd": "pause",
  "params": {
    "targetMissionId": "mission-42"
  }
}
```

按钮条件：

| 命令 | 可用条件 |
|---|---|
| stop | 有非终态 `missionId` |
| pause | `runState` 为 `starting` 或 `running` |
| resume | `runState=paused` |
| replan | 当前为非终态 coverage 任务 |

APP 不缓存旧 mission ID；设备重连后必须等待新 `status`。

## 5. ACK 与任务状态

`cmd_ack.ackStatus=success` 只显示“任务层已接受”，不再显示“任务完成”。业务逻辑忽略 ACK 的 `message`，保存并展示稳定字段 `ackStatus/errorCode`。

任务最终状态由 `status` 驱动，已解析并展示：

```text
missionId taskKind runState operationalMode safetyState phase activeAction
waypointIndex waypointCount errorCode errorRetryable errorSource errorMessage
```

状态显示优先级：急停/解除急停处理中 > 低电量、故障或任务失败 > 普通运行状态。

## 6. 遥控状态机

进入遥控页：

```text
发送 manual
-> 等待成功 cmd_ack
-> 等待 status.operationalMode=manual
-> 启用方向键
```

长按方向键 0.5 秒后约 20Hz 发布 remote。remote 中只包含线速度和角速度，不再发送或依赖 `durationMs`。

松开、冲突、页面失焦、离线或安全状态阻断时主动发布零速。离开遥控页时在同一发送序列中先发布零速，再发送 `auto`；关闭 MQTT 前同样先零速，并在手动模式下发送 `auto`。

## 7. UI 与代码位置

手机和平板自动控制区均新增：暂停任务、继续任务、重新规划。MANUAL/AUTO 由进入和退出遥控页触发。

主要文件：

- `app/src/main/java/com/robot/solar/network/mqtt/CloudCommMqttManager.kt`
- `app/src/main/java/com/robot/solar/network/mqtt/MqttModels.kt`
- `app/src/main/java/com/robot/solar/network/mqtt/MissionCommandPayloads.kt`
- `app/src/main/java/com/robot/solar/viewmodel/MainViewModel.kt`
- `app/src/main/java/com/robot/solar/ui/main/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout-sw600dp/activity_main.xml`

## 8. 自动化验证

```text
testDebugUnitTest：通过
assembleDebug：通过
```

新增回归测试覆盖 mission 状态字段解析、coverage payload、uint32 边界、板块过滤/去重/排序和 `targetMissionId`。

## 9. 尚未完成的联调

代码和本地构建已经完成，但真实 Robot 联调尚未执行。联合验收必须保存：

1. APP 发布的完整 `cmd` payload；
2. Robot 返回的对应 `cmd_ack`；
3. 随后的 `status.missionId/runState/operationalMode/safetyState`；
4. 遥控 topic 的 QoS、retain、约 20Hz 频率与零速消息；
5. APP 按钮状态、提示和最终 UI 结果；
6. Robot ROS 侧 MissionCommand 接收或拒绝证据。

没有 MQTT 与 Robot 任务层两侧证据时，只能标记“代码已实现”，不能标记“联调通过”。

