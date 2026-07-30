# Robot Sim Tools

本目录维护本地 MQTT 联调工具。脚本已适配当前目录结构，默认会自动读取项目根目录的 `local.properties`。

## 文件

```text
tools/robot-sim/device-online.ps1
tools/robot-sim/mqtt-robot-sim.ps1
tools/robot-sim/map-pose-path-sim.ps1
tools/robot-sim/FOUR_PAGE_MANUAL_TEST.md
tools/robot-sim/README.md
```

## 配置来源

默认读取：

```text
local.properties
```

使用的字段：

```properties
java.home=C:/your/project/.local-tools/jdk-21
mqtt.client.dir=C:/your/project/.local-tools/mosquitto
adb.path=C:/Android/Sdk/platform-tools/adb.exe
emulator.path=C:/Android/Sdk/emulator/emulator.exe
mqtt.host=47.103.157.213
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=app mqtt password
mqtt.robot.username=robot_device_001
mqtt.robot.password=robot mqtt password
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

本机工具路径建议统一写入已被 Git 忽略的 `local.properties`。JDK 和 Mosquitto
二进制可以放在项目根目录同样被忽略的 `.local-tools/` 中。后续运行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\run-gradle.ps1 testDebugUnitTest lintDebug assembleDebug
```

Gradle 包装脚本会自动读取 `java.home`；本目录下三个 MQTT 模拟脚本会自动读取
`mqtt.client.dir`。命令行传入 `-MosquittoDir` 时仍以命令行参数为最高优先级。

账号优先级：

```text
1. 命令行 UsernameOverride / PasswordOverride
2. mqtt.robot.username / mqtt.robot.password
3. mqtt.username / mqtt.password
```

设备优先级：

```text
1. 命令行 ProductTypeOverride / DeviceIdOverride
2. mqtt.product_type / mqtt.default_device_id
3. crawler / crawler_00000001
```

## device-online.ps1

只发布在线心跳，用于让 App 判断设备在线：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\device-online.ps1
```

如果 Mosquitto 没有加入 PATH：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\device-online.ps1 -MosquittoDir "C:\Program Files\Mosquitto"
```

## mqtt-robot-sim.ps1

四页面人工逐功能验收使用 `interactive` 模式：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 -Mode interactive
```

操作 APP 时，终端会显示 APP 发出的 `cmd/remote`、模拟 Robot 返回的
`cmd_ack/status/map/pose`，并可用快捷键切换任务、离线、低电量、故障、急停、ACK 失败和
ACK 超时场景。完整步骤见 `FOUR_PAGE_MANUAL_TEST.md`。

无需人工切换场景的日常联调可使用默认 `auto` 模式：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1
```

它会持续发布：

```text
heartbeat
status
pose
```

并监听 App 下发的：

```text
cmd
remote
```

`auto` 模式已同步任务命令接口，支持：

```text
start stop pause resume replan manual auto estop clear_estop
```

其中：

- `start` 校验完整 coverage 参数：
  - `mapId/mapVersion` 是 uint32 范围整数；
  - `targetBlockIds` 非空、每项大于 0 且不能重复；
  - `useCurrentPose=false` 时必须包含完整 `start`；
  - `start.heading` 必须为 `0..3`；
- `stop/pause/resume/replan` 校验 `targetMissionId`；
- `cmd_ack=success` 仅表示命令已受理，最终结果由后续 `status.runState` 表示；
- `remote` 仅在 `operationalMode=manual` 且 `safetyState=normal` 时处理，并校验
  `linearSpeedCms` 在 `[-50, 50] cm/s`、`angularSpeedRadps` 在
  `[-0.5, 0.5] rad/s`；
- `status` 会上报：

```text
missionId taskKind runState operationalMode safetyState phase activeAction
waypointIndex waypointCount errorCode errorRetryable errorSource errorMessage
```

推荐按以下顺序完成 App 端验收：

```text
加载地图
→ 配置并发送 START
→ 检查 cmd_ack=success 后 UI 显示“已受理/等待状态”
→ 检查 status.missionId/runState 驱动任务状态
→ 测试 pause/resume/replan/stop 使用同一最新 missionId
→ 发送 manual 并等待 status.operationalMode=manual
→ 按住、松开方向键并检查 remote 周期帧和零速帧
→ 发送 auto 并等待 operationalMode=auto
→ 测试 estop/clear_estop 及 safetyState 状态回流
```

模拟器默认使用以下地图测试 URL，并持续发布 `map` 通知：

```text
http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json
```

需要替换地图时传入：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 -MapJsonUrl "https://your-server/maps/example_map_complex.json"
```

快速喂一轮页面测试数据：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 -Mode smoke
```

只监听 App 下发：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 -Mode listen
```

监听输出会先显示 Topic，再用标准两空格缩进格式化合法 JSON payload，不对冒号或字段值做
额外对齐；非 JSON 消息会原样显示。该模式不发送 heartbeat、status 或 `cmd_ack`。

手动菜单模式：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 -Mode menu
```

## map-pose-path-sim.ps1

用于测试远程地图下载、地图展示、机器人位置和轨迹：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\map-pose-path-sim.ps1
```

指定地图 URL：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\map-pose-path-sim.ps1 -MapUrl "https://your-server/maps/example_map_complex.json"
```

调整轨迹速度：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\map-pose-path-sim.ps1 -PoseIntervalMs 300
```

## Topic

脚本使用当前 App 的 MQTT topic 格式：

```text
device/{productType}/{deviceId}/heartbeat
device/{productType}/{deviceId}/status
device/{productType}/{deviceId}/cmd_ack
device/{productType}/{deviceId}/map
device/{productType}/{deviceId}/pose
device/{productType}/{deviceId}/cmd
device/{productType}/{deviceId}/remote
```

App 会校验 payload 中的：

```text
version
deviceId
productType
```

三者必须和当前选中设备一致。

## 从 robot-sim 目录运行

也可以先进入本目录再运行：

```powershell
cd tools\robot-sim
powershell -ExecutionPolicy Bypass -File .\mqtt-robot-sim.ps1
```

脚本仍会自动读取项目根目录的 `local.properties`。

## 常见问题

如果 App 没有响应，先检查脚本启动时打印的设备身份：

```text
Device: crawler/crawler_00000001
Topics: device/crawler/crawler_00000001/*
```

它必须和 App 当前选择的设备一致。

如果命令一直显示发送中或超时，确认使用的是 `interactive` 或 `auto` 模式。`menu` 模式下需要手动输入 App 下发的真实 `cmdId`。

如果找不到 `mosquitto_pub` 或 `mosquitto_sub`，安装 Mosquitto 客户端后加入 PATH，或者传入：

```powershell
-MosquittoDir "C:\Program Files\Mosquitto"
```
