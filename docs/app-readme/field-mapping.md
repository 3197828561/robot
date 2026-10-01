# App 字段取值与展示对照

操作顺序、前提和操作后反应见 [README.md](README.md)。

# App 按钮、展示值与 HTTP/MQTT 字段映射

本文记录当前正式化 App（`feature/app-update-channel`）每个可点击入口的用途，以及它绑定的后端接口。协议版本仍为 V1–V6 当前有效定义，不新增接口。当前唯一可控制设备：`productType=crawler`，`deviceId=crawler_00000001`。

HTTP 基址为 `BuildConfig.API_BASE_URL`（通常以 `/api/` 结尾）。MQTT 主题统一为：

```text
device/{productType}/{deviceId}/{topicType}
```

当前 `{topicType}` 仅使用：`heartbeat`、`status`、`pose`、`cmd`、`cmd_ack`、`remote`。命令与遥控 payload 的 `version` 均为 `"1.0"`。

未标注后端的按钮只改本机界面，不发 HTTP/MQTT。

---

## 1. 命令与遥控公共字段

### 1.1 MQTT 命令 `device/{productType}/{deviceId}/cmd`

所有任务/模式/急停按钮都发布同一结构：

| 字段 | 类型 | 固定或来源 | 说明 |
|---|---|---|---|
| `version` | string | `"1.0"` | 协议版本 |
| `cmdId` | string | `cmd_{deviceId}_{毫秒时间}_{uuid前8位}` | 重试必须复用同一 `cmdId` |
| `deviceId` | string | 当前设备 | 与 MQTT 身份一致 |
| `productType` | string | 当前设备 | 当前仅 `crawler` |
| `timestamp` | string | UTC `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` | 发送时间 |
| `cmd` | string | 见各按钮 | 命令名 |
| `params` | object | 见各按钮 | 命令参数 |

Robot 回执订阅 `.../cmd_ack`：

| MQTT 字段 | 协议值 | App 展示 |
|---|---|---|
| `ackStatus` | `success` | 机器人已受理命令 / “{操作}已被任务层受理，请等待状态更新” |
| `ackStatus` | `failed` | 机器人拒绝了命令 / “{操作}被任务层拒绝…” |
| `ackStatus` | 其他 | 收到设备操作反馈 |
| `errorCode` | 见第 8 节 | 失败弹窗中文 + `（ERROR_CODE）` |
| `message` | 任意字符串 | 失败时作为补充说明 |
| `cmd` | 与发出命令相同 | 映射为中文操作名，见第 8 节 |
| `cmdId` | 同上格式 | 诊断信息显示“命令 ID” |

回执超时 5 秒：App 显示“暂未收到“{操作}”的受理回执”。

### 1.2 MQTT 遥控 `device/{productType}/{deviceId}/remote`

方向键按住后先等待 500 ms，再约 20 Hz（每 50 ms）发布；松手或离开页面发一次零速。

| 字段 | 类型 | 取值 |
|---|---|---|
| `version` | string | `"1.0"` |
| `deviceId` | string | 当前设备 |
| `productType` | string | `crawler` |
| `timestamp` | string | UTC 时间 |
| `linearSpeedCms` | number | 线速度 cm/s，前进为正，范围 `-50..50` |
| `angularSpeedRadps` | number | 角速度 rad/s，左转为正，范围 `-0.5..0.5` |
| `durationMs` | int | 固定 `300` |

---

## 2. 登录、设备与账号

