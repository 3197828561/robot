# MQTT 机器人模拟器联调说明

本目录用于在命令行模拟机器人，配合 Android App 测试 UI 按钮和 MQTT 接口。

脚本：

```powershell
tools\mqtt-robot-sim.ps1
```

默认模式是 `auto`：持续发送心跳/状态，监听 App 下发的 `cmd` 和 `remote`，并对 `cmd` 自动返回相同 `cmdId` 的 `cmd_ack`。

## 前置条件

本机需要安装 Mosquitto，默认路径：

```text
C:\Program Files\Mosquitto
```

目录中需要有：

```text
mosquitto_pub.exe
mosquitto_sub.exe
```

脚本默认读取项目根目录的 `local.properties`：

```properties
mqtt.host=47.103.157.213
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=你的MQTT密码
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

也支持机器人专用账号：

```properties
mqtt.robot.username=robot_device_001
mqtt.robot.password=你的机器人MQTT密码
```

如果没有 `mqtt.robot.*`，脚本会退回使用 `mqtt.username` / `mqtt.password`。

## 推荐联调方式

在项目根目录执行：

```powershell
cd D:\AAWorkspace\robot
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1
```

启动后脚本会：

- 发布 `device/{productType}/{deviceId}/heartbeat`
- 发布 `device/{productType}/{deviceId}/status`
- 监听 `device/{productType}/{deviceId}/cmd`
- 监听 `device/{productType}/{deviceId}/remote`
- 收到 `cmd` 后发布同 `cmdId` 的 `cmd_ack`

App 进入机器人主界面后，预期：

- MQTT：已连接
- 在线状态：在线
- 工作状态：已停止
- 开始运行、紧急停止、手动遥控可按当前状态启用

## UI 按钮测试

### 开始运行

点 App 的“开始运行”。

脚本应打印：

```text
[DOWN] device/.../cmd {..."cmd":"start"...}
[UP] cmd_ack cmd=start cmdId=... status=success
```

App 预期：

- 最近命令显示成功
- 状态变为运行中
- “停止运行”可用

### 停止运行

点 App 的“停止运行”。

脚本会自动回 `stop` 的成功回执，并把状态改回 `stopped/manual/normal`。

App 预期：

- 最近命令显示成功
- “开始运行”和手动遥控重新可用

### 紧急停止 / 解除急停

点“紧急停止”后，脚本将状态改为 `estopped`。

App 预期：

- “解除急停”可用
- 其他普通控制不可用

点“解除急停”后，脚本将状态改回 `stopped/manual/normal`。

### 手动遥控摇杆

进入“手动遥控”页，按住摇杆超过 0.5 秒并拖动。

脚本应打印多条：

```text
[DOWN] device/.../remote {..."linearSpeedCms":...,"angularSpeedRadps":...}
```

App 松开摇杆后，脚本应收到一条零速度 `remote`。

## 纯监听模式

只看 App 发了什么，不自动发心跳、不自动回执：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -Mode listen
```

兼容旧参数：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -ListenOnly
```

## 手动菜单模式

手动发布心跳、状态、回执：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 -Mode menu
```

菜单包含：

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

注意：手动菜单里的回执需要输入 App 下发的真实 `cmdId`。如果只是测试按钮闭环，优先使用默认 `auto` 模式。

## 覆盖设备和 Broker

如果 App 当前选择的设备不是默认设备：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 `
  -ProductTypeOverride crawler `
  -DeviceIdOverride crawler_00000001
```

覆盖 Broker：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1 `
  -HostNameOverride 47.103.157.213 `
  -PortOverride 1883 `
  -UsernameOverride robot_device_001 `
  -PasswordOverride "你的密码"
```

## 从 tools 目录执行

```powershell
cd D:\AAWorkspace\robot\tools
powershell -ExecutionPolicy Bypass -File .\mqtt-robot-sim.ps1 -LocalProperties ..\local.properties
```

## Topic 对齐检查

App 会校验 payload：

```text
version == "1.0"
productType == 当前 App 绑定的 productType
deviceId == 当前 App 绑定的 deviceId
```

如果 UI 没反应，先看脚本启动时打印的：

```text
Device: crawler/crawler_00000001
Topics: device/crawler/crawler_00000001/*
```

它必须和 App 当前进入的设备一致。
