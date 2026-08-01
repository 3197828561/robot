# 项目文档索引与协作流程

本文用于区分机器人端接口原文、APP/服务器实现说明和联调证据。判断项目进度时，必须同时查看接口契约、GitHub 最新代码和对应实现说明，不能只看其中一项。

## 文档分层

### 1. `docs/requirements/`：外部需求与机器人端接口契约

此目录保存机器人端或产品侧交付的原始文档。原则上保留原文，不在原文件中混写 APP 实现结论。

- `v3_app_mission_command_migration.md`：机器人端 MissionCommand 升级接口。
- `map_upload_backend_api.md`：机器人地图自动上传、服务器保存和 APP 下载接口。
- `第一版APP需求分析文档.md`、`第二版APP需求分析文档 .md`：APP 需求基线。
- `map_planner/`：地图规划模块资料、消息、服务、示例和源码参考。

### 2. `docs/changes/`：本项目实现与交接说明

每次依据新接口完成代码修改后，在此目录新增独立说明，记录：

- 输入接口文档及版本；
- 对照的 GitHub 基线提交；
- 本次实现提交；
- 实际 payload、状态机和配置；
- 自动化测试结果；
- 尚未完成的真实设备或服务器联调；
- APP、服务器、Robot 各方下一步。

当前说明：

- `2026-08-01-map-upload-backend-implementation.md`
- `2026-08-01-app-mission-command-migration.md`
- `2026-08-01-login-bcrypt-compatibility-fix.md`

### 3. `docs/` 根目录：长期维护资料

根目录包含云通信、HTTP API、部署、联调清单和验收模板，例如：

- `cloud-comm-api.md`
- `interfaces-summary.md`
- `integration-protocol-v1.md`
- `http-api-guide.md`
- `server-http-only-deploy.md`
- `deploy-aliyun.md`
- `desktop-mqtt-test.md`
- `hw-integration-checklist.md`
- `integration-report-template.md`

## 双人 APP 开发流程

1. 收到机器人端新接口文档后，将原文放入 `docs/requirements/`，文件名包含功能或版本。
2. 拉取 GitHub 最新 `main`，记录基线 SHA，检查同事已完成的代码和文档。
3. 逐项建立“接口要求—现有实现—缺口”清单，再决定本次任务范围。
4. 修改代码并执行单元测试、构建和必要的配置检查。
5. 在 `docs/changes/` 新增本次实现说明，明确已实现与未联调内容。
6. 将代码、测试和说明文档放在同一次或相邻的可追踪提交中推送 GitHub。
7. Robot 与 APP 联调时保留 MQTT payload、`cmd_ack`、`status`、服务器响应和 UI 结果作为验收证据。

## 状态判定规则

- “代码已实现”：GitHub 中存在实现提交且自动化测试通过。
- “已部署”：目标服务器或设备已经运行该提交，并记录实际版本。
- “联调通过”：Robot、服务器和 APP 的真实链路均有请求、响应和界面证据。
- “文档已上传”：文档已经被 Git 跟踪并推送到远端，不以聊天附件或本地文件为准。

