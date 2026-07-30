# 手动控制页测试与数据流说明

## 1. 安全级别与运行方式

静态审计不连接设备：

```powershell
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-manual-control.ps1 -StaticOnly
```

默认运行检查会进入手动页并测试速度预设、加减按钮和字段，但不会切换机器人模式或发送速度：

```powershell
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-manual-control.ps1
```

仅在 robot-sim 或隔离测试场地运行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-manual-control.ps1 -AllowRobotCommands
```

`-AllowRobotCommands` 会发送 `manual/auto` 和方向速度帧。脚本不会自动触发急停，因为急停与连续方向测试串行执行会改变后续前置状态；急停按本文单独人工验证。

## 2. 页面总体数据流

### 模式切换

```text
“进入手动模式”
 -> MainActivity.btnEnterManualMode
 -> MainViewModel.enterRemoteMode()
 -> 检查 safetyState == normal
 -> cmd=manual
 -> MQTT /cmd
 -> Robot cmd_ack=success（只代表受理）
 -> Robot status.operationalMode=manual + safetyState=normal
 -> ManualControlPolicy / ControlAvailability.canRemote=true
 -> 方向键、普通停止启用
```

进入手动页面本身只切换 UI section，不会自动发送 `manual`。

### 速度和方向

```text
预设/滑条/+/-
 -> ManualSpeedControlView
 -> onSettingsChanged
 -> MainViewModel.setManualSpeedSettings()
 -> ManualSpeedPolicy.normalize()
 -> ManualSpeedPreferences（按 deviceId 本地保存）

方向按下
 -> DirectionPadView.onPress(ManualDirection)
 -> MainViewModel.startRemote()
 -> 500 ms 安全长按门槛
 -> ManualSpeedPolicy.velocityFor(direction, settings)
 -> 正负方向映射 + 协议限幅
 -> 每 100 ms publishRemote()
 -> MQTT /remote QoS 0，durationMs=300

松开/离页/普通停止/条件失效
 -> cancel job
 -> 立即发布 linear=0, angular=0