| App 控件 | 用途 | 接口 | 请求字段 | 响应字段 → App 展示 |
|---|---|---|---|---|
| 启动页（无按钮） | 已登录则进设备列表，否则进登录页 | 仅读本地会话；Token 失效时 `POST /api/auth/refresh` | `refresh_token` | 无按钮 |
| 登录页「登录」 | 用邮箱密码换 Token，进入设备列表 | HTTP `POST /api/auth/login` | `email`、`password` | 成功不展示 token；失败：`账号或密码错误` / `无法连接服务器…` 等 |
| 「记住登录信息」 | 本机 Keystore 加密保存邮箱和密码 | 无后端 | — | 勾选则下次自动填入 |
| 「自动登录」复选框 | 当前对用户隐藏（`GONE`） | 无 | — | 不展示。若本地仍有有效 Token，启动页会直接进设备列表 |
| 设备列表卡片 / 「进入」 | 选择合法身份设备进入主界面 | HTTP `GET /api/devices` | 无 | `display_name`→设备名；`product_type=crawler`→光伏清扫机器人；`device_id`→设备编号；`role`/`permissions.control`→管理员 · 可控制 |
| 设备列表搜索框 | 本机过滤列表 | 无后端 | — | 匹配名称或编号 |
| 下拉刷新 / 「重新加载」 | 重新拉设备 | 同上 `GET /api/devices` | 无 | 空列表：`当前账号暂无可用设备`；失败：`加载设备失败…` |
| 「账号」菜单 | 显示邮箱；「切换账号」「退出登录」 | HTTP `POST /api/auth/logout`（尽力调用）然后清会话 | `refresh_token` | `status` 不直接展示；回到登录页 |
| 主界面「设备列表」 | 停止 MQTT 并返回设备列表 | 无 HTTP | — | 若正在遥控则先发零速 remote |
| 更多页「账号与设备」 | 同返回设备列表 | 无 HTTP | — | 不是退出登录 |
| 无控制权限账号 | 可看不可控 | 设备 `permissions.control=false` | — | 提示「当前账号只有查看权限」 |

设备身份校验：`productType` 与 `deviceId` 必须符合正式 MQTT 身份。非法设备（含已删除的 `rk3588`）不能进入控制页，列表中也不展示。

---

## 3. 首页与总览展示（只读，来自 MQTT status/heartbeat）

在线判定：订阅 `.../heartbeat`，3 秒内收到心跳视为在线。

| App 展示标签 | MQTT 主题 | 字段 | 字段值 | App 展示 |
|---|---|---|---|---|
| 通信 | 连接状态（非报文） | MQTT 客户端 `isConnected` | true / false | 通信：正常 / 断开 |
| 在线状态 | `heartbeat` | `online` + 最近心跳时间 | 3 秒内有心跳 | 在线 / 离线 / 等待状态 |
| 工作状态 | `status` | 优先 `safetyState`/`orchestrationState`/`runState`，否则 `workStatus` | 见第 8 节 | 急停、正在执行任务、空闲等 |
| 控制模式 | `status` | `controlMode` | `auto` / `manual` | 自动模式 / 手动模式 |
| 电量 | `status` | `batteryPercent` | `0..100` | `80%`；缺测：暂无数据/设备离线 |
| 线速度 | `status` | `linearSpeedCms` | 数值 | `30 cm/s` |
| 角速度 | `status` | `angularSpeedRadps` | 数值 | `0.30 rad/s` |
| 设备状态 | `status` | `deviceStatus` | `normal` / `warning` / `fault` | 正常 / 告警 / 故障 |
| 运动状态 | `status` | `movementStatus` | `moving` / `stopped` / `turning` / `blocked` | 移动中 / 静止 / 转向中 / 受阻 |
| 顶部电量条 | `status` | `batteryPercent` | 同上 | 图形电量 |
| 顶部设备名 | HTTP 设备列表缓存 | `display_name` | 字符串 | 卡片标题 |

首页「同步地图」见第 6 节。「居中机器人」只操作地图视图，不发后端。

---

## 4. 任务控制按钮（MQTT cmd）

除「开始」外，目标任务 ID 取 `status.rootMissionId`，若空则回退 `status.missionId`，放入 `params.targetMissionId`。

| App 按钮 | 用户用途 | MQTT `cmd` | `params` | 成功后主要观察的 status 字段 |
|---|---|---|---|---|
| 「开始运行」→ 对话框「开始任务」 | 启动覆盖清扫 | `start` | 见下表 | `orchestrationState`、`runState`、`taskKind` |
| 「停止运行」 | 停止当前根任务 | `stop` | `{ "targetMissionId": "<id>" }` | `runState=canceled` 或 `orchestrationState=canceled` |
| 「暂停任务」 | 暂停清扫 | `pause` | `{ "targetMissionId": "<id>" }` | `orchestrationState=paused_by_user` 或 `runState=paused` |
| 「恢复任务」 | 从暂停恢复 | `resume` | `{ "targetMissionId": "<id>" }` | `orchestrationState=running` |
| 「重新规划」 | 对当前覆盖任务重规划 | `replan` | `{ "targetMissionId": "<id>" }` | `phase=planning` 后回到 `executing` |
| 「紧急停止」 | 立即安全停止 | `estop` | `{}` | `safetyState=estop` |
| 「解除急停」 | 确认后解除急停 | `clear_estop` | `{}` | `safetyState` 回到 `normal` |
| 「重试最近失败命令」 | 用同一 `cmdId` 重发 | 与失败命令相同 | 与失败命令相同 | 同对应命令 |

