# 2026-10-13 多人开发 CI/CD 调整

## 范围

- 新增 `.github/workflows/android-ci.yml`；
- feature 分支推送只执行单元测试、Lint 和 Debug 构建，不上传 Cloud；
- PR 合入 `develop` 或 `main` 前执行相同 CI；
- `develop` 合并后的相关代码变更自动发布 `test`；
- `v*` 标签仅在目标提交属于 `main` 时发布 `stable`，手动发布只允许 `debug` 或 `test`；
- 修正 debug 发布时 GitHub Artifact 路径。

## 多人协作规则

1. 每位开发者从 `develop` 创建自己的 `feature/*`；
2. feature 推送通过 CI 后创建 PR；
3. PR 审查和必需检查通过后合入 `develop`；
4. 只有合并后的 `develop` 自动更新共享 test 渠道，避免多人 feature 相互覆盖；
5. stable 只由 `v*` 标签发布。

## 验证与部署状态

- 两个工作流已完成 YAML 解析和触发条件静态检查；
- Android 业务代码、Cloud 代码和服务器配置未修改；
- 本次没有推送、没有触发 Actions、没有上传 APK，也没有变更阿里云；
- 回退方式：恢复本次修改前的 `publish-app.yml` 并删除 `android-ci.yml`。

