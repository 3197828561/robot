# 四页面 Robot 模拟器人工联调指南

本指南用于人工操作 Android APP 的四个工作台页面，同时在电脑终端观察 APP 下发的
MQTT 消息和模拟 Robot 返回的反馈。

它不是自动点击 UI 的测试。测试时由人操作 APP，`mqtt-robot-sim.ps1` 负责：

- 持续发送 `heartbeat/status/map/pose`；
- 显示 APP 下发的 `cmd/remote` 完整消息；
- 校验并执行 9 类 Robot 命令；
- 返回相同 `cmdId` 的 `cmd_ack`；
- 根据命令更新并返回任务、模式、安全、速度和位姿状态；
- 人工切换离线、低电量、故障、任务失败、ACK 失败和 ACK 超时等场景。

## 1. 启动

确认：

- `local.properties` 已配置 `mqtt.client.dir`；
- APP 与模拟器使用相同的 MQTT Broker；
- APP 当前设备为 `crawler/crawler_00000001`，或启动时传入对应覆盖参数；
- APP 已安装、登录并进入四页面工作台。

启动人工验收模拟器：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 -Mode interactive
```

默认地图：

```text
http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json
```

如果 APP 选择了其他设备：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 `
  -Mode interactive `
  -ProductTypeOverride crawler `
  -DeviceIdOverride crawler_00000001
