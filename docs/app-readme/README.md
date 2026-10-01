# App 操作说明

更新日期：2026-10-02

本文按用户操作顺序记录：前提、按钮、后端接口、字段取值、界面展示、操作之后的反应。接口仍是 V3 未覆盖部分、V4.3、V5、V6。MQTT 消息 `version` 固定 `"1.0"`。当前设备只有 `crawler/crawler_00000001`。

字段取值总表见 [field-mapping.md](field-mapping.md)。

HTTP 基址为 `BuildConfig.API_BASE_URL`。MQTT 主题为 `device/{productType}/{deviceId}/{topicType}`。

无控制权限时，所有 `cmd` 和 `remote` 都不发送，提示「当前账号只有查看权限」。

## 1. 启动和登录

| 步骤 | 前提 | 操作 | 接口 | 字段 | 展示 | 之后 |
|---|---|---|---|---|---|---|
| 启动 | 已安装 App | 打开应用 | 本地会话；失效时 `POST /api/auth/refresh`，体 `refresh_token` | 有效 Token | 启动页标题后自动离开 | 有会话进设备列表，否则进登录 |
| 登录 | 邮箱和密码非空 | 「登录」 | `POST /api/auth/login` | `email`、`password` | 按钮变为「正在登录…」 | 成功进设备列表；401/403 显示账号错误或停用；网络失败显示无法连接 |
| 记住登录信息 | 在登录页 | 勾选后登录成功 | 无 | 本地 Keystore | 「记住登录信息」 | 下次自动填入邮箱和密码 |
| 自动登录勾选 | — | 当前隐藏 | 无 | — | 不显示 | 不作为用户操作 |

## 2. 设备列表

| 步骤 | 前提 | 操作 | 接口 | 字段 | 展示 | 之后 |
|---|---|---|---|---|---|---|
| 加载设备 | 已登录 | 进入页面或下拉刷新 | `GET /api/devices` | `display_name`、`device_id`、`product_type`、`role`、`permissions` | 「履带机器人01」；`crawler` 显示「光伏清扫机器人」；可控制显示「管理员 · 可控制」 | 非法身份不出现在列表 |
| 搜索 | 列表已加载 | 输入名称或编号 | 无 | 本地过滤 | 「没有匹配的设备」或过滤结果 | 不请求后端 |
| 进入 | 卡片可见 | 点设备卡片 | 无新 HTTP | 把当前设备写入会话 | 进入总览 | 开始连接 MQTT，并拉取当前地图 |
| 检查更新 | 进入列表 | 自动检查 | `GET /api/app-releases/test/latest` | `versionName`、`versionCode`、`mandatory`、`releaseNotes` | 「发现新版本 {versionName}」 | 「稍后」跳过该版本；「下载并安装」下载 APK 并校验 |
| 账号 | 已登录 | 「账号」 | 无 | 邮箱 | 菜单：邮箱、切换账号、退出登录 | — |
| 退出 | 在账号菜单 | 「切换账号」或「退出登录」 | `POST /api/auth/logout` | `refresh_token` | 回到登录页 | 本地会话清除 |

## 3. 总览

进入主界面后订阅 `heartbeat`、`status`、`cmd_ack`、`pose`。3 秒内收到心跳才显示在线数值，否则显示「离线」或「设备离线」。

| 展示 | 来源 | 字段 | 展示规则 |
|---|---|---|---|
| 通信 | MQTT 连接 | 客户端是否连接 | 正常 / 断开 |
| 在线状态 | `heartbeat` | 最近心跳 | 在线 / 离线 / 等待状态 |
| 工作状态 | `status` | 先安全状态，再任务编排，再 `workStatus` | 见字段表 |
| 控制模式 | `status.controlMode` | `auto` / `manual` | 自动模式 / 手动模式 |
| 电量 | `status.batteryPercent` | 0–100 | `80%` |
| 线速度 / 角速度 | `status` | `linearSpeedCms` / `angularSpeedRadps` | `30 cm/s` / `0.30 rad/s` |
| 地图条 | HTTP Map V2 | `mapId`、`mapVersion` | 「编号 · 版本」 |

