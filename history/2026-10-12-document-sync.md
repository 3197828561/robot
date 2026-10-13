# 2026-10-12 文档与跨电脑配置同步

## 范围

- 更新根 README、文档索引、当前状态、交接、发布门禁和 App 操作/字段说明日期；
- 未修改 Android 业务代码、Gradle、工作流或模拟器代码；
- V1–V6 需求原文和既有历史记录保持原样。
- 补齐 GitHub 全新克隆、JDK/Android SDK、Debug 构建、Actions Secrets 和非 GitHub
  交接边界；
- 修正 `local.properties.example`，补入缺失的 `sdk.dir` 并让 MQTT 密码默认留空。
- 删除 Windows 用户绑定的 DPAPI `.clixml` 配置；
- `run-gradle.ps1`、签名和发布工具读取仓库内被忽略的 `.local-tools/config/` 与
  `.local-tools/signing/` 明文配置，使仓库单独克隆后不依赖固定父目录结构。

## 当前基线

- 分支：`feature/app-update-channel`；
- 同步前本地与远端提交：`e968220b17a54fd1b872b99201d70faaf35373a3`；
- Cloud：`feature/cloud-app-distribution@92adba3`，服务器已同步；
- test：`1.3.17-test+3ebc02cd`；
- debug：`1.3.18-debug+3ebc02cd`；
- stable：尚未发布。

## 验证与交接

- DPAPI 移除前的文档同步只有文档变更；后续 4 个 PowerShell 工具均通过语法检查，固定
  JKS 与明文签名参数验证通过；
- 最近代码验证仍是 2026-10-02 的单元测试、Lint 和 Debug 构建通过；
- 2026-10-12 重新执行 `testDebugUnitTest lintDebug assembleDebug`，57 个 Gradle task
  成功完成；DPAPI 移除后再次执行同一命令，结果为 `BUILD SUCCESSFUL`（57 tasks）；
- Robot 实机三方联调和平板真机验收仍未完成；
- 回退点：`e968220b17a54fd1b872b99201d70faaf35373a3`。
