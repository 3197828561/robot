# Solar Robot Android App

光伏机器人远程控制 APP。仓库包含 Android 客户端、接口文档、部署说明和本地 MQTT 机器人模拟器。

## 快速开始

1. 克隆仓库。
2. 复制 `local.properties.example` 为 `local.properties`。
3. 填入 HTTP 和 MQTT 配置。
4. 使用 Android Studio 打开项目并 Sync。
5. 运行 App，登录后进入设备列表，选择设备进入控制台。

`local.properties` 示例：

```properties
api.base.url=http://your-server.example/api
mqtt.host=your-mqtt-host.example
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=your_app_mqtt_password
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

真实密码不要提交到 Git。

## 重要文档

| 文档 | 用途 |
| --- | --- |
| [docs/requirements/第一版APP需求分析文档.md](docs/requirements/第一版APP需求分析文档.md) | 第一版 APP 需求和 MQTT 协议 |
| [docs/interfaces-summary.md](docs/interfaces-summary.md) | HTTP / MQTT / App / Robot 接口总览 |
| [docs/openapi.yaml](docs/openapi.yaml) | HTTP API 契约 |
| [docs/server-http-only-deploy.md](docs/server-http-only-deploy.md) | 已有 MQTT 服务时部署 HTTP API |
| [docs/deploy-aliyun.md](docs/deploy-aliyun.md) | 从零部署云端服务 |
| [tools/README.md](tools/README.md) | MQTT 机器人模拟器使用说明 |

## 本次版本内容

当前第一版控制台实现提交：

```text
00ad3be feat: implement first version app control UI
```

主要内容：

- 主控 UI 按需求重做为顶部栏、底部导航、首页、地图页、手动遥控页、状态详情页。
- 增加平板宽屏布局 `app/src/main/res/layout-sw600dp/activity_main.xml`，默认布局继续适配手机和窄屏。
- 设备列表页增加右上角用户中心，可切换账号或退出登录。
- 首页增加日志入口，日志页继续展示本地 Room 日志。
- MQTT 主题统一为 `device/{productType}/{deviceId}/{topicType}`。
- 进入设备后订阅 `heartbeat`、`status`、`cmd_ack`、`map`。
- 自动控制命令支持 `start`、`stop`、`estop`、`clear_estop`。
- 命令回执按 `cmdId` 匹配，5 秒未收到匹配回执显示超时。
- 在线状态只由 `heartbeat` 判断，超过 3 秒未收到心跳显示离线。
- 手动遥控改为 360 度虚拟摇杆，按住 0.5 秒后周期发布 `remote`，松手发送零速度。
- 增加系统栏 inset 自适应，避免平板任务栏或导航栏遮挡 App。
- 增加 `tools/mqtt-robot-sim.ps1`，可在一个命令行窗口内模拟机器人上报并监听 App 下发。

验证命令：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

## 回退到最初 UI 版本

本次 UI/MQTT 大改前的备份 tag：

```text
before-first-version-ui
```

对应提交：

```text
46a9dde docs: add first version app requirements
```

回退命令：

```bash
git fetch --all --tags
git checkout feature/first-version-app
git pull origin feature/first-version-app
git revert --no-edit before-first-version-ui..HEAD
git push origin feature/first-version-app
```

如果只是想从最初 UI 版本新建一个恢复分支，不影响当前分支，执行：

```bash
git fetch --all --tags
git checkout -b restore-before-first-version-ui before-first-version-ui
```
