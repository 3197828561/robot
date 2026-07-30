# 主页测试与数据流说明

## 1. 页面边界与运行测试

主页对应 `MainActivity.Page.HOME`，布局有两套：

- 手机：`app/src/main/res/layout/activity_main.xml`
- 平板：`app/src/main/res/layout-sw600dp/activity_main.xml`

执行静态审计：

```powershell
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-home.ps1 -StaticOnly
```

连接模拟器或测试机后执行安全 UI 检查：

```powershell
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-home.ps1
```

在隔离的机器人模拟器环境中允许测试任务指令：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-home.ps1 -AllowRobotCommands
```

`-AllowRobotCommands` 会尝试当前状态下启用的任务按钮。真实机器人测试前必须清空作业区域并安排急停监护。开始任务弹窗的完整参数验证仍按本文人工用例执行，脚本不会替用户确认目标区域。

## 2. 页面总体数据流

```text
设备列表 HTTP API
  -> DeviceDto(device_id/display_name/product_type)
  -> DeviceRepository.selectDevice()
  -> SessionManager
  -> MainViewModel.deviceId/deviceDisplayName/productType
  -> 顶栏设备名称、MQTT topic 身份

Robot MQTT heartbeat/status/map/pose/cmd_ack
  -> CloudCommMqttManager.handleMessage()
  -> LiveData
  -> MainViewModel
  -> MainActivity observers
  -> bindHomeStatusCard()/bindMap()/bindPose()/bindCommandState()
  -> 主页字段

主页任务按钮
  -> MainActivity click listener
  -> MainViewModel 参数/状态/防抖校验
  -> CommandPayloadFactory
  -> CloudCommMqttManager.publishCmd()
  -> device/{productType}/{deviceId}/cmd
  -> Robot
  -> cmd_ack（受理结果）+ status（最终状态）
  -> UI、结构化日志、最近命令表
