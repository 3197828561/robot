# 2026-10-01 Formal App V1 交接记录

## 变更范围

- 完成正式化登录、设备列表、总览、地图、控制、记录和更多入口；
- 仅允许合法 Robot 身份进入控制界面，当前设备为 `crawler/crawler_00000001`；
- 修复 HTTP 认证重试与 HTTPS 登录链路；
- 增加设备搜索、刷新、失败重试、权限说明和关于页面；
- Wi-Fi 与 Robot OTA 在执行闭环完成前保持禁用；
- 适配 Android 15 系统栏，并分别整理手机与平板布局；
- 更新 GitHub Actions、配置模板、交付清单和当前交接文档；
- 更多页将 `rk3588CpuTemperatureCelsius` 显示为「主控温度」，任务状态不再对用户展示「根任务/栈深」；
- 补充 `docs/app-control-field-mapping.md`：每个按钮对应的 HTTP/MQTT 主题、字段、取值和界面文案。

## 验证

```text
testDebugUnitTest：通过
lintDebug：通过
assembleDebug：通过
assembleRelease：通过
```

- 手机模拟器 `1080 × 2400`：设备列表、唯一设备身份和系统栏显示通过；
- 平板模拟器 `2560 × 1600` 横屏：登录表单、系统栏和页面比例通过；
- Debug 与固定签名 Release APK 均已生成；
- Robot 实机联合验收尚未执行。

## 部署与发布

- 分支：`feature/app-update-channel`；
- 正式化代码提交：`a16e51d`；
- 新测试 APK 尚未发布，当前下载渠道仍为 `1.3.11-test+1471c285`；
- 本轮未修改 Cloud 运行环境和 Robot 硬件。

## 交接事项

- 下一步发布新版 `test` APK，并验证旧版覆盖升级；
- 使用手机真机完成页面、软键盘、安装和更新测试；
- 使用 `crawler_00000001` 完成 Map V2、自动任务、手动控制、急停与 V6 遥测联调；
- 平板当前仅为模拟器兼容通过，甲方提供设备后补做真机验收。

## 回退点

- 变更前 App 提交：`5ee999f`；
- 正式化代码提交：`a16e51d`；
- 已发布旧测试 APK 保持可下载，回退代码重新发布时必须使用更高 `versionCode`。