开始覆盖任务 `params`：

```json
{
  "taskKind": "coverage",
  "coverage": {
    "mapId": 1,
    "mapVersion": 1,
    "useCurrentPose": true,
    "start": null,
    "targetBlockIds": [1, 2],
    "globalPlan": true
  }
}
```

若未勾选「使用机器人当前位置作为起点」，`start` 为：

| 对话框控件 | MQTT 字段 | 含义 |
|---|---|---|
| 起点区域编号 | `coverage.start.blockId` | 地图 block |
| 起点光伏板行号 | `coverage.start.cellRow` | cell 行 |
| 起点光伏板列号 | `coverage.start.cellCol` | cell 列 |
| 起点板内行号 | `coverage.start.innerRow` | 板内行 |
| 起点板内列号 | `coverage.start.innerCol` | 板内列 |
| 机器人朝向编号 | `coverage.start.heading` | `0..3`，见第 8 节 |
| 目标区域 Chip | `coverage.targetBlockIds` | 可清洁 block 列表 |
| 「全选可清洁区域」 | 同上全选 | 仅 UI |
| 「启用全局规划」 | `coverage.globalPlan` | `true`/`false` |
| 「取消」 | 不发送 | — |

`mapId`/`mapVersion` 来自 HTTP Map V2 当前地图，不是用户手填。

按钮可用性由 `ManualControlPolicy` 根据 `operationalMode`、`safetyState`、`runState`、`orchestrationState` 决定，不是 Cloud HTTP。

---

## 5. 手动控制页

| App 控件 | 用途 | 接口 | 字段与值 |
|---|---|---|---|
| 「进入手动模式」 | 让机器人切到遥控 | MQTT cmd `manual`，`params={}` | 成功后 `status.controlMode=manual` 且 `operationalMode=manual`，App 显示「手动模式」 |
| 「切回自动模式」 | 退出遥控 | 先发 remote 零速，再 MQTT cmd `auto`，`params={}` | `controlMode=auto`，显示「自动模式」 |
| 方向键 前进 | 按住前进 | MQTT remote | `linearSpeedCms=+当前线速度`，`angularSpeedRadps=0` |
| 方向键 后退 | 按住后退 | MQTT remote | `linearSpeedCms=-当前线速度`，`angularSpeedRadps=0` |
| 方向键 左转 | 按住左转 | MQTT remote | `linearSpeedCms=0`，`angularSpeedRadps=+当前角速度` |
| 方向键 右转 | 按住右转 | MQTT remote | `linearSpeedCms=0`，`angularSpeedRadps=-当前角速度` |
| 松开方向键 | 停车 | MQTT remote | `linearSpeedCms=0`，`angularSpeedRadps=0` |
| 「普通停止」 | 普通停止遥控 | MQTT remote 零速 | 不发 `stop` 任务命令 |
| 手动页「急停」 | 安全急停 | 取消方向输入后 MQTT cmd `estop` | 同首页急停 |
| 「慢速」 | 本机速度预设 | 无后端，直到下一次 remote | 线速度 `10` cm/s，角速度 `0.1` rad/s |
| 「标准」 | 本机速度预设 | 无后端 | `30` cm/s，`0.3` rad/s |
| 「高速」 | 本机速度预设 | 无后端 | `50` cm/s，`0.5` rad/s |
| 线速度 − / + | 本机调节 | 无后端 | 步进 `1` cm/s，范围 `0..50` |
| 角速度 − / + | 本机调节 | 无后端 | 步进 `0.1` rad/s，范围 `0..0.5` |

未进入手动模式或设备离线时，方向键不发 remote。

---

## 6. 地图页

