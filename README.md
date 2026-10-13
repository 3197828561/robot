# Solar Robot Android App

光伏清洁机器人 Android 客户端。本仓库只保留 App 代码、App 测试、V1–V6 需求原文和 App 侧实现记录。

本仓库可以独立克隆、配置、测试和构建，不要求与任何其他仓库放在同一父目录。当前联调分支
为 `feature/app-update-channel`；准确版本以该远端分支 HEAD 为准。

## 当前需求基线

- 基础 UI、账号、设备和通用接口：V3 未被后续覆盖的部分；
- 任务命令、根任务和内部子任务：V4.3；
- 地图上传、激活、查询和缓存：V5 Map V2；
- GPS、温度、电流和姿态遥测：V6；
- MQTT Payload `version` 仍为 `"1.0"`，不跟随需求文档版本变化。

## 目录

```text
app/                         Android App 代码和测试
docs/requirements/           V1–V6 需求演进原文
docs/changes/                App 实现变更记录
docs/project-status.md       当前 App 基线与待验收项
tools/robot-sim/             本地 MQTT Robot 模拟脚本
tools/run-gradle.ps1         统一 Gradle 入口
tools/create-release-keystore.ps1
tools/configure-distribution-secrets.ps1
tools/publish-test-app.ps1   测试渠道发布与验签
```

## 本地配置

### 环境要求

- Windows 10/11 和 PowerShell 7；
- JDK 21；
- Android SDK Platform 35、对应 Build Tools、Platform Tools；
- Git。Android Studio 不是命令行构建必需项，但推荐用于 SDK 管理和真机调试。

### 从 GitHub 获取当前联调分支

```powershell
git clone git@github.com:3197828561/robot.git
Set-Location robot
git switch feature/app-update-channel
Copy-Item local.properties.example local.properties
```

`local.properties` 是当前电脑的 App 本地配置：保留标准 `sdk.dir`，以及 API、App MQTT
用户名/密码、设备类型和设备 ID；还可用 `app.login.email/password` 记录测试登录账号，
但这两个键不会编译进 APK，登录时仍由用户输入。该文件已被 Git 忽略。JDK 使用 `JAVA_HOME`，ADB 和
Emulator 从 Android SDK 派生，Mosquitto 使用 `MOSQUITTO_DIR`、PATH 或脚本参数，不再把
这些开发工具的独立路径写入 `local.properties`。

```powershell
.\tools\run-gradle.ps1 testDebugUnitTest lintDebug assembleDebug
```

成功后 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。API/MQTT 构建值优先读取
`local.properties`；缺失值可由仓库内被忽略的 `.local-tools/config/app-runtime.env` 补充。
GitHub Actions 使用仓库 Secrets。不要把 `local.properties`、密码、Token、JKS 或 Base64
提交到仓库。测试账号由负责人通过受控渠道单独提供，开发者只在本机密码管理器或忽略文件中保存。

可选的 `.local-tools/config/app-runtime.env` 格式如下；只在相应键未写入
`local.properties` 时作为补充：

```dotenv
API_BASE_URL=https://example.com/api
APP_RELEASE_BASE_URL=https://example.com
MQTT_HOST=mqtt.example.com
MQTT_PORT=1883
MQTT_USERNAME=replace-me
MQTT_PASSWORD=replace-me
MQTT_PRODUCT_TYPE=crawler
MQTT_DEFAULT_DEVICE_ID=crawler_00000001
```

## 构建与验证

```powershell
.\tools\run-gradle.ps1 testDebugUnitTest lintDebug assembleDebug
```

这是交接时的最小复现命令。`assembleRelease` 还需要固定 JKS 和四个签名变量，发布到 Cloud
还需要 App 发布 Token；这些材料不在 GitHub 中。

Release 签名库放在 `.local-tools/signing/robot-release.jks`，参数放在
`.local-tools/signing/release-signing-secrets.env`；两者均被 Git 忽略。负责人通过受控渠道
单独交付固定签名材料后，开发者将文件放到上述位置。首次创建或验证签名库：

```powershell
.\tools\create-release-keystore.ps1
```

签名参数文件格式：