```

终端中：

```text
[APP -> ROBOT][CMD]       APP 下发任务、模式或安全命令
[APP -> ROBOT][REMOTE]    APP 下发遥控速度
[ROBOT -> APP][ACK]       模拟 Robot 返回命令受理结果
[ROBOT -> APP][STATUS]    模拟 Robot 返回运行状态
[ROBOT -> APP][MAP]       模拟 Robot返回地图通知
[ROBOT -> APP][POSE]      模拟 Robot 返回地图位姿
```

## 2. 交互按键

模拟器运行期间保持终端获得焦点，直接按键，不需要回车：

| 按键 | 场景/反馈 |
| --- | --- |
| `1` | 空闲、自动模式、安全正常 |
| `2` | coverage 任务运行中 |
| `3` | coverage 任务已暂停 |
| `4` | coverage 任务成功完成 |
| `5` | coverage 任务失败，带可重试错误 |
| `6` | 低电量，电量 12% |
| `7` | Robot 安全故障 |
| `8` | Robot 急停 |
| `9` | 恢复安全状态为 normal |
| `M` | 立即重发地图通知 |
| `P` | 前进一步并发送新 pose |
| `O` | 切换 Robot 在线/离线 |
| `F` | 下一条新 `cmd` 强制返回 `MISSION_REJECTED` |
| `T` | 下一条新 `cmd` 丢弃一次 ACK，用于测试 5 秒超时和原 `cmdId` 重试 |
| `S` | 立即发送当前完整 status |
| `H` | 重新显示按键帮助 |
| `Q` | 退出模拟器 |

## 3. 主页逐功能测试

### 3.1 连接、状态卡和地图预览

1. 启动模拟器。
2. APP 应在 3 秒内显示 MQTT 已连接、Robot 在线。
3. 等待默认 `map` 和 `pose`。
4. 核对地图编号 `2`、版本 `1`、电量、工作状态、速度、设备状态和运动状态。
5. 按 `P`，APP 预览中的 Robot 位置应变化。
6. 点击“预览居中”，只改变 APP 画布；终端不应出现新的 APP MQTT 消息。
7. 点击“重新加载地图”，APP 直接按已有 URL 执行 HTTP 下载；终端不应出现 `cmd`。

### 3.2 开始 coverage 任务

1. 按 `1`，确保 `idle/auto/normal`。
2. 点击“开始任务”。
3. 可选择“使用当前位置”，在区域多选中选择地图里的有效区域（默认全选）。
4. 确认后终端必须显示：

```text
[APP -> ROBOT][CMD] cmd=start
```

5. 检查完整 payload 包含：

```text
taskKind=coverage
mapId=2
mapVersion=1
useCurrentPose
targetBlockIds
globalPlan
```

6. 模拟器返回相同 `cmdId` 的成功 ACK，并上报新的 `missionId`、
   `runState=running`、`phase=executing`。
7. APP 应先显示命令已受理，再以 status 显示任务运行中。

### 3.3 暂停、恢复、重新规划和停止

在任务运行状态依次测试：

| APP 操作 | 终端下行 | 模拟器状态反馈 |
| --- | --- | --- |
| 暂停 | `cmd=pause` + 当前 `targetMissionId` | `runState=paused` |
| 恢复 | `cmd=resume` + 相同任务 ID | `runState=running` |
| 重新规划 | `cmd=replan` + 相同任务 ID | `phase=planning` |
| 停止 | `cmd=stop` + 相同任务 ID | `runState=canceled` |

每一步都应先出现相同 `cmdId` 的 ACK，再出现 status。

### 3.4 急停和解除急停

1. 点击“紧急停止”，观察 `cmd=estop`。
2. 模拟器返回成功 ACK 和 `safetyState=estop`。
3. 当前 Debug 测试包的七个任务按钮仍保持可用，用于继续发送并观察 Robot 的真实拒绝/成功 ACK；Release 包才按急停状态禁用。
4. 点击“解除急停”，观察 `cmd=clear_estop`。
5. 模拟器先返回 `safetyState=clearing_estop`，约 1 秒后返回 `normal`。
6. APP 必须等到 `normal` 后才结束“解除中”状态。

### 3.5 失败、超时和重试

失败场景：

1. 按 `F`。
2. 在 APP 点击一条当前可用命令。
3. 模拟器返回 `ackStatus=failed/errorCode=MISSION_REJECTED`。
4. APP 应显示失败并开放“重试”。

超时与幂等重试：

1. 按 `T`。
2. 在 APP 点击一条当前可用命令。
3. 模拟器执行并缓存结果，但第一次不返回 ACK。
4. APP 约 5 秒后显示超时并开放“重试”。
5. 点击“重试”，APP 必须发送与第一次完全相同的 `cmdId` 和 payload。
6. 模拟器返回缓存 ACK，APP 状态收敛。

### 3.6 在线/离线

1. 按 `O` 进入离线。
2. 模拟器先发 `online=false`，随后暂停 heartbeat/status/pose 和命令响应。
3. APP 应显示离线并禁用控制。
4. 再按 `O`，模拟器恢复 heartbeat/status/pose，APP 应重新在线。

“更多日志”、返回设备列表和底部导航属于 APP 本地功能，不会产生 Robot 指令。可核对日志中
出现命令、任务、安全、连接和地图状态变化，但不应逐条记录重复心跳。

## 4. 地图页逐功能测试

### 4.1 地图和位姿

1. 按 `M` 立即发送默认地图通知。
2. APP 应通过 HTTP 下载 `map_2_v1.json` 并显示地图。
3. 按多次 `P`，APP 中 Robot 位置和最近 10 秒轨迹应变化。
4. 状态详情页的 mapId、mapVersion、blockId、cellId、heading 应同步变化。

### 4.2 地图按钮

| APP 操作 | 预期 |
| --- | --- |
| 放大 | 只改变本地画布，不发送 MQTT |
| 缩小 | 只改变本地画布，不发送 MQTT |
| 复位 | 恢复完整地图视口，不发送 MQTT |
| 居中 | Robot 移到画面中心，不发送 MQTT |
| 定位机器人 | 与居中相同，不发送 MQTT |

这些操作没有 Robot 副作用，因此终端不出现 APP 消息本身就是正确结果。

### 4.3 地图重载

主页“重新加载地图”会使用最后一条 `map.mapJsonUrl` 重新执行 HTTP 下载。模拟器不应收到
MQTT 命令。下载后地图仍应为 `2/v1`，pose 可以继续映射。

## 5. 手动控制页逐功能测试

### 5.1 进入和退出手动模式

1. 按 `1` 恢复 `idle/auto/normal`。
2. 点击“进入手动模式”。
3. 终端显示 `cmd=manual`。
4. 模拟器返回成功 ACK 和 `operationalMode=manual`。
5. 当前 Debug 测试包收到 `manual` 成功 ACK 后方向键即可用，Robot status 用于展示和数据核对。
6. 点击“切回自动模式”，APP 应先发零速 remote，再发 `cmd=auto`。
7. APP 收到 `auto` 成功 ACK 后方向键立即禁用，随后用模拟器的 `operationalMode=auto` 状态核对同步结果。

### 5.2 速度预设和正负方向

速度预设本身只保存 APP 本地配置，不发送 MQTT。选择预设后长按方向键至少 500 ms：

| 预设 | 前进/后退 | 左转/右转 |
| --- | --- | --- |
| 慢速 | `±10 cm/s` | `±0.1 rad/s` |
| 标准 | `±30 cm/s` | `±0.3 rad/s` |
| 高速 | `±50 cm/s` | `±0.5 rad/s` |

终端应显示：

| 方向 | 模拟器观察 |
| --- | --- |
| 前进 | `FORWARD linear=正值 angular=0` |
| 后退 | `BACKWARD linear=负值 angular=0` |
| 左转 | `LEFT linear=0 angular=正值` |
| 右转 | `RIGHT linear=0 angular=负值` |

APP 按住时约 20 Hz 发送，模拟器对重复帧按变化或每秒汇总显示，避免刷屏。松开必须立即看到
`STOP linear=0 angular=0`。模拟器会把速度写回 status，APP 右侧状态应同步。

### 5.3 普通停止、离页和看门狗

- 点击“普通停止”：只收到零速 remote，不应出现 `cmd=stop` 或急停状态。
- 按住方向后切换到其他页面：必须收到零速 remote。
- 按住方向后让 APP 进入后台：必须收到零速 remote。
- 若异常情况下 1 秒没有收到新 remote，模拟器自身看门狗会停车并回传零速 status。

### 5.4 手动急停

1. 进入手动模式并发送非零速度。
2. 点击“紧急停止”。
3. 终端出现 `cmd=estop`，模拟器速度归零并返回 `safetyState=estop`。
4. 非零 remote 随后会被模拟器拒绝。
5. 解除急停后仍需根据 APP 状态重新进入允许的手动控制流程。

## 6. 状态详情页逐字段测试

状态详情页没有下行按钮，主要验证 Robot 上报字段。依次按：

| 按键 | 核对字段 |
| --- | --- |
| `1` | `idle/auto/normal`、基础传感器字段、电量 88 |
| `2` | missionId、coverage、running、executing、activeAction、航点 |
| `3` | paused、速度归零 |
| `4` | succeeded、任务完成 |
| `5` | failed、errorCode、errorRetryable、errorSource、errorMessage |
| `6` | low_battery、电量 12 |
| `7` | fault、安全故障与不可重试错误 |
| `8` | estop |
| `9` | safetyState 恢复 normal |
| `P` | mapId、mapVersion、blockId、cellId、heading |
| `O` | 在线状态和 APP 最近收到心跳 |

同时核对固定上报字段：

```text
workStatus controlMode batteryPercent linearSpeedCms angularSpeedRadps
deviceStatus movementStatus yawDeg pitchDeg temperatureC totalMileageM
cleanedRows pressureKpa antiFallLeftM antiFallRightM
```

## 7. 完成标准

四页面人工验收需要同时保留三类证据：

1. APP 页面截图；
2. 模拟器终端中 APP→Robot 和 Robot→APP 的对应消息；
3. APP 日志页中的结构化命令、任务、安全、连接和地图事件。

只看到 UI 改变、只看到 ACK，或只看到 MQTT 发布成功，都不能单独证明整个数据闭环通过。
