# 状态详情页测试与数据流说明

## 1. 运行测试

```powershell
# 逐字段验证代码来源，不需要设备
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-status.ps1 -StaticOnly

# 在设备上验证详情文本包含全部字段标签并保存截图
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-status.ps1
```

详情页没有业务操作按钮，只有底部页面导航。脚本逐字段检查 `tvStatusDetails`，上下滚动后的可读性需要人工视觉验收。

## 2. 数据源边界

详情页刻意按来源分组，避免把本地值包装成机器人真实数据：

```text
设备列表 HTTP API
  -> DeviceDto
  -> DeviceRepository / SessionManager
  -> MainViewModel.device*

BuildConfig
  -> 版本号、版本代码、任务接口能力

MQTT heartbeat
  -> 最近一次 APP 实际接收时间

MQTT status
  -> StatusMessage 原始字段

MQTT map + HTTP 地图缓存
  -> MapUiState.map

MQTT pose
  -> PoseMessage

以上 LiveData/当前状态
  -> MainActivity.buildStatusDetails()
  -> tvStatusDetails
```

缺失字段显示 `--`。不得用演示常量补齐机器人字段。

## 3. HTTP 与本地构建字段

| 页面标签 | Kotlin 来源 | 上游来源 | 修改链路 |
|---|---|---|---|
| `display_name` | `viewModel.deviceDisplayName` | HTTP `DeviceDto.displayName` -> `DeviceRepository.selectDevice` -> SessionManager | `HttpDtos.kt`、设备列表、Repository、SessionManager、MainActivity |
| `device_id` | `viewModel.deviceId` | HTTP `DeviceDto.deviceId` | 同上；同时影响所有 MQTT Topic |
| `product_type` | `viewModel.productType` | HTTP `DeviceDto.productType`，缺失时 Repository 可按 deviceId 前缀推断 | 同上；甲方若强制服务端字段，应移除/调整推断 |
| APP版本 | `BuildConfig.VERSION_NAME/CODE` | `app/build.gradle.kts` | Gradle defaultConfig |
| 任务接口能力 | `BuildConfig.MISSION_COMMAND_API_CAPABILITY` | Gradle buildConfigField | Gradle + 服务端兼容策略 |

## 4. MQTT status 字段逐项说明

所有字段由 `CloudCommMqttManager` 在 `/status` Topic 解析为 `StatusMessage`。消息先经过 `version/deviceId/productType` 身份校验。

| 标签/模型字段 | 含义 | UI处理 | 甲方变更位置 |
|---|---|---|---|
| `timestamp` | Robot 生成状态时间 | 原样展示 | `MqttModels.StatusMessage`、Robot 协议 |
| `workStatus` | 旧版工作状态 | 经过中文映射 | `ProtocolDisplayText.workStatus` |
| `controlMode` | 旧版控制模式 | 经过中文映射 | `ProtocolDisplayText.controlMode` |
| `batteryPercent` | 电量 | 转整数百分比并限制显示 0..100 | DTO、`buildStatusDetails` |
| `linearSpeedCms` | 实际线速度 | 一位小数 cm/s | DTO、Robot |
| `angularSpeedRadps` | 实际角速度 | 两位小数 rad/s | DTO、Robot |
| `deviceStatus` | 设备总体状态 | 中文映射 | `ProtocolDisplayText.deviceStatus` |
| `movementStatus` | 运动状态 | 中文映射 | `ProtocolDisplayText.movementStatus` |
| `missionId` | 当前任务唯一编号 | 原样 | 也是 stop/pause/resume/replan 的 targetMissionId 来源 |
| `taskKind` | 任务类型 | 原样 | DTO、Robot |
| 派生“任务状态” | 给用户看的组合状态 | `MissionStatusDisplay.text(runState,safetyState,等待标记)` | `MissionStatusDisplay` |
| `runState` | Robot 任务运行状态 | 原样 | 最终任务结果权威字段 |
| `operationalMode` | auto/manual | 原样 | 手动控制可用条件 |
| `safetyState` | normal/estop/... | 原样 | 全局控制安全条件 |
| `phase` | 当前任务阶段 | 原样 | Robot/DTO |
| `activeAction` | 当前动作 | 空字符串显示 `--` | Robot/DTO |
| `waypointIndex` | 当前航点索引 | 原样 | Robot/DTO |
| `waypointCount` | 航点总数 | 原样 | Robot/DTO |
| `errorCode` | Robot 数值错误码 | `missionErrorCode` 对应 JSON `errorCode` | `@SerializedName`、错误码文档 |
| `errorRetryable` | 是否允许重试 | 原样 | Robot 状态机 |
| `errorSource` | 错误来源模块 | 空字符串显示 `--` | Robot |
| `errorMessage` | 错误描述 | 空字符串显示 `--` | Robot；不应代替 errorCode 分支判断 |

