# Robot Sim Tools

本目录维护本地 MQTT 联调工具。脚本已适配当前目录结构，默认会自动读取项目根目录的 `local.properties`。

## 文件

```text
tools/robot-sim/device-online.ps1
tools/robot-sim/mqtt-robot-sim.ps1
tools/robot-sim/map-pose-path-sim.ps1
tools/robot-sim/README.md
```

## 配置来源

默认读取：

```text
local.properties
```

使用的字段：

```properties
mqtt.host=47.103.157.213
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=app mqtt password
mqtt.robot.username=robot_device_001
mqtt.robot.password=robot mqtt password
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

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

推荐日常联调用默认 `auto` 模式：

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

传入地图 URL 时，还会发布 `map` 通知：

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

如果命令一直显示发送中或超时，确认使用的是 `auto` 模式。`menu` 模式下需要手动输入 App 下发的真实 `cmdId`。

如果找不到 `mosquitto_pub` 或 `mosquitto_sub`，安装 Mosquitto 客户端后加入 PATH，或者传入：

```powershell
-MosquittoDir "C:\Program Files\Mosquitto"
```
