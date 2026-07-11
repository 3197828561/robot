# 光伏机器人远程控制 APP

包名：`com.robot.solar`

本仓库包含 Android APP、接口文档、部署文档和本地 MQTT 联调工具。

## 文档索引

| 文档 | 说明 |
| --- | --- |
| [docs/requirements/第一版APP需求分析文档.md](docs/requirements/第一版APP需求分析文档.md) | 第一版 APP 需求与 MQTT 协议 |
| [docs/interfaces-summary.md](docs/interfaces-summary.md) | App / HTTP / MQTT / 硬件接口总览 |
| [docs/http-api-guide.md](docs/http-api-guide.md) | HTTP 接口协作说明 |
| [docs/openapi.yaml](docs/openapi.yaml) | REST 契约 |
| [docs/server-http-only-deploy.md](docs/server-http-only-deploy.md) | 已有 EMQX 时只补 HTTP API |
| [docs/deploy-aliyun.md](docs/deploy-aliyun.md) | 阿里云从零部署 EMQX + API + PostgreSQL |
| [docs/preflight-checklist.md](docs/preflight-checklist.md) | 联调前检查清单 |
| [docs/integration-report-template.md](docs/integration-report-template.md) | 联调报告模板 |
| [local.properties.example](local.properties.example) | App 本地配置模板 |
| [tools/README.md](tools/README.md) | 本地 MQTT 机器人模拟器说明 |

## App 配置

复制 `local.properties.example` 为 `local.properties`，不要提交真实密码：

```properties
api.base.url=http://47.103.157.213/api
mqtt.host=47.103.157.213
mqtt.port=1883
mqtt.username=app_user_001
mqtt.password=你的App MQTT密码
mqtt.product_type=crawler
mqtt.default_device_id=crawler_00000001
```

如需本地模拟机器人使用独立账号，可增加：

```properties
mqtt.robot.username=robot_device_001
mqtt.robot.password=你的Robot MQTT密码
```

## 运行流程

1. Android Studio Sync。
2. 运行 APP。
3. 登录。
4. 进入设备列表，选择设备。
5. 进入控制台后由 MQTT 更新设备状态，并通过按钮或摇杆下发控制指令。

## 回退到最初版本

本次第一版 APP UI/MQTT 大改前已经打过备份 tag：

```bash
before-first-version-ui
```

该 tag 指向提交：

```bash
46a9dde docs: add first version app requirements
```

只查看最初版本：

```bash
git checkout before-first-version-ui
```

从最初版本新建恢复分支：

```bash
git checkout -b restore-before-first-version-ui before-first-version-ui
```

如果已经提交并推送了本次版本，但希望在当前分支上撤销这次提交，推荐使用 `revert`，这样不会破坏 GitHub 历史：

```bash
git revert <本次版本的commit-id>
git push origin feature/first-version-app
```

如果只是本地临时查看旧版本，查看结束后回到当前开发分支：

```bash
git checkout feature/first-version-app
```

## 本次版本内容总结

- 按第一版需求重做主控 UI：顶部栏、底部导航、首页、地图页、手动遥控页、状态详情页。
- 增加平板宽屏布局 `layout-sw600dp`，手机和窄屏使用默认布局，业务逻辑共用同一个 `MainActivity`。
- 首页增加日志入口，点击进入本地日志页面。
- 设备列表页右上角增加用户中心，支持查看当前账号、切换账号、退出登录。
- MQTT 主题统一为 `device/{productType}/{deviceId}/{topicType}`。
- App 进入设备后订阅 `heartbeat`、`status`、`cmd_ack`、`map`。
- 命令下发支持 `start`、`stop`、`estop`、`clear_estop`，并按 `cmdId` 匹配 `cmd_ack`。
- 手动遥控改为 360 度虚拟摇杆，按需求延迟 0.5 秒后开始周期发布 `remote`，松手发送零速度。
- 在线状态只由 `heartbeat` 决定，超过 3 秒无心跳显示离线。
- 增加系统栏自适应，避免平板任务栏或系统导航栏遮挡 App 内容。
- 增加本地 MQTT 机器人模拟器，支持在一个命令行窗口内同时模拟上报和监听 App 下发：

```powershell
powershell -ExecutionPolicy Bypass -File tools\mqtt-robot-sim.ps1
```

模拟器可完成：

- 持续发布机器人心跳和状态；
- 监听 App 下发的 `cmd` / `remote`；
- 对 `cmd` 自动返回相同 `cmdId` 的 `cmd_ack`；
- 在命令行打印 App 下发内容，方便测试每个 UI 按钮。

## 验证命令

```powershell
$env:JAVA_HOME='D:\AAwork\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug
```
