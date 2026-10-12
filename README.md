# Solar Robot Android App

光伏清洁机器人 Android 客户端。本仓库只保留 App 代码、App 测试、V1–V6 需求原文和 App 侧实现记录。

云服务代码和运维文档在 `cloud-server` 仓库；Robot 硬件交接、`map_planner` 参考源码、联调样例和跨仓库基线在 `robot-integration` 仓库。

当前联调分支为 `feature/app-update-channel`。2026-10-12 文档同步时，最新 App/CI
实现基线为 `e968220`；本次同步只更新文档，不改变 APK 或运行行为。

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

至少填写 `sdk.dir` 和 `java.home`。仅运行单元测试、Lint 和 Debug 构建时，MQTT 密码可以
留空；连接真实 Robot 时再通过受控交接材料填写，不能提交 `local.properties`。

```powershell
.\tools\run-gradle.ps1 testDebugUnitTest lintDebug assembleDebug
```

成功后 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。真实服务器地址、MQTT
密码和工具路径只放在被 Git 忽略的 `local.properties`。不要把密码、Token、JKS 或
Base64 提交到仓库。

## 构建与验证

```powershell
.\tools\run-gradle.ps1 testDebugUnitTest lintDebug assembleDebug
```

这是交接时的最小复现命令。`assembleRelease` 还需要固定 JKS 和四个签名变量，发布到 Cloud
还需要 App 发布 Token；这些材料不在 GitHub 中。

Release 签名密码由 Windows DPAPI 加密保存，构建时仅注入当前进程。首次创建或验证签名库：

```powershell
.\tools\create-release-keystore.ps1
```

## App 更新渠道

- `debug`：与 `test` 并存的控制门禁对照包；
- `test`：feature 联调包；
- `stable`：完成实机联调后的稳定包。

```powershell
.\tools\configure-distribution-secrets.ps1
.\tools\publish-test-app.ps1
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

普通 `feature/*` 或 `develop` 的 App/Gradle 代码变更发布 `test`；标签构建发布 `stable`；
`workflow_dispatch` 可选择 `debug`、`test` 或 `stable`。文档、history 和模拟器脚本变更不会
自动发布。首次接手时先核对 Secrets 名称和签名证书 SHA-256，不要在日志中打印值。

## GitHub 无法提供的内容

- 固定 Release JKS、签名密码和 DPAPI 文件；
- App/Robot MQTT 密码、测试账号密码和 App 发布 Token；
- Android SDK、JDK、ADB、模拟器和 Mosquitto 的本机安装；
- 已安装设备状态、服务器数据库和 Robot 实机证据。

这些内容通过工作区同级 `import-message/` 私下交接，收到后应迁移到接收人自己的安全存储，
并按需要轮换凭据。

## 协作边界

- App 当前状态：[docs/project-status.md](docs/project-status.md)
- App 功能与交接：[docs/app-handoff.md](docs/app-handoff.md)
- App 正式化产品需求：[docs/requirements/app-formalization-product-requirements.md](docs/requirements/app-formalization-product-requirements.md)
- App 正式交付清单：[docs/app-delivery-checklist.md](docs/app-delivery-checklist.md)
- 需求与文档索引：[docs/README.md](docs/README.md)
- Cloud 部署与 API：`HS678/cloud-server`
- Robot 硬件交接与联调基线：`HS678/robot-integration`

未经 Robot/Cloud/App 真实链路联调的 feature 不得合入 `develop`。

## 分支规范

- `main`：稳定发布；
- `develop`：日常集成；
- `feature/*`：功能开发，经 PR 合入 `develop`；
- 当前联调分支：`feature/app-update-channel`。

详细分支、PR、标签和联合发布规范见 `HS678/robot-integration` 的
`docs/git-workflow.md`。
