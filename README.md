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

## 验证命令

Windows PowerShell 示例：

```powershell
$env:JAVA_HOME='D:\AAwork\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug
```

如果本机 Java 已经在 PATH 中，也可以直接执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

## 回退到最初版本

本次大改前已经打了备份 tag：

```text
before-first-version-ui
```

该 tag 指向：

```text
46a9dde docs: add first version app requirements
```

下面按常见场景说明如何回退。

### 1. 先确认当前状态

```bash
git status
git branch --show-current
git tag --list
git log --oneline --decorate -5
```

如果 `git status` 显示有未提交改动，先提交、暂存或放弃这些改动，再做回退操作。

### 2. 只想临时查看最初版本

这种方式不会修改当前分支历史，适合只是打开旧代码看一眼：

```bash
git checkout before-first-version-ui
```

查看结束后回到当前开发分支：

```bash
git checkout feature/first-version-app
```

注意：临时 checkout tag 会进入 detached HEAD 状态，不建议在这个状态下直接开发。

### 3. 想基于最初版本重新开一个恢复分支

这种方式最安全，适合需要保留当前版本，同时另开一条线恢复旧版：

```bash
git checkout -b restore-before-first-version-ui before-first-version-ui
```

如果需要推送这个恢复分支：

```bash
git push origin restore-before-first-version-ui
```

### 4. 当前版本已经推送，但想在原分支撤销它

推荐用 `git revert`。它会新增一个“反向提交”，不会改写 GitHub 历史。

先确认要撤销的提交，例如当前版本是：

```text
00ad3be feat: implement first version app control UI
```

执行：

```bash
git checkout feature/first-version-app
git pull origin feature/first-version-app
git revert 00ad3be
git push origin feature/first-version-app
```

适用场景：

- 当前提交已经推送到 GitHub；
- 其他人可能已经拉取了这个分支；
- 希望保留完整历史。

### 5. 只在本地强制回到最初版本

只有在确认本地改动都不要了，并且清楚风险时才使用：

```bash
git checkout feature/first-version-app
git reset --hard before-first-version-ui
```

如果还要强制覆盖远端分支，需要额外执行：

```bash
git push --force-with-lease origin feature/first-version-app
```

不推荐日常使用强推。多人协作时优先使用 `git revert`。

### 6. 回退后重新运行验证

无论使用哪种方式，回退后建议执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

确认旧版本仍能构建。