`yawDeg/pitchDeg/temperatureC/totalMileageM/cleanedRows/pressureKpa/antiFallLeftM/antiFallRightM` 当前存在于 `StatusMessage`，但没有放进详情页。甲方要求展示时应直接从同一 `status` 对象增加 UI 行和脚本字段检查，不要生成假数据。

## 5. 地图、位置和心跳字段

| 标签 | 来源 | 说明 |
|---|---|---|
| `mapId/mapVersion` | `currentMapState.map` | 初始由 MQTT map 通知产生；允许恢复同一通知的本地缓存记录 |
| `blockId/cellId/heading` | 最新 MQTT `PoseMessage` | heading 经 `ProtocolDisplayText.mapHeading` 显示 |
| APP 最近收到心跳 | `lastHeartbeatAt` | APP 收到合法 heartbeat 时的本机时间，不是 Robot payload timestamp |

在线判定不仅看 heartbeat 的 `online=true`，还包含约 3 秒接收超时。详情里的接收时间可以用于判断链路新鲜度，但不能当作 Robot 时钟。

## 6. 页面刷新触发

下列 LiveData 更新都会重新调用 `bindStatus()`：

- `mqttConnected`
- `deviceOnline`
- `lastHeartbeatAt`
- `status`
- `missionState`
- `manualSpeedSettings`
- `awaitingStartStatus`
- `awaitingClearEstopStatus`

`map` 和 `pose` 分别在 `bindMap()/bindPose()` 处理后触发详情刷新。因此修改字段时需要确认正确的 observer 能触发重绘。

## 7. 甲方变更定位

- 字段排序、分组、格式：`MainActivity.buildStatusDetails()`。
- 页面容器/字体/滚动：两套 `activity_main.xml` 的 `sectionStatus`。
- MQTT JSON 字段：`MqttModels.kt`。
- 收包、身份校验和 LiveData：`CloudCommMqttManager.kt`。
- 中文枚举映射：`ProtocolDisplayText.kt`。
- 任务状态派生：`MissionStatusDisplay`。
- HTTP 设备字段：`HttpDtos.kt`、`DeviceRepository`、`SessionManager`。
- APP 构建字段：`app/build.gradle.kts`。

新增后端字段的正确顺序是：

1. 更新正式接口/协议文档；
2. 更新 DTO；
3. 更新解析测试 payload 与断言；
4. 更新 UI 绑定；
5. 更新 `test-status.ps1` 的静态和运行标签清单；
6. 用 Robot/服务器真实消息验收，缺失时显示 `--`。

## 8. 人工验收清单

1. HTTP 设备名称、编号、类型与设备列表选择完全一致。
2. 发布一组每字段均有辨识度的 status，逐行对照 payload。
3. 分别省略每个可选字段，确认显示 `--` 且不崩溃。
4. 发布错误 version/deviceId/productType，确认消息被拒绝且 UI 不串设备。
5. status 停止、heartbeat 保持；heartbeat 停止、status 保持；分别观察页面和在线状态。
6. map/pose 切换版本和位置，确认详情同步。
7. phone 和 tablet 上从顶部滚到最底，检查无截断、无虚构固定值。

