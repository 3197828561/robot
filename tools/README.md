# Tools

本目录维护本地联调工具。当前主要工具是 `mqtt-robot-sim.ps1`，用于在没有真实机器人时模拟机器人侧 MQTT 上行消息，并监听 Android App 下发的控制命令。

如果只需要让 App 判定设备在线，可以运行 `device-online.ps1`。它只持续发布 `heartbeat online=true`，不发布状态、不监听命令、不自动回执。

如果需要测试地图下载和机器人轨迹，可以运行 `map-pose-path-sim.ps1`。它会发布指定地图 URL 的 `map` 通知，并沿地图内所有 cell 的内部网格生成从一端到另一端的蛇形 `pose` 路径。

## mqtt-robot-sim.ps1

脚本覆盖 App 控制台中和机器人 MQTT 相关的页面内容：

- 首页：在线状态、MQTT 状态、电量、运行摘要、最近命令反馈。
- 地图页：地图通知、地图加载状态、机器人当前位置 `pose`。
- 手动控制页：方向键下发 `remote`，脚本回显并同步速度/姿态。
- 状态页：工作状态、控制模式、设备状态、电量、速度、地图编号和当前位置。

登录、设备列表、作业记录、固件升级、WiFi 配置走 HTTP 后端接口，不由这个 MQTT 模拟器提供。测试这些页面需要启动云端/本地 HTTP API 服务，并让 App 的 `api.base.url` 指向它。

## device-online.ps1

最小在线模拟脚本，只用于让 App 收到当前设备的在线心跳：

```powershell
powershell -ExecutionPolicy Bypass -File tools\device-online.ps1
```

如果 Mosquitto 没有加入 PATH：

```powershell
powershell -ExecutionPolicy Bypass -File tools\device-online.ps1 -MosquittoDir "C:\Program Files\Mosquitto"
```

脚本默认读取仓库根目录 `local.properties` 中的 `mqtt.host`、`mqtt.port`、`mqtt.robot.username` / `mqtt.username`、`mqtt.robot.password` / `mqtt.password`、`mqtt.product_type`、`mqtt.default_device_id`。也可以用 `-HostNameOverride`、`-PortOverride`、`-UsernameOverride`、`-PasswordOverride`、`-ProductTypeOverride`、`-DeviceIdOverride` 覆盖。

## map-pose-path-sim.ps1

地图与轨迹模拟脚本，用于测试 App 地图文件下载、地图渲染、机器人当前位置和最近轨迹：

```powershell
powershell -ExecutionPolicy Bypass -File tools\map-pose-path-sim.ps1
```

默认地图 URL：

```text
http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json
```

脚本启动后会：

- 下载并解析地图 JSON；
- 发布 `heartbeat online=true`；
- 发布 `status stopped/manual/normal`；
- 每 10 秒发布一次 `map` 通知；
- 按蛇形路径持续发布 `pose`，默认每 400ms 一个点。

如果 Mosquitto 没有加入 PATH：

```powershell
powershell -ExecutionPolicy Bypass -File tools\map-pose-path-sim.ps1 -MosquittoDir "C:\Program Files\Mosquitto"
```

可用 `-PoseIntervalMs` 调整轨迹速度，也可以用 `-MapUrl` 指定其它地图文件。

## MQTT Topics

脚本使用第二版 App 协议：

```text
device/{productType}/{deviceId}/heartbeat   # Robot -> App
device/{productType}/{deviceId}/status      # Robot -> App
device/{productType}/{deviceId}/cmd_ack     # Robot -> App
device/{productType}/{deviceId}/map         # Robot -> App
device/{productType}/{deviceId}/pose        # Robot -> App
device/{productType}/{deviceId}/cmd         # App -> Robot
device/{productType}/{deviceId}/remote      # App -> Robot
```

所有 Robot 上行 payload 都包含：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-19T12:00:00.000Z"
}
```

App 会校验 `version`、`deviceId`、`productType`，三者必须和当前进入的设备一致。

## Requirements

安装 Mosquitto 命令行客户端：

```text
mosquitto_pub
mosquitto_sub
```

Windows 默认安装目录通常是：

```text
C:\Program Files\Mosquitto
```

如果已经加入 PATH，可以不传 `-MosquittoDir`。

## Configuration

默认读取仓库根目录的 `local.properties`：

```properties
mqtt.host=your-mqtt-host.example
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=your_app_mqtt_password
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

如果机器人侧使用独立 MQTT 账号，优先配置：

```properties
mqtt.robot.username=robot_device_001
mqtt.robot.password=your_robot_mqtt_password
```

账号读取优先级：

1. 命令行 `-UsernameOverride` / `-PasswordOverride`
2. `mqtt.robot.username` / `mqtt.robot.password`
3. `mqtt.username` / `mqtt.password`

设备身份读取优先级：

1. 命令行 `-ProductTypeOverride` / `-DeviceIdOverride`
2. `mqtt.product_type` / `mqtt.default_device_id`
3. 默认 `crawler` / `crawler_00000001`

## Auto Mode

推荐日常联调使用默认 `auto` 模式：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1
```

如果 Mosquitto 没有加入 PATH：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -MosquittoDir "C:\Program Files\Mosquitto"
```

`auto` 模式会持续：