| App 控件 | 用途 | 接口 | 字段 → 展示 |
|---|---|---|---|
| 首页「同步地图」/进入主界面 | 拉取并校验当前地图 | HTTP `GET /api/devices/{productType}/{deviceId}/maps/current`，再按 `contentUrl` 或 `GET .../maps/{map_id}/versions/{map_version}/content` | `activeMap.mapId`/`mapVersion`/`mapName` 显示为「地图编号 · 版本」；校验 `checksum`、`fileSizeBytes` |
| MQTT `pose`（自动） | 把机器人画在地图上 | 订阅 `.../pose` | `blockId`→区域；`cellRow`/`cellCol`→单元；`innerRow`/`innerCol`→板内格；`headingCode`/`heading`→朝向中文 |
| 「显示全部」 | 重置缩放 | 无后端 | — |
| 「居中机器人」 | 视野对准 pose | 无后端；无有效 pose 时提示并显示全图 | — |
| 「＋」「−」 | 缩放 | 无后端 | — |
| 「定位」 | 同居中机器人 | 无后端 | — |

地图内容本身是 V5 Map V2 文件，不是 MQTT `map` 主题。

---

## 7. 记录、更多、关于、维护

| App 控件 | 用途 | 接口 | 字段 → 展示 |
|---|---|---|---|
| 「查看作业记录」 | 云端作业列表 | HTTP `GET /api/jobs?device_id={deviceId}` | `status`→等待执行/执行中/已完成/执行失败/已取消；`started_at`/`finished_at`→时间；`cleaned_rows`→清扫行数；`note`→备注 |
| 「查看故障与警告」 | 本机日志，仅错误/警告过滤器 | 无后端 | Room |
| 「查看全部App日志」 | 本机结构化日志 | 无后端 | 按全部/操作/设备/连接/警告错误过滤 |
| 日志「清空」 | 清空本机日志 | 无后端 | Toast「本地日志已清空」 |
| 更多页状态卡片 | 只读遥测 | MQTT `status`/`heartbeat` | 见第 8 节用户可见行 |
| 「查看完整诊断信息」 | 给运维看协议字段 | 同上 MQTT，另含本地心跳时间 | 同时显示字段名与中文，不作为用户主界面 |
| 「Wi-Fi配置」 | 入口保留但不可点 | 当前禁用。页面实现：`GET/PUT /api/devices/{device_id}/wifi` | `ssid`、`configured`；保存体 `ssid`、`password`；成功 Toast「配置请求已提交，请确认机器人重新上线」 |
| 「Robot固件升级」 | 入口保留但不可点 | 当前禁用。页面实现：`GET /api/firmware/latest?device_id=`，`POST /api/firmware/upgrade` | `version`、`download_url`；请求 `device_id`、`target_version`；成功 Toast「升级任务已提交，请留意设备状态」 |
| 「关于、版本与App更新」 | 打开关于页 | 无即时接口 | `versionName`/`versionCode`/`APP_UPDATE_CHANNEL` |
| 「检查App更新」 | 查并下载渠道包 | HTTP `GET /api/app-releases/{channel}/latest`，下载 `GET /api/app-releases/{channel}/{version_code}/content` | 弹窗「发现新版本 {versionName}」；`releaseNotes`、`mandatory`、`sha256`；无新版本静默 |
| 底栏 总览/地图/控制/记录/更多 | 切页 | 无后端；离开控制页会停遥控 | — |

---

## 8. 字段值与 App 展示对照

### 8.1 命令名 `cmd`

| MQTT `cmd` | App 按钮/文案 |
|---|---|
| `start` | 开始运行 / 开始任务 |
| `stop` | 停止运行 |
| `pause` | 暂停任务 |
| `resume` | 恢复任务 |
| `replan` | 重新规划 |
| `manual` | 切换手动模式 |
| `auto` | 切回自动模式 |
| `estop` | 紧急停止 |
| `clear_estop` | 解除急停 |

### 8.2 工作状态 `workStatus`

| 字段值 | App 展示 |
|---|---|
| `idle` | 待机 |
| `running` | 运行中 |
| `stopped` | 已停止 |
| `estopped` | 急停锁定 |
| `fault` | 故障 |
| `null` | -- |
| 其他 | 未知状态 |

### 8.3 控制模式 `controlMode` / `operationalMode`

| 字段值 | App 展示 |
|---|---|
| `auto` | 自动模式 |
| `manual` | 手动模式 |
| `null` | -- / 暂无数据 |
| 其他 | 未知模式 |

### 8.4 设备状态 `deviceStatus`

| 字段值 | App 展示 |
|---|---|
| `normal` | 正常 |
| `warning` | 告警 |
| `fault` | 故障 |

### 8.5 运动状态 `movementStatus`

