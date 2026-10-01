# Android App 当前状态

更新日期：2026-10-02

## 开发基线

- 当前分支：`feature/app-update-channel`；
- 当前唯一有效联调设备：`crawler_00000001`；
- 无效旧设备 `rk3588` 已由 App 拒绝，并已从当前测试账号的云端绑定中移除；
- 当前下载：test 渠道 `1.3.17-test+3ebc02cd`，debug 渠道 `1.3.18-debug+3ebc02cd`；
- 可以交给测试人员对照机器人做功能测试。Robot 实机证据未齐，不能标为三方联调通过，也不能合入 `develop`。

## 当前可用功能

- 登录、退出和 Token 会话；登录页支持显示/隐藏密码，并使用 Android Keystore 加密记住登录信息；
- 设备列表仅允许符合正式 MQTT 身份格式的设备进入主界面；
- MQTT 连接、心跳在线状态、状态、命令回执和地图位姿；
- Map V2 鉴权下载、checksum/大小/设备/版本校验、分设备缓存和离线缓存；
- 首页设备状态、覆盖任务开始、停止、暂停、恢复、重新规划、急停和解除急停；
- 地图页缩放、显示全部、居中机器人、航向和最近 10 秒轨迹；
- 手动模式、自动模式、长按方向控制、速度预设与松手零速；
- V6 GPS、温度、电流和姿态遥测；更多页用户文案显示「主控温度」，不再显示 RK3588；
- 从登录到每个按钮的前提、接口、取值、展示和操作后反应见 [app-readme/README.md](app-readme/README.md)；
- 本地结构化日志、作业记录、关于与版本页面；
- Wi-Fi 配置和固件升级入口保持禁用，等待 Robot 执行闭环；
- `test`/`stable` App 更新渠道、固定签名、HTTPS 下载和安装前校验。

## 当前验证结果

```text
2026-10-02 testDebugUnitTest：通过
2026-10-02 lintDebug：通过
2026-10-02 assembleDebug：通过
```

可安装调试包：`app/build/outputs/apk/debug/app-debug.apk`。Debug 包开启控制门禁旁路，测试人员应安装 `test` 渠道 Release 包。

界面验证：

- 手机模拟器 `1080 × 2400`：已登录进入 `crawler_00000001`，总览、地图、控制、记录、更多均可打开；离线文案为中文；用户页面没有 MQTT、RK3588、runState；
- 平板模拟器 `2560 × 1600`：登录页显示邮箱、密码、记住登录信息，不显示自动登录；
- 机器人当前离线，本次没有实机动作证据；
- 平板仍只代表模拟器，不代表甲方平板真机。

## 正式发布前待完成

- 发布包含本轮文案的新 `test` APK，测试人员下载该包后再测；
- 在手机真机完成登录、设备、地图、任务、手动控制、日志和更新流程；
- 完成 `crawler_00000001` 的 Robot/Cloud/App 三方实机联调；
- 保存地图上传与激活、任务 ACK/status、20 Hz 遥控、松手零速、ESTOP 和 V6 真实遥测证据；
- 甲方提供平板后补做平板真机布局、触摸、软键盘和长时间运行验收；
- 联调通过后通过 PR 合入 `develop`，再发布 `stable`。

## 交接入口

- 功能、页面和验收责任：[app-handoff.md](app-handoff.md)
- 需求文档：`docs/requirements/`
- App 构建与测试：仓库根目录 `README.md`
- Cloud API 与部署：`cloud-server` 仓库
- Robot MQTT/ROS 和联合验收：`robot-integration` 仓库

## 回退

- 本轮正式化代码提交为 `a16e51d`，变更前回退点为 `5ee999f`；
- 已发布 App 回退时，使用旧代码重新构建，并设置更大的 `versionCode`；
- JKS 和真实凭据不进入 Git。
