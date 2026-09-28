# Solar Robot Android App

这是光伏清洁机器人 Android App 仓库，包含 Android 客户端、HTTP/MQTT 接口说明、地图规划 JSON 示例，以及本地 MQTT 机器人模拟器。

当前功能基线按需求文档 V1–V6 演进，冲突部分以最新专项文档为准：

- 登录、设备列表、作业记录、固件升级、WiFi 配置通过 HTTP 后端提供；
- 机器人在线状态、运行状态、命令回执、V6 遥测和机器人姿态通过 MQTT 提供；
- 地图按 V5 Map V2 通过鉴权 HTTP `current/version/content` 接口同步，不订阅 MQTT `map`；
- 地图展示按 `docs/requirements/map_planner/config/example_map_complex.json` 的格式解析和绘制；
- 云端不可用时，App 只恢复此前经过 checksum、大小和地图身份校验的离线缓存。

## Project Layout

```text
app/                                  Android App
docs/                                 需求、接口、部署和联调文档
docs/requirements/                    V1–V6 App 需求与协议演进文档
docs/requirements/map_planner/        机器人地图 JSON 生成逻辑和示例地图
tools/robot-sim/                      MQTT 在线、命令监听和手动模式测试脚本
tools/robot-sim/README.md             在线、监听和手动模式说明
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
.\tools\run-gradle.ps1 assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Run The Robot Test Scripts

安装 Mosquitto 客户端后，在仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-online.ps1
```

如果 Mosquitto 没有加入 PATH：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-online.ps1 -MosquittoDir "C:\Program Files\Mosquitto"
```

主页在线测试只发布 `heartbeat`。查看App命令使用只监听脚本：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-command-listener.ps1
```

测试手动控制页面使用会回复 `manual/auto/estop/clear_estop` 的脚本：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\robot-manual-mode.ps1
```

更多说明见 [tools/robot-sim/README.md](tools/robot-sim/README.md)。

## Validate

运行单元测试：

```powershell
.\tools\run-gradle.ps1 testDebugUnitTest
```

构建调试包：

```powershell
.\tools\run-gradle.ps1 assembleDebug
```

常用完整验证：

```powershell
.\tools\run-gradle.ps1 testDebugUnitTest assembleDebug
```

## Main Documents

| Document | Purpose |
| --- | --- |
| [docs/requirements/v3_第三版APP接口.md](docs/requirements/v3_第三版APP接口.md) | 未被后续专项文档覆盖的基础 App 接口 |
| [docs/requirements/v4.3_app_mission_command_migration.md](docs/requirements/v4.3_app_mission_command_migration.md) | 当前任务与根任务状态契约 |
| [docs/requirements/v5_map_backend_app_implementation_guide.md](docs/requirements/v5_map_backend_app_implementation_guide.md) | 当前 Map V2 契约 |
| [docs/requirements/v6_app_device_telemetry.md](docs/requirements/v6_app_device_telemetry.md) | 当前设备遥测契约 |
| [docs/interfaces-summary.md](docs/interfaces-summary.md) | HTTP / MQTT / App / Robot 接口总览 |
| [docs/desktop-mqtt-test.md](docs/desktop-mqtt-test.md) | 桌面 MQTT 联调步骤 |
| [docs/server-http-only-deploy.md](docs/server-http-only-deploy.md) | 已有 MQTT 服务时部署 HTTP API |
| [docs/deploy-aliyun.md](docs/deploy-aliyun.md) | 从零部署云端服务 |
| [tools/robot-sim/README.md](tools/robot-sim/README.md) | 本地 MQTT 机器人模拟器 |

## App Test Flow

1. 启动 HTTP API 和 MQTT Broker。
2. 确认 `local.properties` 中的 `api.base.url`、`mqtt.host`、账号、设备 ID 正确。
3. 启动 App，登录后进入设备列表。
4. 选择与模拟器一致的设备，例如 `crawler/crawler_00000001`。
5. 按测试目标启动在线、监听或手动模式脚本；地图使用 Cloud Map V2 测试数据。
6. 在App中检查首页在线状态、实际下发命令或手动控制闭环。
7. 作业记录、固件升级、WiFi 配置页面通过 HTTP 后端数据验证。

## Notes

- MQTT topic 格式是 `device/{productType}/{deviceId}/{topicType}`。
- App 会校验 MQTT payload 中的 `version`、`productType`、`deviceId`。
- 地图页通过鉴权 HTTP Map V2 获取当前地图；网络失败时仅使用已完整校验的设备隔离缓存。
- 不要提交真实服务器地址、账号或密码。
