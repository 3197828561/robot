# Solar Robot Android App

光伏清洁机器人 Android 客户端。本仓库只保留 App 代码、App 测试、V1–V6 需求原文和 App 侧实现记录。

云服务代码和运维文档在 `cloud-server` 仓库；Robot 硬件交接、`map_planner` 参考源码、联调样例和跨仓库基线在 `robot-integration` 仓库。

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

```powershell
Copy-Item local.properties.example local.properties
```

真实服务器地址、MQTT 密码和工具路径只放在被 Git 忽略的 `local.properties`。不要把密码、Token、JKS 或 Base64 提交到仓库。

## 构建与验证

```powershell
.\tools\run-gradle.ps1 testDebugUnitTest lintDebug assembleDebug
```

Release 签名密码由 Windows DPAPI 加密保存，构建时仅注入当前进程。首次创建或验证签名库：

```powershell
.\tools\create-release-keystore.ps1
```

## App 更新渠道

- `test`：feature 联调包；
- `stable`：完成实机联调后的稳定包。

```powershell
.\tools\configure-distribution-secrets.ps1
.\tools\publish-test-app.ps1
```

测试人员可直接下载：<https://47.103.157.213/downloads/app/test>。测试 APK 的浏览器入口支持公开 `GET`/`HEAD`；APK 上传、App 登录和业务 API 仍需要各自凭据。

## 协作边界

- App 当前状态：[docs/project-status.md](docs/project-status.md)
- 需求与文档索引：[docs/README.md](docs/README.md)
- Cloud 部署与 API：`HS678/cloud-server`
- Robot 硬件交接与联调基线：`HS678/robot-integration`

未经 Robot/Cloud/App 真实链路联调的 feature 不得合入 `develop`。
