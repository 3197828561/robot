# Solar Robot Android App

这是光伏清洁机器人 Android App 仓库，包含 Android 客户端、HTTP/MQTT 接口说明、地图规划 JSON 示例，以及本地 MQTT 机器人模拟器。

当前分支主要面向第二版 App 需求：

- 登录、设备列表、作业记录、固件升级、WiFi 配置通过 HTTP 后端提供；
- 机器人在线状态、运行状态、命令回执、地图通知、机器人姿态通过 MQTT 提供；
- 地图展示按 `docs/requirements/map_planner/config/example_map_complex.json` 的格式解析和绘制；
- 没有新 `map` 通知时，App 会优先显示本地缓存的最新地图；没有缓存时再加载内置示例地图，方便手机直接安装 APK 后验证地图页。

## Project Layout

```text
app/                                  Android App
docs/                                 需求、接口、部署和联调文档
docs/requirements/                    第一版/第二版 App 需求文档
docs/requirements/map_planner/        机器人地图 JSON 生成逻辑和示例地图
tools/robot-sim/mqtt-robot-sim.ps1    本地 MQTT 机器人模拟器
tools/robot-sim/README.md             模拟器使用说明和页面测试清单
tools/apk-download/                   APK 局域网下载工具
local.properties.example              本地配置示例
```

## Requirements

- Android Studio，建议使用支持 Java 21 的版本。
- JDK 21。
- Android SDK，`compileSdk=35`。
- 可访问的 HTTP API 服务，用于登录、设备、作业、固件、WiFi 页面。
- 可访问的 MQTT Broker，用于机器人控制台页面。
- 可选：Mosquitto 命令行客户端，用于运行本地机器人模拟器。

## Local Configuration

复制配置示例：

```powershell
Copy-Item local.properties.example local.properties
```

按实际环境填写：

```properties
api.base.url=http://your-server.example/api
mqtt.host=your-mqtt-host.example
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=your_app_mqtt_password
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

如果本地模拟机器人使用独立 MQTT 账号，可额外添加：

```properties
mqtt.robot.username=robot_device_001
mqtt.robot.password=your_robot_mqtt_password
```

`local.properties` 只用于本机，不要提交真实密码。

## Start The App

用 Android Studio：

1. 打开仓库根目录。
2. 等待 Gradle Sync 完成。
3. 选择 `app` 配置。
4. 连接手机或启动模拟器。
5. 点击 Run。

用命令行构建调试 APK：

```powershell
.\gradlew.bat assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Run The Robot Simulator

安装 Mosquitto 客户端后，在仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1
```

如果 Mosquitto 没有加入 PATH：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 -MosquittoDir "C:\Program Files\Mosquitto"
```

默认 `auto` 模式会持续上报 `heartbeat`、`status`、`pose`，并自动监听 App 下发的 `cmd` 和 `remote`。传入 `-MapJsonUrl` 时还会发布远程地图 `map` 通知。它适合测试控制台首页、地图页、手动控制页、状态页和日志页。

快速喂一轮 MQTT 页面测试数据：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 -Mode smoke
```

更多说明见 [tools/robot-sim/README.md](tools/robot-sim/README.md)。

## Validate

运行单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

构建调试包：

```powershell
.\gradlew.bat assembleDebug
```

常用完整验证：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

## Main Documents

| Document | Purpose |
| --- | --- |
| [docs/requirements/第二版APP需求分析文档 .md](docs/requirements/第二版APP需求分析文档%20.md) | 第二版 App 需求和接口设计 |
| [docs/requirements/第一版APP需求分析文档.md](docs/requirements/第一版APP需求分析文档.md) | 第一版 App 需求 |
| [docs/interfaces-summary.md](docs/interfaces-summary.md) | HTTP / MQTT / App / Robot 接口总览 |
| [docs/desktop-mqtt-test.md](docs/desktop-mqtt-test.md) | 桌面 MQTT 联调步骤 |
| [docs/server-http-only-deploy.md](docs/server-http-only-deploy.md) | 已有 MQTT 服务时部署 HTTP API |
| [docs/deploy-aliyun.md](docs/deploy-aliyun.md) | 从零部署云端服务 |
| [tools/robot-sim/README.md](tools/robot-sim/README.md) | 本地 MQTT 机器人模拟器 |
| [tools/apk-download/serve-apk.md](tools/apk-download/serve-apk.md) | APK 局域网下载 |

## App Test Flow

1. 启动 HTTP API 和 MQTT Broker。
2. 确认 `local.properties` 中的 `api.base.url`、`mqtt.host`、账号、设备 ID 正确。
3. 启动 App，登录后进入设备列表。
4. 选择与模拟器一致的设备，例如 `crawler/crawler_00000001`。
5. 启动 `tools/robot-sim/mqtt-robot-sim.ps1` 默认 `auto` 模式。
6. 在 App 控制台检查首页、地图、手动控制、状态、日志。
7. 作业记录、固件升级、WiFi 配置页面通过 HTTP 后端数据验证。

## Notes

- MQTT topic 格式是 `device/{productType}/{deviceId}/{topicType}`。
- App 会校验 MQTT payload 中的 `version`、`productType`、`deviceId`。
- 地图页优先显示本地缓存的最新地图；没有缓存时可用内置示例 JSON 展示。接入真实 `mapJsonUrl` 后，App 会下载并缓存远程地图，下载失败时继续保留当前可用地图。
- 不要提交真实服务器地址、账号或密码。