```

## 3. 按钮逐项说明

| UI/ID | 点击入口 | 逻辑与数据流 | 启用条件/测试断言 | 修改位置 |
|---|---|---|---|---|
| 返回设备列表 `btnDeviceList` | `MainActivity.bindControls` | 关闭当前 MQTT，会话设备信息仍在本地；启动 `DeviceListActivity` 并结束工作台 | 点击后出现设备列表；不应继续保留旧 MQTT 订阅 | `MainActivity.kt`、`DeviceListActivity.kt` |
| 重新加载地图 `btnReloadMap` | `viewModel.retryMapDownload()` | 读取当前 `MapMessage`；有 URL 时重新 HTTP 下载，否则尝试当前设备地图缓存 | 状态依次为“正在加载/地图已加载”或明确失败；旧地图可按实现继续显示 | `MainActivity.kt`、`MainViewModel.kt`、`CloudCommMqttManager.retryMapDownload()` |
| 预览居中 `btnCenterRobot` | `mapPreviewView.centerRobot()` | 只改变预览画布 viewport，不发网络消息 | 有有效 pose 时机器人居中；无 pose 时复位并提示 | `MainActivity.kt`、地图 View 类 |
| 开始任务 `btnStart` | `showCoverageTaskDialog()` | 从 `MapUiState.pvMap` 和最新 `PoseMessage`填充弹窗；确认后生成 `CoverageTaskSelection`，由 `startCoverage()` 校验并发布 `start` | Debug 包在线即启用，但仍须地图 READY 才能构造参数；Release 包执行完整状态校验 | `MainActivity.kt`、`dialog_coverage_task.xml`、`MainViewModel.startCoverage()` |
| 停止 `btnStopRun` | `sendMissionCommand("stop")` | 从最新 `MissionState.missionId` 生成 `params.targetMissionId`，发布 `cmd=stop` | Debug 包无任务 ID 时也会发送空 ID 供 Robot 返回真实 ACK；Release 包要求有效任务 | `MainActivity.kt`、`MainViewModel.sendMissionCommand()` |
| 暂停 `btnPause` | 同上，`pause` | 使用最新任务编号 | 运行中可用；最终应收到 paused 状态 | 同上 |
| 恢复 `btnResume` | 同上，`resume` | 使用最新任务编号 | 暂停状态可用；最终应回到 running | 同上 |
| 重新规划 `btnReplan` | 同上，`replan` | 使用最新任务编号 | 任务存在且策略允许；观察 phase/activeAction/status | 同上 |
| 紧急停止 `btnEmergency` | `sendCmd("estop")` | 发布独立急停命令，不依赖任务编号 | Debug 包在线即启用；Release 包按安全状态启用 | `MainActivity.kt`、`MainViewModel.sendCmd()` |
| 解除急停 `btnClearEstop` | `sendCmd("clear_estop")` | 发布清除急停命令；UI 进入等待安全状态回流 | Debug 包在线即启用；Release 包仅 `safetyState=estop` 时启用 | `MainViewModel` 的 awaitingClearEstop 流程 |
| 重试 `btnRetryCommand` | `retryLastCommand()` | 复用上次失败命令的已准备 payload/参数摘要，重新生成发布流程 | 只在失败/超时且连接恢复、无在途命令时可用 | `MainViewModel.retryLastCommand()` |
| 更多日志 `btnViewLogs` | 启动 `LogActivity` | Room 的结构化日志由 `LogListViewModel` 观察并展示 | 页面可打开、筛选、搜索、详情、清空 | `MainActivity.kt`、`ui/log`、`LogRepository` |
| 底部四项导航 | `showPage(Page.*)` | 同一 Activity 内切换四个 section，不新建 Activity | 离开手动页会发送零速停止；进入手动页不会自动切模式 | `MainActivity.showPage()` |

### 开始任务弹窗字段

| ID | 含义 | 来源/校验 | 最终协议字段 |
|---|---|---|---|
| `tvCoverageMap` | 当前地图 | `MapUiState.pvMap.mapId/version` | `params.mapId/mapVersion` |
| `cbUseCurrentPose` | 使用当前位置 | 用户选择；为 false 时六个起点字段必填 | `params.useCurrentPose` |
| `etStartBlockId` | 起点区域 | 默认来自 `PoseMessage.blockId`；必须存在于地图 | `params.start.blockId` |
| `etStartCellRow/Col` | 起点 cell 行列 | 默认来自 pose；必须存在 | `params.start.cellRow/cellCol` |
| `etStartInnerRow/Col` | cell 内部坐标 | 默认来自 pose；不得越界 | `params.start.innerRow/innerCol` |
| `etStartHeading` | 朝向编码 | 默认来自 pose；只允许 0..3 | `params.start.heading` |
| `cbSelectAllBlocks/chipGroupTargetBlocks` | 目标区域 | 地图中的可清洁区域生成多选项，默认全选；至少选择一个 | `params.targetBlockIds` |
| `cbGlobalPlan` | 全局规划 | 用户选择 | `params.globalPlan` |

## 4. 字段逐项说明

| UI/ID | 展示值 | 权威来源 | 绑定位置 |
|---|---|---|---|
| `tvDeviceName` | 设备显示名称 | HTTP `DeviceDto.display_name` 经 SessionManager | `MainActivity.onCreate` |
| `tvToolbarTime` | APP 当前时间 | 本机系统时钟，每秒更新 | `clockRunnable` |
| `batteryIndicator` | 顶栏电池图形 | MQTT `status.batteryPercent` | `batteryPercent.observe` |
| `mapPreviewView` | 地图、机器人、10 秒轨迹 | MQTT `map` 下载后的 `PvMap` + MQTT `pose` | `bindMap()/bindPose()` |
| `tvMapState` | 暂无/加载中/已加载/失败 | `MapUiState.status` | `bindMap()` |
| `tvMapMeta` | 名称、编号、版本 | MQTT `MapMessage` | `bindMap()` |
| `tvHomeOnline` | 在线/离线 | MQTT heartbeat + 3 秒超时监控 | `bindHomeStatusCard()` |
| `tvHomeWorkStatus` | 工作状态中文 | MQTT `status.workStatus` | 同上，经 `ProtocolDisplayText` |
| `tvHomeControlMode` | 控制模式中文 | MQTT `status.controlMode` | 同上 |
| `tvHomeBattery` | 电量百分比 | MQTT `status.batteryPercent` | 同上 |
| `tvHomeLinearSpeed` | cm/s | MQTT `status.linearSpeedCms` | 同上 |
| `tvHomeAngularSpeed` | rad/s | MQTT `status.angularSpeedRadps` | 同上 |
| `tvHomeDeviceStatus` | 设备状态中文 | MQTT `status.deviceStatus` | 同上 |
| `tvHomeMovementStatus` | 运动状态中文 | MQTT `status.movementStatus` | 同上 |
| `commandHistoryTable` | 最近四条命令五列 | Room `app_logs` 中命令类别，经 `LogRepository.observeRecentCommands()` | `bindCommandRows()`、`CommandHistoryTableView.kt` |
| `tvCommandState` | 最近操作即时状态 | `MainViewModel.commandState` | `bindCommandState()` |

最近命令表的“发送成功”不能理解为任务完成：`cmd_ack=success` 只表示 Robot 任务层受理，最终完成/失败由后续 `status.runState/error*` 决定。

## 5. 状态与命令判定

`ControlAvailability` 是所有按钮是否可用的唯一 UI 汇总。输入包括：

- MQTT 是否连接；
- heartbeat 判断机器人是否在线；
- `MissionState` 的 `missionId/runState/operationalMode/safetyState`；
- 是否等待 start/clear-estop 的 status；
- 是否存在在途命令；
- 是否有可重试命令。

当前 Debug 包设置 `BuildConfig.DEBUG_CONTROL_BYPASS=true`：MQTT 已连接且设备在线时，七个任务控制按钮均可点击，不采用任务状态、安全状态、在途命令之间的 APP 互斥；每条命令独立跟踪 `cmdId`，关联的 `cmd_ack` 按到达顺序弹窗。Release 包该开关为 false，继续使用上述完整状态策略。

修改甲方按钮启用规则时，优先修改 `MissionControlPolicy.compute()` 并同步 `ManualControlPolicyTest`，不要只在 XML 中设置 enabled。

## 6. 甲方变更定位

- 调整布局、字号、卡片顺序：两套 `activity_main.xml`。
- 调整中文显示映射：`ProtocolDisplayText.kt`。
- 调整任务按钮状态机：`MissionControlPolicy.kt`、`MainViewModel.kt`。
- 调整命令协议参数：`MqttModels.kt` 的 DTO/Factory、`MainViewModel.startCoverage()`。
- 调整 Topic/QoS/收发：`CloudCommMqttManager.kt`。
- 调整最近命令列：`CommandHistoryTableView.kt`、`MainActivity.bindCommandRows()`。
- 调整设备基础字段：HTTP `HttpDtos.kt`、`DeviceRepository`、`SessionManager`、UI。

## 7. 人工验收清单

1. 登录并选择与模拟器相同的 deviceId/productType。
2. 启动 robot-sim，确认在线、八个状态字段和地图字段均不是错误的固定值。
3. Debug 包在线后连续点击七个任务按钮，验证每条下行 `cmdId` 都能独立关联 ACK 弹窗；Release 包另测严格启用条件。
4. 对 start 覆盖：当前位置、显式起点、区域多选/全选、空目标、越界 heading。
5. 断开 MQTT 和停止 heartbeat，确认按钮禁用且不保留旧任务/手动授权。
6. 验证最近命令表五列与日志详情一致。