| 按钮 | 前提 | 接口 | 关键字段 | 之后 |
|---|---|---|---|---|
| 同步地图 | 已选设备 | `GET /api/devices/crawler/crawler_00000001/maps/current`，再下载 content | `activeMap.mapId`、`mapVersion`、`checksum` | 地图重绘；失败时保留已校验缓存 |
| 居中机器人 | 已有地图 | 无 | 使用 `pose` | 无有效位置时提示并显示全图 |
| 开始运行 | Release：在线、安全正常、自动模式、有地图、无命令在途。Debug 包放宽门禁 | 先打开配置框，确认后 MQTT `cmd=start` | `params.taskKind=coverage`，`params.coverage` | 见下一节 |
| 停止运行 | 存在 `rootMissionId` 或旧 `missionId` | `cmd=stop` | `params.targetMissionId` | 受理后等 `orchestrationState=canceled` 或 `runState=canceled` |
| 暂停任务 | 任务处于启动或运行 | `cmd=pause` | `targetMissionId` | `paused_by_user` 或 `runState=paused`，界面「已暂停」 |
| 恢复任务 | 任务已暂停 | `cmd=resume` | `targetMissionId` | `orchestrationState=running` |
| 重新规划 | 根任务为覆盖且没有内部子任务 | `cmd=replan` | `targetMissionId` | `phase` 先到规划，再回到执行 |
| 紧急停止 | 在线且可控制 | `cmd=estop`，`params={}` | — | `safetyState=estop`，界面「急停」 |
| 解除急停 | 当前为急停，并在确认框点确定 | `cmd=clear_estop` | — | 先显示「解除急停请求已受理」；`safetyState` 回到 `normal` 后恢复普通状态 |
| 重试最近失败命令 | 上一条失败且没有新命令在途 | 同一 `cmdId` 和同一 payload | — | 重新等待回执 |
| 设备列表 | 任意 | 无 HTTP | — | 停止遥控，断开当前 MQTT，回到列表 |

开始任务对话框：

| 控件 | 前提 | 写入字段 | 不满足时 |
|---|---|---|---|
| 使用机器人当前位置作为起点 | 默认勾选 | `coverage.useCurrentPose=true`，`start=null` | — |
| 六个起点输入 | 取消勾选当前位置 | `coverage.start.blockId/cellRow/cellCol/innerRow/innerCol/heading` | 「请完整填写起点的六个字段」 |
| 区域 Chip | 地图里可清洁区域 | `coverage.targetBlockIds` | 「至少选择一个目标区域」 |
| 启用全局规划 | 默认勾选 | `coverage.globalPlan` | — |
| 取消 | — | 不发送 | 对话框关闭 |
| 开始任务 | 地图编号和版本在协议范围内 | `coverage.mapId`、`coverage.mapVersion` | 无地图时总览直接提示「请先加载有效地图」 |

命令发出后的共同反应：

1. 状态条显示「正在发送」。
2. 发布到 `.../cmd`，QoS 1。
3. 5 秒内收到 `cmd_ack`：`ackStatus=success` 显示已受理；`failed` 显示拒绝，并带 `errorCode` 中文。
4. 超时显示「暂未收到受理回执」。
5. 受理不等于任务完成。最终结果看后续 `status`。

## 4. 地图

| 按钮 | 前提 | 接口 | 之后 |
|---|---|---|---|
| 显示全部 | 已有地图 | 无 | 视野复位 |
| 居中机器人 | 已有地图 | 无；位置来自 `pose` | 无位置时提示 |
| + / − / 定位 | 已有地图 | 无 | 放大、缩小、定位到机器人 |
| 手势缩放拖动 | 已有地图 | 无 | 只改本机视野 |

`pose` 字段：`blockId` 显示区域，`cellRow/cellCol` 显示单元，`headingCode` 0–3 显示横向或纵向正反向。`pose.mapId/mapVersion` 与当前地图不一致时，界面提示正在同步并重新拉 HTTP 地图。

## 5. 手动控制

