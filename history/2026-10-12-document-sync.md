# 2026-10-12 文档同步

## 范围

- 更新根 README、文档索引、当前状态、交接、发布门禁和 App 操作/字段说明日期；
- 未修改 Android、Gradle、工作流或模拟器代码；
- V1–V6 需求原文和既有历史记录保持原样。
- 补齐 GitHub 全新克隆、JDK/Android SDK、Debug 构建、Actions Secrets 和非 GitHub
  交接边界；
- 修正 `local.properties.example`，补入缺失的 `sdk.dir` 并让 MQTT 密码默认留空。

## 当前基线

- 分支：`feature/app-update-channel`；
- 同步前本地与远端提交：`e968220b17a54fd1b872b99201d70faaf35373a3`；
- Cloud：`feature/cloud-app-distribution@92adba3`，服务器已同步；
- test：`1.3.17-test+3ebc02cd`；
- debug：`1.3.18-debug+3ebc02cd`；
- stable：尚未发布。

## 验证与交接

- 本次只有文档变更，因此未重新运行 Gradle 构建；
- 最近代码验证仍是 2026-10-02 的单元测试、Lint 和 Debug 构建通过；
- 2026-10-12 重新执行 `testDebugUnitTest lintDebug assembleDebug`，57 个 Gradle task
  成功完成；
- Robot 实机三方联调和平板真机验收仍未完成；
- 回退点：`e968220b17a54fd1b872b99201d70faaf35373a3`。