- 每 1 秒发布 `heartbeat`；
- 每 1.5 秒发布 `status`；
- 每 1 秒发布 `pose`；
- 传入 `-MapJsonUrl` 时，每 10 秒发布一次 `map` 通知；
- 监听 App 下发的 `cmd` 和 `remote`；
- 对 `start`、`stop`、`estop`、`clear_estop` 自动返回相同 `cmdId` 的 `cmd_ack`。

启动后会打印：

```text
Robot MQTT simulator
Mode: auto
Broker: <host>:<port>
Username: <username>
Device: <productType>/<deviceId>
Topics: device/<productType>/<deviceId>/*
```

App 点击开始、停止、急停或解除急停时，脚本会显示：

```text
[DOWN] device/.../cmd {...}
[UP] cmd_ack cmd=start cmdId=cmd_... status=success
```

App 长按方向键时，脚本会显示：

```text
[DOWN] device/.../remote {"linearSpeedCms":20.0,"angularSpeedRadps":0.0,"durationMs":300}
[UP] status work=stopped movement=moving device=normal speed=20/0
[UP] pose map=2 block=1 cell=1 heading=block_u_positive
```

不要同时为同一个设备启动多个 `auto` 模拟器，否则多个状态源会相互覆盖。

## Smoke Mode

`smoke` 模式用于快速给 App 主控制台所有 MQTT 页面喂一轮数据：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -Mode smoke
```

它会依次发布：

1. 在线心跳；
2. `stopped/manual/normal`，用于验证首页和手动控制页可用状态；
3. 当前位置 `pose`；
4. `running/auto/normal`，用于验证停止按钮和运行状态；
5. `estopped`，用于验证解除急停按钮；
6. `fault`，用于验证异常状态展示；
7. 回到 `stopped/manual/normal`。

`smoke` 只发布一次，不监听 App 下发命令。未传 `-MapJsonUrl` 时不会发布 `map` 通知，App 会保留本地示例地图；需要交互式按钮测试时使用 `auto`。

## Map Testing

当前 App 已内置本地示例地图：

```text
docs/requirements/map_planner/config/example_map_complex.json
```

因此即使 `mapJsonUrl` 为空，App 也应能显示本地测试地图。模拟器默认 `mapId=2`、`mapVersion=1`，和该示例 JSON 对齐。为了避免空 URL 的地图通知覆盖本地示例地图，脚本未传 `-MapJsonUrl` 时会跳过 `map` 通知。

如果要测试远程地图下载，可以传入：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -MapJsonUrl "https://your-server/maps/example_map_complex.json"
```

App 收到 `map` 通知后会下载、缓存并解析该 JSON。没有远程 URL 时，主要验证本地示例地图和 `pose` 展示。

## Menu Mode

手动菜单模式适合单项调试：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -Mode menu
```

菜单项：

```text
1. Heartbeat online
2. Heartbeat offline
3. Status: stopped/manual/normal
4. Status: running/auto/normal
5. Status: estopped
6. Status: fault
7. Ack success by cmdId
8. Ack failed by cmdId
9. Pose update
10. Map notice with -MapJsonUrl
11. Smoke sequence for App main pages
0. Exit
```

手动发送 `cmd_ack` 时必须输入 App 下发的真实 `cmdId`。完整按钮闭环优先用 `auto` 模式。

## Listen Mode

只监听 App 下发，不模拟机器人上报：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -Mode listen
```

兼容旧参数：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -ListenOnly
```

## Run From tools Directory

如果当前目录已经在 `tools` 下，需要显式指定根目录的配置文件：

```powershell
cd tools
powershell -ExecutionPolicy Bypass -File .\mqtt-robot-sim.ps1 -LocalProperties ..\local.properties
```

## Page Test Checklist

1. 登录 App，进入设备列表，选择和脚本一致的设备。
2. 启动 `auto` 模式，确认首页显示 MQTT 已连接、设备在线、电量和状态摘要。
3. 进入地图页，确认本地示例地图显示，机器人位置点随 `pose` 出现。
4. 点击开始运行，确认脚本收到 `cmd=start`，App 最近操作显示成功，状态变为运行中。
5. 点击停止运行，确认状态回到停止/手动/正常，方向控制可用。
6. 进入手动控制页，长按方向键，确认脚本持续收到 `remote`，松手后收到零速度。
7. 点击急停和解除急停，确认按钮可用状态按 `estopped` 切换。
8. 进入状态页，确认状态明细、地图编号、当前位置、传感器字段都有值。
9. 进入日志页，确认本轮 MQTT 收发事件被记录。

## Troubleshooting

如果 App 没有响应，先检查脚本启动时打印的设备身份：

```text
Device: crawler/crawler_00000001
Topics: device/crawler/crawler_00000001/*
```

它必须和 App 当前选择的设备一致。若 App 中设备来自 HTTP 后端，请确保该设备的 `deviceId` 和 `productType` 与脚本一致。

如果命令一直显示发送中或超时，确认使用的是 `auto` 模式。`menu` 模式下必须手动输入真实 `cmdId`，否则 App 无法把回执匹配到当前命令。

如果找不到 `mosquitto_pub` 或 `mosquitto_sub`，安装 Mosquitto 客户端后把安装目录加入 PATH，或运行脚本时传入 `-MosquittoDir`。

真实账号和密码只放在 `local.properties` 或命令行环境中，不要提交到 Git。