```

## 3. 模式与停止按钮

| ID | 功能 | 前置条件 | 数据流/预期 |
|---|---|---|---|
| `btnEnterManualMode` | 请求手动模式 | MQTT 连接、机器人在线、`safetyState=normal`、无在途命令 | 发布 `cmd=manual`；等待 ACK 和 status，不能在 ACK 前启用方向键 |
| `btnReturnAutoMode` | 切回自动模式 | 当前 `operationalMode=manual` | 先普通零速停止，再发布 `cmd=auto`；最终等 status=auto |
| `btnRemoteStop` | 普通停止 | `canRemote=true` | 取消持续发送并发布零速帧；不改变 safetyState |
| `btnRemoteEmergency` | 紧急停止 | `canEstop=true` | 取消本地输入且不先发送普通零速，然后发布 `cmd=estop`；最终以 `status.safetyState=estop` 为准 |

离开手动页会调用 `leaveRemotePage()`：只要手动会话/输入活跃，就停止并发零速；不会自动切回 auto，避免单纯页面导航产生任务命令。

## 4. 方向键逐项说明

`directionPad` 是自绘控件，不是四个独立 Android Button。逻辑区域是 3×3 网格：

| 方向 | `ManualDirection` 符号 | MQTT `linearSpeedCms` | MQTT `angularSpeedRadps` |
|---|---|---|---|
| 前进 | `(linearSign=+1, angularSign=0)` | `+设置线速度` | 0 |
| 后退 | `(-1, 0)` | `-设置线速度` | 0 |
| 左转 | `(0, +1)` | 0 | `+设置角速度` |
| 右转 | `(0, -1)` | 0 | `-设置角速度` |

关键安全行为：

- 未满足 MQTT/在线/manual/normal/ACK 确认时触摸不会发送速度；
- 按住不足 500 ms 不开始运动；
- 有效长按后约每 100 ms 重发一帧，Robot 使用 `durationMs=300` 看门狗；
- 松开立即发零速；
- 同时按多个方向会普通停止并提示；
- APP 退到后台时取消方向输入并普通停止；
- `RemoteControlContract` 最终限制线速度 `[-50,50] cm/s`、角速度 `[-0.5,0.5] rad/s`。

## 5. 速度控件逐项说明

| ID | 功能 | 变化 | 持久化 |
|---|---|---|---|
| `btnSpeedSlow` | 慢速预设 | 10 cm/s、0.1 rad/s | 保存到当前 MQTT deviceId |
| `btnSpeedStandard` | 标准预设 | 30、0.3 | 同上 |
| `btnSpeedHigh` | 高速预设 | 50、0.5 | 同上 |
| `sliderLinearSpeed` | 线速度大小 | 0..50，步进 1 | 同上 |
| `btnLinearMinus/Plus` | 线速度减/加 | 每次 1 cm/s，边界夹紧 | 同上 |
| `tvLinearSpeedValue` | 显示线速度 | `N cm/s` | 来自归一化后的 LiveData |
| `sliderAngularSpeed` | 角速度大小 | 0..0.5，步进 0.1 | 同上 |
| `btnAngularMinus/Plus` | 角速度减/加 | 每次 0.1 rad/s，边界夹紧 | 同上 |
| `tvAngularSpeedValue` | 显示角速度 | 一位小数 rad/s | 来自归一化后的 LiveData |

UI 只允许输入非负“大小”，方向键决定协议正负；不要把 UI 滑条改为负数范围，否则会与方向符号重复。

## 6. 状态字段

| ID | 内容 | 来源 |
|---|---|---|
| `tvRemoteModeState` | 运行模式、安全状态 | MQTT `status.operationalMode/safetyState`；状态未到时可显示 `--` |
| `tvRemoteHint` | 长按说明或不可用原因 | `ControlAvailability`、连接、在线、mode、safety |
| `tvRemoteStatus` | MQTT/在线、mode/safety、手动可用性及设定速度 | MQTT LiveData + 本地速度设置 |

右侧不展示与手动控制无关的任务详情；完整原始字段在详情页。

## 7. MQTT remote payload

```json
{
  "version": "1.0",
  "deviceId": "当前 MQTT 设备",
  "productType": "当前产品类型",
  "timestamp": "UTC ISO-8601",
  "linearSpeedCms": 30.0,
  "angularSpeedRadps": 0.0,
  "durationMs": 300
}
```

Topic：`device/{productType}/{deviceId}/remote`，QoS 0，非 retained。

## 8. 甲方变更定位

- 左右栏、方向盘大小、停止按钮：两套 `activity_main.xml` 的 `sectionRemote`。
- 速度面板：两套 `view_manual_speed_control.xml`。
- 速度控件事件和显示：`ManualSpeedControlView.kt`。
- 预设、范围、步进、正负映射：`ManualSpeedSettings.kt`。
- 方向触摸区域/冲突：`DirectionPadView.kt`。
- 长按门槛、周期发送、停止、模式命令：`MainViewModel.kt`。
- 控制可用条件：`ManualControlPolicy.kt`、`MissionControlPolicy.kt`。
- MQTT payload、限幅、Topic/QoS：`MqttModels.kt`、`CloudCommMqttManager.kt`。
- 按设备保存速度：`ManualSpeedPreferences.kt`。

修改速度范围必须同时修改协议合同、UI policy、XML slider、robot-sim 校验和单元测试，不能只改显示文本。

## 9. 人工验收清单

1. 页面导航不自动弹出 manual 命令结果。
2. 离线、MQTT 断开、安全非 normal、auto 模式时方向键均不可运动。
3. 三个预设、四个加减按钮、两个滑条均测试最小/最大/中间值和重启持久化。
4. 进入 manual 后分别长按前后左右，订阅 `/remote` 验证四组正负号。
5. 每次松开必须观察到零速帧；离页、切后台、断线也必须停止。
6. 同时多方向测试只在安全模拟环境进行，确认停止和提示。
7. 普通停止不改变 safety；急停必须回传 estop；解除后必须等 normal 才能重新运动。
