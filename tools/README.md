# MQTT Robot Simulator

`mqtt-robot-sim.ps1` 是本地机器人模拟器，用于在没有真实机器人时测试 Android App 的 MQTT 接口和 UI 按钮。

脚本可以在一个命令行窗口内同时完成：

- 模拟机器人向 App 上报 `heartbeat` 和 `status`；
- 监听 App 下发的 `cmd` 和 `remote`；
- 对 `cmd` 自动返回相同 `cmdId` 的 `cmd_ack`；
- 在命令行打印 App 下发的内容，方便确认方向按钮是否真的发出了 MQTT 消息。

## 适用范围

该脚本测试 MQTT 链路，不模拟 HTTP 登录、设备列表或云端 REST API。

对应 MQTT topic：

```text
device/{productType}/{deviceId}/heartbeat
device/{productType}/{deviceId}/status
device/{productType}/{deviceId}/cmd_ack
device/{productType}/{deviceId}/cmd
device/{productType}/{deviceId}/remote
```

## 前置条件

需要安装 Mosquitto 命令行客户端：

```text
mosquitto_pub
mosquitto_sub
```

任一方式均可：

- 将 Mosquitto 安装目录加入 PATH；
- 或运行脚本时用 `-MosquittoDir` 指定安装目录。

Windows 示例：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -MosquittoDir "C:\Program Files\Mosquitto"
```

如果 `mosquitto_pub` / `mosquitto_sub` 已在 PATH 中，可以不传 `-MosquittoDir`。

## 配置 MQTT 参数

脚本默认读取仓库根目录下的 `local.properties`：

```properties
mqtt.host=your-mqtt-host.example
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=your_app_mqtt_password
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

如果需要机器人专用 MQTT 账号，可添加：

```properties
mqtt.robot.username=robot_device_001
mqtt.robot.password=your_robot_mqtt_password
```

脚本读取账号的优先级：

1. 命令行 `-UsernameOverride` / `-PasswordOverride`
2. `mqtt.robot.username` / `mqtt.robot.password`
3. `mqtt.username` / `mqtt.password`

设备身份读取优先级：

1. 命令行 `-ProductTypeOverride` / `-DeviceIdOverride`
2. `mqtt.product_type` / `mqtt.default_device_id`
3. 默认 `crawler` / `crawler_00000001`

## 推荐运行方式：自动机器人

在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1
```

这就是默认 `auto` 模式。

启动后会打印当前连接信息：

```text
Robot MQTT simulator
Mode: auto
Broker: <host>:<port>
Username: <username>
Device: <productType>/<deviceId>
Topics: device/<productType>/<deviceId>/*
```

同一个窗口会持续打印：

```text
[UP] heartbeat online=True
[UP] status work=stopped movement=stopped device=normal speed=0/0
```

当 App 点击命令按钮或长按方向按钮时，会打印：

```text
[DOWN] device/.../cmd {...}
[UP] cmd_ack cmd=start cmdId=cmd_... status=success
[DOWN] device/.../remote {...}
```

不要同时为同一个设备启动多个 `auto` 模拟器，否则多个模拟器会同时上报状态，测试结果会变混乱。

## UI 按钮测试清单

### 1. 进入设备控制台

启动脚本后进入 App 设备控制台。

App 预期：

- MQTT 显示已连接；
- 在线状态显示在线；
- 状态字段由 `status` 上报刷新；
- 开始运行、紧急停止、手动遥控按当前状态启用。

### 2. 开始运行

点击 App 的“开始运行”。

脚本预期输出：

```text
[DOWN] device/.../cmd {..."cmd":"start","cmdId":"cmd_..."}
[UP] cmd_ack cmd=start cmdId=cmd_... status=success
```

App 预期：

- 最近命令显示成功；
- 工作状态切换为运行中；
- 停止运行按钮可用。

### 3. 停止运行

点击 App 的“停止运行”。

脚本会自动回 `stop` 的成功回执，并把状态改为：

```text
workStatus=stopped
controlMode=manual
deviceStatus=normal
```

App 预期：

- 最近命令显示成功；
- 开始运行和手动遥控可用。

### 4. 紧急停止 / 解除急停

点击“紧急停止”。

脚本会将状态改为：

```text
workStatus=estopped
```

App 预期：

- 解除急停按钮可用；
- 普通开始/停止/遥控不可用。

点击“解除急停”后，脚本会改回 `stopped/manual/normal`。

### 5. 手动控制方向按钮

进入“手动控制”页，按住前进、后退、左转或右转按钮超过 0.5 秒。

脚本预期输出多条：

```text
[DOWN] device/.../remote {"linearSpeedCms":...,"angularSpeedRadps":...,"durationMs":300}
```

松开方向按钮或点击普通停止后，脚本应收到零速度：

```text
[DOWN] device/.../remote {"linearSpeedCms":0,"angularSpeedRadps":0,...}
```

## 纯监听模式

只监听 App 下发，不模拟机器人上报、不自动回执：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -Mode listen
```

兼容旧参数：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -ListenOnly
```

## 手动菜单模式

手动发布心跳、状态或回执：

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
9. Map notice without URL
0. Exit
```

手动发送 `cmd_ack` 时必须输入 App 下发的真实 `cmdId`。测试按钮闭环时优先使用默认 `auto` 模式。

## 不使用 local.properties

可以完全通过命令行覆盖配置：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 `
  -HostNameOverride your-mqtt-host.example `
  -PortOverride 1883 `
  -UsernameOverride robot_device_001 `
  -PasswordOverride "your_password" `
  -ProductTypeOverride crawler `
  -DeviceIdOverride crawler_00000001
```

## 从 tools 目录执行

如果当前目录是 `tools`，需要指定 `local.properties` 的相对路径：

```powershell
cd tools
powershell -ExecutionPolicy Bypass -File .\mqtt-robot-sim.ps1 -LocalProperties ..\local.properties
```

## 常见问题

### App 没反应

检查脚本启动时打印的设备身份：

```text
Device: crawler/crawler_00000001
Topics: device/crawler/crawler_00000001/*
```

它必须和 App 当前进入的设备一致。

App 会校验 payload：

```text
version == "1.0"
productType == 当前设备 productType
deviceId == 当前设备 deviceId
```

### 命令一直显示发送中或超时

确认使用默认 `auto` 模式。`auto` 会读取 App 下发的真实 `cmdId` 并自动回 `cmd_ack`。

如果使用 `menu` 模式，必须手动输入 App 下发的真实 `cmdId`，否则 App 不会把回执匹配到当前命令。

### 找不到 mosquitto_pub 或 mosquitto_sub

安装 Mosquitto 客户端后，任选一种：

- 将安装目录加入 PATH；
- 或运行脚本时传 `-MosquittoDir`。

### 不要提交真实密码

`local.properties` 不应提交到 Git。公开文档中只写占位符。