| 按钮 | 前提 | 接口 | 字段值 | 之后 |
|---|---|---|---|---|
| 进入手动模式 | 通信正常、在线、安全状态 `normal` | `cmd=manual` | `params={}` | 回执成功且 `operationalMode=manual` 后，方向键可用 |
| 切回自动模式 | 已在手动流程 | 先发零速，再 `cmd=auto` | `linearSpeedCms=0`，`angularSpeedRadps=0` | `controlMode=auto` |
| 方向键按住 | 已确认手动模式 | `.../remote`，约 20 Hz | 前进 `+线速度`；后退 `-线速度`；左转 `+角速度`；右转 `-角速度` | 0.5 秒后才开始发速度 |
| 松开方向键 | 正在遥控 | `remote` 一次零速 | 两个速度都为 0 | 机器人应停止 |
| 普通停止 | 手动控制可用 | `remote` 零速 | 不发任务 `stop` | 遥控停止 |
| 紧急停止 | 可急停 | 取消方向键后 `cmd=estop` | — | 同总览急停 |
| 慢速 / 标准 / 高速 | 手动控制可用 | 无，直到下次 remote | 10/0.1、30/0.3、50/0.5 | 只改本机速度 |
| 线速度 ± | 手动控制可用 | 无 | 步进 1，范围 0–50 cm/s | 只改本机速度 |
| 角速度 ± | 手动控制可用 | 无 | 步进 0.1，范围 0–0.5 rad/s | 只改本机速度 |

不可用时的提示：「通信连接已断开，手动控制不可用」「设备离线，手动控制不可用」「安全状态不允许手动控制」「正在等待机器人切换到手动模式」。

离开本页、切到其他 Tab、或返回设备列表时，若正在遥控，再发一次零速。

## 6. 记录

| 按钮 | 前提 | 接口 | 展示 | 之后 |
|---|---|---|---|---|
| 查看作业记录 | 已选设备 | `GET /api/jobs?device_id=crawler_00000001` | `status` 映射为等待执行、执行中、已完成、执行失败、已取消；另显示时间和清扫行数 | 失败提示检查网络；空列表「暂无作业记录」 |
| 查看故障与警告 | 任意 | 本机数据库 | 只看警告和错误 | 不访问云端 |
| 查看全部App日志 | 任意 | 本机数据库 | 全部、操作、设备、连接、警告/错误 | 下拉刷新不请求网络 |
| 清空 | 在日志页并确认 | 无 | 「本地日志已清空」 | 只删本机记录 |

## 7. 更多、关于和维护

| 按钮 | 前提 | 接口 | 展示 | 之后 |
|---|---|---|---|---|
| 状态卡片 | 在线才显示数值 | `status`、`heartbeat` | 通信服务、机器人状态、电量、主控温度、整机电流、板面倾角、任务状态 | 离线时明确写「机器人离线」 |
| 查看完整诊断信息 | 任意 | 同上 | 中文加协议字段名，供联调 | 不作为日常文案 |
| Wi-Fi配置 | 按钮禁用 | 页面实现为 `GET/PUT /api/devices/{id}/wifi`，当前不开放 | 「Wi-Fi配置和固件升级暂不可用」 | 不能据此认为机器人已改网 |
| Robot固件升级 | 按钮禁用 | `GET /api/firmware/latest`、`POST /api/firmware/upgrade` 尚未形成执行闭环 | 同上 | 不能据此认为升级完成 |
| 关于、版本与App更新 | 任意 | 打开关于页 | 版本名称、编号、渠道、构建提交 | — |
| 检查App更新 | 在关于页 | `GET /api/app-releases/{channel}/latest`，下载 `.../content` | 发现新版本或保持沉默 | 校验大小、SHA-256、包名、版本和签名后再安装 |
| 账号与设备 | 任意 | 无 | 回到设备列表 | 不是退出登录 |

主控温度对应 `status.rk3588CpuTemperatureCelsius`，单位 °C。界面只写「主控温度」。

## 8. 测试人员边界

可以交给测试人员做的：

- 用已发布 `test` 渠道 APK 登录、选 `crawler_00000001`、看地图、下发自动任务、手动遥控、急停，并对照机器人实际动作。
- 记录 App 版本、手机型号、时间、页面、按钮状态和机器人是否动作。

还不能写成已通过的：

- Robot 实机闭环尚未在本次记录中取得动作证据。
- 不把本轮标为三方联调通过，也不把 feature 合入 `develop`。
- Wi-Fi 和固件保持不可用。
- 平板目前只在 `2560 × 1600` 模拟器核对登录页，不是甲方平板真机。