| 字段值 | App 展示 |
|---|---|
| `moving` | 移动中 |
| `stopped` | 静止 |
| `turning` | 转向中 |
| `blocked` | 受阻 |

### 8.6 安全状态 `safetyState`（任务状态优先使用）

| 字段值 | 首页/更多「任务状态」 | 诊断页 |
|---|---|---|
| `estop` | 急停；若已点解除急停则为「解除急停请求已受理，等待安全状态更新」 | 急停中 |
| `clearing_estop` | 解除急停中 | 正在解除急停 |
| `low_battery` | 低电量 | 低电量保护 |
| `fault` | 故障 | 设备故障 |
| `normal` | 不单独展示，继续看任务编排 | 正常 |
| `null` | 继续看任务编排 | 暂无数据 |

### 8.7 任务编排 `orchestrationState`（用户主展示）

| 字段值 | App 展示 |
|---|---|
| `idle` | 空闲 |
| `running` | 正在执行任务 |
| `paused_by_user` | 已暂停 |
| `paused_by_safety` | 因安全保护已暂停 |
| `running_child` | 任务已中断，正在执行内部动作；`interruptionReason=LOW_BATTERY` 时追加「（低电量）」 |
| `resuming` | 正在恢复任务 |
| `succeeded` | 任务已完成 |
| `failed` | 任务失败 |
| `canceled` | 任务已取消 |
| `unknown` | 任务状态未知 |
| `null` | 回退 `runState` |

`taskStackDepth` 只用于内部策略（例如重新规划是否可点），用户界面不再显示「栈深」。

### 8.8 当前栈顶任务 `runState`

| 字段值 | App 展示 |
|---|---|
| `idle` | 空闲 |
| `starting` | 启动中 / 正在启动 |
| `running` | 运行中 |
| `paused` | 已暂停 |
| `succeeded` | 已完成 |
| `failed` | 失败 / 执行失败 |
| `canceled` | 已取消 |
| `null` | 暂无任务；若刚点开始则为「启动请求已受理，等待任务状态」 |

### 8.9 任务类型 `taskKind`

| 字段值 | App 展示 |
|---|---|
| `coverage` | 覆盖清扫 |
| `return_to_charge` | 返回充电 |
| `null` | -- |

### 8.10 阶段 `phase`

| 字段值 | App 展示 |
|---|---|
| `none` | 未开始 |
| `waiting_for_robot` | 等待机器人响应 |
| `resolving_start` | 确认任务起点 |
| `planning` | 正在规划路径 |
| `executing` | 正在执行 |
| `placeholder` | 准备中 |
| `null` | 暂无阶段信息 |

### 8.11 当前动作 `activeAction`

| 字段值 | App 展示 |
|---|---|
| `starting` | 启动任务 |
| `cross_panel` | 跨板移动 |
| `null` | 暂无动作 |
| 其他小写字符串 | 执行机器人动作 |

### 8.12 GPS `gpsStatus` + 坐标

来源：`status.gpsStatus`、`status.latitudeDeg`、`status.longitudeDeg`。仅在机器人在线时展示数值。

| `gpsStatus` | App 展示 |
|---|---|
| `null` | 暂无定位数据 |
| `0` | 无定位 |
| `1` | 2D定位；坐标合法时追加 `lat, lon` |
| `2` | 3D定位；坐标合法时追加 `lat, lon` |
| `3` | RTK固定解；坐标合法时追加 `lat, lon` |
| 其他 | 未知定位状态 |
| `1..3` 但经纬度非法 | 定位数据不可用 |

合法范围：纬度 `-90..90`，经度 `-180..180`。

### 8.13 V6 遥测（更多页用户可见）

| App 标签 | MQTT `status` 字段 | 单位 | 缺测展示 |
|---|---|---|---|
| 主控温度 | `rk3588CpuTemperatureCelsius` | °C | 暂无数据 |
| 整机电流 | `totalCurrentAmpere` | A | 暂无数据 |
| 板面倾角 | `panelTiltDeg` | ° | 暂无数据 |
| 电量 | `batteryPercent` | % | 暂无数据 |

`rk3588CpuTemperatureCelsius` 是主控 SoC 温度，不是旧设备名 `rk3588`。用户界面只显示「主控温度」。完整诊断里仍写出协议字段名，便于联调。

诊断页额外字段（不作为日常文案）：

