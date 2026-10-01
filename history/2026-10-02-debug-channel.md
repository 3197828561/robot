# 2026-10-02 Debug 下载渠道

## 变更范围

- Debug 构建使用包名后缀 `.debug`，桌面名「光伏机器人调试」，可与 test 包同时安装；
- 发布流程可以选择 `debug` 渠道并上传 Debug APK；
- `docs/app-readme/README.md` 写明 Debug 与 test 的按钮触发条件。

## 验证

- 按钮条件来自 `MissionControlPolicy`：离线时两种包都不可控；
- Debug 在在线后放宽任务按钮，方向键仍要求手动模式已确认；
- test 继续按安全状态和任务状态禁用按钮。

## 部署与发布

- 分支：`feature/app-update-channel`；
- Debug 包下载：`/downloads/app/debug`，要等 Cloud 迁移完成且发布流程结束后才可用；
- 不合入 `develop`。

## 回退点

- 上一记录：`history/2026-10-02-formal-app-v1.md`。
