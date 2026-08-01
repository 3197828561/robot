# Robot MQTT 测试脚本

本目录保留四个单一职责的测试入口。它们默认读取项目根目录中已被Git忽略的
`local.properties`，并通过 `mqtt.client.dir` 自动定位Mosquitto客户端。

## 1. 主页：机器人在线

持续发布在线心跳，让App主页显示机器人在线：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-online.ps1
```

脚本只发布 `heartbeat`，不回复命令。按 `Ctrl+C` 停止。

## 2. 仅监听App命令

以机器人身份监听并格式化显示App发布的 `cmd` 和 `remote`：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-command-listener.ps1
```

该脚本严格只监听，不发布心跳、状态或ACK，适合检查App实际发送的命令和参数。

## 3. 手动控制页面

发布在线心跳和空闲状态，监听App命令，并对以下命令返回ACK与状态：

```text
manual auto estop clear_estop
```

启动：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-manual-mode.ps1
```

测试流程：

1. App进入手动控制页并点击进入手动模式。
2. 脚本收到 `cmd=manual`，返回成功ACK和 `operationalMode=manual`。
3. 长按方向键，终端显示方向、线速度、角速度和保留的 `durationMs`。
4. 松开后应看到 `STOP` 零速消息。
5. 点击切回自动模式，脚本返回 `operationalMode=auto`。

脚本同时支持手动页面的急停和解除急停。其他任务命令会返回
`SIM_MANUAL_MODE_ONLY`，避免误以为完整任务模拟已经启动。

## 4. 发布地图通知

默认验证并发布当前测试地图：

```text
http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json
```

目标设备和Topic：

```text
crawler/crawler_00000001
device/crawler/crawler_00000001/map
```

运行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-map-notice.ps1
```

脚本会先下载地图JSON，读取 `map_id/version`，计算文件大小和SHA-256，再发布一次
`map` 通知。App收到后会从 `mapJsonUrl` 下载、校验并缓存地图。

指定其他地图或设备：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-map-notice.ps1 `
  -MapUrl "http://server/maps/example.json" `
  -ProductTypeOverride crawler `
  -DeviceIdOverride crawler_00000001
```

## 配置

脚本读取以下本地配置，但不会打印密码：

```properties
mqtt.client.dir=C:/path/to/mosquitto
mqtt.host=your-broker
mqtt.port=1883
mqtt.robot.username=robot-account
mqtt.robot.password=robot-password
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

账号优先使用 `mqtt.robot.username/password`，缺少时回退到
`mqtt.username/password`。命令行的 `*Override` 参数优先级最高。

启动后显示的设备类型和设备ID必须与App当前选中的设备一致。

## 有限次数运行

用于快速检查或自动验证：

```powershell
# 只发一次心跳
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-online.ps1 -Count 1

# 收到一条消息后退出
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-command-listener.ps1 -MaxMessages 1

# 运行10秒后退出
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-manual-mode.ps1 -RunSeconds 10
```
