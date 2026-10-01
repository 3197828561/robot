# 2026-10-02 Formal App V1 测试开放记录

## 变更范围

- 用户界面继续使用中文：主控温度、通信服务、任务状态不再展示协议字段名；
- 新增 `docs/app-readme/`：从登录到每个按钮的前提、接口、字段值、展示和操作后反应；
- 判断可以交给测试人员做 App 与机器人功能测试，但不能标为三方联调通过。

## 验证

```text
testDebugUnitTest：通过
lintDebug：通过
assembleDebug：通过
```

- 手机模拟器 `1080 × 2400`：设备列表、总览、地图、控制、记录、更多通过；机器人离线，任务按钮在 Debug 包因门禁旁路仍可点，Release 测试包按状态禁用；
- 平板模拟器 `2560 × 1600`：登录页通过；
- 未做 Robot 实机动作验收。

## 部署与发布

- 分支：`feature/app-update-channel`；
- 不合并 `develop`；
- 已上架测试包为 `1.3.12-test+e765941`。本轮提交后的新包需等 GitHub Actions 完成后，下载入口才会更新；
- Cloud 运行容器不因本文件重建。

## 交接事项

- 测试人员使用 `test` 渠道 APK，设备 `crawler/crawler_00000001`；
- Wi-Fi 与固件仍不可用；
- 记录 App 版本、手机型号、时间、按钮状态和机器人是否动作。

## 回退点

- 上一份 App 记录：`history/2026-10-01-formal-app-v1.md`；
- 回退已安装包时使用更高 `versionCode` 重发，不覆盖已发布 APK。