```dotenv
ANDROID_KEYSTORE_FILE=robot-release.jks
ANDROID_KEYSTORE_PASSWORD=replace-me
ANDROID_KEY_ALIAS=robot-app
ANDROID_KEY_PASSWORD=replace-me
```

## App 更新渠道

- `debug`：与 `test` 并存的控制门禁对照包；
- `test`：多人开发合入 `develop` 后的共享联调包；
- `stable`：完成实机联调后的稳定包。

```powershell
.\tools\configure-distribution-secrets.ps1
.\tools\publish-test-app.ps1
```

`configure-distribution-secrets.ps1` 会在忽略目录生成
`.local-tools/config/distribution-secrets.env`，格式为：

```dotenv
MQTT_PASSWORD=replace-me
APP_RELEASE_UPLOAD_TOKEN=replace-me
APP_DOWNLOAD_USERNAME=replace-me
APP_DOWNLOAD_PASSWORD=replace-me
```

测试人员可直接下载 [test](https://47.103.157.213/downloads/app/test) 或
[debug](https://47.103.157.213/downloads/app/debug)。浏览器入口支持公开 `GET`/`HEAD`；
`stable` 当前尚未发布。APK 上传、App 登录和业务 API 仍需要各自凭据。

## GitHub Actions 发布复现

仓库工作流 `.github/workflows/publish-app.yml` 需要以下 GitHub Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `API_BASE_URL`
- `APP_RELEASE_BASE_URL`
- `APP_RELEASE_UPLOAD_TOKEN`
- `MQTT_HOST`
- `MQTT_PORT`
- `MQTT_USERNAME`
- `MQTT_PASSWORD`

`feature/*` 推送和面向 `develop`/`main` 的 PR 由 `.github/workflows/android-ci.yml` 执行
单元测试、Lint 和 Debug 构建，不上传 Cloud。相关代码合入 `develop` 后，
`publish-app.yml` 自动发布共享 `test`；`v*` 标签发布 `stable`；手动发布仅允许 `debug` 或
`test`，且稳定标签必须指向 `main` 已包含的提交。文档、history 和模拟器脚本变更不会触发构建。首次接手时先核对 Secrets 名称和
签名证书 SHA-256，不要在日志中打印值。

## GitHub 无法提供的内容

- 固定 Release JKS 和明文签名参数文件；
- App/Robot MQTT 密码、测试账号密码和 App 发布 Token；
- Android SDK、JDK、ADB、模拟器和 Mosquitto 的本机安装；
- 已安装设备状态、服务器数据库和 Robot 实机证据。

这些内容由相应负责人通过受控渠道单独交付，不属于本仓库，也不要求位于本仓库相邻目录。
本地明文配置只能保存到 Git 已忽略的位置，并应限制文件访问权限。

## 协作边界

- App 当前状态：[docs/project-status.md](docs/project-status.md)
- App 功能与交接：[docs/app-handoff.md](docs/app-handoff.md)
- App 正式化产品需求：[docs/requirements/app-formalization-product-requirements.md](docs/requirements/app-formalization-product-requirements.md)
- App 正式交付清单：[docs/app-delivery-checklist.md](docs/app-delivery-checklist.md)
- 需求与文档索引：[docs/README.md](docs/README.md)
- Cloud 合约：HTTPS API 基址通过 `api.base.url`/`API_BASE_URL` 配置；更新检查使用
  `/api/app-releases/{channel}/latest`；详细请求和字段已完整记录在本仓库 `docs/app-readme/`。
- Robot 合约：MQTT Topic、QoS、payload、Map V2 和实机验收要求已完整记录在
  `docs/app-handoff.md`、`docs/app-readme/field-mapping.md` 与 `docs/requirements/`。
- 未取得 Robot、Cloud、MQTT 和 App 真实链路证据的 feature 不得合入 `develop`。

## 分支规范

- `main`：稳定发布；
- `develop`：日常集成；
- `feature/*`：功能开发，经 PR 合入 `develop`；
- 当前联调分支：`feature/app-update-channel`。

开发者从 `develop` 创建 `feature/<topic>`，推送后只运行 CI；通过评审和联调后以 PR 合入
`develop`，由 `develop` 自动发布 test。稳定版本从 `main` 上已包含的提交创建 `v*` 标签；
debug 仅允许手动发布。禁止直接在功能分支生成 stable。