| 诊断标签 | 字段 |
|---|---|
| 机身内部温度 | `internalTemperatureCelsius` |
| H7 CPU温度 | `h7CpuTemperatureCelsius` |
| 横滚角 | `rollDeg` |
| 俯仰角 | `pitchDeg` |
| 航向角 | `yawDeg` |

### 8.14 地图朝向 `heading` / `headingCode`

| 字段值 | 名称（pose.heading） | App 展示 |
|---|---|---|
| `0` | `block_u_positive` | 沿板块横向正向 |
| `1` | `block_u_negative` | 沿板块横向反向 |
| `2` | `block_v_positive` | 沿板块纵向正向 |
| `3` | `block_v_negative` | 沿板块纵向反向 |
| 其他 | — | -- |

### 8.15 产品类型 `productType`

| 字段值 | App 展示 |
|---|---|
| `crawler` | 光伏清扫机器人 |
| 其他 | 其他设备（不能进入当前控制页） |

### 8.16 作业记录 `jobs.status`

| HTTP 字段值 | App 展示 |
|---|---|
| `pending` / `queued` | 等待执行 |
| `running` / `in_progress` | 执行中 |
| `completed` / `success` / `succeeded` | 已完成 |
| `failed` / `fault` | 执行失败 |
| `cancelled` / `canceled` | 已取消 |

### 8.17 命令错误码 `cmd_ack.errorCode`

| 字段值 | App 展示 |
|---|---|
| `INVALID_PAYLOAD` | 命令参数不符合接口要求 |
| `UNSUPPORTED_VERSION` | Robot 不支持当前接口版本 |
| `DEVICE_MISMATCH` | 命令设备与 Robot 不匹配 |
| `UNSUPPORTED_CMD` | Robot 不支持该命令 |
| `MISSION_SERVICE_UNAVAILABLE` | 任务服务不可用 |
| `MISSION_SERVICE_TIMEOUT` | 任务服务响应超时 |
| `MISSION_SERVICE_ERROR` | 任务服务调用失败 |
| `MISSION_INVALID_COMMAND` | 任务命令无效 |
| `MISSION_INVALID_REQUEST` | 任务请求参数无效 |
| `MISSION_BUSY` | Robot 当前有任务正在处理 |
| `MISSION_NOT_FOUND` | 目标任务不存在或已失效 |
| `MISSION_ILLEGAL_STATE` | 当前任务状态不允许执行该操作 |
| `MISSION_INTERNAL_ERROR` | 任务模块内部错误 |
| `MISSION_REJECTED` | 任务层拒绝了该操作 |
| 其他非空 | Robot 返回错误 |

展示格式：`中文（ERROR_CODE）`。

### 8.18 命令发送状态（本机，非 MQTT 字段）

| 本机状态 | App 展示 |
|---|---|
| SENDING | 正在发送 |
| SUCCESS | 已受理 |
| FAILED | 已拒绝 |
| TIMEOUT | 等待超时 |
| CONNECTION_LOST | 连接中断 |

---

## 9. 当前不可用入口

以下入口对用户可见但不可用，避免误以为 Cloud HTTP 200 等于 Robot 已执行：

- 「Wi-Fi配置」：`isEnabled=false`，Robot 写网闭环未完成
- 「Robot固件升级」：`isEnabled=false`，OTA 执行闭环未完成
- 登录页「自动登录」复选框：已隐藏

无控制权限账号可以进入主界面查看，但不能发 `cmd`/`remote`。

Debug 构建开启 `DEBUG_CONTROL_BYPASS` 时会放宽任务按钮门禁，Release 包按安全状态和任务状态严格禁用。

App 更新检查可用，依赖 Cloud 已发布对应渠道 APK。设备列表进入时也会静默检查一次。

---

## 10. 实现位置

| 内容 | 代码 |
|---|---|
| 按钮点击绑定 | `app/src/main/java/com/robot/solar/ui/main/MainActivity.kt` |
| 命令/遥控发送 | `viewmodel/MainViewModel.kt`、`network/mqtt/CloudCommMqttManager.kt` |
| HTTP 接口 | `network/http/ApiClient.kt`、`network/http/dto/HttpDtos.kt` |
| 用户中文映射 | `ui/common/ProtocolDisplayText.kt`、`viewmodel/ManualControlPolicy.kt` |
| 协议数据类 | `network/mqtt/MqttModels.kt` |
